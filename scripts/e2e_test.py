#!/usr/bin/env python3
"""
End-to-end verification script for Distributed PongGame.
Uses Python standard library only (zero external dependencies).

Tests:
  1. Health checks for Gateway & all services
  2. Auth Service (C# + AWS KMS asymmetric signing): register & login 2 players
  3. Player Service (Java + KMS cached public key): verify JWT auth & initial profile
  4. Matchmaking Service (Java + ElastiCache Redis + RabbitMQ): 202 Accepted async queue -> match.created -> Game provisioning -> READY
  5. Game Service (Go x2 replicas + ElastiCache distributed memory + Redis Pub/Sub):
     - Both players connect via WebSocket through Nginx load balancer
     - Send UP/DOWN/STOP paddle commands & receive real-time Pong frames
     - Complete a match and observe match.finished event propagation
  6. Multi-consumer verification:
     - Player Service persisted stats + match history in PostgreSQL
     - Matchmaking Service released arena slot & player locks in Redis
  7. R2 / F5 Concurrency Control: 30 parallel arena slot reservations against 10 slots -> strictly 10 created, 20 conflicts
  8. R1 / F3 Idempotency: republish duplicate match.finished with same messageId -> zero duplicate stat increment
  9. F4 Poison Message DLQ: publish invalid payload to player.match.finished -> routed to player.match.finished.dlq
"""

import base64
import concurrent.futures
import hashlib
import json
import os
import secrets
import socket
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8080")
RABBIT_API = os.environ.get("RABBITMQ_API", "http://127.0.0.1:15672/api")
RABBIT_USER = os.environ.get("RABBITMQ_USERNAME", "pong")
RABBIT_PASS = os.environ.get("RABBITMQ_PASSWORD", "pong_secret")


