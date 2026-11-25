package engine

import (
	"context"
	"log"
	"math"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/ponggame/game-service/internal/store"
)

const (
	BoardWidth       = 800.0
	BoardHeight      = 600.0
	PaddleHeight     = 100.0
	PaddleWidth      = 14.0
	PaddleMargin     = 24.0
	PaddleSpeed      = 420.0
	BallRadius       = 8.0
	InitialBallSpeed = 380.0
	MaxBallSpeed     = 680.0
	LeaseTTL         = 3 * time.Second
)

type StateFrame struct {
	Type         string            `json:"type"`
	MatchID      string            `json:"matchId"`
	InstanceID   string            `json:"instanceId"`
	Status       string            `json:"status"`
	Tick         int64             `json:"tick"`
	WinningScore int               `json:"winningScore"`
	Board        store.BoardSpec   `json:"board"`
	Ball         store.BallState   `json:"ball"`
	Paddles      store.PaddlesPair `json:"paddles"`
	WinnerID     *string           `json:"winnerId,omitempty"`
	FinishReason string            `json:"finishReason,omitempty"`
}

type EventEnvelope struct {
	MessageID     string `json:"messageId"`
	CorrelationID string `json:"correlationId"`
	Type          string `json:"type"`
	Version       int    `json:"version"`
	OccurredAt    string `json:"occurredAt"`
	Payload       any    `json:"payload"`
}

type MatchFinishedPayload struct {
	MatchID         string  `json:"matchId"`
	Player1ID       string  `json:"player1Id"`
	Player1Username string  `json:"player1Username"`
	Player1Score    int     `json:"player1Score"`
	Player2ID       string  `json:"player2Id"`
	Player2Username string  `json:"player2Username"`
	Player2Score    int     `json:"player2Score"`
	WinnerID        *string `json:"winnerId"`
	Reason          string  `json:"reason"`
	DurationSeconds int     `json:"durationSeconds"`
	FinishedAt      string  `json:"finishedAt"`
}

type Manager struct {
	store      *store.RedisStore
	instanceID string
	tickHz     int
	mu         sync.Mutex
	running    map[string]context.CancelFunc
}

func NewManager(st *store.RedisStore, instanceID string, tickHz int) *Manager {
	if tickHz <= 0 {
		tickHz = 30
	}
	return &Manager{
		store:      st,
		instanceID: instanceID,
		tickHz:     tickHz,
		running:    make(map[string]context.CancelFunc),
	}
}

func NewInitialState(matchID, correlationID, p1ID, p1User, p2ID, p2User string, winningScore int, instanceID string) *store.MatchState {
	if winningScore <= 0 {
		winningScore = 5
	}
	return &store.MatchState{
		MatchID:       matchID,
		CorrelationID: correlationID,
		Status:        "WAITING_FOR_PLAYERS",
		Tick:          0,
		WinningScore:  winningScore,
		Board:         store.BoardSpec{Width: BoardWidth, Height: BoardHeight},
		Ball:          resetBall(1),
		Paddles: store.PaddlesPair{
			Left: store.PaddleState{
				PlayerID: p1ID,
				Username: p1User,
				Y:        (BoardHeight - PaddleHeight) / 2,
				Height:   PaddleHeight,
				Score:    0,
			},
			Right: store.PaddleState{
				PlayerID: p2ID,
				Username: p2User,
				Y:        (BoardHeight - PaddleHeight) / 2,
				Height:   PaddleHeight,
				Score:    0,
			},
		},
		InstanceID: instanceID,
		UpdatedAt:  time.Now().UnixMilli(),
	}
}

// StartFailoverWatcher periodically scans active matches in ElastiCache Redis and
// claims leadership for any orphaned match (e.g., after another replica crashes).
func (m *Manager) StartFailoverWatcher(ctx context.Context) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			matchIDs, err := m.store.ListActiveMatches(ctx)
			if err != nil {
				continue
			}
			for _, id := range matchIDs {
				m.EnsureSimulationRunning(ctx, id)
			}
		}
	}
}

func (m *Manager) EnsureSimulationRunning(parentCtx context.Context, matchID string) {
	m.mu.Lock()
	if _, already := m.running[matchID]; already {
		m.mu.Unlock()
		return
	}
	m.mu.Unlock()

	acquired, err := m.store.AcquireLease(parentCtx, matchID, LeaseTTL)
	if err != nil || !acquired {
		return
	}

	m.mu.Lock()
	if _, already := m.running[matchID]; already {
		m.mu.Unlock()
		return
	}
	simCtx, cancel := context.WithCancel(parentCtx)
	m.running[matchID] = cancel
	m.mu.Unlock()

	log.Printf("[engine:%s] acquired simulation lease for match %s", m.instanceID, matchID)
	go m.runLoop(simCtx, matchID)
}

