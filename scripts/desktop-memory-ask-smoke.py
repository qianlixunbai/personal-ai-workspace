"""Real Windows WPF -> packaged Runtime -> isolated SQLite -> real local Ollama.

Build with mvnw.cmd package -DskipTests, then python scripts/desktop-memory-ask-smoke.py.
Only synthetic Memory and test-owned credentials; safe check names, never response bodies.
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
import urllib.request
import urllib.error

root = Path(__file__).resolve().parent.parent
jar = root / "target/personal-ai-workspace-0.1.0.jar"
project = root / "desktop/acceptance/PersonalAiWorkspace.MemoryAskAcceptance/PersonalAiWorkspace.MemoryAskAcceptance.csproj"
flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def main():
    if os.name != "nt" or not jar.is_file():
        raise RuntimeError("windows-and-packaged-runtime-required")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 8765))  # Never stop someone else's listener.
    built = subprocess.run(["dotnet", "build", str(project)], cwd=root, capture_output=True,
                           creationflags=flags, timeout=90)
    if built.returncode:
        raise RuntimeError("acceptance-build")
    settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True,
                              text=True, check=True, creationflags=flags)
    home = re.search(r"^\s*java\.home = (.+)$", settings.stderr, re.MULTILINE)
    if home is None:
        raise RuntimeError("java-executable-resolution")
    java = Path(home.group(1).strip()) / "bin/java.exe"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, method="GET", body=None, credential=None, origin=None, preflight=False):
        headers = {"Content-Type": "application/json"}
        if credential:
            headers["Authorization"] = "Bearer " + credential
        if origin:
            headers["Origin"] = origin
        if origin or credential and credential.startswith("br1."):
            headers.update({"Sec-Fetch-Site": "none", "Sec-Fetch-Mode": "cors", "Sec-Fetch-Dest": "empty"})
        if preflight:
            headers["Access-Control-Request-Method"] = "POST"
        payload = json.dumps(body).encode() if body is not None else None
        try:
            with opener.open(urllib.request.Request("http://127.0.0.1:8765" + path, data=payload, headers=headers, method=method), timeout=5) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read() or b"{}")

    with tempfile.TemporaryDirectory(prefix="workspace-m3c1-smoke-") as directory:
        temporary = Path(directory).resolve()
        if temporary.parent != Path(tempfile.gettempdir()).resolve() or not temporary.name.startswith("workspace-m3c1-smoke-"):
            raise RuntimeError("isolated-temporary-location")
        token_file, data, log = temporary / "auth/client-token", temporary / "data", temporary / "runtime.log"
        with log.open("wb") as output:
            process = subprocess.Popen([str(java), "-jar", str(jar), "--server.port=8765",
                f"--workspace.security.token-file={token_file}", f"--workspace.data-directory={data}"], cwd=root,
                stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
        try:
            until = time.monotonic() + 30
            while True:
                if process.poll() is not None or time.monotonic() > until:
                    raise RuntimeError("isolated-runtime-startup")
                try:
                    status, _ = request("/actuator/health/readiness")
                    if status == 200 and token_file.is_file():
                        break
                except OSError:
                    pass
                time.sleep(0.1)
            token = token_file.read_text("ascii").strip()
            env = os.environ.copy()
            env["M3C1_TEST_TOKEN_FILE"] = str(token_file)
            dll = project.parent / "bin/Debug/net10.0-windows/PersonalAiWorkspace.MemoryAskAcceptance.dll"
            accepted = subprocess.run(["dotnet", str(dll)], cwd=root, env=env, capture_output=True,
                                     text=True, encoding="utf-8", creationflags=flags, timeout=360)
            try:
                evidence = json.loads(accepted.stdout)
            except ValueError:
                raise RuntimeError("safe-wpf-evidence") from None
            if accepted.returncode or evidence.get("result") != "PASS":
                raise RuntimeError("wpf-acceptance-" + str(evidence.get("check", "unknown")))
            # Pair a synthetic Browser using only this temporary registry/token; deny the new route.
            origin = "chrome-extension://" + "n" * 32
            status, pairing = request("/api/v1/security/pairings", "POST",
                {"origin": origin, "displayName": "Synthetic acceptance", "userApproved": True}, token)
            if status != 200:
                raise RuntimeError("synthetic-browser-pairing")
            status, exchanged = request("/api/v1/security/pairings/exchange", "POST",
                {"pairingId": pairing["pairingId"], "pairingSecret": pairing["pairingSecret"]}, origin=origin)
            if status != 200:
                raise RuntimeError("synthetic-browser-exchange")
            browser = exchanged["credential"]
            path, body = "/api/v1/memory/ask/tasks", {"question": "synthetic", "memories": []}
            for name, credential, route_origin, method, expected in [
                ("browser-origin", browser, origin, "POST", 403), ("browser-originless", browser, None, "POST", 401),
                ("preflight", None, origin, "OPTIONS", 401), ("web-origin", token, "https://example.com", "POST", 401),
                ("missing-credential", None, None, "POST", 401)]:
                status, _ = request(path, method, body if method == "POST" else None, credential, route_origin, method == "OPTIONS")
                if status != expected:
                    raise RuntimeError("memory-ask-security-" + name)
            status, _ = request("/api/v1/security/clients/" + exchanged["client"]["clientId"], "DELETE", credential=token)
            if status != 204:
                raise RuntimeError("synthetic-client-cleanup")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
        sensitive = [token, browser, pairing["pairingSecret"], "Synthetic Project Context", "ORCHID-7319",
                     "What is the synthetic project codename?", "Reply with exactly READY.", "The synthetic project codename is"]
        logs = log.read_text("utf-8") + accepted.stderr
        if any(value in logs for value in sensitive):
            raise RuntimeError("runtime-and-harness-log-privacy")
        with closing(sqlite3.connect(data / "memory.db")) as db:
            if db.execute("SELECT count(*) FROM memory_items").fetchone()[0] != 0:
                raise RuntimeError("sqlite-delete-verification")
            tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if any("task" in name or "conversation" in name or "selection" in name for name in tables):
                raise RuntimeError("unexpected-persistence")
        database_bytes = (data / "memory.db").read_bytes()
        if any(value in database_bytes for value in [b"What is the synthetic project codename?", b"Reply with exactly READY.", b'"question":', b'"memory":', b"READY"]):
            raise RuntimeError("sqlite-question-answer-context-byte-audit")
        evidence.update(syntheticOnly=True, isolatedDataDirectory=True, testOwnedCredential=True,
                        credentialManagerUntouched=True, sqliteVerified=True, browserDenied=True,
                        logPrivacy=True, runtimeStopped=True)
    evidence["temporaryDataRemoved"] = True
    evidence_path = root / ".verification/m3c1-memory-ask-evidence.json"
    evidence_path.parent.mkdir(exist_ok=True)
    evidence_path.write_text(json.dumps(evidence, indent=2), "utf-8")
    print(json.dumps(evidence))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        check = str(error) if type(error) is RuntimeError else type(error).__name__
        print(json.dumps({"result": "FAIL", "check": check}))
        raise SystemExit(1) from None