def http_json(method: str, url: str, body=None, token: str | None = None, basic_auth=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if basic_auth:
        creds = base64.b64encode(f"{basic_auth[0]}:{basic_auth[1]}".encode()).decode()
        headers["Authorization"] = f"Basic {creds}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw) if raw else None
        except Exception:
            parsed = raw
        return e.code, parsed


class SimpleWSClient:
    """Minimal RFC 6455 WebSocket client using Python stdlib sockets."""

    def __init__(self, host: str, port: int, path: str, token: str):
        self.sock = socket.create_connection((host, port), timeout=10)
        key = base64.b64encode(secrets.token_bytes(16)).decode()
        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Authorization: Bearer {token}\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(req.encode("ascii"))
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("WebSocket handshake closed prematurely")
            buf += chunk
        header_part, self.extra = buf.split(b"\r\n\r\n", 1)
        status_line = header_part.split(b"\r\n")[0].decode()
        if "101" not in status_line:
            raise RuntimeError(f"WebSocket handshake failed: {status_line}")

    def _read_exact(self, n: int) -> bytes:
        while len(self.extra) < n:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise EOFError("WebSocket closed")
            self.extra += chunk
        out, self.extra = self.extra[:n], self.extra[n:]
        return out

    def send_json(self, obj: dict):
        payload = json.dumps(obj).encode("utf-8")
        mask_key = secrets.token_bytes(4)
        header = bytearray([0x81])  # FIN + text frame
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < 65536:
            header.append(0x80 | 126)
            header.extend(struct.pack("!H", length))
        else:
            header.append(0x80 | 127)
            header.extend(struct.pack("!Q", length))
        header.extend(mask_key)
        masked = bytes(b ^ mask_key[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def recv_json(self, timeout: float = 5.0) -> dict:
        self.sock.settimeout(timeout)
        while True:
            b1, b2 = self._read_exact(2)
            opcode = b1 & 0x0F
            masked = (b2 & 0x80) != 0
            length = b2 & 0x7F
            if length == 126:
                length = struct.unpack("!H", self._read_exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self._read_exact(8))[0]
            mask = self._read_exact(4) if masked else b""
            payload = self._read_exact(length)
            if masked:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if opcode == 0x8:  # close
                raise EOFError("WebSocket close frame received")
            if opcode == 0x9:  # ping -> pong
                continue
            if opcode == 0x1:  # text
                return json.loads(payload.decode("utf-8"))

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


def assert_eq(actual, expected, label):
    if actual != expected:
        raise AssertionError(f"{label}: expected {expected!r}, got {actual!r}")


def main():
    suffix = secrets.token_hex(4)
    p1_user = f"alice_{suffix}"
    p2_user = f"bob_{suffix}"
    password = "SuperStrongPassword123!"

    print("1. Checking Gateway & Auth JWKS...")
    st, body = http_json("GET", f"{GATEWAY_URL}/health")
    assert_eq(st, 200, "gateway /health")
    st, jwks = http_json("GET", f"{GATEWAY_URL}/api/auth/jwks")
    assert_eq(st, 200, "auth /jwks")
    assert len(jwks["keys"]) == 1 and jwks["keys"][0]["alg"] == "RS256", f"unexpected JWKS: {jwks}"
    print(f"   OK (KMS RSA JWK kid={jwks['keys'][0]['kid']})")

    print("2. Registering & logging in 2 players via Auth Service (KMS RS256 signing)...")
    st, r1 = http_json("POST", f"{GATEWAY_URL}/api/auth/register", {"username": p1_user, "password": password})
    assert_eq(st, 201, "register p1")
    st, r2 = http_json("POST", f"{GATEWAY_URL}/api/auth/register", {"username": p2_user, "password": password})
    assert_eq(st, 201, "register p2")

    st, l1 = http_json("POST", f"{GATEWAY_URL}/api/auth/login", {"username": p1_user, "password": password})
    assert_eq(st, 200, "login p1")
    st, l2 = http_json("POST", f"{GATEWAY_URL}/api/auth/login", {"username": p2_user, "password": password})
    assert_eq(st, 200, "login p2")
    t1, t2 = l1["accessToken"], l2["accessToken"]
    print(f"   OK ({p1_user}={r1['userId']}, {p2_user}={r2['userId']})")

    print("3. Verifying KMS public-key JWT validation on Player Service...")
    st, unauth = http_json("GET", f"{GATEWAY_URL}/api/players/me")
    assert_eq(st, 401, "unauthenticated player/me rejected")
    st, prof1 = http_json("GET", f"{GATEWAY_URL}/api/players/me", token=t1)
    assert_eq(st, 200, "player1 profile")
    assert_eq(prof1["username"], p1_user, "player1 username")
    st, prof2 = http_json("GET", f"{GATEWAY_URL}/api/players/me", token=t2)
    assert_eq(st, 200, "player2 profile")
    print(f"   OK (ELO={prof1['elo']}, matchesPlayed={prof1['matchesPlayed']})")

    print("4. Testing Async Matchmaking (202 Accepted) -> RabbitMQ -> Game Service provisioning...")
    st, q1 = http_json("POST", f"{GATEWAY_URL}/api/matchmaking/queue", token=t1)
    assert_eq(st, 202, "p1 join queue returns 202 Accepted")
    st, q2 = http_json("POST", f"{GATEWAY_URL}/api/matchmaking/queue", token=t2)
    assert_eq(st, 202, "p2 join queue returns 202 Accepted")

    ticket1_id = q1["ticketId"]
    ticket2_id = q2["ticketId"]
    match_id = None
    ws_path = None
    for _ in range(30):
        st, tk = http_json("GET", f"{GATEWAY_URL}/api/matchmaking/tickets/{ticket1_id}", token=t1)
        assert_eq(st, 200, "poll ticket1")
        if tk["status"] == "READY":
            match_id = tk["matchId"]
            ws_path = tk["wsPath"]
            break
        time.sleep(0.25)
    assert match_id and ws_path, f"Match did not reach READY in time; last ticket={tk}"
    print(f"   OK (matchId={match_id}, wsPath={ws_path})")

    print("5. Playing real-time Pong over WebSockets through Nginx load-balanced Go replicas...")
    gateway = urllib.parse.urlsplit(GATEWAY_URL)
    ws1 = SimpleWSClient(gateway.hostname, gateway.port or 80, ws_path, t1)
    ws2 = SimpleWSClient(gateway.hostname, gateway.port or 80, ws_path, t2)
    try:
        w1 = ws1.recv_json()
        w2 = ws2.recv_json()
        assert_eq(w1["type"], "welcome", "ws1 welcome")
        assert_eq(w2["type"], "welcome", "ws2 welcome")
        print(f"   Connected: {p1_user} ({w1['side']}) -> {w1['instanceId']}, {p2_user} ({w2['side']}) -> {w2['instanceId']}")

        # Send paddle movement commands from both players
        ws1.send_json({"type": "input", "direction": "UP"})
        ws2.send_json({"type": "input", "direction": "DOWN"})

        playing_frames = 0
        moved_left = False
        moved_right = False
        initial_y = 250.0
        for _ in range(40):
            frame = ws1.recv_json(timeout=3.0)
            if frame.get("type") == "state" and frame.get("status") == "PLAYING":
                playing_frames += 1
                if frame["paddles"]["left"]["y"] < initial_y - 1:
                    moved_left = True
                if frame["paddles"]["right"]["y"] > initial_y + 1:
                    moved_right = True
                if playing_frames >= 10 and moved_left and moved_right:
                    break

        assert playing_frames >= 10, f"Expected real-time PLAYING frames, got {playing_frames}"
        assert moved_left and moved_right, f"Expected paddle movement sync (left={moved_left}, right={moved_right})"
        print(f"   OK (received {playing_frames} real-time frames, both paddles moved across distributed memory!)")

        # Forfeit player 2 to conclude match deterministically & verify match.finished propagation
        ws2.send_json({"type": "forfeit"})
        finished_frame = None
        for _ in range(60):
            frame = ws1.recv_json(timeout=3.0)
            if frame.get("type") == "state" and frame.get("status") == "FINISHED":
                finished_frame = frame
                break
        assert finished_frame is not None, "Did not receive FINISHED frame after forfeit"
        print(f"   OK (Match FINISHED, winnerId={finished_frame['winnerId']}, reason={finished_frame['finishReason']})")
    finally:
        ws1.close()
        ws2.close()

    print("6. Verifying multi-consumer event (match.finished -> Player Service + Matchmaking Service)...")
    for _ in range(20):
        st, p1_after = http_json("GET", f"{GATEWAY_URL}/api/players/me", token=t1)
        if p1_after["matchesPlayed"] == 1:
            break
        time.sleep(0.25)
    assert_eq(p1_after["matchesPlayed"], 1, "player1 matchesPlayed")
    assert_eq(p1_after["wins"], 1, "player1 wins")
    st, p1_matches = http_json("GET", f"{GATEWAY_URL}/api/players/me/matches", token=t1)
    assert_eq(st, 200, "player1 match history")
    assert len(p1_matches) == 1 and p1_matches[0]["matchId"] == match_id, f"unexpected history: {p1_matches}"

    for _ in range(20):
        st, mm_status = http_json("GET", f"{GATEWAY_URL}/api/matchmaking/status")
        st_tk, tk2 = http_json("GET", f"{GATEWAY_URL}/api/matchmaking/tickets/{ticket2_id}", token=t2)
        if tk2["status"] == "FINISHED" and mm_status["activeMatches"] == 0:
            break
        time.sleep(0.25)
    assert_eq(tk2["status"], "FINISHED", "matchmaking ticket transitioned to FINISHED")
    assert_eq(mm_status["activeMatches"], 0, "matchmaking released arena slot")
    print(f"   OK (Player ELO={p1_after['elo']}, history=1; Matchmaking activeMatches=0, ticket=FINISHED)")

    print("7. Testing R2 / F5 Concurrency Control (30 parallel reservations for 10 scarce arena slots)...")
    def reserve(_):
        return http_json("POST", f"{GATEWAY_URL}/api/matchmaking/arenas/reserve", token=t1)

    with concurrent.futures.ThreadPoolExecutor(max_workers=30) as pool:
        results = list(pool.map(reserve, range(30)))
    created = [b for s, b in results if s == 201]
    conflicts = [b for s, b in results if s == 409]
    assert_eq(len(created), 10, "strictly 10 arena slots reserved")
    assert_eq(len(conflicts), 20, "strictly 20 conflicts rejected")
    for item in created:
        st, _ = http_json("DELETE", f"{GATEWAY_URL}/api/matchmaking/arenas/reserve/{item['reservationId']}", token=t1)
        assert_eq(st, 200, "release test arena slot")
    print("   OK (10 created, 20 rejected with 409 Conflict, all 10 cleanly released)")

    print("8. Testing R1 / F3 Idempotency (republishing duplicate messageId 5 times -> single effect)...")
    dup_msg_id = str(uuid.uuid4())
    dup_match_id = str(uuid.uuid4())
    dup_event = {
        "messageId": dup_msg_id,
        "correlationId": str(uuid.uuid4()),
        "type": "match.finished",
        "version": 1,
        "occurredAt": "2026-11-22T20:00:00Z",
        "payload": {
            "matchId": dup_match_id,
            "player1Id": r1["userId"],
            "player1Username": p1_user,
            "player1Score": 5,
            "player2Id": r2["userId"],
            "player2Username": p2_user,
            "player2Score": 2,
            "winnerId": r1["userId"],
            "reason": "SCORE_LIMIT",
            "durationSeconds": 42,
            "finishedAt": "2026-11-22T20:00:00Z",
        },
    }
    for _ in range(5):
        st, _ = http_json(
            "POST",
            f"{RABBIT_API}/exchanges/%2f/pong.events/publish",
            {
                "properties": {"content_type": "application/json"},
                "routing_key": "match.finished",
                "payload": json.dumps(dup_event),
                "payload_encoding": "string",
            },
            basic_auth=(RABBIT_USER, RABBIT_PASS),
        )
        assert_eq(st, 200, "publish duplicate event")
    time.sleep(1.5)
    st, p1_idem = http_json("GET", f"{GATEWAY_URL}/api/players/me", token=t1)
    assert_eq(p1_idem["matchesPlayed"], 2, "5 duplicate deliveries produced strictly +1 match")
    print("   OK (5 identical deliveries -> strictly 1 stat update)")

    print("9. Testing F4 Poison Message -> Dead-Letter Queue (DLQ)...")
    st, dlq_before = http_json("GET", f"{RABBIT_API}/queues/%2f/player.match.finished.dlq", basic_auth=(RABBIT_USER, RABBIT_PASS))
    before_count = dlq_before.get("messages", 0) if isinstance(dlq_before, dict) else 0
    st, _ = http_json(
        "POST",
        f"{RABBIT_API}/exchanges/%2f/pong.events/publish",
        {
            "properties": {"content_type": "application/json"},
            "routing_key": "match.finished",
            "payload": '{"bad":"poison-payload"}',
            "payload_encoding": "string",
        },
        basic_auth=(RABBIT_USER, RABBIT_PASS),
    )
    assert_eq(st, 200, "publish poison message")
    dlq_increased = False
    for _ in range(20):
        time.sleep(0.5)
        st, dlq_after = http_json("GET", f"{RABBIT_API}/queues/%2f/player.match.finished.dlq", basic_auth=(RABBIT_USER, RABBIT_PASS))
        if isinstance(dlq_after, dict) and dlq_after.get("messages", 0) > before_count:
            dlq_increased = True
            break
    assert dlq_increased, "Poison message did not reach player.match.finished.dlq"
    print("   OK (poison payload retried and routed to player.match.finished.dlq)")

    print("\nALL END-TO-END & DISTRIBUTED SYSTEMS CHECKS PASSED!")


if __name__ == "__main__":
    main()