func (m *Manager) CancelMatch(ctx context.Context, matchID, reason string) error {
	m.mu.Lock()
	if cancel, ok := m.running[matchID]; ok {
		cancel()
		delete(m.running, matchID)
	}
	m.mu.Unlock()

	state, err := m.store.GetMatch(ctx, matchID)
	if err != nil {
		return err
	}
	if state.Status == "FINISHED" || state.Status == "CANCELLED" {
		return nil
	}
	state.Status = "CANCELLED"
	state.FinishReason = reason
	state.InstanceID = m.instanceID
	if err := m.store.SaveMatch(ctx, state); err != nil {
		return err
	}
	_ = m.store.PublishState(ctx, matchID, ToFrame(state, m.instanceID))
	m.store.ReleaseLease(ctx, matchID)
	return nil
}

func (m *Manager) runLoop(ctx context.Context, matchID string) {
	defer func() {
		m.mu.Lock()
		delete(m.running, matchID)
		m.mu.Unlock()
		m.store.ReleaseLease(context.Background(), matchID)
	}()

	dt := 1.0 / float64(m.tickHz)
	tickTicker := time.NewTicker(time.Second / time.Duration(m.tickHz))
	defer tickTicker.Stop()

	leaseTicker := time.NewTicker(time.Second)
	defer leaseTicker.Stop()

	state, err := m.store.GetMatch(ctx, matchID)
	if err != nil {
		return
	}

	for {
		select {
		case <-ctx.Done():
			return

		case <-leaseTicker.C:
			ok, err := m.store.RenewLease(ctx, matchID, LeaseTTL)
			if err != nil || !ok {
				log.Printf("[engine:%s] lost lease for match %s, stopping local loop", m.instanceID, matchID)
				return
			}
			// Check if match was cancelled externally
			latest, err := m.store.GetMatch(ctx, matchID)
			if err == nil && (latest.Status == "CANCELLED" || latest.Status == "FINISHED") {
				return
			}

		case <-tickTicker.C:
			inputs, err := m.store.GetInputs(ctx, matchID)
			if err != nil {
				continue
			}

			state.InstanceID = m.instanceID
			state.Paddles.Left.Connected = inputs["left_connected"] == "1"
			state.Paddles.Right.Connected = inputs["right_connected"] == "1"

			// Handle explicit forfeit command
			if forfeitSide := inputs["forfeit"]; forfeitSide == "left" || forfeitSide == "right" {
				winnerID := state.Paddles.Right.PlayerID
				if forfeitSide == "right" {
					winnerID = state.Paddles.Left.PlayerID
				}
				m.finishMatch(ctx, state, &winnerID, "FORFEIT")
				return
			}

			if state.Status == "WAITING_FOR_PLAYERS" {
				if state.Paddles.Left.Connected && state.Paddles.Right.Connected {
					state.Status = "PLAYING"
					state.StartedAt = time.Now().UnixMilli()
					log.Printf("[engine:%s] match %s started (%s vs %s)",
						m.instanceID, matchID, state.Paddles.Left.Username, state.Paddles.Right.Username)
				} else {
					// Broadcast waiting status at ~5Hz so clients see opponent connection updates
					state.Tick++
					if state.Tick%6 == 0 && !m.publishFrame(ctx, state) {
						return
					}
					continue
				}
			}

			if state.Status != "PLAYING" {
				return
			}

			stepPhysics(state, inputs["left"], inputs["right"], dt)

			if state.Paddles.Left.Score >= state.WinningScore {
				winner := state.Paddles.Left.PlayerID
				m.finishMatch(ctx, state, &winner, "SCORE_LIMIT")
				return
			}
			if state.Paddles.Right.Score >= state.WinningScore {
				winner := state.Paddles.Right.PlayerID
				m.finishMatch(ctx, state, &winner, "SCORE_LIMIT")
				return
			}

			if !m.publishFrame(ctx, state) {
				return
			}
		}
	}
}

func (m *Manager) publishFrame(ctx context.Context, state *store.MatchState) bool {
	written, err := m.store.SaveFrame(ctx, state)
	if err != nil {
		log.Printf("[engine:%s] failed saving frame for match %s: %v", m.instanceID, state.MatchID, err)
		return false
	}
	if written {
		_ = m.store.PublishState(ctx, state.MatchID, ToFrame(state, m.instanceID))
	}
	return written
}

