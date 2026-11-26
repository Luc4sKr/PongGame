"""Regression checks: python -m unittest discover -s scripts -p 'test_*.py'."""

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch, call

from demo_ambiente import Demo, Pair


class ExistingEnvironmentTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = patch("demo_ambiente.ROOT", Path(self.directory.name))
        root.start()
        self.addCleanup(root.stop)
        self.demo = Demo("F2", project="existing")
        self.demo.gateway = "http://127.0.0.1:8080"
        self.demo.show = Mock()

    def test_never_creates_or_removes_environment(self):
        with patch("demo_ambiente.subprocess.run") as run:
            for action in ("up", "down", "build", "pull", "rm", "create"):
                with self.assertRaises(ValueError):
                    self.demo.compose(action)
            run.assert_not_called()
        self.assertEqual(self.demo.command, ["docker", "compose", "-p", "existing"])

    def test_missing_services_fail_before_mutation(self):
        self.demo.compose = Mock(return_value="")
        with self.assertRaisesRegex(RuntimeError, "Serviços ausentes"):
            self.demo.start()
        self.demo.finish()
        self.demo.compose.assert_called_once_with("ps", "--status", "running", "--services")

    def test_recovery_starts_only_target_container_without_dependencies(self):
        with patch("demo_ambiente.subprocess.run", side_effect=[
            SimpleNamespace(returncode=0, stdout="existing-container-id\n", stderr=""),
            SimpleNamespace(returncode=0, stdout="existing-container-id\n", stderr=""),
        ]) as run:
            self.demo.compose("start", "game-2")
        self.assertEqual(run.call_args_list[0].args[0],
                         ["docker", "compose", "-p", "existing", "ps", "-a", "-q", "game-2"])
        self.assertEqual(run.call_args_list[1].args[0], ["docker", "start", "existing-container-id"])

    def test_failed_cleanup_still_restores_other_resources(self):
        d = self.demo
        d.attached = True
        d.stopped = {"rabbitmq", "game-1"}
        d.fault_keys = ["demo:dependency:test-player"]
        d.reservations = {"test-reservation": "test-token"}
        d.pairs = [Pair("test-match", [])]
        d.compose = Mock()
        d.consumers = Mock()
        d.redis = Mock(side_effect=RuntimeError("redis unavailable"))
        d.api = Mock()
        d.finish_pair = Mock()
        d.save_logs = Mock()
        d.log_process = Mock()
        with self.assertRaisesRegex(RuntimeError, "redis unavailable"):
            d.finish()
        self.assertEqual(d.compose.call_args_list,
                         [call("start", "game-1"), call("start", "rabbitmq")])
        d.api.assert_called_once_with("DELETE", "/api/matchmaking/arenas/reserve/test-reservation",
                                      token="test-token")
        d.finish_pair.assert_called_once_with(d.pairs[0])
        d.save_logs.assert_called_once()
        d.log_process.close.assert_called_once()

    def test_completed_ticket_cleanup_accepts_not_found(self):
        with patch("demo_ambiente.http_json", return_value=(404, {})):
            self.demo.api("DELETE", "/api/matchmaking/queue", token="test-token", status=(200, 404))

    def test_queries_target_application_redis_not_fallback(self):
        d = self.demo
        d.redis_host, d.redis_port = "172.20.0.5", "6379"
        d.compose = Mock(return_value="2")
        d.record_query = Mock()
        self.assertEqual(d.slots(), 2)
        d.compose.assert_called_once_with("exec", "-T", "redis", "redis-cli", "-h", "172.20.0.5",
                                          "-p", "6379", "--raw", "GET", "mm:arena:active_count")


if __name__ == "__main__":
    unittest.main()
