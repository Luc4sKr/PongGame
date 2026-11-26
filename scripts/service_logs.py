"""Stream existing Compose service logs without starting or stopping services."""

import os
import re
import signal
import subprocess
from threading import Thread


class ServiceLogs:
    def __init__(self, command, cwd, since, services, emit, env=None):
        options = ({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt"
                   else {"start_new_session": True})
        self.process = subprocess.Popen(
            [*command, "logs", "--follow", "--since", since, "--timestamps", "--no-color", *services],
            cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", **options)
        def relay():
            for line in self.process.stdout:
                line = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", line).rstrip()
                if line:
                    emit(line)
        self.thread = Thread(target=relay, daemon=True)
        self.thread.start()

    def close(self):
        if self.process.poll() is None:
            if os.name == "nt":
                # docker.exe launches the Compose plugin as a child on Windows.
                # Terminating only docker.exe leaks the follower and duplicates logs.
                subprocess.run(["taskkill", "/PID", str(self.process.pid), "/T", "/F"],
                               capture_output=True, timeout=10, check=True)
            else:
                os.killpg(self.process.pid, signal.SIGTERM)
        self.process.wait(timeout=10)
        self.thread.join(timeout=10)
        if self.thread.is_alive():
            raise RuntimeError("O acompanhamento de logs não encerrou corretamente.")
        self.process.stdout.close()
