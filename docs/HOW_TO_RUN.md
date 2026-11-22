# How to Run Distributed PongGame

This document explains how to run, configure, test, and demonstrate the **Distributed PongGame** architecture locally using **Docker** or **Podman**, **MiniStack** (AWS emulator), **Terraform** (Infrastructure as Code), and **RabbitMQ**.

---

## 1. Architecture Overview

| Service / Component | Language / Tech | Persistence / Backing Store | Responsibility |
| --- | --- | --- | --- |
| **Frontend** | React 19 + TypeScript + Vite (`frontend/`) | Stateless (per-tab `sessionStorage`) | Web UI at `http://localhost:8080`: auth, recovery code, profile/ELO, matchmaking, leaderboard, and 60 FPS Canvas Pong arena |
| **API Gateway** | Nginx (`gateway/default.conf`) | Stateless | Single entrypoint (`http://localhost:8080`), SPA proxy, HTTP routing & WebSocket load balancing across Game replicas |
| **Auth Service** | C# / .NET 10 (`services/PongGame.Auth`) | Dedicated PostgreSQL (`postgres-auth`) | Registration, login, refresh, recovery. Signs RS256 JWTs using **AWS KMS asymmetric key** (`Sign`) |
| **Player Service** | Java 21 / Spring Boot (`services/player-service`) | Dedicated PostgreSQL (`postgres-player`) + ElastiCache cache | Player profiles, ELO statistics, match history, leaderboard, transactional idempotency (`processed_messages`) |
| **Matchmaking Service** | Java 21 / Spring Boot (`services/matchmaking-service`) | ElastiCache Redis (no SQL DB) | Async `202 Accepted` queue API, atomic Lua pairing & arena slot concurrency control, Saga compensation, Redis outbox |
| **Game Service (x2+)** | Go 1.24 (`services/game-service`) | ElastiCache Redis distributed memory + Pub/Sub (no SQL DB) | Multi-instance real-time Pong simulation, WebSocket state streaming, lease-based failover, competing RabbitMQ consumers, simulated unstable dependency |
| **Message Broker** | RabbitMQ 4 (`rabbitmq`) | Durable exchanges/queues + DLX/DLQ | Event-driven choreography (`pong.events`, `pong.dlx`) |
| **Cloud Services** | MiniStack (`ministack:4566`) provisioned by **Terraform** (`infra/terraform`) | AWS KMS (`RSA_2048 SIGN_VERIFY`), AWS ElastiCache (Redis 7), AWS SSM Parameter Store | Cloud services emulated locally (zero hardcoded endpoints; same code runs on AWS) |

