package messaging

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math/rand/v2"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"github.com/ponggame/game-service/internal/config"
	"github.com/ponggame/game-service/internal/engine"
	"github.com/ponggame/game-service/internal/store"
	amqp "github.com/rabbitmq/amqp091-go"
)

const maxDeliveryRetries = 3

type RawEnvelope struct {
	MessageID     string          `json:"messageId"`
	CorrelationID string          `json:"correlationId"`
	Type          string          `json:"type"`
	Version       int             `json:"version"`
	OccurredAt    string          `json:"occurredAt"`
	Payload       json.RawMessage `json:"payload"`
}

type PlayerSlot struct {
	PlayerID string `json:"playerId"`
	Username string `json:"username"`
	TicketID string `json:"ticketId"`
}

type MatchCreatedPayload struct {
	MatchID           string     `json:"matchId"`
	Player1           PlayerSlot `json:"player1"`
	Player2           PlayerSlot `json:"player2"`
	WinningScore      int        `json:"winningScore"`
	ProvisionDeadline string     `json:"provisionDeadline"`
}

type MatchCancelPayload struct {
	MatchID string `json:"matchId"`
	Reason  string `json:"reason"`
}

type Broker struct {
	cfg       config.Config
	store     *store.RedisStore
	engineMgr *engine.Manager

	mu        sync.Mutex
	conn      *amqp.Connection
	pubCh     *amqp.Channel
	connected atomic.Bool
}

func NewBroker(cfg config.Config, st *store.RedisStore, mgr *engine.Manager) *Broker {
	return &Broker{
		cfg:       cfg,
		store:     st,
		engineMgr: mgr,
	}
}

func (b *Broker) IsConnected() bool {
	return b.connected.Load()
}

// Run maintains a resilient RabbitMQ connection with automatic reconnect (R6 / F6)
// and drains the Redis outbox continuously once connected.
func (b *Broker) Run(ctx context.Context) {
	go b.runOutboxDrainer(ctx)

	backoff := time.Second
	for {
		if ctx.Err() != nil {
			return
		}
		err := b.connectAndConsume(ctx)
		b.connected.Store(false)
		b.closeConn()
		if ctx.Err() != nil {
			return
		}
		log.Printf("[rabbit:%s] disconnected (%v); reconnecting in %s...", b.cfg.InstanceID, err, backoff)
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < 10*time.Second {
			backoff *= 2
		}
	}
}

func (b *Broker) closeConn() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.pubCh != nil {
		_ = b.pubCh.Close()
		b.pubCh = nil
	}
	if b.conn != nil {
		_ = b.conn.Close()
		b.conn = nil
	}
}

func (b *Broker) connectAndConsume(ctx context.Context) error {
	conn, err := amqp.Dial(b.cfg.RabbitMQURL)
	if err != nil {
		return err
	}

	pubCh, err := conn.Channel()
	if err != nil {
		_ = conn.Close()
		return err
	}
	consumeCh, err := conn.Channel()
	if err != nil {
		_ = pubCh.Close()
		_ = conn.Close()
		return err
	}

	if err := b.declareTopology(consumeCh); err != nil {
		_ = consumeCh.Close()
		_ = pubCh.Close()
		_ = conn.Close()
		return err
	}

	// Competing consumers across replicas with fair dispatch (R4 / F8)
	if err := consumeCh.Qos(1, 0, false); err != nil {
		_ = consumeCh.Close()
		_ = pubCh.Close()
		_ = conn.Close()
		return err
	}

	createdDeliveries, err := consumeCh.Consume(b.cfg.MatchCreatedQueue, b.cfg.InstanceID+"-created", false, false, false, false, nil)
	if err != nil {
		_ = consumeCh.Close()
		_ = pubCh.Close()
		_ = conn.Close()
		return err
	}

	cancelDeliveries, err := consumeCh.Consume(b.cfg.MatchCancelQueue, b.cfg.InstanceID+"-cancel", false, false, false, false, nil)
	if err != nil {
		_ = consumeCh.Close()
		_ = pubCh.Close()
		_ = conn.Close()
		return err
	}

	b.mu.Lock()
	b.conn = conn
	b.pubCh = pubCh
	b.mu.Unlock()
	b.connected.Store(true)
	log.Printf("[rabbit:%s] connected and consuming %s & %s", b.cfg.InstanceID, b.cfg.MatchCreatedQueue, b.cfg.MatchCancelQueue)

	closeCh := conn.NotifyClose(make(chan *amqp.Error, 1))

	for {
		select {
		case <-ctx.Done():
			_ = consumeCh.Close()
			return ctx.Err()
		case amqpErr := <-closeCh:
			if amqpErr == nil {
				return errors.New("connection closed")
			}
			return amqpErr
		case d, ok := <-createdDeliveries:
			if !ok {
				return errors.New("match.created delivery channel closed")
			}
			b.handleMatchCreatedDelivery(ctx, d)
		case d, ok := <-cancelDeliveries:
			if !ok {
				return errors.New("match.cancel delivery channel closed")
			}
			b.handleMatchCancelDelivery(ctx, d)
		}
	}
}

