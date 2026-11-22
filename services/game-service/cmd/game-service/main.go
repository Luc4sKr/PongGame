package main

import (
	"context"
	"log"
	"net/http"
	"os/signal"
	"syscall"
	"time"

	"github.com/ponggame/game-service/internal/auth"
	"github.com/ponggame/game-service/internal/config"
	"github.com/ponggame/game-service/internal/engine"
	"github.com/ponggame/game-service/internal/messaging"
	"github.com/ponggame/game-service/internal/store"
	"github.com/ponggame/game-service/internal/ws"
)

func main() {
	cfg := config.Load()
	log.Printf("[game-service:%s] starting on :%s", cfg.InstanceID, cfg.ServerPort)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

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

	handler := ws.NewHandler(cfg.InstanceID, verifier, redisStore, engineMgr, broker)
	srv := &http.Server{
		Addr:              ":" + cfg.ServerPort,
		Handler:           handler.Routes(),
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
