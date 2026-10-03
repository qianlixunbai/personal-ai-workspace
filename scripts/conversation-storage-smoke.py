"""Packaged M4A restart durability; synthetic data only, no execution or backup/recovery claim.

Run after mvnw.cmd clean verify. Test-only Java fixture seeds M3 v1 and writes internal turns.
No product turn/message HTTP endpoints are introduced. Evidence excludes bodies and credentials.
"""
from contextlib import closing
from pathlib import Path
import json
import os
import re
import socket
import sqlite3
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

root = Path(__file__).resolve().parent.parent
jar = root / "target/personal-ai-workspace-0.1.0.jar"
flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
evidence = {"result": "FAIL", "syntheticOnly": True, "packagedRuntime": True,
            "providerRequired": False, "backupRestoreAcceptance": False, "checks": []}
stage = "configuration"


def require(condition, check):
    global stage
    stage = check
    if not condition:
        raise RuntimeError(check)
    if check not in evidence["checks"]:
        evidence["checks"].append(check)


def main():
    require(jar.is_file(), "packaged-runtime-present")
    settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True,
                              text=True, check=True, creationflags=flags, timeout=15)
    home = re.search(r"^\s*java\.home = (.+)$", settings.stderr, re.MULTILINE)
    require(home is not None, "java-executable-resolution")
    java = Path(home.group(1).strip()) / "bin" / ("java.exe" if os.name == "nt" else "java")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with tempfile.TemporaryDirectory(prefix="workspace-conversation-smoke-") as directory:
        temporary = Path(directory).resolve()
        require(temporary.parent == Path(tempfile.gettempdir()).resolve()
                and temporary.name.startswith("workspace-conversation-smoke-"), "isolated-temporary-root")
        auth, data = temporary / "auth/client-token", temporary / "data"
        log = temporary / "runtime.log"
        process, token = None, None
        pids = []
        fixture_output = []
        with zipfile.ZipFile(jar) as archive:
            libraries = []
            for prefix in ("BOOT-INF/lib/sqlite-jdbc-", "BOOT-INF/lib/slf4j-api-"):
                name = next(n for n in archive.namelist() if n.startswith(prefix) and n.endswith(".jar"))
                destination = temporary / Path(name).name
                destination.write_bytes(archive.read(name))
                libraries.append(str(destination))
        classpath = os.pathsep.join([str(root / "target/test-classes"), str(root / "target/classes"), *libraries])

        def fixture(mode, conversation_id=""):
            completed = subprocess.run([str(java), "-cp", classpath,
                "io.github.qianlixunbai.workspace.memory.ConversationSmokeFixture", str(temporary), mode, conversation_id],
                cwd=root, capture_output=True, creationflags=flags, timeout=20)
            fixture_output.extend([completed.stdout, completed.stderr])
            require(completed.returncode == 0, "fixture-" + mode)

        def request(method, path, body=None, authenticate=True):
            headers = {"Accept": "application/json"}
            if authenticate:
                headers["Authorization"] = "Bearer " + token
            if body is not None:
                headers["Content-Type"] = "application/json"
            req = urllib.request.Request(f"http://127.0.0.1:{port}" + path,
                data=json.dumps(body).encode("utf-8") if body is not None else None, headers=headers, method=method)
            try:
                response = opener.open(req, timeout=8)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                raw = response.read(1024 * 1024 + 1)
                require(len(raw) <= 1024 * 1024, "response-bounded")
                return response.status, json.loads(raw) if raw else None

        def stop():
            nonlocal process
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)

        def start():
            nonlocal process, token
            with log.open("ab") as output:
                process = subprocess.Popen([str(java), "-jar", str(jar), f"--server.port={port}",
                    f"--workspace.security.token-file={auth}", f"--workspace.data-directory={data}",
                    "--workspace.ollama.base-url=http://127.0.0.1:1"], cwd=root, stdout=output,
                    stderr=subprocess.STDOUT, creationflags=flags)
            pids.append(process.pid)
            deadline = time.monotonic() + 35
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("owned-runtime-startup")
                try:
                    if request("GET", "/actuator/health/readiness", authenticate=False)[0] == 200:
                        token = auth.read_text("ascii").strip()
                        return
                except (OSError, ValueError):
                    pass
                time.sleep(0.1)
            raise RuntimeError("owned-runtime-deadline")

        try:
            fixture("seed-m3")
            with closing(sqlite3.connect(data / "memory.db")) as db:
                require(db.execute("PRAGMA user_version").fetchone()[0] == 1, "actual-m3-v1-input")
                before = db.execute("SELECT * FROM memory_items").fetchall()
            start()
            with closing(sqlite3.connect(data / "memory.db")) as db:
                require(db.execute("PRAGMA user_version").fetchone()[0] == 2, "v1-to-v2-migration")
                require(db.execute("SELECT * FROM memory_items").fetchall() == before, "memory-source-exact-preserved")
            code, item = request("POST", "/api/v1/conversations", {})
            require(code == 201 and item["status"] == "ACTIVE" and item["title"] == "New conversation", "create-defaults")
            path = "/api/v1/conversations/" + item["id"]
            evidence["conversationId"] = item["id"]
            code, renamed = request("PATCH", path, {"title": " Synthetic renamed conversation "})
            require(code == 200 and renamed["title"] == "Synthetic renamed conversation", "rename-trimmed")
            require(request("GET", "/api/v1/conversations")[1]["total"] == 1, "list")
            require(request("GET", "/api/v1/memory/items?query=migration")[1]["total"] == 1, "memory-search-regression")
            require(request("GET", "/api/v1/memory/backup")[1]["schemaVersion"] == 1, "memory-backup-contract-v1")
            stop()
            fixture("turns", item["id"])
            start()
            code, detail = request("GET", path)
            turns = detail["turns"]
            require(code == 200 and detail["conversation"]["title"] == renamed["title"], "restart-title")
            require(detail["conversation"]["status"] == "ACTIVE" and detail["totalTurns"] == 2, "restart-lifecycle-count")
            require([t["sequence"] for t in turns] == [1, 2], "restart-linear-sequence")
            require([t["status"] for t in turns] == ["SUCCEEDED", "TIMED_OUT"], "restart-terminal-statuses")
            require(turns[0]["userMessage"]["role"] == "USER" and turns[0]["assistantMessage"]["role"] == "ASSISTANT"
                    and turns[1]["assistantMessage"] is None, "restart-role-and-optional-assistant")
            require(turns[0]["userMessage"]["content"] == "Synthetic user text 中文"
                    and turns[0]["assistantMessage"]["content"] == "Synthetic assistant text 中文"
                    and turns[1]["userMessage"]["content"] == "Synthetic timeout question", "restart-exact-contents")
            evidence["turnIds"] = [t["id"] for t in turns]
            evidence["turnCount"] = 2
            evidence["turnStatuses"] = [t["status"] for t in turns]
            require(request("POST", path + "/archive")[1]["status"] == "ARCHIVED", "archive")
            stop()
            start()
            require(request("GET", path)[1]["conversation"]["status"] == "ARCHIVED", "archived-after-restart")
            require(request("GET", "/api/v1/conversations")[1]["total"] == 0, "archived-hidden-default")
            require(request("POST", path + "/unarchive")[1]["status"] == "ACTIVE", "unarchive")
            require(request("DELETE", path)[0] == 204, "delete")
            stop()
            start()
            require(request("GET", path)[0] == 404, "deleted-after-restart")
            stop()
            with closing(sqlite3.connect(data / "memory.db")) as db:
                for table in ("conversations", "conversation_turns", "conversation_messages"):
                    require(db.execute("SELECT count(*) FROM " + table).fetchone()[0] == 0, "cascade-empty-" + table)
                require(db.execute("SELECT * FROM memory_items").fetchall() == before, "memory-unchanged-after-conversation-delete")
                require(db.execute("PRAGMA quick_check").fetchone()[0] == "ok", "sqlite-quick-check")
            require(len(set(pids)) == 4, "four-distinct-runtime-processes")
            logs = log.read_bytes() + b"".join(fixture_output)
            for private in (token, renamed["title"], "Synthetic user text 中文", "Synthetic assistant text 中文", "Synthetic timeout question",
                            "Synthetic migration note", "Synthetic migration preserved"):
                require(private.encode("utf-8") not in logs, "logs-private")
            evidence["result"] = "PASS"
        finally:
            stop()
    require(not temporary.exists(), "temporary-data-cleaned")


try:
    main()
except Exception:
    evidence["result"] = "FAIL"
    evidence["failureStage"] = stage
    evidence["failure"] = "Synthetic persistence acceptance did not complete."
destination = root / ".verification/m4a-conversation-smoke-evidence.json"
destination.parent.mkdir(exist_ok=True)
destination.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
print(json.dumps(evidence, indent=2))
raise SystemExit(0 if evidence["result"] == "PASS" else 1)
