package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/elasticache"
	"github.com/ponggame/game-service/internal/config"
	"github.com/redis/go-redis/v9"
)

const (
	ActiveMatchesSetKey = "game:active_matches"
	OutboxListKey       = "game:outbox"
)

var ErrMatchNotFound = errors.New("match not found")

type PaddleState struct {
	PlayerID  string  `json:"playerId"`
	Username  string  `json:"username"`
	Y         float64 `json:"y"`
	Height    float64 `json:"height"`
	Score     int     `json:"score"`
	Connected bool    `json:"connected"`
}

type BallState struct {
	X      float64 `json:"x"`
	Y      float64 `json:"y"`
	VX     float64 `json:"vx"`
	VY     float64 `json:"vy"`
	Radius float64 `json:"radius"`
}

type BoardSpec struct {
	Width  float64 `json:"width"`
	Height float64 `json:"height"`
}

type PaddlesPair struct {
	Left  PaddleState `json:"left"`
	Right PaddleState `json:"right"`
}

type MatchState struct {
	MatchID       string      `json:"matchId"`
	CorrelationID string      `json:"correlationId"`
	Status        string      `json:"status"` // WAITING_FOR_PLAYERS, PLAYING, FINISHED, CANCELLED
	Tick          int64       `json:"tick"`
	WinningScore  int         `json:"winningScore"`
	Board         BoardSpec   `json:"board"`
	Ball          BallState   `json:"ball"`
	Paddles       PaddlesPair `json:"paddles"`
	WinnerID      *string     `json:"winnerId,omitempty"`
	FinishReason  string      `json:"finishReason,omitempty"`
	InstanceID    string      `json:"instanceId"`
	StartedAt     int64       `json:"startedAt,omitempty"`
	UpdatedAt     int64       `json:"updatedAt"`
}

type OutboxItem struct {
	RoutingKey string `json:"routingKey"`
	Body       string `json:"body"`
}

type RedisStore struct {
	client     *redis.Client
	instanceID string
}

var renewLeaseScript = redis.NewScript(`
if redis.call("GET", KEYS[1]) == ARGV[1] then
    return redis.call("PEXPIRE", KEYS[1], ARGV[2])
end
return 0
`)

var releaseLeaseScript = redis.NewScript(`
if redis.call("GET", KEYS[1]) == ARGV[1] then
    return redis.call("DEL", KEYS[1])
end
return 0
`)

func NewRedisStore(ctx context.Context, cfg config.Config) (*RedisStore, error) {
	host := "redis"
	port := 6379

	if cfg.RedisHostOverride != "" {
		host = cfg.RedisHostOverride
		if cfg.RedisPortOverride > 0 {
			port = cfg.RedisPortOverride
		}
		log.Printf("[redis] using explicit override %s:%d", host, port)
	} else {
		h, p := discoverElastiCache(ctx, cfg)
		host, port = h, p
		log.Printf("[redis] discovered ElastiCache cluster '%s' endpoint %s:%d", cfg.ElastiCacheClusterID, host, port)
	}

	client := redis.NewClient(&redis.Options{
		Addr:         fmt.Sprintf("%s:%d", host, port),
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 5 * time.Second,
		PoolSize:     32,
	})

	for attempt := 1; attempt <= 20; attempt++ {
		if err := client.Ping(ctx).Err(); err == nil {
			return &RedisStore{client: client, instanceID: cfg.InstanceID}, nil
		} else {
			log.Printf("[redis] waiting for Redis %s:%d (attempt %d/20): %v", host, port, attempt, err)
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(time.Second):
			}
		}
	}
	return nil, fmt.Errorf("failed to connect to Redis at %s:%d", host, port)
}

