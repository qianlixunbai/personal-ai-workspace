"""Synthetic Windows/WPF/HTTP/SQLite/Ollama logical recovery; no user data or WinCred.

Original source is deleted before maintenance restore. Picker choices are injected;
production controls, streaming file IO, full validation and restore remain in use.
"""
from pathlib import Path
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import base64
import hashlib
import json
import os
import re
import shutil
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
project = root / "desktop/acceptance/PersonalAiWorkspace.WorkspaceBackupAcceptance/PersonalAiWorkspace.WorkspaceBackupAcceptance.csproj"
flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
stage = "configuration"
evidence = {"result": "FAIL", "syntheticOnly": True, "checks": []}


def require(value, check):
    global stage
    stage = check
    if not value:
        raise RuntimeError(check)
    evidence["checks"].append(check)


def logical(path):
    queries = {
        "memory": "SELECT id,type,title,content,status,revision,source,created_at,updated_at FROM memory_items ORDER BY id",
        "conversations": "SELECT id,title,status,created_at,updated_at FROM conversations ORDER BY id",
        "turns": "SELECT id,conversation_id,sequence,status,created_at,updated_at,failure_code FROM conversation_turns ORDER BY conversation_id,sequence",
        "messages": "SELECT id,turn_id,role,content,created_at FROM conversation_messages ORDER BY id",
        "selections": "SELECT turn_id,position,memory_id,revision FROM conversation_memory_selections ORDER BY turn_id,position",
    }
    with closing(sqlite3.connect(path)) as db:
        return {name: db.execute(query).fetchall() for name, query in queries.items()}