func (b *Broker) declareTopology(ch *amqp.Channel) error {
	if err := ch.ExchangeDeclare(b.cfg.RabbitMQEventsExchange, "topic", true, false, false, false, nil); err != nil {
		return err
	}
	if err := ch.ExchangeDeclare(b.cfg.RabbitMQDLXExchange, "direct", true, false, false, false, nil); err != nil {
		return err
	}

	queues := []struct {
		name       string
		routingKey string
	}{
		{b.cfg.MatchCreatedQueue, "match.created"},
		{b.cfg.MatchCancelQueue, "match.cancel"},
	}

	for _, q := range queues {
		dlqName := q.name + ".dlq"
		if _, err := ch.QueueDeclare(dlqName, true, false, false, false, nil); err != nil {
			return err
		}
		if err := ch.QueueBind(dlqName, dlqName, b.cfg.RabbitMQDLXExchange, false, nil); err != nil {
			return err
		}
		args := amqp.Table{
			"x-dead-letter-exchange":    b.cfg.RabbitMQDLXExchange,
			"x-dead-letter-routing-key": dlqName,
		}
		if _, err := ch.QueueDeclare(q.name, true, false, false, false, args); err != nil {
			return err
		}
		if err := ch.QueueBind(q.name, q.routingKey, b.cfg.RabbitMQEventsExchange, false, nil); err != nil {
			return err
		}
	}
	return nil
}