func discoverElastiCache(ctx context.Context, cfg config.Config) (string, int) {
	opts := []func(*awsconfig.LoadOptions) error{
		awsconfig.WithRegion(cfg.AWSRegion),
	}
	if cfg.AWSEndpointURL != "" {
		opts = append(opts, awsconfig.WithCredentialsProvider(
			credentials.NewStaticCredentialsProvider(cfg.AWSAccessKeyID, cfg.AWSSecretAccessKey, ""),
		))
	}
	awsCfg, err := awsconfig.LoadDefaultConfig(ctx, opts...)
	if err != nil {
		log.Printf("[elasticache] aws config error, falling back to redis:6379: %v", err)
		return "redis", 6379
	}

	ec := elasticache.NewFromConfig(awsCfg, func(o *elasticache.Options) {
		if cfg.AWSEndpointURL != "" {
			o.BaseEndpoint = aws.String(cfg.AWSEndpointURL)
		}
	})

	for attempt := 1; attempt <= 20; attempt++ {
		out, err := ec.DescribeCacheClusters(ctx, &elasticache.DescribeCacheClustersInput{
			CacheClusterId:   aws.String(cfg.ElastiCacheClusterID),
			ShowCacheNodeInfo: aws.Bool(true),
		})
		if err == nil && len(out.CacheClusters) > 0 && len(out.CacheClusters[0].CacheNodes) > 0 {
			ep := out.CacheClusters[0].CacheNodes[0].Endpoint
			if ep != nil && ep.Address != nil && *ep.Address != "" {
				p := 6379
				if ep.Port != nil && *ep.Port > 0 {
					p = int(*ep.Port)
				}
				return *ep.Address, p
			}
		}
		log.Printf("[elasticache] waiting for cluster '%s' (attempt %d/20)", cfg.ElastiCacheClusterID, attempt)
		select {
		case <-ctx.Done():
			return "redis", 6379
		case <-time.After(time.Second):
		}
	}
	return "redis", 6379
}

func (s *RedisStore) Close() error {
	return s.client.Close()
}

func (s *RedisStore) Ping(ctx context.Context) error {
	return s.client.Ping(ctx).Err()
}

func matchKey(matchID string) string       { return "game:match:" + matchID }
func inputsKey(matchID string) string      { return "game:match:" + matchID + ":inputs" }
func ownerKey(matchID string) string       { return "game:match:" + matchID + ":owner" }
func StateChannel(matchID string) string   { return "game:match:" + matchID + ":state" }
func msgDoneKey(msgID string) string       { return "game:msg:done:" + msgID }
func msgClaimKey(msgID string) string      { return "game:msg:claim:" + msgID }
func finishedGuardKey(id string) string    { return "game:finished_emitted:" + id }

// IsMessageDone checks permanent idempotency marker.
func (s *RedisStore) IsMessageDone(ctx context.Context, messageID string) (bool, error) {
	n, err := s.client.Exists(ctx, msgDoneKey(messageID)).Result()
	return n > 0, err
}

// TryClaimMessage claims a short-lived processing lock so competing replicas don't run concurrently,
// while still allowing takeover if a replica is killed mid-processing (F2).
func (s *RedisStore) TryClaimMessage(ctx context.Context, messageID string, ttl time.Duration) (bool, error) {
	return s.client.SetNX(ctx, msgClaimKey(messageID), s.instanceID, ttl).Result()
}

func (s *RedisStore) MarkMessageDone(ctx context.Context, messageID string) error {
	pipe := s.client.Pipeline()
	pipe.Set(ctx, msgDoneKey(messageID), s.instanceID, 7*24*time.Hour)
	pipe.Del(ctx, msgClaimKey(messageID))
	_, err := pipe.Exec(ctx)
	return err
}

func (s *RedisStore) ReleaseMessageClaim(ctx context.Context, messageID string) {
	_ = s.client.Del(ctx, msgClaimKey(messageID)).Err()
}

func (s *RedisStore) SaveMatch(ctx context.Context, state *MatchState) error {
	state.UpdatedAt = time.Now().UnixMilli()
	data, err := json.Marshal(state)
	if err != nil {
		return err
	}
	pipe := s.client.Pipeline()
	pipe.Set(ctx, matchKey(state.MatchID), data, 4*time.Hour)
	if state.Status == "WAITING_FOR_PLAYERS" || state.Status == "PLAYING" {
		pipe.SAdd(ctx, ActiveMatchesSetKey, state.MatchID)
	} else {
		pipe.SRem(ctx, ActiveMatchesSetKey, state.MatchID)
	}
	_, err = pipe.Exec(ctx)
	return err
}

