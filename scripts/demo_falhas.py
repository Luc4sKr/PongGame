"""Execute da raiz: python scripts/demo_falhas.py F2"""

import argparse
import json
import re
import subprocess
import sys
import time
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from threading import Barrier

from demo_ambiente import Demo, equal, now, wait
from e2e_test import http_json


def compensated(d, pair, reason, occupied=0):
    match, tickets = pair
    rows = d.tickets(pair, "COMPENSATED")
    equal("motivo nos dois tickets", [r["reason"] for r in rows], [reason] * 2)
    released = d.redis("HGET", "mm:match:" + match, "slotReleased")
    locks = d.redis("EXISTS", *["mm:player:active:" + t.player_id for t in tickets])
    state = (released, locks, d.slots())
    equal("slotReleased, jogadores bloqueados, vagas ocupadas", state, ("1", "0", occupied))
    d.show("Redis (slotReleased, jogadores bloqueados, vagas ocupadas)", state)


def f1(d):
    equal("vagas ocupadas antes", d.slots(), 0)
    pair = d.pair()
    reservation = d.redis("MGET", "mm:arena:active_count",
                          *["mm:player:active:" + t.player_id for t in pair.tickets]).splitlines()
    equal("vaga ocupada e bloqueios antes da compensação", reservation, ["1"] + [t.id for t in pair.tickets])
    d.show("Redis antes da falha (vagas ocupadas e tickets dos dois jogadores)", reservation)
    d.evidence_lines(d.games, "unstable dependency failed", pair.match_id, "SIMULATED_ARENA_DEPENDENCY_FAILURE")
    compensated(d, pair, "SIMULATED_ARENA_DEPENDENCY_FAILURE")
    d.evidence_lines(["matchmaking"], "Saga compensated", pair.match_id)


def f2(d):
    pair = d.pair()
    match = pair.match_id
    line, = d.evidence_lines(d.games, "processing match.created", match, "redelivered=false")
    replica, message = re.search(r"\[rabbit:(game-\d+)\].*?messageId=([\w-]+)", line).groups()
    queue = "game.match.created"
    def unacked():
        rows = d.compose("exec", "-T", "rabbitmq", "rabbitmqctl", "-q", "list_queues",
                         "name", "messages_unacknowledged")
        return dict(row.split() for row in rows.splitlines() if row.startswith(queue + "\t")).get(queue)
    wait("entrega sem ACK", unacked, lambda n: n == "1", 6)
    equal("réplica com a reserva", d.redis("GET", "game:msg:claim:" + message), replica)
    d.show("RabbitMQ confirmou 1 entrega sem ACK. Encerrando com SIGKILL", replica)
    d.compose("kill", "-s", "SIGKILL", replica)
    survivor, = set(d.games) - {replica}
    d.evidence_lines([survivor], "processing match.created", message, "redelivered=true", timeout=55)
    d.tickets(pair, "READY")
    d.evidence_lines(d.games, "provisioned match", match, message)
    equal("marcador Redis do consumidor", d.redis("GET", "game:msg:done:" + message), survivor)
    equal("partida ativa", d.redis("SISMEMBER", "game:active_matches", match), "1")
    d.show("Redis confirma a réplica que concluiu a mensagem", survivor)


def finished_event():
    match, first, second = (str(uuid.uuid4()) for _ in range(3))
    return {
        "messageId": str(uuid.uuid4()), "correlationId": str(uuid.uuid4()), "type": "match.finished",
        "version": 1, "occurredAt": now(),
        "payload": {"matchId": match, "player1Id": first, "player2Id": second,
                    "player1Username": "p_" + first[:12], "player2Username": "p_" + second[:12],
                    "player1Score": 5, "player2Score": 2, "winnerId": first,
                    "reason": "SCORE_LIMIT", "durationSeconds": 40, "finishedAt": now()},
    }


def stats(d, event):
    p = event["payload"]
    # All interpolated SQL values are fixture UUIDs generated in this process.
    query = f"""SELECT
        (SELECT count(*) FROM processed_messages WHERE message_id='{event["messageId"]}'),
        (SELECT count(*) FROM player_matches WHERE match_id='{p["matchId"]}' AND player_id='{p["player1Id"]}'),
        (SELECT count(*) FROM player_matches WHERE match_id='{p["matchId"]}' AND player_id='{p["player2Id"]}'),
        COALESCE((SELECT matches_played FROM players WHERE id='{p["player1Id"]}'),0),
        COALESCE((SELECT matches_played FROM players WHERE id='{p["player2Id"]}'),0)"""
    return d.sql(query)