def main():
    require(os.name == "nt" and jar.is_file(), "windows-packaged-runtime")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 8765))
    built = subprocess.run(["dotnet", "build", str(project)], cwd=root, capture_output=True, creationflags=flags, timeout=90)
    require(built.returncode == 0, "real-wpf-harness-build")
    settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True, creationflags=flags, timeout=15)
    home = re.search(r"^\s*java\.home = (.+)$", settings.stderr, re.MULTILINE)
    require(home is not None, "direct-java-executable-resolution")
    java = Path(home.group(1).strip()) / "bin/java.exe"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with tempfile.TemporaryDirectory(prefix="workspace-m4c-smoke-") as directory:
        temporary = Path(directory).resolve()
        require(temporary.parent == Path(tempfile.gettempdir()).resolve(), "isolated-task-owned-root")
        source, maintenance, target = [temporary / name for name in ("source", "maintenance", "restored")]
        backup, token_file = temporary / "workspace-backup.json", temporary / "auth/client-token"
        process, token, logs = None, None, []
        counts = {"tags": 0, "chat": 0}

        class Relay(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                counts["tags"] += 1
                self.forward("GET")

            def do_POST(self):
                counts["chat"] += 1
                self.forward("POST")

            def forward(self, method):
                payload = self.rfile.read(int(self.headers.get("Content-Length", "0"))) if method == "POST" else None
                req = urllib.request.Request("http://127.0.0.1:11434" + self.path, data=payload, method=method,
                                             headers={"Content-Type": "application/json"})
                try:
                    with opener.open(req, timeout=130) as response:
                        body = response.read()
                        self.send_response(response.status)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", str(len(body)))
                        self.end_headers()
                        self.wfile.write(body)
                except Exception:
                    self.send_response(503)
                    self.end_headers()

        relay = ThreadingHTTPServer(("127.0.0.1", 0), Relay)
        relay.daemon_threads = True
        threading.Thread(target=relay.serve_forever, daemon=True).start()

        def request(method, path, body=None, raw=None, extra=None, auth=True):
            headers = {"Accept": "application/json", "Content-Type": "application/json"}
            if auth:
                headers["Authorization"] = "Bearer " + token
            if extra:
                headers.update(extra)
            payload = raw if raw is not None else json.dumps(body).encode() if body is not None else None
            req = urllib.request.Request("http://127.0.0.1:8765" + path, data=payload, method=method, headers=headers)
            try:
                response = opener.open(req, timeout=20)
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

        def start(data, counted=False):
            nonlocal process, token
            stop()
            log = temporary / ("runtime-" + str(len(logs)) + ".log")
            logs.append(log)
            args = [str(java), "-jar", str(jar), "--server.port=8765", f"--workspace.security.token-file={token_file}",
                    f"--workspace.data-directory={data}"]
            if counted:
                args.append(f"--workspace.ollama.base-url=http://127.0.0.1:{relay.server_port}")
            with log.open("wb") as output:
                process = subprocess.Popen(args, cwd=root, stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
            until = time.monotonic() + 35
            while True:
                if process.poll() is not None or time.monotonic() > until:
                    raise RuntimeError("isolated-runtime-startup")
                try:
                    if request("GET", "/actuator/health/readiness", auth=False)[0] == 200 and token_file.is_file():
                        token = token_file.read_text("ascii").strip()
                        break
                except OSError:
                    pass
                time.sleep(0.15)
            require(True, "packaged-runtime-startup")

        def send(conversation, message, memories=None):
            status, accepted = request("POST", f"/api/v1/conversations/{conversation}/turns", {"message": message, "memories": memories or []})
            require(status == 202, "explicit-new-turn-admitted")
            until = time.monotonic() + 165
            while True:
                _, task = request("GET", "/api/v1/tasks/" + accepted["taskId"])
                if task["status"] not in ("QUEUED", "RUNNING"):
                    require(task["status"] == "SUCCEEDED", "real-ollama-terminal-success")
                    return accepted, task
                if time.monotonic() > until:
                    raise RuntimeError("task-deadline")
                time.sleep(0.15)

        def wpf(phase, conversation=None):
            global stage
            stage = "real-wpf-" + phase
            env = os.environ.copy()
            env.update(M4C_TOKEN_FILE=str(token_file), M4C_BACKUP_FILE=str(backup), M4C_TARGET_DIRECTORY=str(target))
            if conversation:
                env["M4C_CONVERSATION_ID"] = conversation
            dll = project.parent / "bin/Debug/net10.0-windows/PersonalAiWorkspace.WorkspaceBackupAcceptance.dll"
            result = subprocess.run(["dotnet", str(dll), phase], cwd=root, env=env, capture_output=True,
                                    text=True, encoding="utf-8", creationflags=flags, timeout=540)
            try:
                safe = json.loads(result.stdout)
            except ValueError:
                raise RuntimeError("safe-wpf-evidence") from None
            require(result.returncode == 0 and safe.get("result") == "PASS", "wpf-" + phase + "-" + safe.get("check", "complete"))
            evidence["wpf-" + phase] = safe

        try:
            start(source)
            _, memory = request("POST", "/api/v1/memory/items", {"type": "PROJECT_NOTE", "title": "Synthetic M4C Memory", "content": "The synthetic explicit Memory marker is M4C-MEMORY-492. 中文恢复搜索"})
            _, archived = request("POST", "/api/v1/memory/items", {"type": "PREFERENCE", "title": "Synthetic M4C archived", "content": "Synthetic archived preference."})
            request("POST", f"/api/v1/memory/items/{archived['id']}/archive", {"expectedRevision": 1})
            _, a = request("POST", "/api/v1/conversations", {"title": "Synthetic M4C active"})
            accepted, _ = send(a["id"], "Within this conversation the synthetic code word is M4C-HISTORY-731. Reply with exactly M4C-HISTORY-731.", [{"id": memory["id"], "revision": 1}])
            old_task = accepted["taskId"]
            _, answer = send(a["id"], "What synthetic code word did I give you? Reply with only that code word.")
            require("M4C-HISTORY-731" in answer["result"], "original-real-multi-turn-context")
            request("PUT", f"/api/v1/memory/items/{memory['id']}", {"expectedRevision": 1, "type": "PROJECT_NOTE", "title": memory["title"], "content": memory["content"] + " Synthetic edited revision."})
            _, b = request("POST", "/api/v1/conversations", {"title": "Synthetic M4C archived"})
            send(b["id"], "Reply with exactly READY.")
            request("POST", f"/api/v1/conversations/{b['id']}/archive")
            stop()
            # Controlled terminal fixtures; never manufacture fake Assistant errors or live task state.
            with closing(sqlite3.connect(source / "memory.db")) as db:
                now = int(time.time() * 1000)
                for sequence, status in enumerate(("FAILED", "CANCELLED", "TIMED_OUT"), 3):
                    turn_id = str(uuid.uuid4())
                    db.execute("INSERT INTO conversation_turns(id,conversation_id,sequence,status,created_at,updated_at,failure_code) VALUES(?,?,?,?,?,?,?)",
                               (turn_id, a["id"], sequence, status, now, now, "MODEL_UNAVAILABLE" if status == "FAILED" else None))
                    db.execute("INSERT INTO conversation_messages VALUES(?,?,'USER',?,?)", (str(uuid.uuid4()), turn_id, "Synthetic M4C terminal user", now))
                # A dangling historical selection is intentionally independent of current Memory existence.
                first = db.execute("SELECT id FROM conversation_turns WHERE conversation_id=? AND sequence=2", (a["id"],)).fetchone()[0]
                db.execute("INSERT INTO conversation_memory_selections VALUES(?,0,?,1)", (first, str(uuid.uuid4())))
                db.execute("UPDATE conversations SET updated_at=? WHERE id=?", (now, a["id"]))
                db.commit()
            start(source)
            original = logical(source / "memory.db")
            wpf("export")
            require(backup.is_file(), "real-plaintext-backup-file")
            stop()
            require(source.parent == temporary and source.name == "source", "original-removal-scope")
            shutil.rmtree(source)
            require(not source.exists(), "original-workspace-unavailable-before-recovery")
            start(maintenance, True)
            maintenance_before = hashlib.sha256((maintenance / "memory.db").read_bytes()).digest()
            wpf("restore")
            require(logical(target / "memory.db") == original, "every-logical-field-exact-after-restore")
            require(hashlib.sha256((maintenance / "memory.db").read_bytes()).digest() == maintenance_before, "active-workspace-untouched")
            with closing(sqlite3.connect(target / "memory.db")) as db:
                require(db.execute("PRAGMA user_version").fetchone()[0] == 3, "restored-current-workspace-schema-v3")
                require(db.execute("SELECT count(*) FROM conversation_turns WHERE task_id IS NOT NULL").fetchone()[0] == 0, "task-identity-not-restored")
                require(db.execute("PRAGMA quick_check").fetchone()[0] == "ok", "restored-sqlite-integrity")
            raw = backup.read_bytes()
            for corrupted in (raw[:-1], raw.replace(b"M4C-MEMORY-492", b"TAMPERED-DIGEST")):
                require(request("POST", "/api/v1/workspace/backup/validate", raw=corrupted)[0] == 400, "corrupt-backup-controlled-rejection")
            for refused in (maintenance, target):
                encoded = base64.urlsafe_b64encode(str(refused).encode()).rstrip(b"=").decode()
                require(request("POST", "/api/v1/workspace/backup/restore", raw=raw, extra={"X-Workspace-Restore-Target": encoded})[0] == 409, "active-or-nonempty-target-rejected")
            require(logical(target / "memory.db") == original and hashlib.sha256((maintenance / "memory.db").read_bytes()).digest() == maintenance_before, "negative-recovery-no-partial-publication")
            start(target, True)
            require(counts == {"tags": 0, "chat": 0}, "restored-startup-zero-provider-replay")
            require(logical(target / "memory.db") == original, "restored-startup-preserves-terminal-truth")
            require(request("GET", "/api/v1/tasks/" + old_task)[0] == 404, "old-task-not-recreated")
            require(request("POST", f"/api/v1/conversations/{b['id']}/turns", {"message": "Synthetic archived send"})[0] == 409, "archived-conversation-send-blocked")
            wpf("recover", a["id"])
            require(counts["chat"] == 2, "only-two-explicit-restored-model-calls")
            request("POST", f"/api/v1/conversations/{b['id']}/unarchive")
            send(b["id"], "Reply with exactly READY.")
            require(request("GET", "/api/v1/memory/items?status=ACTIVE")[1]["total"] == 1, "no-automatic-memory")
            evidence["counts"] = {"memory": len(original["memory"]), "conversations": len(original["conversations"]),
                                  "turns": len(original["turns"]), "messages": len(original["messages"]), "selections": len(original["selections"])}
            evidence["backupBytes"] = len(raw)
            stop()
            private = [token, memory["title"], memory["content"], "M4C-MEMORY-492", "M4C-HISTORY-731", "Synthetic M4C terminal user", str(target)]
            require(all(not any(value.encode() in log.read_bytes() for value in private) for log in logs), "logs-no-credential-content-or-target")
            evidence["result"] = "PASS"
        finally:
            stop()
            relay.shutdown()
            relay.server_close()


try:
    main()
except Exception:
    evidence["failedCheck"] = stage
finally:
    folder = root / ".verification"
    folder.mkdir(exist_ok=True)
    (folder / "m4c-workspace-recovery-evidence.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    print(json.dumps(evidence, indent=2))
    if evidence["result"] != "PASS":
        raise SystemExit(1)
