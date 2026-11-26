"""Regenerate provisioned Grafana dashboard/alerts using only Python stdlib."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PROM = {"type": "prometheus", "uid": "prometheus"}
TEMPO = {"type": "tempo", "uid": "tempo"}
TRACE_PANEL_ID = 100
panels = []


def panel(title, expr, x, y, w=12, h=8, kind="timeseries", unit="short", legend="{{service_name}} {{outcome}}"):
    item = {
        "id": len(panels) + 1, "title": title, "type": kind,
        "gridPos": {"x": x, "y": y, "w": w, "h": h}, "datasource": PROM,
        "targets": [{"refId": "A", "expr": expr, "legendFormat": legend, "range": kind != "stat", "instant": kind == "stat"}],
        "fieldConfig": {"defaults": {"unit": unit, "color": {"mode": "palette-classic"}}, "overrides": []},
        "options": {"legend": {"displayMode": "table", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
    }
    if kind == "stat":
        item["options"] = {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "colorMode": "value", "graphMode": "area"}
    panels.append(item)


def section(title, y):
    panels.append({"id": len(panels) + 1, "title": title, "type": "row", "collapsed": False,
                   "gridPos": {"x": 0, "y": y, "w": 24, "h": 1}, "panels": []})


panels.append({
    "id": 1, "title": "Traces de partidas", "type": "table", "gridPos": {"x": 0, "y": 0, "w": 24, "h": 8},
    "description": "Clique no Trace ID e escolha Abrir trace da partida para ver o pareamento, a arena e o registro do resultado.",
    "datasource": TEMPO,
    "targets": [{"refId": "A", "datasource": TEMPO, "queryType": "traceql", "tableType": "traces", "limit": 20,
                 "query": '{ resource.service.name = "ponggame-matchmaking" && span:name = "matchmaking.pair" }'}],
    "fieldConfig": {"defaults": {}, "overrides": [{
        "matcher": {"id": "byName", "options": "Trace ID"},
        "properties": [{"id": "links", "value": [{"title": "Abrir trace da partida",
                        "url": f"/d/ponggame?viewPanel=panel-{TRACE_PANEL_ID}&var-traceId=${{__value.raw}}"}]}],
    }]},
    "options": {"showHeader": True, "sortBy": [{"displayName": "Start time", "desc": True}]},
})

section("HTTP · Disponibilidade e desempenho", 8)
panel("Disponibilidade HTTP (1 = saudável)", 'httpcheck_status{http_status_class="2xx"}', 0, 9, legend="{{http_url}}")
panel("Requisições e operações por segundo", 'sum by (service, span_kind) (rate(traces_spanmetrics_calls_total[1m]))', 12, 9, unit="ops", legend="{{service}} {{span_kind}}")
panel("Erros de spans por serviço", 'sum by (service) (increase(traces_spanmetrics_calls_total{status_code="STATUS_CODE_ERROR"}[5m]))', 0, 17, legend="{{service}}")
panel("Latência p95 das operações", 'histogram_quantile(0.95, sum by (le, service) (rate(traces_spanmetrics_latency_bucket[5m])))', 12, 17, unit="s", legend="{{service}}")

section("Matchmaking e Game · Filas e arenas", 25)
panel("Partidas ativas", 'max(pong_matchmaking_active)', 0, 26, 12, 4, "stat")
panel("Vagas disponíveis", 'max(pong_matchmaking_capacity) - max(pong_matchmaking_active)', 12, 26, 12, 4, "stat")
panel("Reservas criadas / rejeitadas", 'sum by (outcome) (increase(pong_arenas_reservations_total[5m]))', 0, 30, legend="{{outcome}}")
panel("Compensações de saga", 'sum by (outcome) (increase(pong_saga_compensations_total[5m]))', 12, 30, legend="{{outcome}}")
panel("Dependência simulada — p95", 'histogram_quantile(0.95, sum by (le, service_instance_id) (rate(pong_game_dependency_duration_seconds_bucket[5m])))', 0, 38, unit="s", legend="{{service_instance_id}}")
panel("Mensagens por réplica Game", 'sum by (service_instance_id, outcome) (increase(pong_game_messages_total[5m]))', 12, 38, legend="{{service_instance_id}} {{outcome}}")

section("RabbitMQ e outboxes · Entrega de eventos", 46)
panel("Outbox pendente", 'max(pong_matchmaking_outbox_pending) + max(pong_game_outbox_pending)', 0, 47, 12, 4, "stat")
panel("Mensagens na DLQ", 'sum(rabbitmq_queue_messages{queue=~".*dlq"})', 12, 47, 12, 4, "stat")
panel("Outbox por serviço", '{__name__=~"pong_matchmaking_outbox_pending|pong_game_outbox_pending"}', 0, 51, legend="{{service_name}} {{service_instance_id}}")
panel("Conexão das réplicas com RabbitMQ", 'pong_game_broker_connected', 12, 51, legend="{{service_instance_id}}")
panel("Filas — mensagens prontas", 'rabbitmq_queue_messages_ready', 0, 59, legend="{{queue}}")
panel("Entregas sem ACK", 'rabbitmq_queue_messages_unacked', 12, 59, legend="{{queue}}")
panel("RabbitMQ — reentregas", 'sum by (queue) (increase(rabbitmq_queue_messages_redelivered_total[5m]))', 0, 67, legend="{{queue}}")
panel("Player — duplicatas ignoradas e payloads rejeitados", 'sum by (__name__, outcome) ({__name__=~"pong_messages_duplicates_total|pong_messages_rejected_total"})', 12, 67, legend="{{__name__}} {{outcome}}")

section("Persistência e runtimes · Recursos", 75)
panel("PostgreSQL — conexões", 'postgresql_backends', 0, 76, legend="{{postgresql_database_name}}")
panel(".NET — memória do processo", 'dotnet_process_memory_working_set_bytes', 12, 76, unit="bytes")
panel("Redis — memória utilizada", 'pong_redis_memory_used', 0, 84, unit="bytes", legend="{{service_instance_id}}")
panel("Redis — latência p95", 'histogram_quantile(0.95, sum by (le, db_operation_name) (rate(pong_redis_operation_duration_seconds_bucket[5m])))', 12, 84, unit="s", legend="{{db_operation_name}}")
panel("JVM — memória", 'sum by (service_name) (jvm_memory_used_bytes)', 0, 92, unit="bytes", legend="{{service_name}}")
panel("Go — memória alocada", 'process_runtime_go_mem_heap_alloc_bytes', 12, 92, unit="bytes", legend="{{service_instance_id}}")

section("Logs · Correlação por Trace ID", 100)
panels.append({"id": len(panels)+1, "title": "Logs dos serviços", "type": "logs", "gridPos": {"x": 0, "y": 101, "w": 24, "h": 12},
               "datasource": {"type": "loki", "uid": "loki"}, "targets": [{"refId": "A", "expr": '{service_name=~"ponggame-.+"}', "queryType": "range"}],
               "options": {"showTime": True, "showLabels": False, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True}})
panels.append({
    "id": TRACE_PANEL_ID, "title": "Trace da partida", "type": "traces",
    "gridPos": {"x": 0, "y": 113, "w": 24, "h": 16}, "datasource": TEMPO,
    "targets": [{"refId": "A", "datasource": TEMPO, "queryType": "traceql", "query": "${traceId}"}],
})

dashboard = {"uid": "ponggame", "title": "PongGame · Observabilidade", "schemaVersion": 39, "version": 3,
             "tags": ["ponggame", "opentelemetry", "demonstracao"], "timezone": "browser", "refresh": "5s", "time": {"from": "now-15m", "to": "now"},
             "templating": {"list": [{"type": "textbox", "name": "traceId", "hide": 2, "query": "",
                                       "current": {"text": "", "value": ""}}]},
             "panels": panels,
             "links": [{"title": "Alertas", "url": "/alerting/list", "targetBlank": True}]}
(ROOT / "dashboards").mkdir(exist_ok=True)
(ROOT / "dashboards" / "ponggame.json").write_text(json.dumps(dashboard, ensure_ascii=False, indent=2), encoding="utf-8")


def alert(uid, title, expr, threshold, evaluator="gt"):
    return {"uid": uid, "title": title, "condition": "B", "for": "10s", "noDataState": "NoData", "execErrState": "Error", "isPaused": False,
            "annotations": {"summary": title, "description": "Alerta local para demonstrar o cenário. Consulte o dashboard PongGame e o trace da execução."},
            "labels": {"project": "ponggame", "severity": "warning"},
            "data": [{"refId": "A", "relativeTimeRange": {"from": 60, "to": 0}, "datasourceUid": "prometheus",
                      "model": {"refId": "A", "expr": expr, "instant": True, "range": False, "intervalMs": 1000, "maxDataPoints": 43200}},
                     {"refId": "B", "relativeTimeRange": {"from": 0, "to": 0}, "datasourceUid": "__expr__",
                      "model": {"refId": "B", "type": "threshold", "expression": "A", "conditions": [{"evaluator": {"type": evaluator, "params": [threshold]}, "operator": {"type": "and"}, "query": {"params": ["A"]}, "reducer": {"type": "last"}, "type": "query"}]}}]}

alerts = {"apiVersion": 1, "groups": [{"orgId": 1, "name": "PongGame", "folder": "PongGame", "interval": "10s", "rules": [
    alert("pong-broker-down", "Game sem conexão com RabbitMQ", "min(pong_game_broker_connected)", 1, "lt"),
    alert("pong-outbox", "Eventos acumulados na outbox", "max(pong_matchmaking_outbox_pending) + max(pong_game_outbox_pending)", 0),
    alert("pong-dlq", "Mensagens aguardando análise na DLQ", 'sum(rabbitmq_queue_messages{queue=~".*dlq"})', 0),
]}]}
(ROOT / "alerts.yaml").write_text(json.dumps(alerts, ensure_ascii=False, indent=2), encoding="utf-8")
