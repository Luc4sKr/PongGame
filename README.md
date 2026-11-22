# Distributed PongGame

A polyglot, event-driven distributed Pong platform built with **C# (.NET 10)**, **Java 21 (Spring Boot)**, and **Go 1.24**, using **RabbitMQ**, **PostgreSQL** (database-per-service), and AWS cloud services (**KMS**, **ElastiCache**, **SSM Parameter Store**) emulated locally with **MiniStack** and provisioned via **Terraform**.

## Services

- **Frontend** (`frontend/` — **React + TypeScript + Vite**): Web UI at `http://localhost:8080` for authentication (register/login/recovery), matchmaking lobby, live ELO stats & match history, leaderboard, and 60 FPS HTML5 Canvas Pong gameplay over WebSockets.
- **API Gateway** (`gateway/`): Nginx HTTP reverse proxy and WebSocket load balancer (`http://localhost:8080`).
- **Auth Service** (`services/PongGame.Auth` — **C# / .NET 10**): Account registration, login, refresh, recovery, and asymmetric `RS256` JWT signing via **AWS KMS** (`Sign` + `GetPublicKey`). Dedicated PostgreSQL database (`postgres-auth`).
- **Player Service** (`services/player-service` — **Java 21 / Spring Boot**): Player profiles, ELO rankings, statistics, match history, ElastiCache profile caching, KMS public-key JWT validation, and transactional RabbitMQ idempotency (`processed_messages`). Dedicated PostgreSQL database (`postgres-player`).
- **Matchmaking Service** (`services/matchmaking-service` — **Java 21 / Spring Boot**): Asynchronous `202 Accepted` matchmaking queue backed by **ElastiCache Redis** (atomic Lua pairing & arena slot concurrency control), Saga compensation, Redis outbox, and KMS public-key JWT validation.
- **Game Service** (`services/game-service` — **Go 1.24**, 2+ replicas): Real-time WebSocket Pong engine synchronized via **ElastiCache Redis** distributed memory & Pub/Sub, lease-based failover across replicas, competing RabbitMQ consumers, simulated unstable dependency, and KMS public-key JWT validation.
- **Contracts** (`contracts/`): Explicit AsyncAPI 3.0 specification and JSON Schemas using the standard event envelope.
- **Infrastructure** (`infra/terraform/`): Terraform IaC provisioning AWS KMS asymmetric key + alias, ElastiCache Redis cluster, and SSM parameters against MiniStack (or real AWS).

## Quick Start

```bash
cp .env.example .env
docker compose up --build -d --wait
python3 scripts/e2e_test.py
```

Then open **http://localhost:8080** in two browser tabs side-by-side to register/login two players and play a real-time distributed Pong match!

For full setup instructions (Docker & Podman, Terraform, API/WebSocket protocol, and distributed failure scenarios F1–F8), see **[docs/HOW_TO_RUN.md](docs/HOW_TO_RUN.md)**.