### Asymmetric JWT Authentication with AWS KMS
1. **Terraform** creates an asymmetric `RSA_2048` KMS key (`key_usage = "SIGN_VERIFY"`) with alias `alias/ponggame-jwt-signing`.
2. **Auth Service (C#)** calls AWS KMS `Sign` (`RSASSA_PKCS1_V1_5_SHA_256`) to sign access tokens without ever exporting the private key, and serves the public key via `GET /api/auth/jwks`.
3. **Player Service (Java)**, **Matchmaking Service (Java)**, and **Game Service (Go)** call AWS KMS `GetPublicKey` for `alias/ponggame-jwt-signing`, **cache the RSA public key** in memory (TTL configurable via `KMS_PUBLIC_KEY_CACHE_TTL_SECONDS`, with automatic force-refresh on signature mismatch), and verify `RS256` JWTs locally.

### Distributed Memory & Multi-Instance Game Service
- `game-1` and `game-2` (or more replicas) sit behind Nginx (`upstream game_cluster`).
- Player 1 may connect via WebSocket to `game-1` while Player 2 connects to `game-2`.
- Authoritative match state, paddle inputs, and simulation ownership leases live in **ElastiCache Redis**.
- The active lease holder ticks Pong physics at `GAME_TICK_HZ` (default 30 Hz), writes state to Redis, and publishes snapshots over **Redis Pub/Sub** (`game:match:{matchId}:state`). Every replica forwards frames in real time to its connected WebSocket clients.
- If a Game instance crashes mid-match, another replica acquires the expired 3-second Redis lease and seamlessly continues the match.

---

## 2. Prerequisites

- **Docker** (24+) with `docker compose` v2, **or** **Podman** with `podman compose` / `docker-compose`
- Optional (for running automated E2E test): **Python 3.10+** (standard library only, no `pip install` needed)
- Optional (for running Terraform manually outside containers): **Terraform >= 1.6**

---

## 3. Quick Start (Single Command)

### Option A: Docker Compose (Recommended)

```bash
# Optional: customize environment variables
cp .env.example .env

# Build all services, start MiniStack, run Terraform IaC, and wait for all health checks
docker compose up --build -d --wait
```

### Option B: Podman Compose

```bash
cp .env.example .env

# With podman compose (or podman-compose):
podman compose up --build -d --wait
```

> **Note on SELinux (Fedora / RHEL):** `compose.yaml` already includes `security_opt: [label:disable]` for MiniStack and `:ro,z` for the Nginx bind mount so it works out of the box on SELinux-enforcing Linux hosts.

### Verify All Services Are Healthy

```bash
docker compose ps
curl -s http://localhost:8080/health
curl -s http://localhost:8080/api/auth/jwks
curl -s http://localhost:8080/api/matchmaking/status
curl -s http://localhost:4566/_ministack/health
```

RabbitMQ Management UI is available at **http://localhost:15672** (default user `pong`, password `pong_secret`).

---

## 4. Infrastructure as Code (Terraform)

All cloud resources live in `infra/terraform/`:
- `kms.tf`: `aws_kms_key` (`RSA_2048`, `SIGN_VERIFY`) + `aws_kms_alias` (`alias/ponggame-jwt-signing`)
- `elasticache.tf`: `aws_elasticache_cluster` (`ponggame-redis`, Redis 7)
- `ssm.tf`: `aws_ssm_parameter` entries for JWT & cache configuration
- `providers.tf`: points to MiniStack (`var.aws_endpoint_url = "http://localhost:4566"`) by default; set `TF_VAR_aws_endpoint_url=""` to target real AWS.

During `docker compose up`, the `terraform-init` container automatically runs `infra/terraform/apply.sh` against MiniStack.

To run Terraform manually against a running MiniStack on `localhost:4566`:

```bash
# Using local Terraform CLI:
cd infra/terraform
terraform init
terraform apply -auto-approve

# Or using the Terraform Docker image (no local Terraform required):
docker run --rm --network host -v "$(pwd)/infra/terraform:/work:z" -w /work \
  -e TF_VAR_aws_endpoint_url=http://127.0.0.1:4566 \
  hashicorp/terraform:1.11 init
docker run --rm --network host -v "$(pwd)/infra/terraform:/work:z" -w /work \
  -e TF_VAR_aws_endpoint_url=http://127.0.0.1:4566 \
  hashicorp/terraform:1.11 apply -auto-approve
```

---

## 5. Automated End-to-End Verification

Run the built-in Python verification script (uses stdlib only):

```bash
python3 scripts/e2e_test.py
```

This tests the entire distributed lifecycle:
1. Auth registration & login with KMS `RS256` signing
2. Player Service KMS public-key JWT validation
3. Async `202 Accepted` matchmaking queue & RabbitMQ `match.created` -> `match.ready`
4. Dual-player real-time WebSocket Pong gameplay across load-balanced Go replicas (`game-1` / `game-2`)
5. Multi-consumer `match.finished` event (Player Service stats/history + Matchmaking slot release)
6. **R2 / F5**: 30 parallel arena slot reservations against 10 slots -> strictly 10 `201 Created` & 20 `409 Conflict`
7. **R1 / F3**: 5 duplicate `match.finished` deliveries with identical `messageId` -> strictly 1 stat update
8. **F4**: Poison message rejected after retries and routed to `player.match.finished.dlq`

---

## 6. HTTP & WebSocket API Reference

All endpoints are exposed through the Nginx Gateway at `http://localhost:8080`:

### Auth Service (`/api/auth`)
- `POST /api/auth/register` — `{ "username", "password" }` -> `201 Created`
- `POST /api/auth/login` — `{ "username", "password" }` -> `200 OK` (`accessToken`, `refreshToken`)
- `POST /api/auth/refresh` — `{ "refreshToken" }` -> `200 OK`
- `POST /api/auth/recover` — `{ "username", "recoveryCode", "newPassword" }` -> `200 OK`
- `GET /api/auth/jwks` — Public RSA key fetched from KMS

### Player Service (`/api/players`)
- `GET /api/players/me` (Bearer JWT) — Current player profile & statistics (cached in ElastiCache)
- `GET /api/players/{playerId}` (Bearer JWT) — Player profile by UUID
- `GET /api/players/me/matches?limit=20` (Bearer JWT) — Authenticated player's match history
- `GET /api/players/{playerId}/matches?limit=20` (Bearer JWT) — Match history by player UUID
- `GET /api/players/leaderboard?limit=20` — Top players ordered by ELO

### Matchmaking Service (`/api/matchmaking`)
- `POST /api/matchmaking/queue` (Bearer JWT) -> **`202 Accepted`** with `ticketId`, `status`, `correlationId`
- `GET /api/matchmaking/tickets/{ticketId}` (Bearer JWT) — Poll status (`QUEUED`, `PROVISIONING`, `READY` with `matchId` & `wsPath`, `COMPENSATED`, `CANCELLED`, `FINISHED`)
- `DELETE /api/matchmaking/queue` (Bearer JWT) — Leave matchmaking queue
- `POST /api/matchmaking/arenas/reserve` (Bearer JWT) — Atomic Lua arena slot reservation (`201 Created` / `409 Conflict`)
- `DELETE /api/matchmaking/arenas/reserve/{reservationId}` (Bearer JWT) — Release arena reservation
- `GET /api/matchmaking/status` — Queue length, active matches, available slots, outbox depth

### Game Service (`/api/game` & `/ws/game`)
- `GET /api/game/matches/{matchId}` (Bearer JWT) — Current match state from ElastiCache distributed memory
- `WS ws://localhost:8080/ws/game/{matchId}?token=<jwt>` (or `Authorization: Bearer <jwt>` header):
  - **Client -> Server commands:**
    - `{"type":"input","direction":"UP"}`
    - `{"type":"input","direction":"DOWN"}`
    - `{"type":"input","direction":"STOP"}`
    - `{"type":"forfeit"}`
  - **Server -> Client real-time frames (~30 Hz):**
    - `{"type":"welcome","side":"left|right","instanceId":"game-1","state":{...}}`
    - `{"type":"state","matchId":"...","instanceId":"game-1","status":"PLAYING","tick":42,"board":{...},"ball":{...},"paddles":{...}}`

---

## 7. Messaging Contracts (`contracts/`)

Every RabbitMQ message uses the standard envelope (`contracts/envelope.schema.json`):
```json
{
  "messageId": "uuid",
  "correlationId": "uuid",
  "type": "match.created",
  "version": 1,
  "occurredAt": "2026-11-22T20:00:00Z",
  "payload": {}
}
```

See `contracts/asyncapi.yaml` and `contracts/*.schema.json` for all schemas:
- `match.created` (v1): Matchmaking -> Game (`game.match.created`)
- `match.ready` (v1): Game -> Matchmaking (`matchmaking.match.ready`)
- `match.provisioning_failed` (v1): Game -> Matchmaking (`matchmaking.match.failed`)
- `match.cancel` (v1): Matchmaking -> Game (`game.match.cancel`)
- `match.finished` (v1): Game -> **Player Service** (`player.match.finished`) **AND** **Matchmaking Service** (`matchmaking.match.finished`)

---

## 8. Demonstrating Distributed Failure Scenarios (F1–F8)

| Scenario | How to Trigger | Expected Result |
| --- | --- | --- |
| **F1 — Saga Compensation** | Restart game replicas with `DEPENDENCY_FAILURE_RATE=1.0`:<br>`DEPENDENCY_FAILURE_RATE=1.0 docker compose up -d game-1 game-2` | Matchmaking pairs 2 players, Game fails arena provisioning and emits `match.provisioning_failed`; Matchmaking transitions both tickets to `COMPENSATED`, releases the arena slot & player locks. |
| **F2 — Consumer Killed Mid-Processing** | `docker kill ponggame-game-1-1` during provisioning or mid-match | RabbitMQ redelivers unacked `match.created` to `game-2` (or `game-2` acquires the 3s Redis lease mid-match); effect applied strictly once. |
| **F3 — Duplicate Message** | Republish same `messageId` 5x (covered automatically in `scripts/e2e_test.py`) | Player Service `processed_messages` table & Redis `SETNX` guards ensure strictly 1 effect. |
| **F4 — Poison Message -> DLQ** | Publish malformed JSON to `pong.events` (covered automatically in `scripts/e2e_test.py`) | Retried 3 times, then routed to `*.dlq` while valid traffic continues unaffected. |
| **F5 — Concurrency Race** | 30–100 concurrent `POST /api/matchmaking/arenas/reserve` calls for `MATCHMAKING_MAX_CONCURRENT_MATCHES=10` | Atomic Redis Lua script grants strictly 10 slots and rejects the rest with `409 Conflict`. |
| **F6 — Broker Outage & Recovery** | `docker compose stop rabbitmq && sleep 30 && docker compose start rabbitmq` | Outbound events accumulate in Redis outbox (`mm:outbox` / `game:outbox`). All services automatically reconnect without manual restart and drain pending events. |
| **F7 — Late Response Compensation** | `DEPENDENCY_MIN_LATENCY_MS=12000 DEPENDENCY_MAX_LATENCY_MS=13000 MATCHMAKING_PROVISIONING_TIMEOUT_MS=3000 docker compose up -d matchmaking game-1 game-2` | Matchmaking expires the match at 3s (`COMPENSATED` + `match.cancel`), and when late `match.ready` arrives at 12s, Matchmaking emits `match.cancel` so Game tears down the orphaned arena. |
| **F8 — Horizontal Scaling** | Add a third Game replica (`game-3`) or run `game-1` & `game-2` under load | Competing consumers on `game.match.created` distribute provisioning across replicas with zero duplicate match state. |

---

## 9. Stopping & Cleaning Up

```bash
# Stop containers (preserves DB & Redis volumes)
docker compose down

# Stop and wipe all volumes (clean slate)
docker compose down -v
```