func (m *Manager) finishMatch(ctx context.Context, state *store.MatchState, winnerID *string, reason string) {
	state.Status = "FINISHED"
	state.WinnerID = winnerID
	state.FinishReason = reason
	state.InstanceID = m.instanceID
	_ = m.store.SaveMatch(ctx, state)
	_ = m.store.PublishState(ctx, state.MatchID, ToFrame(state, m.instanceID))

	// Guarantee match.finished is enqueued at most once across all replicas
	claimed, err := m.store.ClaimFinishEmission(ctx, state.MatchID)
	if err != nil || !claimed {
		return
	}

	durationSec := 0
	if state.StartedAt > 0 {
		durationSec = int(math.Max(0, float64(time.Now().UnixMilli()-state.StartedAt)/1000.0))
	}

	nowIso := time.Now().UTC().Format(time.RFC3339Nano)
	corrID := state.CorrelationID
	if corrID == "" {
		corrID = uuid.NewString()
	}

	env := EventEnvelope{
		MessageID:     uuid.NewString(),
		CorrelationID: corrID,
		Type:          "match.finished",
		Version:       1,
		OccurredAt:    nowIso,
		Payload: MatchFinishedPayload{
			MatchID:         state.MatchID,
			Player1ID:       state.Paddles.Left.PlayerID,
			Player1Username: state.Paddles.Left.Username,
			Player1Score:    state.Paddles.Left.Score,
			Player2ID:       state.Paddles.Right.PlayerID,
			Player2Username: state.Paddles.Right.Username,
			Player2Score:    state.Paddles.Right.Score,
			WinnerID:        winnerID,
			Reason:          reason,
			DurationSeconds: durationSec,
			FinishedAt:      nowIso,
		},
	}

	if err := m.store.EnqueueOutbox(ctx, "match.finished", env); err != nil {
		log.Printf("[engine:%s] failed enqueuing match.finished for %s: %v", m.instanceID, state.MatchID, err)
	} else {
		log.Printf("[engine:%s] match %s finished (%s %d - %d %s, reason=%s)",
			m.instanceID, state.MatchID,
			state.Paddles.Left.Username, state.Paddles.Left.Score,
			state.Paddles.Right.Score, state.Paddles.Right.Username, reason)
	}
}

func stepPhysics(state *store.MatchState, leftDir, rightDir string, dt float64) {
	state.Tick++

	// Move paddles
	movePaddle(&state.Paddles.Left, leftDir, dt)
	movePaddle(&state.Paddles.Right, rightDir, dt)

	// Move ball
	b := &state.Ball
	b.X += b.VX * dt
	b.Y += b.VY * dt

	// Top / bottom wall bounce
	if b.Y-b.Radius <= 0 {
		b.Y = b.Radius
		b.VY = math.Abs(b.VY)
	} else if b.Y+b.Radius >= BoardHeight {
		b.Y = BoardHeight - b.Radius
		b.VY = -math.Abs(b.VY)
	}

	// Left paddle collision
	leftPaddleRightX := PaddleMargin + PaddleWidth
	if b.VX < 0 && b.X-b.Radius <= leftPaddleRightX && b.X >= PaddleMargin {
		if b.Y+b.Radius >= state.Paddles.Left.Y && b.Y-b.Radius <= state.Paddles.Left.Y+PaddleHeight {
			b.X = leftPaddleRightX + b.Radius
			bounceOffPaddle(b, state.Paddles.Left.Y, 1)
		}
	}

	// Right paddle collision
	rightPaddleLeftX := BoardWidth - PaddleMargin - PaddleWidth
	if b.VX > 0 && b.X+b.Radius >= rightPaddleLeftX && b.X <= BoardWidth-PaddleMargin {
		if b.Y+b.Radius >= state.Paddles.Right.Y && b.Y-b.Radius <= state.Paddles.Right.Y+PaddleHeight {
			b.X = rightPaddleLeftX - b.Radius
			bounceOffPaddle(b, state.Paddles.Right.Y, -1)
		}
	}

	// Scoring
	if b.X+b.Radius < 0 {
		state.Paddles.Right.Score++
		state.Ball = resetBall(-1)
	} else if b.X-b.Radius > BoardWidth {
		state.Paddles.Left.Score++
		state.Ball = resetBall(1)
	}
}

func movePaddle(p *store.PaddleState, dir string, dt float64) {
	switch dir {
	case "UP":
		p.Y -= PaddleSpeed * dt
	case "DOWN":
		p.Y += PaddleSpeed * dt
	}
	if p.Y < 0 {
		p.Y = 0
	}
	if p.Y+PaddleHeight > BoardHeight {
		p.Y = BoardHeight - PaddleHeight
	}
}

func bounceOffPaddle(b *store.BallState, paddleY float64, dirX float64) {
	relIntersect := ((paddleY + PaddleHeight/2) - b.Y) / (PaddleHeight / 2)
	if relIntersect > 1 {
		relIntersect = 1
	} else if relIntersect < -1 {
		relIntersect = -1
	}
	bounceAngle := relIntersect * (math.Pi / 3.0) // up to 60 degrees
	speed := math.Min(MaxBallSpeed, math.Hypot(b.VX, b.VY)*1.06)
	b.VX = dirX * speed * math.Cos(bounceAngle)
	b.VY = -speed * math.Sin(bounceAngle)
}

func resetBall(dirX float64) store.BallState {
	return store.BallState{
		X:      BoardWidth / 2,
		Y:      BoardHeight / 2,
		VX:     dirX * InitialBallSpeed * 0.9,
		VY:     InitialBallSpeed * 0.35,
		Radius: BallRadius,
	}
}

func ToFrame(s *store.MatchState, instanceID string) StateFrame {
	return StateFrame{
		Type:         "state",
		MatchID:      s.MatchID,
		InstanceID:   instanceID,
		Status:       s.Status,
		Tick:         s.Tick,
		WinningScore: s.WinningScore,
		Board:        s.Board,
		Ball:         s.Ball,
		Paddles:      s.Paddles,
		WinnerID:     s.WinnerID,
		FinishReason: s.FinishReason,
	}
}
