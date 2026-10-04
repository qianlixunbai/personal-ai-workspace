"""M4B real WPF/HTTP/Ollama and interrupted-execution smoke, isolated synthetic data only.

Requires clean verify and installed local qwen3.5:4b. Never uses user Memory/WinCred.
Evidence contains statuses/counts/checks, never messages, Memory text, prompts or credentials.
"""
from pathlib import Path
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
jar = root / "target/personal-ai-workspace-0.1.0.jar"
project = root / "desktop/acceptance/PersonalAiWorkspace.ConversationAcceptance/PersonalAiWorkspace.ConversationAcceptance.csproj"
flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
evidence = {"result": "FAIL", "syntheticOnly": True, "checks": [], "realTimeout": "UNVERIFIED; automated timeout tests are primary evidence"}
stage = "configuration"


def require(condition, check):
    global stage
    stage = check
    if not condition:
        raise RuntimeError(check)
    evidence["checks"].append(check)


def main():
    require(os.name == "nt" and jar.is_file(), "windows-packaged-runtime")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 8765))
    built = subprocess.run(["dotnet", "build", str(project)], cwd=root, capture_output=True, creationflags=flags, timeout=90)
    require(built.returncode == 0, "wpf-harness-build")
    settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True, creationflags=flags, timeout=15)
    home = re.search(r"^\s*java\.home = (.+)$", settings.stderr, re.MULTILINE)
    require(home is not None, "java-resolution")
    java = Path(home.group(1).strip()) / "bin/java.exe"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with tempfile.TemporaryDirectory(prefix="workspace-m4b-smoke-") as directory:
        temporary = Path(directory).resolve()
        require(temporary.parent == Path(tempfile.gettempdir()).resolve(), "isolated-temporary-root")
        token_file, data = temporary / "auth/client-token", temporary / "data"
        process, token = None, None
        logs = []

        def request(method, path, body=None, auth=True):
            headers = {"Accept": "application/json"}
            if auth:
                headers["Authorization"] = "Bearer " + token
            if body is not None:
                headers["Content-Type"] = "application/json"
            req = urllib.request.Request("http://127.0.0.1:8765" + path, method=method, headers=headers,
                                         data=json.dumps(body).encode() if body is not None else None)
            try:
                response = opener.open(req, timeout=8)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                return response.status, json.loads(response.read() or b"{}")

        def stop():
            nonlocal process
            if process is not None:
                process.terminate()
                try:
                    process.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
                process = None

        def start(*extra):
            nonlocal process, token
            log = temporary / ("runtime-" + str(len(logs)) + ".log")
            logs.append(log)
            with log.open("wb") as output:
                process = subprocess.Popen([str(java), "-jar", str(jar), "--server.port=8765",
                    f"--workspace.security.token-file={token_file}", f"--workspace.data-directory={data}", *extra],
                    cwd=root, stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
            until = time.monotonic() + 35
            while True:
                require(process.poll() is None and time.monotonic() < until, "runtime-startup")
                try:
                    if request("GET", "/actuator/health/readiness", auth=False)[0] == 200 and token_file.is_file():
                        token = token_file.read_text("ascii").strip()
                        break
                except OSError:
                    pass
                time.sleep(0.15)

        def wpf(phase, conversation_id=None):
            global stage
            stage = "real-wpf-" + phase
            env = os.environ.copy()
            env["M4B_TEST_TOKEN_FILE"] = str(token_file)
            if conversation_id:
                env["M4B_TEST_CONVERSATION_ID"] = conversation_id
            dll = project.parent / "bin/Debug/net10.0-windows/PersonalAiWorkspace.ConversationAcceptance.dll"
            result = subprocess.run(["dotnet", str(dll), phase], cwd=root, env=env, capture_output=True,
                                    text=True, encoding="utf-8", creationflags=flags, timeout=540)
            try:
                safe = json.loads(result.stdout)
            except ValueError:
                raise RuntimeError("safe-wpf-evidence") from None
            require(result.returncode == 0 and safe.get("result") == "PASS", "wpf-" + phase + "-" + safe.get("check", "complete"))
            evidence["wpf-" + phase] = safe
            return safe["conversationId"]

        def send(conversation_id, message, memories=None):
            status, accepted = request("POST", f"/api/v1/conversations/{conversation_id}/turns",
                                       {"message": message, "memories": memories or []})
            require(status == 202, "turn-accepted")
            until = time.monotonic() + 165
            while True:
                _, task = request("GET", "/api/v1/tasks/" + accepted["taskId"])
                if task["status"] not in ("QUEUED", "RUNNING"):
                    return accepted, task
                require(time.monotonic() < until, "task-deadline")
                time.sleep(0.15)

        try:
            start()
            conversation_id = wpf("initial")
            stop()
            start()
            wpf("reopen", conversation_id)
            _, other = request("POST", "/api/v1/conversations", {"title": "Synthetic isolated conversation"})
            accepted, task = send(other["id"], "What synthetic code word did I give you? If none was supplied, reply UNKNOWN.")
            require(task["status"] == "SUCCEEDED" and "ORBIT-731" not in task["result"] and not accepted["admittedSequences"], "no-cross-conversation-history")
            _, item = request("POST", "/api/v1/memory/items", {"type": "PROJECT_NOTE", "title": "Synthetic per-turn reference", "content": "The synthetic marker is VECTOR-268."})
            accepted, task = send(other["id"], "What synthetic marker is in the explicit Memory? Reply with that marker only.", [{"id": item["id"], "revision": item["revision"]}])
            require(task["status"] == "SUCCEEDED" and "VECTOR-268" in task["result"] and accepted["memoryCount"] == 1, "real-explicit-memory-admission")
            accepted, task = send(other["id"], "Reply with exactly READY.")
            require(task["status"] == "SUCCEEDED" and accepted["memoryCount"] == 0, "next-turn-context-memory-count-zero")
            evidence["context"] = {key: accepted[key] for key in ("memoryCount", "admittedSequences", "inputCharacters", "inputBytes")}
            stop()
            # Seed one synthetic interrupted execution only while our Runtime is stopped.
            pending_id, turn_id, task_id, message_id = [str(uuid.uuid4()) for _ in range(4)]
            now = int(time.time() * 1000)
            with closing(sqlite3.connect(data / "memory.db")) as db:
                db.execute("PRAGMA foreign_keys=ON")
                db.execute("INSERT INTO conversations VALUES(?,?,'ACTIVE',?,?)", (pending_id, "Synthetic interrupted execution", now, now))
                db.execute("INSERT INTO conversation_turns(id,conversation_id,sequence,status,created_at,updated_at,task_id) VALUES(?,?,1,'PENDING',?,?,?)", (turn_id, pending_id, now, now, task_id))
                db.execute("INSERT INTO conversation_messages VALUES(?,?,'USER',?,?)", (message_id, turn_id, "Synthetic interrupted user instruction", now))
                db.commit()
            counts = {"tags": 0, "chat": 0}

            class CountingProvider(BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass
                def do_GET(self):
                    counts["tags"] += 1
                    body = b'{"models":[]}'
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(body)
                def do_POST(self):
                    counts["chat"] += 1
                    self.send_response(503)
                    self.end_headers()

            server = ThreadingHTTPServer(("127.0.0.1", 0), CountingProvider)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                start(f"--workspace.ollama.base-url=http://127.0.0.1:{server.server_port}")
                _, detail = request("GET", "/api/v1/conversations/" + pending_id)
                turn = detail["turns"][0]
                require(turn["status"] == "FAILED" and turn["failureCode"] == "EXECUTION_INTERRUPTED" and turn["assistantMessage"] is None, "restart-pending-fail-closed")
                require(counts == {"tags": 0, "chat": 0}, "restart-no-provider-execution-or-replay")
                accepted, task = send(pending_id, "Synthetic new user request after interruption")
                require(task["status"] == "FAILED" and task["error"]["code"] == "MODEL_UNAVAILABLE", "real-controlled-provider-model-failure")
                _, detail = request("GET", "/api/v1/conversations/" + pending_id)
                require(detail["turns"][1]["status"] == "FAILED" and detail["turns"][1]["assistantMessage"] is None, "failure-user-retained-no-assistant")
                require(counts == {"tags": 1, "chat": 0}, "failure-no-automatic-retry")
                wpf("failure", pending_id)
            finally:
                stop()
                server.shutdown()
                server.server_close()
                thread.join(timeout=5)
            with closing(sqlite3.connect(data / "memory.db")) as db:
                require(db.execute("PRAGMA user_version").fetchone()[0] == 3 and db.execute("PRAGMA quick_check").fetchone()[0] == "ok", "workspace-v3-integrity")
            needles = [token, "ORBIT-731", "QUARTZ-492", "VECTOR-268", "Synthetic interrupted user instruction"]
            require(all(all(value.encode() not in log.read_bytes() for value in needles) for log in logs), "runtime-logs-no-content-or-secret")
            evidence["result"] = "PASS"
        finally:
            stop()
    require(not temporary.exists(), "synthetic-data-cleanup")


try:
    main()
except Exception:
    evidence["result"] = "FAIL"
    evidence["failedCheck"] = stage
finally:
    (root / ".verification").mkdir(exist_ok=True)
    (root / ".verification/m4b-conversation-smoke-evidence.json").write_text(json.dumps(evidence, indent=2), "utf-8")
    print(json.dumps(evidence, indent=2))
if evidence["result"] != "PASS":
    raise SystemExit(1)