func (b *Broker) handleMatchCreatedDelivery(ctx context.Context, d amqp.Delivery) {
	var env RawEnvelope
	if err := json.Unmarshal(d.Body, &env); err != nil || env.MessageID == "" || env.Type != "match.created" || env.Version < 1 {
		b.retryOrDeadLetter(d, b.cfg.MatchCreatedQueue, fmt.Errorf("invalid match.created envelope: %v", err))
		return
	}

	var payload MatchCreatedPayload
	if err := json.Unmarshal(env.Payload, &payload); err != nil ||
		payload.MatchID == "" || payload.Player1.PlayerID == "" || payload.Player2.PlayerID == "" {
		b.retryOrDeadLetter(d, b.cfg.MatchCreatedQueue, fmt.Errorf("invalid match.created payload: %v", err))
		return
	}

	// R1 / F3 Idempotency check
	done, err := b.store.IsMessageDone(ctx, env.MessageID)
	if err != nil {
		_ = d.Nack(false, true)
		return
	}
	if done {
		log.Printf("[rabbit:%s] duplicate match.created messageId=%s ignored", b.cfg.InstanceID, env.MessageID)
		_ = d.Ack(false)
		return
	}

	// Crash-safe processing claim (F2): if this replica is killed mid-processing, claim expires and another replica takes over
	claimed, err := b.store.TryClaimMessage(ctx, env.MessageID, 30*time.Second)
	if err != nil || !claimed {
		time.Sleep(250 * time.Millisecond)
		_ = d.Nack(false, true)
		return
	}

	// R5 / F1 / F7 Simulated slow/unstable external Arena Provisioner dependency
	if depErr := b.simulateUnstableDependency(ctx, payload.MatchID); depErr != nil {
		log.Printf("[rabbit:%s] unstable dependency failed for match %s: %v", b.cfg.InstanceID, payload.MatchID, depErr)
		failEnv := engine.EventEnvelope{
			MessageID:     uuid.NewString(),
			CorrelationID: env.CorrelationID,
			Type:          "match.provisioning_failed",
			Version:       1,
			OccurredAt:    time.Now().UTC().Format(time.RFC3339Nano),
			Payload: map[string]any{
				"matchId": payload.MatchID,
				"reason":  depErr.Error(),
			},
		}
		if err := b.store.EnqueueOutbox(ctx, "match.provisioning_failed", failEnv); err != nil {
			b.store.ReleaseMessageClaim(ctx, env.MessageID)
			_ = d.Nack(false, true)
			return
		}
		_ = b.store.MarkMessageDone(ctx, env.MessageID)
		_ = d.Ack(false)
		return
	}

	// Provision match in ElastiCache distributed memory
	winningScore := payload.WinningScore
	if winningScore <= 0 {
		winningScore = b.cfg.DefaultWinningScore
	}
	state := engine.NewInitialState(
		payload.MatchID,
		env.CorrelationID,
		payload.Player1.PlayerID, payload.Player1.Username,
		payload.Player2.PlayerID, payload.Player2.Username,
		winningScore,
		b.cfg.InstanceID,
	)

	if err := b.store.SaveMatch(ctx, state); err != nil {
		b.store.ReleaseMessageClaim(ctx, env.MessageID)
		_ = d.Nack(false, true)
		return
	}

	readyEnv := engine.EventEnvelope{
		MessageID:     uuid.NewString(),
		CorrelationID: env.CorrelationID,
		Type:          "match.ready",
		Version:       1,
		OccurredAt:    time.Now().UTC().Format(time.RFC3339Nano),
		Payload: map[string]any{
			"matchId":       payload.MatchID,
			"wsPath":        "/ws/game/" + payload.MatchID,
			"provisionedBy": b.cfg.InstanceID,
		},
	}
	if err := b.store.EnqueueOutbox(ctx, "match.ready", readyEnv); err != nil {
		b.store.ReleaseMessageClaim(ctx, env.MessageID)
		_ = d.Nack(false, true)
		return
	}

	_ = b.store.MarkMessageDone(ctx, env.MessageID)
	_ = d.Ack(false)
	log.Printf("[rabbit:%s] provisioned match %s (%s vs %s) (messageId=%s, correlationId=%s)",
		b.cfg.InstanceID, payload.MatchID, payload.Player1.Username, payload.Player2.Username, env.MessageID, env.CorrelationID)
}

func (b *Broker) handleMatchCancelDelivery(ctx context.Context, d amqp.Delivery) {
	var env RawEnvelope
	if err := json.Unmarshal(d.Body, &env); err != nil || env.MessageID == "" || env.Type != "match.cancel" {
		b.retryOrDeadLetter(d, b.cfg.MatchCancelQueue, fmt.Errorf("invalid match.cancel envelope: %v", err))
		return
	}
	var payload MatchCancelPayload
	if err := json.Unmarshal(env.Payload, &payload); err != nil || payload.MatchID == "" {
		b.retryOrDeadLetter(d, b.cfg.MatchCancelQueue, fmt.Errorf("invalid match.cancel payload: %v", err))
		return
	}
	done, _ := b.store.IsMessageDone(ctx, env.MessageID)
	if done {
		_ = d.Ack(false)
		return
	}
	if err := b.engineMgr.CancelMatch(ctx, payload.MatchID, payload.Reason); err != nil && !errors.Is(err, store.ErrMatchNotFound) {
		_ = d.Nack(false, true)
		return
	}
	_ = b.store.MarkMessageDone(ctx, env.MessageID)
	_ = d.Ack(false)
	log.Printf("[rabbit:%s] cancelled match %s (reason=%s)", b.cfg.InstanceID, payload.MatchID, payload.Reason)
}

