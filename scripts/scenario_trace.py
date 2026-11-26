"""W3C propagation and OTLP/JSON export for demo scripts (stdlib only).

The global carrier is immutable during a scenario, so parallel F5 clients share
the scenario parent. No credentials, tokens or HTTP bodies are recorded.
"""
import json
import os
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request

_active = None


def trace_headers():
    return {"traceparent": _active.traceparent} if _active else {}


def attrs(values):
    return [{"key": key, "value": {"stringValue": str(value)}} for key, value in values.items()]


class ScenarioTrace:
    def __init__(self, name):
        global _active
        self.name = name
        self.trace_id = secrets.token_hex(16)
        self.span_id = secrets.token_hex(8)
        self.traceparent = f"00-{self.trace_id}-{self.span_id}-01"
        self.started = time.time_ns()
        self.events = []
        self.warned = False
        self.resource = {"attributes": attrs({"service.name": "ponggame-demo", "service.namespace": "ponggame", "deployment.environment.name": "local"})}
        self.endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4318").rstrip("/")
        _active = self
        # A short child span makes the trace searchable while the scenario runs.
        self.send("traces", {"resourceSpans": [{"resource": self.resource, "scopeSpans": [{"scope": {"name": "ponggame/demo"}, "spans": [
            {"traceId": self.trace_id, "spanId": secrets.token_hex(8), "parentSpanId": self.span_id,
             "name": "scenario.started", "kind": 1, "startTimeUnixNano": str(self.started), "endTimeUnixNano": str(time.time_ns()),
             "attributes": attrs({"pong.scenario": name})}]}]}]})

    @property
    def url(self):
        panes = {"trace": {"datasource": "tempo", "queries": [{"refId": "A", "queryType": "traceql", "query": self.trace_id}],
                           "range": {"from": "now-1h", "to": "now"}}}
        return os.getenv("GRAFANA_URL", "http://127.0.0.1:3000") + "/explore?schemaVersion=1&panes=" + urllib.parse.quote(json.dumps(panes))

    def send(self, signal, payload):
        try:
            request = urllib.request.Request(self.endpoint + "/v1/" + signal, json.dumps(payload).encode(),
                                             {"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(request, timeout=2) as response:
                response.read()
        except (OSError, urllib.error.URLError) as error:
            if not self.warned:
                print(f"[telemetria] Exportação indisponível: {error}. O teste continua.", flush=True)
                self.warned = True

    def event(self, name, value=""):
        timestamp = str(time.time_ns())
        self.events.append({"timeUnixNano": timestamp, "name": name, "attributes": attrs({"detail": value})})
        self.send("logs", {"resourceLogs": [{"resource": self.resource, "scopeLogs": [{"scope": {"name": "ponggame/demo"}, "logRecords": [
            {"timeUnixNano": timestamp, "observedTimeUnixNano": timestamp, "severityNumber": 9, "severityText": "INFO",
             "body": {"stringValue": f"{name}: {value}"}, "traceId": self.trace_id, "spanId": self.span_id,
             "attributes": attrs({"pong.scenario": self.name})}]}]}]})

    def finish(self, error=None):
        global _active
        span = {"traceId": self.trace_id, "spanId": self.span_id, "name": "demo." + self.name, "kind": 1,
                "startTimeUnixNano": str(self.started), "endTimeUnixNano": str(time.time_ns()),
                "attributes": attrs({"pong.scenario": self.name, "pong.test.result": "failed" if error else "passed"}),
                "events": self.events, "status": {"code": 2 if error else 1}}
        if error:
            span["status"]["message"] = str(error)
        self.send("traces", {"resourceSpans": [{"resource": self.resource, "scopeSpans": [{"scope": {"name": "ponggame/demo"}, "spans": [span]}]}]})
        _active = None
