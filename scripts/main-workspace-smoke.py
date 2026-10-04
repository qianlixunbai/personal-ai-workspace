"""Release WPF + WebView2 + bundled React + isolated real Runtime acceptance.

Only synthetic domain markers and a test-owned WinCred target are used. No bearer,
personal text, JS response bodies or user paths enter retained evidence.
"""
from pathlib import Path
import json
import os
import re
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
stage = "configuration"
flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def require(value, check):
    global stage
    stage = check
    if not value:
        raise RuntimeError(check)


def main():
    require(os.name == "nt", "windows-required")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 8765))
    project = root / "desktop/acceptance/PersonalAiWorkspace.MainWorkspaceAcceptance/PersonalAiWorkspace.MainWorkspaceAcceptance.csproj"
    published = root / ".verification/m5a-acceptance-publish"
    built = subprocess.run(["dotnet", "publish", str(project), "-c", "Release", "-p:FrontendSkipBuild=true", "-o", str(published)],
                           cwd=root, capture_output=True, creationflags=flags, timeout=120)
    require(built.returncode == 0, "release-equivalent-publish")
    java_settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True, creationflags=flags, timeout=15)
    match = re.search(r"^\s*java\.home = (.+)$", java_settings.stderr, re.MULTILINE)
    require(match is not None, "direct-java-resolution")
    java = Path(match.group(1).strip()) / "bin/java.exe"
    jar = root / "target/personal-ai-workspace-0.1.0.jar"
    require(jar.is_file(), "packaged-runtime-present")
    markers = ["M5A-" + kind + "-" + uuid.uuid4().hex for kind in ("USER", "MEMORY", "TITLE")]
    evidence_dir = root / ".verification"
    evidence_dir.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="workspace-m5a-") as temporary:
        temporary = Path(temporary)
        token_file = temporary / "auth/client-token"
        log_file = temporary / "runtime.log"
        with log_file.open("wb") as log:
            runtime = subprocess.Popen([str(java), "-jar", str(jar), "--server.port=8765",
                                        "--workspace.security.token-file=" + str(token_file),
                                        "--workspace.data-directory=" + str(temporary / "data")], cwd=root,
                                       stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 35
                ready = False
                while time.monotonic() < deadline and runtime.poll() is None:
                    try:
                        with opener.open("http://127.0.0.1:8765/actuator/health", timeout=1) as response:
                            ready = json.load(response).get("status") == "UP"
                        if ready:
                            break
                    except Exception:
                        time.sleep(.2)
                require(ready, "isolated-runtime-ready")
                token = token_file.read_text().strip()
                env = dict(os.environ, M5A_TEST_TOKEN_FILE=str(token_file), M5A_TEST_MARKERS=json.dumps(markers),
                           M5A_TEST_EVIDENCE_DIR=str(evidence_dir))
                accepted = subprocess.run([str(published / "PersonalAiWorkspace.MainWorkspaceAcceptance.exe")],
                                          cwd=published, env=env, capture_output=True, creationflags=flags, timeout=180)
                lines = accepted.stdout.decode("utf-8-sig").splitlines()
                report = json.loads(next(line for line in reversed(lines) if line.startswith('{')))
                if accepted.returncode or report.get("result") != "PASS":
                    print(json.dumps(report, ensure_ascii=False))
                    raise RuntimeError("real-webview-acceptance:" + report.get("check", "unknown"))
            finally:
                runtime.terminate()
                runtime.wait(timeout=15)
        needles = [text.encode(encoding) for text in markers + [token] for encoding in ("utf-8", "utf-16-le")]
        require(not any(needle in log_file.read_bytes() for needle in needles), "runtime-log-privacy")
        udf = Path(os.environ["LOCALAPPDATA"]) / "PersonalAiWorkspace/MainWorkspaceWebView2"
        # Controller shutdown precedes this bounded retry; no process is killed by its port/name.
        privacy_files = 0
        unreadable = []
        for attempt in range(20):
            unreadable = []
            privacy_files = 0
            for path in udf.rglob('*'):
                if not path.is_file():
                    continue
                try:
                    data = path.read_bytes()
                except OSError:
                    unreadable.append(path)
                    continue
                privacy_files += 1
                require(not any(needle in data for needle in needles), "udf-synthetic-content-or-bearer-leak")
            if not unreadable:
                break
            time.sleep(.25)
        require(not unreadable, "udf-all-files-readable-for-privacy-scan")
        report["privacy"] = {"result": "PASS", "udfFilesScanned": privacy_files, "syntheticMarkerKinds": 3,
                             "bearerScanned": True, "runtimeLogScanned": True, "forensicErasureClaimed": False}
        report["closingGate"] = "LOCAL_ACCEPTANCE_PASS" if report["metrics"].get("windowsImeVerified") else "PARTIAL_AWAITING_REAL_WINDOWS_IME_ACCEPTANCE"
        audit = subprocess.run(["python", "-X", "utf8", str(root / "scripts/privacy-audit.py")], cwd=root,
                               input=json.dumps({"secrets": [token], "logBodies": markers}).encode(), capture_output=True, creationflags=flags, timeout=90)
        require(audit.returncode == 0, "repository-package-evidence-privacy-audit")
        report["repositoryPrivacyAudit"] = json.loads(audit.stdout)
        (evidence_dir / "m5a-main-workspace-evidence.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({"result": "FAIL", "check": str(error) if type(error) is RuntimeError else stage}))
        raise SystemExit(1) from None
