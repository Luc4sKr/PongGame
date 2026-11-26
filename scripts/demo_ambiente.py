"""Infraestrutura local e evidências das demonstrações F1–F8."""

import json
import os
import re
import secrets
import subprocess
import time
import urllib.error
import uuid
from threading import Lock
from datetime import datetime, timezone
from pathlib import Path
from typing import NamedTuple

from e2e_test import http_json, SimpleWSClient
from urllib.parse import urlsplit
from service_logs import ServiceLogs
from scenario_trace import ScenarioTrace, trace_headers

ROOT = Path(__file__).resolve().parents[1]
GAMES = ("game-1", "game-2")


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
    def __init__(self, scenario, inspect=False, project=None):
        self.inspection_pending = inspect
        self.scenario = scenario
        self.command = ["docker", "compose"] + (["-p", project] if project else [])
        self.folder = ROOT / "artifacts" / "demo" / f"{scenario.lower()}-{uuid.uuid4().hex[:12]}"
        self.folder.mkdir(parents=True)
        self.env = dict(os.environ)
        self.since = now()
        self.games = GAMES
        self.pairs = []
        self.players = []
        self.reservations = {}
        self.fault_keys = []
        self.stopped = set()
        self.log_process = None
        self.attached = False
        self.trace = None
        self.output_lock = Lock()

    def show(self, label, value=""):
        line = f"{now()}  {label}  {value}".rstrip()
        with self.output_lock:
            with (self.folder / "evidencias.log").open("a", encoding="utf-8") as report:
                print(line, file=report)
            print(line, flush=True)
        if self.trace and label in ("Partida criada", "Docker", "Redis confirma a réplica que concluiu a mensagem",
                                    "Tickets via API", "Contagem HTTP", "Mensagem encontrada em player.match.finished.dlq"):
            self.trace.event(label, value)

    def compose(self, *args, timeout=120):
        if args[0] in ("up", "down", "build", "pull", "rm", "create"):
            raise ValueError("Os testes usam apenas containers existentes; criação e remoção são proibidas.")
        command = [*self.command, *args]
        if args[0] == "start":
            # Compose v2.29 start also traverses dependencies and can rerun
            # terraform-init. Start only the existing container we interrupted.
            ids = self.compose("ps", "-a", "-q", args[-1]).splitlines()
            if len(ids) != 1:
                raise RuntimeError(f"Esperado um container existente para {args[-1]}: {ids}")
            command = ["docker", "start", ids[0]]
        if args[0] in ("kill", "stop"):
            self.stopped.add(args[-1])
        if args[0] in ("kill", "stop", "start"):
            self.show("Docker", " ".join(command))
        result = subprocess.run(command, cwd=ROOT, env=self.env, capture_output=True,
                                encoding="utf-8", errors="replace", timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"compose {' '.join(args[:4])}: {(result.stderr or result.stdout)[-2000:]}")
        if args[0] in ("kill", "stop", "start"):
            self.show("Docker resultado", (result.stdout + result.stderr).strip())
        return result.stdout.strip()

    def start(self):
        self.show("Usando ambiente já em execução", " ".join(self.command))
        running = self.compose("ps", "--status", "running", "--services").splitlines()
        required = {"gateway", "auth", "player", "matchmaking", "rabbitmq", "redis", "postgres-player", *GAMES}
        missing = required - set(running)
        if missing:
            raise RuntimeError("Serviços ausentes/parados: " + ", ".join(sorted(missing)) +
                               ". Inicie o ambiente normalmente antes do teste.")
        self.attached = True
        self.games = tuple(sorted(s for s in running if re.fullmatch(r"game-\d+", s)))
        self.show("Serviços existentes", self.compose("ps"))
        self.rabbit_auth = self.container_env("rabbitmq")
        self.rabbit_auth = (self.rabbit_auth.get("RABBITMQ_DEFAULT_USER", "guest"),
                            self.rabbit_auth.get("RABBITMQ_DEFAULT_PASS", "guest"))
        endpoints = []
        for game in self.games:
            output = self.compose("logs", "--no-color", game)
            matches = re.findall(r"\[redis\] (?:using explicit override |discovered[^\n]* endpoint )(\S+):(\d+)", output)
            if not matches:
                raise RuntimeError(f"Não foi possível identificar o Redis usado por {game} nos logs de inicialização.")
            endpoints.append(matches[-1])
        if len(set(endpoints)) != 1:
            raise RuntimeError(f"Réplicas usam Redis diferentes: {endpoints}")
        self.redis_host, self.redis_port = endpoints[0]
        self.show("Redis real da aplicação", f"{self.redis_host}:{self.redis_port}")
        self.gateway = "http://" + self.compose("port", "gateway", "80")
        self.show("Gateway", self.gateway)
        self.locate_broker()
        self.consumers("game.match.created", len(self.games))
        self.consumers("matchmaking.match.ready", 1)
        self.consumers("player.match.finished", 1)
        state = self.api("GET", "/api/matchmaking/status")
        if state["queuedPlayers"] or state["activeMatches"] or state["pendingOutboxMessages"]:
            raise RuntimeError("Finalize partidas/reservas e esvazie a fila antes da demonstração; os testes não apagam dados existentes.")
        equal("contador Redis corresponde à API", self.slots(), state["activeMatches"])
        self.capacity = state["maxConcurrentMatches"]
        self.trace = ScenarioTrace(self.scenario)
        self.show("Trace ID", self.trace.trace_id)
        self.show("Grafana — trace desta execução", self.trace.url)
        (self.folder / "trace.json").write_text(json.dumps({"traceId": self.trace.trace_id, "url": self.trace.url}), encoding="utf-8")
        self.follow_logs()

    def container_env(self, service):
        cid = self.compose("ps", "-q", service)
        result = subprocess.run(["docker", "inspect", cid], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", check=True, timeout=30)
        return dict(item.split("=", 1) for item in json.loads(result.stdout)[0]["Config"]["Env"])

    def follow_logs(self):
        self.log_process = ServiceLogs(self.command, ROOT, self.since,
                                       ["rabbitmq", "matchmaking", "player", *self.games],
                                       lambda line: self.show("SERVIÇO", line), self.env)

    def locate_broker(self):
        address = self.compose("port", "rabbitmq", "15672")
        self.broker = "http://" + address + "/api"
        self.show("Painel RabbitMQ", "http://" + address)

    def consumers(self, queue, count):
        def read():
            try:
                status, body = http_json("GET", self.broker + "/queues/%2F/" + queue, basic_auth=self.rabbit_auth)
            except (urllib.error.URLError, TimeoutError):
                return "painel inicializando"
            if status in (404, 503):
                return f"fila inicializando (HTTP {status})"
            equal("consulta de consumidores no RabbitMQ", status, 200)
            return body.get("consumers")

        result = wait(f"{count} consumidores em {queue}", read, lambda n: n == count, 60)
        self.show("RabbitMQ consumidores", (queue, result))
        return result

    def api(self, method, path, body=None, token=None, status=200):
        code, result = http_json(method, self.gateway + path, body, token)
        self.show("HTTP", f"{method} {path} -> {code}")
        if code not in (status if isinstance(status, tuple) else (status,)):
            raise ValueError(f"{method} {path}: esperado HTTP {status}, observado {code}: {result}")
        if method == "POST" and path == "/api/matchmaking/arenas/reserve":
            self.reservations[result["reservationId"]] = token
        if method == "DELETE" and path.startswith("/api/matchmaking/arenas/reserve/"):
            self.reservations.pop(path.rsplit("/", 1)[-1], None)
        return result

    def rabbit(self, method, path, body=None):
        code, result = http_json(method, self.broker + path, body, basic_auth=self.rabbit_auth)
        equal(f"RabbitMQ {method} {path}", code, 200)
        return result

    def publish(self, event):
        result = self.rabbit("POST", "/exchanges/%2F/pong.events/publish", {
            "routing_key": event["type"], "payload_encoding": "string", "payload": json.dumps(event),
            "properties": {"content_type": "application/json", "delivery_mode": 2,
                           "headers": trace_headers(),
                           "message_id": event["messageId"]},
        })
        equal("RabbitMQ encaminhou a mensagem", result["routed"], True)

    def redis(self, *args):
        result = self.compose("exec", "-T", "redis", "redis-cli", "-h", self.redis_host,
                              "-p", self.redis_port, "--raw", *args)
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
        self.show("CONSULTA", query)
        self.show("RESULTADO", result)
        with (self.folder / "consultas.log").open("a", encoding="utf-8") as report:
            print(f"{now()}  {query}\n{result}\n", file=report)

    def logs(self, *services):
        return self.compose("logs", "--since", self.since, "--timestamps", "--no-color", *services).splitlines()

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
        player = (user["userId"], login["accessToken"])
        self.players.append(player)
        return player

    def pair(self, players=None):
        players = players or [self.user(), self.user()]
        if self.scenario in ("F1", "F2", "F7"):
            delay = {"F1": 4000, "F2": 6000, "F7": 12000}[self.scenario]
            for uid, _ in players:
                key = "demo:dependency:" + uid
                self.fault_keys.append(key)
                self.redis("SET", key, json.dumps({"delayMs": delay, "fail": self.scenario == "F1"}), "EX", "180")
            self.show("Injeção temporária apenas nos jogadores do teste", (self.scenario, delay))
        tickets = [self.api("POST", "/api/matchmaking/queue", token=token, status=202)
                   for _, token in players]
        match = tickets[1]["matchId"]
        if not match:
            raise ValueError(f"O segundo ticket não recebeu uma partida: {tickets[1]}")
        self.show("Partida criada", match)
        pair = Pair(match, [Ticket(t["ticketId"], token, uid) for t, (uid, token) in zip(tickets, players)])
        self.pairs.append(pair)
        if self.scenario in ("F2", "F6", "F7"):
            deadline = int(time.time() * 1000) + (3000 if self.scenario == "F7" else 120000)
            self.redis("EVAL", "if redis.call('HGET', KEYS[1], 'status') == 'PROVISIONING' then "
                       "redis.call('HSET', KEYS[1], 'deadlineMs', ARGV[1]); "
                       "return redis.call('ZADD', KEYS[2], ARGV[1], ARGV[2]) end; return 0",
                       "2", "mm:match:" + match, "mm:provisioning:deadlines", str(deadline), match)
            self.show("Prazo ajustado somente para a partida do teste", (match, deadline))
        return pair

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

    def finish(self):
        if not self.attached:
            self.show("Nenhum serviço alterado", "A verificação do ambiente não foi concluída.")
            return
        errors = []
        def attempt(label, action):
            try:
                action()
            except Exception as error:
                errors.append(f"{label}: {error}")
                self.show("FALHA NA RESTAURAÇÃO", errors[-1])
        for service in sorted(self.stopped):
            attempt("reiniciar " + service, lambda s=service: self.compose("start", s))
        if self.stopped:
            attempt("consumidores", lambda: self.consumers("game.match.created", len(self.games)))
        for key in self.fault_keys:
            attempt("remover injeção", lambda k=key: self.redis("DEL", k))
        for rid, token in list(self.reservations.items()):
            attempt("liberar reserva", lambda r=rid, t=token: self.api(
                "DELETE", "/api/matchmaking/arenas/reserve/" + r, token=t))
        for pair in self.pairs:
            attempt("finalizar partida " + pair.match_id, lambda p=pair: self.finish_pair(p))
        for _, token in self.players:
            attempt("cancelar ticket pendente", lambda t=token: self.api(
                "DELETE", "/api/matchmaking/queue", token=t, status=(200, 404)))
        attempt("salvar logs", self.save_logs)
        if self.log_process:
            attempt("encerrar acompanhamento de logs", self.log_process.close)
        if errors:
            raise RuntimeError("Restauração incompleta: " + "; ".join(errors))
        self.show("Ambiente preservado", "Serviços mantidos; reservas e partidas desta execução liberadas.")

    def finish_pair(self, pair):
        ticket = pair.tickets[0]
        path = "/api/matchmaking/tickets/" + ticket.id
        row = wait("fim do provisionamento", lambda: self.api("GET", path, token=ticket.token),
                   lambda r: r["status"] != "PROVISIONING", timeout=130)
        if row["status"] == "READY":
            address = urlsplit(self.gateway)
            ws = SimpleWSClient(address.hostname, address.port or 80, "/ws/game/" + pair.match_id, ticket.token)
            try:
                ws.recv_json()
                ws.send_json({"type": "forfeit"})
                wait("partida finalizada", lambda: self.api("GET", path, token=ticket.token),
                     lambda r: r["status"] == "FINISHED")
            finally:
                ws.close()

    def inspect_if_requested(self):
        if self.inspection_pending:
            input("Inspecione os serviços. Enter conclui o teste; o ambiente continuará rodando... ")
            self.inspection_pending = False

    def finish_trace(self, error=None):
        if self.trace:
            self.trace.finish(error)
