"""Synthetic Windows WPF -> Runtime -> SQLite -> real Ollama -> portable recovery.

Build the Runtime jar first. Uses only test-owned processes, credentials and temporary files.
Native file-picker choices are injected; real WPF controls, file IO and HTTP remain in use.
Never print backup documents, paths, UUIDs, questions, answers or provider diagnostics.
"""
from contextlib import closing
from pathlib import Path
import datetime
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

root = Path(__file__).resolve().parent.parent
jar = root / "target/personal-ai-workspace-0.1.0.jar"
project = root / "desktop/acceptance/PersonalAiWorkspace.MemoryBackupAcceptance/PersonalAiWorkspace.MemoryBackupAcceptance.csproj"
flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
checks = []
stage = "configuration"


def require(value, name):
    global stage
    stage = name
    if not value:
        raise RuntimeError(name)
    checks.append(name)


def main():
    require(os.name == "nt" and jar.is_file(), "windows-and-packaged-runtime")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 8765))  # Never stop a user's Runtime.
    built = subprocess.run(["dotnet", "build", str(project)], cwd=root, capture_output=True, creationflags=flags, timeout=90)
    require(built.returncode == 0, "wpf-harness-build")
    settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True, creationflags=flags, timeout=15)
    home = re.search(r"^\s*java\.home = (.+)$", settings.stderr, re.MULTILINE)
    require(home is not None, "java-executable-resolution")
    java = Path(home.group(1).strip()) / "bin/java.exe"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, method="GET", body=None, credential=None, origin=None):
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if credential:
            headers["Authorization"] = "Bearer " + credential
        if origin:
            headers["Origin"] = origin
        if origin or credential and credential.startswith("br1."):
            headers.update({"Sec-Fetch-Site": "none", "Sec-Fetch-Mode": "cors", "Sec-Fetch-Dest": "empty"})
        payload = json.dumps(body).encode("utf-8") if body is not None else None
        try:
            with opener.open(urllib.request.Request("http://127.0.0.1:8765" + path, data=payload, headers=headers, method=method), timeout=8) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read() or b"{}")

    with tempfile.TemporaryDirectory(prefix="workspace-m3c2-smoke-") as directory:
        temporary = Path(directory).resolve()
        require(temporary.parent == Path(tempfile.gettempdir()).resolve() and temporary.name.startswith("workspace-m3c2-smoke-"), "test-owned-temporary-root")
        source, target, maintenance = temporary / "source", temporary / "restored", temporary / "maintenance"
        backup_file = temporary / "memory-backup.json"
        token_file = temporary / "auth/client-token"
        process = None
        logs = []
        safe_outputs = []
        credentials = []

        def stop():
            nonlocal process
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
            process = None

        def start(data, name):
            nonlocal process
            stop()
            log = temporary / (name + ".log")
            logs.append(log)
            with log.open("wb") as output:
                process = subprocess.Popen([str(java), "-jar", str(jar), "--server.port=8765",
                    f"--workspace.security.token-file={token_file}", f"--workspace.data-directory={data}"],
                    cwd=root, stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
            until = time.monotonic() + 35
            while True:
                if process.poll() is not None or time.monotonic() >= until:
                    raise RuntimeError("owned-runtime-startup")
                try:
                    status, _ = request("/actuator/health/readiness")
                    if status == 200 and token_file.is_file():
                        break
                except OSError:
                    pass
                time.sleep(0.1)
            credential = token_file.read_text("ascii").strip()
            if credential not in credentials:
                credentials.append(credential)
            return credential

        def wpf(phase):
            env = os.environ.copy()
            env.update(M3C2_TOKEN_FILE=str(token_file), M3C2_BACKUP_FILE=str(backup_file),
                       M3C2_TARGET_DIRECTORY=str(target), M3C2_PHASE=phase)
            dll = project.parent / "bin/Debug/net10.0-windows/PersonalAiWorkspace.MemoryBackupAcceptance.dll"
            accepted = subprocess.run(["dotnet", str(dll)], cwd=root, env=env, capture_output=True,
                text=True, encoding="utf-8", creationflags=flags, timeout=300)
            safe_outputs.append(accepted.stderr)
            try:
                evidence = json.loads(accepted.stdout)
            except ValueError:
                raise RuntimeError("safe-wpf-evidence") from None
            require(accepted.returncode == 0 and evidence.get("result") == "PASS", "wpf-" + phase + "-" + str(evidence.get("check", "complete")))
            checks.extend(evidence["checks"])

        def source_rows(data):
            with closing(sqlite3.connect(data / "memory.db")) as db:
                return db.execute("SELECT id,type,title,content,status,revision,source,created_at,updated_at FROM memory_items ORDER BY id").fetchall()

        try:
            token = start(source, "source-create")
            wpf("seed")
            stop()
            original_rows = source_rows(source)
            require(len(original_rows) == 3, "sqlite-synthetic-source-count")
            token = start(source, "source-restarted")
            wpf("export")  # Persistence + management + explicit Ask + real export file IO.
            stop()
            original_bytes = (source / "memory.db").read_bytes()
            backup_bytes = backup_file.read_bytes()
            backup = json.loads(backup_bytes)
            require(backup["formatVersion"] == 1 and backup["schemaVersion"] == 1 and backup["itemCount"] == 3, "versioned-logical-backup")
            millis = lambda value: int(datetime.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)
            logical_rows = sorted([(row["id"], row["type"], row["title"], row["content"], row["status"], row["revision"],
                row["source"], millis(row["createdAt"]), millis(row["updatedAt"])) for row in backup["items"]])
            require(logical_rows == original_rows, "export-exact-source-records")
            # A separate maintenance Runtime can restore while the source Runtime is stopped.
            token = start(maintenance, "maintenance")
            maintenance_bytes = (maintenance / "memory.db").read_bytes()
            status, pairing = request("/api/v1/security/pairings", "POST", {"origin": "chrome-extension://" + "n" * 32, "displayName": "Synthetic recovery", "userApproved": True}, token)
            require(status == 200, "synthetic-browser-pairing")
            status, exchanged = request("/api/v1/security/pairings/exchange", "POST",
                {"pairingId": pairing["pairingId"], "pairingSecret": pairing["pairingSecret"]}, origin="chrome-extension://" + "n" * 32)
            require(status == 200, "synthetic-browser-exchange")
            browser = exchanged["credential"]
            credentials.extend([browser, pairing["pairingSecret"]])
            for path, method in [("/api/v1/memory/backup", "GET"), ("/api/v1/memory/backup/restore", "POST")]:
                for name, auth, origin, expected in [("paired-browser", browser, "chrome-extension://" + "n" * 32, 403),
                    ("originless-browser", browser, None, 403 if method == "GET" else 401),
                    ("web-origin", token, "https://example.com", 401), ("missing-auth", None, None, 401)]:
                    status, _ = request(path, method, {} if method == "POST" else None, auth, origin)
                    require(status == expected, "backup-security-" + name + "-" + method.lower())
            corrupt = json.loads(backup_bytes)
            corrupt["items"][0]["content"] += " tamper"
            negative = temporary / "corrupt-target"
            status, error = request("/api/v1/memory/backup/restore", "POST", {"backup": corrupt, "targetDirectory": str(negative)}, token)
            require(status == 400 and error["code"] == "MEMORY_BACKUP_INVALID" and not negative.exists(), "corrupt-backup-target-not-finalized")
            unsupported = dict(backup, formatVersion=2)
            status, error = request("/api/v1/memory/backup/restore", "POST", {"backup": unsupported, "targetDirectory": str(negative)}, token)
            require(status == 400 and error["code"] == "MEMORY_BACKUP_UNSUPPORTED" and not negative.exists(), "unsupported-backup-fail-closed")
            existing = temporary / "nonempty"
            existing.mkdir()
            (existing / "keep.txt").write_text("keep", "ascii")
            status, error = request("/api/v1/memory/backup/restore", "POST", {"backup": backup, "targetDirectory": str(existing)}, token)
            require(status == 409 and error["code"] == "MEMORY_RESTORE_TARGET_NOT_EMPTY" and (existing / "keep.txt").read_text() == "keep"
                and sorted(path.name for path in existing.iterdir()) == ["keep.txt"], "nonempty-target-unchanged")
            require((maintenance / "memory.db").read_bytes() == maintenance_bytes and (source / "memory.db").read_bytes() == original_bytes,
                "negative-restore-current-and-source-bytes-unchanged")
            empty_target = temporary / "existing-empty"
            empty_target.mkdir()
            status, result = request("/api/v1/memory/backup/restore", "POST", {"backup": backup, "targetDirectory": str(empty_target)}, token)
            require(status == 200 and result["itemCount"] == 3 and source_rows(empty_target) == original_rows, "existing-empty-target-exact-restore")
            wpf("restore")
            require((maintenance / "memory.db").read_bytes() == maintenance_bytes and (source / "memory.db").read_bytes() == original_bytes,
                "successful-restore-current-and-source-bytes-unchanged")
            require(source_rows(target) == original_rows, "restored-sqlite-all-source-fields-exact")
            with closing(sqlite3.connect(target / "memory.db")) as db:
                require(db.execute("PRAGMA quick_check").fetchone()[0] == "ok" and db.execute("PRAGMA user_version").fetchone()[0] == 1, "restored-schema-quick-check")
                require(db.execute("SELECT count(*) FROM memory_fts").fetchone()[0] == 3, "restored-fts-rebuilt")
            request("/api/v1/security/clients/" + exchanged["client"]["clientId"], "DELETE", credential=token)
            stop()
            token = start(target, "restored-new-runtime")
            wpf("restored")
            require(source_rows(target) == [], "restored-delete-source-verification")
            require((source / "memory.db").read_bytes() == original_bytes, "source-never-mutated-by-recovery")
        finally:
            stop()
        sensitive = credentials + ["Synthetic Recovery Preference", "Synthetic Recovery Project", "Synthetic Recovery Archived", "M3RECOVERY-4821",
            "The synthetic recovery project codename is", "Use concise synthetic replies.", "Archived synthetic recovery record.",
            "What is the synthetic recovery project codename?", str(temporary)]
        diagnostics = "\n".join(path.read_text("utf-8") for path in logs) + "\n".join(safe_outputs)
        require(not any(value in diagnostics for value in sensitive), "runtime-harness-log-privacy")
        with closing(sqlite3.connect(target / "memory.db")) as db:
            names = [row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")]
            require(not any(any(part in name for part in ["task", "selection", "ask"]) for name in names), "no-provider-task-selection-persistence")
            require(all(db.execute("SELECT count(*) FROM " + table).fetchone()[0] == 0
                for table in ["conversations", "conversation_turns", "conversation_messages"]), "memory-restore-does-not-restore-conversations")
        require(not any(path.name.startswith(".memory-restore-") for path in temporary.iterdir()), "staging-cleaned")
        metadata = {"formatVersion": 1, "schemaVersion": 1, "itemCount": 3, "contentDigest": backup["contentDigest"], "fileSize": len(backup_bytes)}
    require(not temporary.exists(), "temporary-source-backup-target-cleaned")
    evidence = dict(result="PASS", syntheticOnly=True, realWindowsWpf=True, realHttp=True, realOllama=True,
        testOwnedCredential=True, credentialManagerUntouched=True, realRecovery=True, negativeRecovery=True,
        integratedM3=True, metadata=metadata, checks=checks)
    destination = root / ".verification/m3c2-memory-backup-evidence.json"
    destination.parent.mkdir(exist_ok=True)
    destination.write_text(json.dumps(evidence, indent=2), "utf-8")
    print(json.dumps(evidence))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print(json.dumps({"result": "FAIL", "check": stage}))
        raise SystemExit(1) from None