def f3(d):
    event = finished_event()
    equal("estado inicial dos jogadores e da mensagem", stats(d, event), "0|0|0|0|0")
    d.show("Publicando 5 cópias idênticas de match.finished", event["messageId"])
    for _ in range(5):
        d.publish(event)
    d.evidence_lines(["player"], "Ignoring duplicate", event["messageId"], count=4)
    result = stats(d, event)
    equal("marcador, históricos individuais e partidas por jogador", result, "1|1|1|1|1")
    d.show("PostgreSQL (marcador, histórico P1/P2, partidas P1/P2)", result)


def f4(d):
    bad, good = finished_event(), finished_event()
    del bad["payload"]["player1Score"]
    d.show("Publicando evento sem player1Score, seguido de evento válido", [bad["messageId"], good["messageId"]])
    d.publish(bad)
    d.publish(good)
    d.evidence_lines(["player"], "Rejecting invalid match.finished payload", bad["messageId"], count=3)
    def read_dlq():
        items = d.rabbit("POST", "/queues/%2F/player.match.finished.dlq/get",
                         {"count": 1, "ackmode": "ack_requeue_true", "encoding": "auto", "truncate": 100000})
        return [json.loads(item["payload"]) for item in items]
    wait("evento original na DLQ", read_dlq, lambda items: items == [bad])
    d.show("Mensagem encontrada em player.match.finished.dlq", bad["messageId"])
    equal("evento inválido sem efeito", stats(d, bad), "0|0|0|0|0")
    result = wait("evento válido persistido", lambda: stats(d, good), lambda s: s == "1|1|1|1|1")
    d.show("Evento válido confirmado no PostgreSQL", (good["messageId"], result))


def f5(d):
    _, token = d.user()
    path = "/api/matchmaking/arenas/reserve"
    equal("vagas ocupadas antes", d.slots(), 0)
    barrier = Barrier(100, timeout=20)
    def reserve(_):
        barrier.wait()
        return http_json("POST", d.gateway + path, token=token)
    d.show("Liberando 100 clientes simultâneos para 10 vagas")
    with ThreadPoolExecutor(max_workers=100) as pool:
        responses = list(pool.map(reserve, range(100)))
    totals = Counter(code for code, _ in responses)
    d.show("Contagem HTTP", dict(totals))
    equal("respostas HTTP", totals, {201: 10, 409: 90})
    ids = {r["reservationId"] for code, r in responses if code == 201}
    equal("quantidade de reservas únicas", len(ids), 10)
    equal("reservas no Redis", set(d.redis("--scan", "--pattern", "mm:reservation:*").splitlines()),
          {"mm:reservation:" + rid for rid in ids})
    equal("vagas ocupadas no Redis", d.slots(), 10)
    d.show("10 reservas verificadas no Redis", sorted(ids))
    d.inspect_if_requested()
    for rid in ids:
        equal("liberação da reserva", d.api("DELETE", path + "/" + rid, token=token)["released"], True)
    equal("vagas ocupadas depois", d.slots(), 0)
    d.show("Vagas ocupadas após a liberação", 0)


def f6(d):
    players = [[d.user(), d.user()] for _ in range(3)]
    d.compose("stop", "rabbitmq")
    stopped = time.monotonic()
    equal("broker em execução", d.compose("ps", "--status", "running", "-q", "rabbitmq"), "")
    d.show("RabbitMQ parado. Criando três partidas via API")
    pairs = [d.pair(users) for users in players]
    raw = d.redis("LRANGE", "mm:outbox", "0", "-1")
    events = [json.loads(json.loads(line)["bodyJson"]) for line in raw.splitlines()]
    equal("eventos acumulados", Counter((e["type"], e["payload"]["matchId"]) for e in events),
          Counter(("match.created", match) for match, _ in pairs))
    d.show("Outbox Redis (messageId, matchId)", [(e["messageId"], e["payload"]["matchId"]) for e in events])
    time.sleep(max(0, 30 - (time.monotonic() - stopped)))
    d.show("Reiniciando apenas o broker após a interrupção (segundos)", round(time.monotonic() - stopped, 1))
    d.compose("start", "rabbitmq")
    d.locate_broker()
    # The management API starts after the container. Use the real ticket API while clients reconnect.
    for pair in pairs:
        d.tickets(pair, "READY", timeout=80)
    for event in events:
        d.evidence_lines(d.games, "provisioned match", event["messageId"], event["payload"]["matchId"])
    for key in ("mm:outbox", "game:outbox"):
        wait(key + " vazia", lambda: d.redis("LLEN", key), lambda value: value == "0")
    for q, n in [("game.match.created", 2), ("matchmaking.match.ready", 1), ("player.match.finished", 1)]:
        d.consumers(q, n)
    d.show("Reconexão confirmada nas três filas. Outboxes vazias, partidas reconciliadas", len(events))


