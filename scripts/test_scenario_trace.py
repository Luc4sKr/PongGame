import json
import unittest
from unittest.mock import patch

from scenario_trace import ScenarioTrace, trace_headers


class ScenarioTraceTests(unittest.TestCase):
    def test_all_requests_share_scenario_parent_and_export_result(self):
        with patch.object(ScenarioTrace, "send") as send:
            scenario = ScenarioTrace("F2")
            self.assertEqual(trace_headers()["traceparent"], scenario.traceparent)
            self.assertIn(scenario.trace_id, scenario.url)
            scenario.event("Docker", "kill game-1")
            scenario.finish()
            exported = send.call_args.args[1]["resourceSpans"][0]["scopeSpans"][0]["spans"][0]
            self.assertEqual(exported["traceId"], scenario.trace_id)
            self.assertEqual(exported["status"]["code"], 1)
            self.assertEqual(exported["events"][0]["name"], "Docker")
            self.assertEqual(trace_headers(), {})
            json.dumps(exported)

    def test_failure_is_exported_as_error(self):
        with patch.object(ScenarioTrace, "send") as send:
            scenario = ScenarioTrace("F7")
            scenario.finish(ValueError("missing compensation"))
            exported = send.call_args.args[1]["resourceSpans"][0]["scopeSpans"][0]["spans"][0]
            self.assertEqual(exported["status"], {"code": 2, "message": "missing compensation"})
