"""Infraestrutura local e evidências das demonstrações F1–F8."""

import json
import os
import secrets
import subprocess
import time
import urllib.error
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import NamedTuple

from e2e_test import http_json

ROOT = Path(__file__).resolve().parents[1]
GAMES = ("game-1", "game-2")
RABBIT_AUTH = ("pong_demo", "pong_demo_local")


class Ticket(NamedTuple):
    id: str
    token: str
    player_id: str


class Pair(NamedTuple):
    match_id: str
    tickets: list[Ticket]


def now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def equal(label, actual, expected):
    if actual != expected:
        raise ValueError(f"{label}: esperado {expected!r}, observado {actual!r}")


def wait(label, read, accepts, timeout=30):
    deadline = time.monotonic() + timeout
    while True:
        value = read()
        if accepts(value):
            return value
        if time.monotonic() >= deadline:
            raise TimeoutError(f"{label}: prazo de {timeout}s expirou. Última leitura: {str(value)[-1500:]}")
        time.sleep(0.3)


class Demo:
    def __init__(self, scenario, inspect=False):
        self.inspection_pending = inspect
        self.project = f"pongdemo-{scenario.lower()}-{uuid.uuid4().hex[:12]}"
        self.folder = ROOT / "artifacts" / "demo" / self.project
        self.folder.mkdir(parents=True)
        self.games = (*GAMES, "game-3") if scenario == "F8" else GAMES
        self.env = {
            **os.environ,
            "COMPOSE_PROFILES": "escala" if scenario == "F8" else "",
            "COMPOSE_PROJECT_NAME": self.project,
            "RABBITMQ_USERNAME": RABBIT_AUTH[0],
            "RABBITMQ_PASSWORD": RABBIT_AUTH[1],
            "DEPENDENCY_FAILURE_RATE": "1" if scenario == "F1" else "0",
            "MATCHMAKING_MAX_CONCURRENT_MATCHES": "10",
            "MATCHMAKING_PROVISIONING_TIMEOUT_MS": "3000" if scenario == "F7" else "120000",
        }
        delay = {"F1": "4000", "F2": "14000", "F7": "12000"}.get(scenario, "100")
        self.env.update(DEPENDENCY_MIN_LATENCY_MS=delay, DEPENDENCY_MAX_LATENCY_MS=delay)

    def show(self, label, value=""):
        line = f"{now()}  {label}  {value}".rstrip()
        with (self.folder / "evidencias.log").open("a", encoding="utf-8") as report:
            print(line, file=report)
        print(line, flush=True)

    def compose(self, *args, timeout=120):
        command = ["docker", "compose", "-p", self.project, "-f", "compose.yaml",
                   "-f", "compose.demo.yaml", *args]
        result = subprocess.run(command, cwd=ROOT, env=self.env, capture_output=True,
                                encoding="utf-8", errors="replace", timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"compose {' '.join(args[:4])}: {(result.stderr or result.stdout)[-2000:]}")
        return result.stdout.strip()

    def start(self):
        self.show("Iniciando ambiente de demonstração", self.project)
        self.compose("up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180", timeout=210)
        self.gateway = "http://" + self.compose("port", "gateway", "80")
        self.show("Gateway", self.gateway)
        self.locate_broker()
        self.consumers("game.match.created", len(self.games))
        self.consumers("matchmaking.match.ready", 1)
        self.consumers("player.match.finished", 1)

    def locate_broker(self):
        address = self.compose("port", "rabbitmq", "15672")
        self.broker = "http://" + address + "/api"
        self.show("Painel RabbitMQ", "http://" + address)

    def consumers(self, queue, count):
        def read():
            try:
                status, body = http_json("GET", self.broker + "/queues/%2F/" + queue, basic_auth=RABBIT_AUTH)
            except (urllib.error.URLError, TimeoutError):
                return "painel inicializando"
            if status in (404, 503):
                return f"fila inicializando (HTTP {status})"
            equal("consulta de consumidores no RabbitMQ", status, 200)
            return body.get("consumers")

        return wait(f"{count} consumidores em {queue}", read, lambda n: n == count, 45)

    def api(self, method, path, body=None, token=None, status=200):
        code, result = http_json(method, self.gateway + path, body, token)
        equal(f"{method} {path} (HTTP)", code, status)
        return result

    def rabbit(self, method, path, body=None):
        code, result = http_json(method, self.broker + path, body, basic_auth=RABBIT_AUTH)
        equal(f"RabbitMQ {method} {path}", code, 200)
        return result

    def publish(self, event):
        result = self.rabbit("POST", "/exchanges/%2F/pong.events/publish", {
            "routing_key": event["type"], "payload_encoding": "string", "payload": json.dumps(event),
            "properties": {"content_type": "application/json", "delivery_mode": 2,
                           "message_id": event["messageId"]},
        })
        equal("RabbitMQ encaminhou a mensagem", result["routed"], True)

    def redis(self, *args):
        result = self.compose("exec", "-T", "redis", "redis-cli", "--raw", *args)
        self.record_query("redis-cli " + " ".join(args), result)
        return result

    def slots(self):
        return int(self.redis("GET", "mm:arena:active_count") or 0)

    def sql(self, query):
        result = self.compose("exec", "-T", "postgres-player", "psql", "-XAt", "-v",
                              "ON_ERROR_STOP=1", "-U", "pong_player", "-c", query)
        self.record_query(query, result)
        return result

    def record_query(self, query, result):
        with (self.folder / "consultas.log").open("a", encoding="utf-8") as report:
            print(f"{now()}  {query}\n{result}\n", file=report)

    def logs(self, *services):
        return self.compose("logs", "--timestamps", "--no-color", *services).splitlines()

    def lines(self, services, *terms):
        return [line for line in self.logs(*services) if all(term in line for term in terms)]

    def evidence_lines(self, services, *terms, count=1, timeout=30):
        lines = wait("log " + " ".join(terms), lambda: self.lines(services, *terms),
                     lambda rows: len(rows) >= count, timeout)
        equal("quantidade de linhas " + " ".join(terms), len(lines), count)
        for line in lines:
            self.show("Log", line)
        return lines

    def user(self):
        name = "demo_" + secrets.token_hex(6)
        credentials = {"username": name, "password": secrets.token_urlsafe(20) + "aA1!"}
        user = self.api("POST", "/api/auth/register", credentials, status=201)
        login = self.api("POST", "/api/auth/login", credentials)
        return user["userId"], login["accessToken"]

    def pair(self, players=None):
        players = players or [self.user(), self.user()]
        tickets = [self.api("POST", "/api/matchmaking/queue", token=token, status=202)
                   for _, token in players]
        match = tickets[1]["matchId"]
        if not match:
            raise ValueError(f"O segundo ticket não recebeu uma partida: {tickets[1]}")
        self.show("Partida criada", match)
        return Pair(match, [Ticket(t["ticketId"], token, uid) for t, (uid, token) in zip(tickets, players)])

    def tickets(self, pair, status, timeout=45):
        match, tickets = pair

        def read():
            return [self.api("GET", "/api/matchmaking/tickets/" + ticket.id, token=ticket.token)
                    for ticket in tickets]

        rows = wait(f"dois tickets de {match} em {status}", read,
                    lambda rows: all(t["status"] == status and t["matchId"] == match for t in rows),
                    timeout)
        self.show("Tickets via API", [(t["ticketId"], t["status"], t["reason"]) for t in rows])
        return rows

    def save_logs(self):
        for service in ("matchmaking", "player", *self.games):
            (self.folder / f"{service}.log").write_text("\n".join(self.logs(service)), encoding="utf-8")

    def inspect_if_requested(self):
        if self.inspection_pending:
            input("Inspecione os serviços. Pressione Enter para concluir e liberar os recursos... ")
            self.inspection_pending = False
