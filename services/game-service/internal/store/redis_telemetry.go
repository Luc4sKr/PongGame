package store

import (
	"context"
	"strconv"
	"strings"
	"time"

	"github.com/ponggame/game-service/internal/telemetry"
	"github.com/redis/go-redis/v9"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// Aggregate Redis latency instead of emitting a span for every 30 Hz game tick.
// Only the command name is recorded: keys and payloads may contain player data.
type redisMetricsHook struct{}

func (redisMetricsHook) DialHook(next redis.DialHook) redis.DialHook { return next }
func (redisMetricsHook) ProcessHook(next redis.ProcessHook) redis.ProcessHook {
	return func(ctx context.Context, cmd redis.Cmder) error {
		start := time.Now()
		err := next(ctx, cmd)
		recordRedis(ctx, cmd.Name(), start, err)
		return err
	}
}
func (redisMetricsHook) ProcessPipelineHook(next redis.ProcessPipelineHook) redis.ProcessPipelineHook {
	return func(ctx context.Context, cmds []redis.Cmder) error {
		start := time.Now()
		err := next(ctx, cmds)
		recordRedis(ctx, "pipeline", start, err)
		return err
	}
}
func recordRedis(ctx context.Context, operation string, start time.Time, err error) {
	h, _ := otel.Meter(telemetry.Scope).Float64Histogram("pong.redis.operation.duration", metric.WithUnit("s"))
	h.Record(ctx, time.Since(start).Seconds(), metric.WithAttributes(
		attribute.String("db.operation.name", operation), attribute.Bool("failed", err != nil && err != redis.Nil)))
}
func registerRedisMetrics(client *redis.Client) {
	client.AddHook(redisMetricsHook{})
	telemetry.Gauge("pong.redis.memory.used", func(ctx context.Context) (int64, error) {
		info, err := client.Info(ctx, "memory").Result()
		if err != nil {
			return 0, err
		}
		for _, line := range strings.Split(info, "\n") {
			if strings.HasPrefix(line, "used_memory:") {
				return strconv.ParseInt(strings.TrimSpace(strings.TrimPrefix(line, "used_memory:")), 10, 64)
			}
		}
		return 0, nil
	})
}