def f7(d):
    equal("vagas ocupadas antes", d.slots(), 0)
    players = [d.user(), d.user()]
    token = players[0][1]
    path = "/api/matchmaking/arenas/reserve"
    control = d.api("POST", path, token=token, status=201)["reservationId"]
    pair = d.pair(players)
    equal("vaga de controle mais vaga da partida", d.slots(), 2)
    d.show("Duas vagas ocupadas. Reserva independente mantida durante a compensação", control)
    match = pair.match_id
    d.evidence_lines(["matchmaking"], "Provisioning timeout expired", match)
    compensated(d, pair, "PROVISIONING_TIMEOUT", occupied=1)
    d.evidence_lines(["matchmaking"], "Late match.ready", match)
    d.evidence_lines(d.games, "cancelled match", match, "LATE_PROVISIONING_RESPONSE")
    # Watch past multiple game-loop lease checks for a stale writer reviving the arena.
    for _ in range(3):
        time.sleep(1)
        state = json.loads(d.redis("GET", "game:match:" + match))
        equal("estado final da arena", state["status"], "CANCELLED")
        equal("arena fora do conjunto ativo", d.redis("SISMEMBER", "game:active_matches", match), "0")
    compensated(d, pair, "PROVISIONING_TIMEOUT", occupied=1)
    equal("reserva de controle preservada", d.redis("GET", "mm:reservation:" + control), players[0][0])
    d.show("Estado final da arena no Redis", (match, state["status"], state["finishReason"]))
    d.inspect_if_requested()
    equal("liberação da vaga de controle", d.api("DELETE", path + "/" + control, token=token)["released"], True)
    equal("ocupação após liberar a vaga de controle", d.slots(), 0)


def f8(d):
    d.show("Consumidores registrados em game.match.created", d.consumers("game.match.created", 3))
    pairs = [d.pair() for _ in range(6)]
    for pair in pairs:
        d.tickets(pair, "READY")
    lines = d.lines(d.games, "provisioned match")
    by_replica = Counter()
    matches = []
    for line in lines:
        replica, match = re.search(r"\[rabbit:(game-\d+)\] provisioned match ([\w-]+)", line).groups()
        by_replica[replica] += 1
        matches.append(match)
        d.show("Provisionamento", line)
    equal("partidas provisionadas uma vez", Counter(matches), Counter(match for match, _ in pairs))
    equal("réplicas que processaram partidas", set(by_replica), set(d.games))
    equal("partidas ativas no Redis", set(d.redis("SMEMBERS", "game:active_matches").splitlines()), set(matches))
    d.show("Partidas por réplica", dict(by_replica))


SCENARIOS = {"F1": f1, "F2": f2, "F3": f3, "F4": f4, "F5": f5, "F6": f6, "F7": f7, "F8": f8}


def run(scenario, inspect, pause):
    d = Demo(scenario, inspect)
    print(f"Evidências: {d.folder}", flush=True)
    try:
        with ExitStack() as cleanup:
            cleanup.callback(d.compose, "down", "--volumes", "--timeout", "2")
            cleanup.callback(d.save_logs)
            d.start()
            if pause:
                input("Ambiente pronto. Pressione Enter para provocar a falha... ")
            SCENARIOS[scenario](d)
            d.inspect_if_requested()
    except BaseException as error:
        d.show("INTERROMPIDO" if isinstance(error, KeyboardInterrupt) else "FALHOU", str(error))
        raise
    d.show("APROVADO", scenario)


def main():
    parser = argparse.ArgumentParser(description="Demonstrações de falhas distribuídas do PongGame")
    parser.add_argument("cenario", type=str.upper, choices=["PREPARAR", *sorted(SCENARIOS), "TODOS"])
    parser.add_argument("--inspecionar", action="store_true", help="mantém a stack aberta até pressionar Enter")
    parser.add_argument("--aguardar", action="store_true", help="espera Enter antes de provocar a falha")
    args = parser.parse_args()
    if args.cenario == "PREPARAR":
        d = Demo("preparar")
        d.show("Preparando imagens da demonstração")
        d.compose("build", "terraform-init", "auth", "player", "matchmaking", "game-1", "frontend",
                  timeout=1800)
        d.compose("pull", "--policy", "missing", "gateway", "rabbitmq", "redis", "postgres-auth",
                  "ministack", timeout=1200)
        d.show("Imagens prontas")
        return
    for scenario in sorted(SCENARIOS) if args.cenario == "TODOS" else [args.cenario]:
        run(scenario, args.inspecionar, args.aguardar)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        sys.exit(f"Erro na demonstração: {error}")
