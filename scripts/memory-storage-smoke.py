"""Packaged Runtime restart acceptance with isolated synthetic Memory and safe evidence only.

Run after mvnw.cmd package: python scripts/memory-storage-smoke.py
Never reads the user's Runtime credentials or Memory directory; never prints HTTP bodies.
"""
from pathlib import Path
from contextlib import closing
import json
import os
import re
import socket
import sqlite3
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
jar = root / "target/personal-ai-workspace-0.1.0.jar"
if not jar.is_file():
    raise SystemExit("Build the packaged Runtime first.")

# Oracle javapath can spawn a child JVM; resolve java.home so the owned PID is the actual Runtime.
settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True,
                          check=True, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
home = re.search(r"^\s*java\.home = (.+)$", settings.stderr, re.MULTILINE)
if home is None:
    raise SystemExit("Cannot resolve the Java runtime executable.")
java = Path(home.group(1).strip()) / "bin" / ("java.exe" if os.name == "nt" else "java")

with socket.socket() as listener:
    listener.bind(("127.0.0.1", 0))
    port = listener.getsockname()[1]

base = f"http://127.0.0.1:{port}"
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
evidence = {"result": "FAIL", "packagedRuntime": True, "syntheticOnly": True,
            "isolatedDataDirectory": True, "providerRequired": False, "checks": []}


def require(condition, check):
    if not condition:
        raise RuntimeError(check)
    evidence["checks"].append(check)


with tempfile.TemporaryDirectory(prefix="workspace-memory-smoke-") as directory:
    temporary = Path(directory).resolve()
    # Explicitly constrain recursive TemporaryDirectory cleanup to this newly allocated temp child.
    if temporary.parent != Path(tempfile.gettempdir()).resolve() or not temporary.name.startswith("workspace-memory-smoke-"):
        raise SystemExit("Unexpected isolated temporary location.")
    auth = temporary / "auth/client-token"
    data = temporary / "data"
    log = temporary / "runtime.log"
    marker = str(uuid.uuid4())
    title = "Synthetic Memory " + marker
    content = "Synthetic restart persistence 中文项目 " + marker
    process = None
    process_ids = []
    token = None

    def request(method, path, body=None, authenticated=True, headers=None):
        merged = dict(headers or {})
        if authenticated:
            merged["Authorization"] = "Bearer " + token
        if body is not None:
            merged["Content-Type"] = "application/json"
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(base + path, data=body, headers=merged, method=method)
        try:
            response = opener.open(req, timeout=6)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            parsed = json.loads(raw) if raw else None
            return response.status, parsed

    def start():
        global process
        with log.open("ab") as output:
            launched = subprocess.Popen([str(java), "-jar", str(jar), f"--server.port={port}",
                f"--workspace.security.token-file={auth}", f"--workspace.data-directory={data}",
                "--workspace.ollama.base-url=http://127.0.0.1:1"], cwd=root, stdout=output,
                stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        process = launched
        process_ids.append(launched.pid)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if launched.poll() is not None:
                raise RuntimeError("Runtime startup failed")
            try:
                if request("GET", "/actuator/health/readiness", authenticated=False)[0] == 200:
                    return launched
            except (OSError, ValueError):
                pass
            time.sleep(0.1)
        launched.terminate()
        launched.wait(timeout=10)
        raise RuntimeError("Runtime startup deadline")

    def stop():
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)

    try:
        process = start()
        token = auth.read_text("ascii").strip()
        code, item = request("POST", "/api/v1/memory/items", {"type": "PROJECT_NOTE", "title": title, "content": content})
        require(code == 201 and item["revision"] == 1, "create")
        path = "/api/v1/memory/items/" + item["id"]
        stop()
        process = start()
        code, loaded = request("GET", path)
        require(code == 200 and loaded == item, "restart-read-exact")
        code, page = request("GET", "/api/v1/memory/items?query=" + urllib.parse.quote("中文项"))
        require(code == 200 and page["total"] == 1 and page["items"][0]["id"] == item["id"], "chinese-search")
        code, page = request("GET", "/api/v1/memory/items?query=" + urllib.parse.quote("中"))
        require(code == 200 and page["total"] == 1, "short-query-search")
        code, changed = request("PUT", path, {"expectedRevision": 1, "type": "PREFERENCE", "title": title, "content": content + " edited"})
        require(code == 200 and changed["revision"] == 2, "update")
        code, error = request("POST", path + "/archive", {"expectedRevision": 1})
        require(code == 409 and error["code"] == "MEMORY_REVISION_CONFLICT", "stale-revision-rejected")
        code, archived = request("POST", path + "/archive", {"expectedRevision": 2})
        require(code == 200 and archived["revision"] == 3 and archived["status"] == "ARCHIVED", "archive")
        code, page = request("GET", "/api/v1/memory/items")
        require(code == 200 and page["total"] == 0, "archived-hidden-default")
        code, page = request("GET", "/api/v1/memory/items?status=ARCHIVED&query=" + urllib.parse.quote("中文项"))
        require(code == 200 and page["total"] == 1, "archived-search-explicit")
        require(request("POST", "/api/v1/memory/index/rebuild")[0] == 204, "rebuild")
        require(request("GET", path)[1] == archived, "rebuild-source-unchanged")
        code, restored = request("POST", path + "/restore", {"expectedRevision": 3})
        require(code == 200 and restored["revision"] == 4 and restored["status"] == "ACTIVE", "restore")
        require(request("GET", path, authenticated=False)[0] == 401, "missing-credential-denied")
        require(request("GET", path, headers={"Origin": "https://untrusted.example"})[0] == 401, "web-origin-denied")
        require(request("DELETE", path, {"expectedRevision": 4})[0] == 204, "delete")
        require(request("GET", path)[0] == 404, "deleted-read-denied")
        require(request("GET", "/api/v1/memory/items?query=" + urllib.parse.quote("中文项"))[1]["total"] == 0, "deleted-search-absent")
        stop()
        process = start()
        require(request("GET", path)[0] == 404, "deleted-after-second-restart")
        stop()
        require(len(process_ids) == 3 and len(set(process_ids)) == 3, "three-distinct-runtime-processes")
        with closing(sqlite3.connect(data / "memory.db")) as db:
            require(db.execute("PRAGMA user_version").fetchone()[0] == 3, "workspace-schema-v3")
            require(db.execute("PRAGMA journal_mode").fetchone()[0] == "delete", "journal-delete")
            require(db.execute("SELECT count(*) FROM memory_items").fetchone()[0] == 0, "source-empty")
            require(db.execute("SELECT count(*) FROM memory_fts").fetchone()[0] == 0, "index-empty")
        logs = log.read_text("utf-8", errors="replace")
        require(not any(value in logs for value in (marker, title, content, token, "中文项")), "logs-private")
        evidence["result"] = "PASS"
    except Exception:
        # Do not leak paths, request/response bodies or exception diagnostics in evidence.
        evidence["failure"] = "Packaged Memory acceptance did not complete."
    finally:
        stop()

destination = root / ".verification/m3a-memory-smoke-evidence.json"
destination.parent.mkdir(exist_ok=True)
destination.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
print(json.dumps(evidence, indent=2))
raise SystemExit(0 if evidence["result"] == "PASS" else 1)
