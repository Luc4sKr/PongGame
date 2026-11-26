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
- **Observability** (`infra/observability/`): OpenTelemetry Collector, Grafana, Tempo, Prometheus, and Loki. The Grafana dashboard shows traces from joining the queue to recording the match result.

The [relational database diagram](docs/modelo-relacional.svg) shows the Auth and Player tables, foreign keys, indexes, and how the two services share the player's identity.

## Quick Start

Use Docker Compose v2 with Linux containers (Docker Desktop with WSL 2 on Windows).

Windows (PowerShell):

```powershell
# Optional, only if .env does not exist:
Copy-Item .env.example .env
docker compose up --build -d --wait
```

Linux:

```bash
cp .env.example .env
docker compose up --build -d --wait
```

Then open **http://localhost:8080** in two browser tabs side-by-side to register/login two players and play a real-time distributed Pong match!

Open the **[PongGame dashboard](http://127.0.0.1:3000/d/ponggame)**, click a Trace ID under **Traces de partidas**, and choose **Abrir trace da partida**. See the [tracing walkthrough](docs/OBSERVABILIDADE.md) for the expected spans by service.

See the [deployment and API guide](docs/HOW_TO_RUN.md) and the [failure diagnosis guide F1–F8](docs/DEMO_FALHAS.md).
