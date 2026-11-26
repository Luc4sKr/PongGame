package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	ServerPort                string
	InstanceID                string
	AWSRegion                 string
	AWSEndpointURL            string
	AWSAccessKeyID            string
	AWSSecretAccessKey        string
	KMSKeyID                  string
	KMSCacheTTLSeconds        int
	JWTIssuer                 string
	JWTAudience               string
	ElastiCacheClusterID      string
	RabbitMQURL               string
	RabbitMQEventsExchange    string
	RabbitMQDLXExchange       string
	MatchCreatedQueue         string
	MatchCancelQueue          string
	DependencyMinLatencyMs    int
	DependencyMaxLatencyMs    int
	DependencyFailureRate     float64
	GameTickHz                int
	DefaultWinningScore       int
}

func Load() Config {
	hostname, _ := os.Hostname()
	if hostname == "" {
		hostname = "game-instance"
	}

	rabbitURL := strings.TrimSpace(os.Getenv("RABBITMQ_URL"))
	if rabbitURL == "" {
		host := envOr("RABBITMQ_HOST", "rabbitmq")
		port := envOr("RABBITMQ_PORT", "5672")
		user := envOr("RABBITMQ_USERNAME", "pong")
		pass := envOr("RABBITMQ_PASSWORD", "pong_secret")
		rabbitURL = fmt.Sprintf("amqp://%s:%s@%s:%s/", user, pass, host, port)
	}

	return Config{
		ServerPort:             envOr("SERVER_PORT", "8080"),
		InstanceID:             envOr("INSTANCE_ID", hostname),
		AWSRegion:              envOr("AWS_REGION", "us-east-1"),
		AWSEndpointURL:         strings.TrimSpace(os.Getenv("AWS_ENDPOINT_URL")),
		AWSAccessKeyID:         envOr("AWS_ACCESS_KEY_ID", "000000000000"),
		AWSSecretAccessKey:     envOr("AWS_SECRET_ACCESS_KEY", "test"),
		KMSKeyID:               envOr("KMS_KEY_ID", "alias/ponggame-jwt-signing"),
		KMSCacheTTLSeconds:     envInt("KMS_PUBLIC_KEY_CACHE_TTL_SECONDS", 300),
		JWTIssuer:              envOr("JWT_ISSUER", "PongGame.Auth"),
		JWTAudience:            envOr("JWT_AUDIENCE", "PongGame"),
		ElastiCacheClusterID:   envOr("ELASTICACHE_CLUSTER_ID", "ponggame-redis"),
		RabbitMQURL:            rabbitURL,
		RabbitMQEventsExchange: envOr("RABBITMQ_EVENTS_EXCHANGE", "pong.events"),
		RabbitMQDLXExchange:    envOr("RABBITMQ_DLX_EXCHANGE", "pong.dlx"),
		MatchCreatedQueue:      envOr("GAME_MATCH_CREATED_QUEUE", "game.match.created"),
		MatchCancelQueue:       envOr("GAME_MATCH_CANCEL_QUEUE", "game.match.cancel"),
		DependencyMinLatencyMs: envInt("DEPENDENCY_MIN_LATENCY_MS", 50),
		DependencyMaxLatencyMs: envInt("DEPENDENCY_MAX_LATENCY_MS", 150),
		DependencyFailureRate:  envFloat("DEPENDENCY_FAILURE_RATE", 0.0),
		GameTickHz:             envInt("GAME_TICK_HZ", 30),
		DefaultWinningScore:    envInt("GAME_DEFAULT_WINNING_SCORE", 5),
	}
}

func envOr(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

func envInt(key string, fallback int) int {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return fallback
	}
	return n
}

func envFloat(key string, fallback float64) float64 {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	f, err := strconv.ParseFloat(v, 64)
	if err != nil {
		return fallback
	}
	return f
}
