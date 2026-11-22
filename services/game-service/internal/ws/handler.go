package ws

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/gorilla/websocket"
	"github.com/ponggame/game-service/internal/auth"
	"github.com/ponggame/game-service/internal/engine"
	"github.com/ponggame/game-service/internal/messaging"
	"github.com/ponggame/game-service/internal/store"
)

type ClientCommand struct {
	Type      string `json:"type"`      // "input" or "forfeit"
	Direction string `json:"direction"` // "UP", "DOWN", "STOP"
}

type Handler struct {
	instanceID string
	verifier   *auth.Verifier
	store      *store.RedisStore
	engineMgr  *engine.Manager
	broker     *messaging.Broker
	upgrader   websocket.Upgrader
}

func NewHandler(instanceID string, verifier *auth.Verifier, st *store.RedisStore, mgr *engine.Manager, broker *messaging.Broker) *Handler {
	return &Handler{
		instanceID: instanceID,
		verifier:   verifier,
		store:      st,
		engineMgr:  mgr,
		broker:     broker,
		upgrader: websocket.Upgrader{
			ReadBufferSize:  1024,
			WriteBufferSize: 4096,
			CheckOrigin:     func(r *http.Request) bool { return true },
		},
	}
}

func (h *Handler) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", h.handleHealth)
	mux.HandleFunc("/api/game/matches/", h.handleGetMatch)
	mux.HandleFunc("/ws/game/", h.handleWebSocket)
	return mux
}

func (h *Handler) handleHealth(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()

	if err := h.store.Ping(ctx); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"status":     "DOWN",
			"service":    "game-service",
			"instanceId": h.instanceID,
			"error":      err.Error(),
		})
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"status":            "UP",
		"service":           "game-service",
		"instanceId":        h.instanceID,
		"rabbitmqConnected": h.broker.IsConnected(),
	})
}

func (h *Handler) handleGetMatch(w http.ResponseWriter, r *http.Request) {
	if _, err := h.authenticate(r); err != nil {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized", "message": err.Error()})
		return
	}

	matchID := strings.TrimPrefix(r.URL.Path, "/api/game/matches/")
	matchID = strings.Trim(matchID, "/")
	if matchID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "missing matchId"})
		return
	}

	state, err := h.store.GetMatch(r.Context(), matchID)
	if errors.Is(err, store.ErrMatchNotFound) {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "match_not_found"})
		return
	}
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"servedBy": h.instanceID,
		"match":    state,
	})
}

func (h *Handler) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	claims, err := h.authenticate(r)
	if err != nil {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized", "message": err.Error()})
		return
	}

	matchID := strings.TrimPrefix(r.URL.Path, "/ws/game/")
	matchID = strings.Trim(matchID, "/")
	if matchID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "missing matchId"})
		return
	}

	state, err := h.store.GetMatch(r.Context(), matchID)
	if errors.Is(err, store.ErrMatchNotFound) {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "match_not_found"})
		return
	}
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	side := ""
	switch claims.PlayerID {
	case state.Paddles.Left.PlayerID:
		side = "left"
	case state.Paddles.Right.PlayerID:
		side = "right"
	default:
		writeJSON(w, http.StatusForbidden, map[string]any{"error": "player_not_in_match"})
		return
	}

	conn, err := h.upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("[ws:%s] upgrade error for match %s: %v", h.instanceID, matchID, err)
		return
	}
	defer conn.Close()

	log.Printf("[ws:%s] player %s (%s) connected to match %s", h.instanceID, claims.Username, side, matchID)

	sessionCtx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Register player connection in distributed memory & ensure a replica runs the match loop
	_ = h.store.SetPlayerConnected(sessionCtx, matchID, side, true)
	defer func() {
		_ = h.store.SetPlayerConnected(context.Background(), matchID, side, false)
		_ = h.store.SetPlayerInput(context.Background(), matchID, side, "STOP")
		log.Printf("[ws:%s] player %s (%s) disconnected from match %s", h.instanceID, claims.Username, side, matchID)
	}()

	h.engineMgr.EnsureSimulationRunning(context.Background(), matchID)

	// Send immediate welcome + initial state snapshot
	welcome := map[string]any{
		"type":       "welcome",
		"matchId":    matchID,
		"side":       side,
		"playerId":   claims.PlayerID,
		"username":   claims.Username,
		"instanceId": h.instanceID,
		"state":      engine.ToFrame(state, h.instanceID),
	}
	if err := conn.WriteJSON(welcome); err != nil {
		return
	}

	// Subscribe to Redis Pub/Sub real-time game state channel (works across all replicas!)
	pubsub := h.store.SubscribeState(sessionCtx, matchID)
	defer pubsub.Close()

	writeErrCh := make(chan error, 1)
	go func() {
		ch := pubsub.Channel()
		for {
			select {
			case <-sessionCtx.Done():
				return
			case msg, ok := <-ch:
				if !ok {
					writeErrCh <- errors.New("pubsub closed")
					return
				}
				_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
				if err := conn.WriteMessage(websocket.TextMessage, []byte(msg.Payload)); err != nil {
					writeErrCh <- err
					return
				}
			}
		}
	}()

	// Read paddle commands from WebSocket client and write to distributed memory (Redis)
	readErrCh := make(chan error, 1)
	go func() {
		for {
			_, raw, err := conn.ReadMessage()
			if err != nil {
				readErrCh <- err
				return
			}
			var cmd ClientCommand
			if err := json.Unmarshal(raw, &cmd); err != nil {
				continue
			}
			switch strings.ToLower(cmd.Type) {
			case "input":
				dir := strings.ToUpper(strings.TrimSpace(cmd.Direction))
				if dir == "UP" || dir == "DOWN" || dir == "STOP" {
					_ = h.store.SetPlayerInput(sessionCtx, matchID, side, dir)
				}
			case "forfeit":
				_ = h.store.SetPlayerForfeit(sessionCtx, matchID, side)
			}
		}
	}()

	select {
	case <-writeErrCh:
	case <-readErrCh:
	}
}

func (h *Handler) authenticate(r *http.Request) (*auth.PlayerClaims, error) {
	token := ""
	authHeader := r.Header.Get("Authorization")
	if strings.HasPrefix(authHeader, "Bearer ") {
		token = strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
	}
	if token == "" {
		token = strings.TrimSpace(r.URL.Query().Get("token"))
	}
	if token == "" {
		return nil, errors.New("missing Bearer token or ?token query parameter")
	}
	return h.verifier.Verify(r.Context(), token)
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
