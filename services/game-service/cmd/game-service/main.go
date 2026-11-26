package main

import (
	"context"
	"log"
	"net/http"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/ponggame/game-service/internal/auth"
	"github.com/ponggame/game-service/internal/config"
	"github.com/ponggame/game-service/internal/engine"
	"github.com/ponggame/game-service/internal/messaging"
	"github.com/ponggame/game-service/internal/store"
	"github.com/ponggame/game-service/internal/telemetry"
	"github.com/ponggame/game-service/internal/ws"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

func main() {
	cfg := config.Load()
	log.Printf("[game-service:%s] starting on :%s", cfg.InstanceID, cfg.ServerPort)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	shutdownTelemetry, err := telemetry.Init(ctx, cfg.InstanceID)
	if err != nil {
		log.Printf("[telemetry] initialization failed: %v", err)
	} else {
		defer shutdownTelemetry()
	}

	verifier, err := auth.NewVerifier(ctx, cfg)
	if err != nil {
		log.Fatalf("[game-service:%s] KMS JWT verifier init failed: %v", cfg.InstanceID, err)
	}

	redisStore, err := store.NewRedisStore(ctx, cfg)
	if err != nil {
		log.Fatalf("[game-service:%s] ElastiCache Redis store init failed: %v", cfg.InstanceID, err)
	}
	defer redisStore.Close()

	engineMgr := engine.NewManager(redisStore, cfg.InstanceID, cfg.GameTickHz)
	go engineMgr.StartFailoverWatcher(ctx)

	broker := messaging.NewBroker(cfg, redisStore, engineMgr)
	go broker.Run(ctx)
	telemetry.Gauge("pong.game.broker.connected", func(context.Context) (int64, error) {
		if broker.IsConnected() {
			return 1, nil
		}
		return 0, nil
	})

	handler := ws.NewHandler(cfg.InstanceID, verifier, redisStore, engineMgr, broker)
	srv := &http.Server{
		Addr: ":" + cfg.ServerPort,
		Handler: otelhttp.NewHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			span := trace.SpanFromContext(r.Context())
			span.SetAttributes(attribute.String("http.route", route(r.URL.Path)))
			w.Header().Set("X-Trace-Id", span.SpanContext().TraceID().String())
			handler.Routes().ServeHTTP(w, r)
		}), "game.http", otelhttp.WithFilter(func(r *http.Request) bool { return r.URL.Path != "/health" }),
			otelhttp.WithSpanNameFormatter(func(_ string, r *http.Request) string { return r.Method + " " + route(r.URL.Path) })),
		ReadHeaderTimeout: 10 * time.Second,
	}

	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("[game-service:%s] HTTP server error: %v", cfg.InstanceID, err)
		}
	}()

	<-ctx.Done()
	log.Printf("[game-service:%s] shutting down gracefully...", cfg.InstanceID)
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
}

func route(path string) string {
	for _, prefix := range []string{"/ws/game/", "/api/game/matches/"} {
		if strings.HasPrefix(path, prefix) {
			return prefix + "{matchId}"
		}
	}
	return path
}