func (s *RedisStore) GetMatch(ctx context.Context, matchID string) (*MatchState, error) {
	raw, err := s.client.Get(ctx, matchKey(matchID)).Bytes()
	if errors.Is(err, redis.Nil) {
		return nil, ErrMatchNotFound
	}
	if err != nil {
		return nil, err
	}
	var st MatchState
	if err := json.Unmarshal(raw, &st); err != nil {
		return nil, err
	}
	return &st, nil
}

func (s *RedisStore) ListActiveMatches(ctx context.Context) ([]string, error) {
	return s.client.SMembers(ctx, ActiveMatchesSetKey).Result()
}

func (s *RedisStore) SetPlayerInput(ctx context.Context, matchID, side, direction string) error {
	pipe := s.client.Pipeline()
	pipe.HSet(ctx, inputsKey(matchID), side, direction)
	pipe.Expire(ctx, inputsKey(matchID), 4*time.Hour)
	_, err := pipe.Exec(ctx)
	return err
}

func (s *RedisStore) SetPlayerConnected(ctx context.Context, matchID, side string, connected bool) error {
	val := "0"
	if connected {
		val = "1"
	}
	pipe := s.client.Pipeline()
	pipe.HSet(ctx, inputsKey(matchID), side+"_connected", val)
	pipe.Expire(ctx, inputsKey(matchID), 4*time.Hour)
	_, err := pipe.Exec(ctx)
	return err
}

func (s *RedisStore) SetPlayerForfeit(ctx context.Context, matchID, side string) error {
	pipe := s.client.Pipeline()
	pipe.HSet(ctx, inputsKey(matchID), "forfeit", side)
	pipe.Expire(ctx, inputsKey(matchID), 4*time.Hour)
	_, err := pipe.Exec(ctx)
	return err
}

func (s *RedisStore) GetInputs(ctx context.Context, matchID string) (map[string]string, error) {
	return s.client.HGetAll(ctx, inputsKey(matchID)).Result()
}

func (s *RedisStore) AcquireLease(ctx context.Context, matchID string, ttl time.Duration) (bool, error) {
	return s.client.SetNX(ctx, ownerKey(matchID), s.instanceID, ttl).Result()
}

func (s *RedisStore) RenewLease(ctx context.Context, matchID string, ttl time.Duration) (bool, error) {
	res, err := renewLeaseScript.Run(ctx, s.client, []string{ownerKey(matchID)}, s.instanceID, ttl.Milliseconds()).Int()
	if err != nil {
		return false, err
	}
	return res == 1, nil
}

func (s *RedisStore) ReleaseLease(ctx context.Context, matchID string) {
	_ = releaseLeaseScript.Run(ctx, s.client, []string{ownerKey(matchID)}, s.instanceID).Err()
}

func (s *RedisStore) PublishState(ctx context.Context, matchID string, frame any) error {
	data, err := json.Marshal(frame)
	if err != nil {
		return err
	}
	return s.client.Publish(ctx, StateChannel(matchID), data).Err()
}

func (s *RedisStore) SubscribeState(ctx context.Context, matchID string) *redis.PubSub {
	return s.client.Subscribe(ctx, StateChannel(matchID))
}

func (s *RedisStore) ClaimFinishEmission(ctx context.Context, matchID string) (bool, error) {
	return s.client.SetNX(ctx, finishedGuardKey(matchID), s.instanceID, 24*time.Hour).Result()
}

func (s *RedisStore) EnqueueOutbox(ctx context.Context, routingKey string, envelope any) error {
	body, err := json.Marshal(envelope)
	if err != nil {
		return err
	}
	item, err := json.Marshal(OutboxItem{RoutingKey: routingKey, Body: string(body)})
	if err != nil {
		return err
	}
	return s.client.RPush(ctx, OutboxListKey, item).Err()
}

func (s *RedisStore) PeekOutbox(ctx context.Context) (*OutboxItem, error) {
	raw, err := s.client.LIndex(ctx, OutboxListKey, 0).Bytes()
	if errors.Is(err, redis.Nil) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var item OutboxItem
	if err := json.Unmarshal(raw, &item); err != nil {
		return nil, err
	}
	return &item, nil
}

func (s *RedisStore) PopOutbox(ctx context.Context) error {
	return s.client.LPop(ctx, OutboxListKey).Err()
}
