"""Read-only verification of Grafana, metrics, traces and correlated logs.

Example: py -3 scripts/check_observability.py --trace-id <id> --expect-service ponggame-game
Does not create/restart containers or generate application traffic.
"""
import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter


def fetch(base, path, params=None):
    if params:
        path += "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(base + path, timeout=15) as response:
        return json.load(response)


def attributes(items):
    return {item["key"]: next(iter(item.get("value", {}).values()), None) for item in items}


def inspect_trace(document):
    services = Counter()
    spans = []
    for batch in document.get("batches", document.get("resourceSpans", [])):
        resource = attributes(batch.get("resource", {}).get("attributes", []))
        name = resource.get("service.name", "unknown")
        for scope in batch.get("scopeSpans", batch.get("instrumentationLibrarySpans", [])):
            for span in scope.get("spans", []):
                services[name] += 1
                spans.append({"service": name, "name": span["name"], "attributes": attributes(span.get("attributes", [])),
                              "status": span.get("status", {})})
    return services, spans


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--grafana", default=os.getenv("GRAFANA_URL", "http://127.0.0.1:3000"))
    parser.add_argument("--trace-id")
    parser.add_argument("--expect-service", action="append", default=[])
    parser.add_argument("--expect-span", action="append", default=[])
    args = parser.parse_args()
    base = args.grafana.rstrip("/")
    health = fetch(base, "/api/health")
    assert health["database"] == "ok", health
    dashboard = fetch(base, "/api/dashboards/uid/ponggame")
    print("Grafana:", health["version"], "| Dashboard:", dashboard["dashboard"]["title"])
    prom = "/api/datasources/proxy/uid/prometheus/api/v1/"
    names = set(fetch(base, prom + "label/__name__/values")["data"])
    required = {"httpcheck_status", "postgresql_backends", "rabbitmq_queue_consumers",
                "pong_game_broker_connected", "pong_matchmaking_active", "pong_matchmaking_outbox_pending"}
    assert required <= names, f"Métricas ausentes: {required - names}"
    print("Métricas confirmadas:", ", ".join(sorted(required)))
    # Evaluate every provisioned PromQL query: syntax errors cannot hide in the UI.
    for panel in dashboard["dashboard"]["panels"]:
        if panel.get("datasource", {}).get("uid") == "prometheus":
            for target in panel["targets"]:
                result = fetch(base, prom + "query", {"query": target["expr"]})
                assert result["status"] == "success", (panel["title"], result)
                print(f"Painel: {panel['title']} — {len(result['data']['result'])} séries")
    if args.trace_id:
        deadline = time.monotonic() + 60
        while True:
            try:
                document = fetch(base, "/api/datasources/proxy/uid/tempo/api/traces/" + args.trace_id)
                services, spans = inspect_trace(document)
                assert set(args.expect_service) <= services.keys(), f"Serviços no trace: {dict(services)}"
                assert set(args.expect_span) <= {s["name"] for s in spans}, "Spans esperados ausentes"
                assert spans, "Trace sem spans"
                break
            except (AssertionError, OSError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(2)
        print("Trace:", args.trace_id, "| spans por serviço:", dict(services))
        logs = fetch(base, "/api/datasources/proxy/uid/loki/loki/api/v1/query_range", {
            "query": '{service_name=~"ponggame-.+"} | trace_id = "' + args.trace_id + '"',
            "start": str(time.time_ns() - 3600 * 1_000_000_000), "end": str(time.time_ns()), "limit": 1000})
        count = sum(len(row["values"]) for row in logs["data"]["result"])
        assert count > 0, "Nenhum log correlacionado ao trace"
        print("Logs correlacionados:", count)
        forbidden = {"url.query", "http.request.header.authorization", "db.statement", "db.query.text"}
        assert not any(forbidden & s["attributes"].keys() for s in spans), "Atributos sensíveis não removidos"
    print("OBSERVABILIDADE VERIFICADA")


if __name__ == "__main__":
    main()