func (b *Broker) simulateUnstableDependency(ctx context.Context, matchID string) error {
	minMs := b.cfg.DependencyMinLatencyMs
	maxMs := b.cfg.DependencyMaxLatencyMs
	if maxMs < minMs {
		maxMs = minMs
	}
	delayMs := minMs
	if maxMs > minMs {
		delayMs = minMs + rand.IntN(maxMs-minMs+1)
	}
	if delayMs > 0 {
		log.Printf("[dependency:%s] provisioning arena for match %s (simulated latency=%dms, failureRate=%.2f)",
			b.cfg.InstanceID, matchID, delayMs, b.cfg.DependencyFailureRate)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Duration(delayMs) * time.Millisecond):
		}
	}
	if b.cfg.DependencyFailureRate > 0 && rand.Float64() < b.cfg.DependencyFailureRate {
		return errors.New("SIMULATED_ARENA_DEPENDENCY_FAILURE")
	}
	return nil
}

// retryOrDeadLetter implements F4 (poison messages): republishes up to maxDeliveryRetries
// with incremented x-retry-count, then NACKs without requeue so RabbitMQ routes to DLQ.
func (b *Broker) retryOrDeadLetter(d amqp.Delivery, queueName string, reason error) {
	retries := 0
	if d.Headers != nil {
		switch v := d.Headers["x-retry-count"].(type) {
		case int32:
			retries = int(v)
		case int64:
			retries = int(v)
		case int:
			retries = v
		}
	}

	if retries+1 < maxDeliveryRetries {
		b.mu.Lock()
		ch := b.pubCh
		b.mu.Unlock()
		if ch != nil {
			headers := amqp.Table{}
			for k, v := range d.Headers {
				headers[k] = v
			}
			headers["x-retry-count"] = int32(retries + 1)
			err := ch.Publish("", queueName, false, false, amqp.Publishing{
				ContentType:  d.ContentType,
				DeliveryMode: amqp.Persistent,
				Headers:      headers,
				Body:         d.Body,
			})
			if err == nil {
				log.Printf("[rabbit:%s] poison message retry %d/%d on %s (%v)",
					b.cfg.InstanceID, retries+1, maxDeliveryRetries, queueName, reason)
				_ = d.Ack(false)
				return
			}
		}
	}

	log.Printf("[rabbit:%s] poison message exceeded %d attempts on %s -> routing to DLQ (%v)",
		b.cfg.InstanceID, maxDeliveryRetries, queueName, reason)
	_ = d.Nack(false, false)
}

func (b *Broker) runOutboxDrainer(ctx context.Context) {
	ticker := time.NewTicker(300 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if !b.connected.Load() {
				continue
			}
			for {
				item, err := b.store.PeekOutbox(ctx)
				if err != nil || item == nil {
					break
				}
				if err := b.publishRaw(item.RoutingKey, []byte(item.Body)); err != nil {
					log.Printf("[rabbit:%s] outbox publish failed (will retry): %v", b.cfg.InstanceID, err)
					break
				}
				_ = b.store.PopOutbox(ctx)
				log.Printf("[rabbit:%s] published outbox event routingKey=%s", b.cfg.InstanceID, item.RoutingKey)
			}
		}
	}
}

func (b *Broker) publishRaw(routingKey string, body []byte) error {
	b.mu.Lock()
	ch := b.pubCh
	b.mu.Unlock()
	if ch == nil {
		return errors.New("rabbitmq channel not connected")
	}
	return ch.Publish(
		b.cfg.RabbitMQEventsExchange,
		routingKey,
		false,
		false,
		amqp.Publishing{
			ContentType:  "application/json",
			DeliveryMode: amqp.Persistent,
			Body:         body,
		},
	)
}
