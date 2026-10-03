"""Real Windows WPF + packaged Runtime + SQLite acceptance, using isolated synthetic data.

Build Runtime with mvnw.cmd package -DskipTests, then run python scripts/desktop-memory-smoke.py.
Never reads normal Runtime/Windows credentials. Prints check names only, never bodies or tokens.
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

root = Path(__file__).resolve().parent.parent
jar = root / "target/personal-ai-workspace-0.1.0.jar"
project = root / "desktop/acceptance/PersonalAiWorkspace.MemoryAcceptance/PersonalAiWorkspace.MemoryAcceptance.csproj"
flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def main():
    if os.name != "nt" or not jar.is_file():
        raise RuntimeError("windows-and-packaged-runtime-required")
    # Fixed production loopback endpoint; never stop an existing listener.
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 8765))
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
    with tempfile.TemporaryDirectory(prefix="workspace-m3b-smoke-") as directory:
        temporary = Path(directory).resolve()
        if temporary.parent != Path(tempfile.gettempdir()).resolve() or not temporary.name.startswith("workspace-m3b-smoke-"):
            raise RuntimeError("isolated-temporary-location")
        token_file, data, log = temporary / "auth/client-token", temporary / "data", temporary / "runtime.log"
        marker = "m3b-synthetic-" + os.urandom(16).hex()
        with log.open("wb") as output:
            process = subprocess.Popen([str(java), "-jar", str(jar), "--server.port=8765",
                f"--workspace.security.token-file={token_file}", f"--workspace.data-directory={data}",
                "--workspace.ollama.base-url=http://127.0.0.1:1"], cwd=root, stdout=output,
                stderr=subprocess.STDOUT, creationflags=flags)
        try:
            until = time.monotonic() + 30
            while True:
                if process.poll() is not None or time.monotonic() > until:
                    raise RuntimeError("isolated-runtime-startup")
                try:
                    with opener.open("http://127.0.0.1:8765/actuator/health/readiness", timeout=1) as response:
                        if response.status == 200 and token_file.is_file():
                            break
                except OSError:
                    pass
                time.sleep(0.1)
            env = os.environ.copy()
            env["M3B_TEST_TOKEN_FILE"], env["M3B_SYNTHETIC_MARKER"] = str(token_file), marker
            dll = project.parent / "bin/Debug/net10.0-windows/PersonalAiWorkspace.MemoryAcceptance.dll"
            accepted = subprocess.run(["dotnet", str(dll)], cwd=root, env=env, capture_output=True,
                                      text=True, encoding="utf-8", creationflags=flags, timeout=90)
            try:
                evidence = json.loads(accepted.stdout)
            except ValueError:
                raise RuntimeError("safe-wpf-evidence") from None
            if accepted.returncode or evidence.get("result") != "PASS":
                raise RuntimeError("wpf-acceptance-" + str(evidence.get("check", "unknown")))
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
        token = token_file.read_text("ascii").strip()
        if marker in log.read_text("utf-8") or token in log.read_text("utf-8") or marker in accepted.stderr or token in accepted.stderr:
            raise RuntimeError("runtime-and-harness-log-privacy")
        with closing(sqlite3.connect(data / "memory.db")) as db:
            if db.execute("SELECT count(*) FROM memory_items").fetchone()[0] != 0:
                raise RuntimeError("sqlite-delete-verification")
        evidence.update(syntheticOnly=True, isolatedDataDirectory=True, testOwnedCredential=True,
                        credentialManagerUntouched=True, providerRequired=False, sqliteVerified=True,
                        logPrivacy=True, runtimeStopped=True)
    evidence["temporaryDataRemoved"] = True
    print(json.dumps(evidence))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Do not print unexpected exception details, subprocess output, paths, HTTP bodies or credentials.
        check = str(error) if type(error) is RuntimeError else type(error).__name__
        print(json.dumps({"result": "FAIL", "check": check}))
        raise SystemExit(1) from None
