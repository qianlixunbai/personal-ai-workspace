"""Compact M5E acceptance of a fixed portable package, using synthetic/private fixtures.

Actual shipped EXE launch/lifecycle precedes a separate driver which references
only shipped assemblies. No source Debug output or rebuilt product is accepted.
"""
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
import ctypes
import hashlib
import http.client
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
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
flags = subprocess.CREATE_NO_WINDOW
ps = Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/powershell.exe'
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
stage = 'configuration'
checks = []


def require(value, name):
    global stage
    stage = name
    if not value:
        raise RuntimeError(name)
    checks.append(name)


def json_get(url, token=''):
    request = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + token} if token else {})
    with opener.open(request, timeout=4) as response:
        return json.load(response)


def run(arguments, env=None, timeout=120):
    return subprocess.run(list(map(str, arguments)), env=env, capture_output=True, encoding='utf-8', errors='replace',
                          creationflags=flags, timeout=timeout)


def ps_run(code, env=None):
    return run([ps, '-NoProfile', '-Command', code], env)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def logical(directory):
    fields = dict(memory_items='id,type,title,content,status,revision,source,created_at,updated_at',
                  conversations='id,title,status,created_at,updated_at',
                  conversation_turns='id,conversation_id,sequence,status,created_at,updated_at,failure_code',
                  conversation_messages='id,turn_id,role,content,created_at',
                  conversation_memory_selections='turn_id,position,memory_id,revision')
    with closing(sqlite3.connect(directory / 'memory.db')) as db:
        return {table: db.execute('SELECT ' + columns + ' FROM ' + table + ' ORDER BY 1,2').fetchall()
                for table, columns in fields.items()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--package', required=True)
    args = parser.parse_args()
    package = Path(args.package).resolve()
    require(os.name == 'nt', 'windows-required')
    for port in (8765, 18768, 11435):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    require(ps_run("@(Get-Process PersonalAiWorkspace.Desktop -ErrorAction SilentlyContinue).Count").stdout.strip() == '0', 'no-preexisting-user-desktop')
    manifest = json.loads((package / 'package-manifest.json').read_text('utf-8-sig'))
    require(manifest['selfContainedDesktop'] and manifest['targetRid'] == 'win-x64', 'self-contained-manifest')
    for entry in manifest['files']:
        require(sha(package / entry['path']) == entry['sha256'], 'package-hash')
    for line in (package / 'SHA256SUMS.txt').read_text().splitlines():
        digest, name = line.split('  ', 1)
        require(sha(package / name) == digest, 'sha256sum-match')
    require(not any(key in json.dumps(manifest).lower() for key in ('username', 'bearer', 'machineid', ':\\', 'conversation')), 'manifest-no-personal-metadata')
    frontend = package / 'desktop/MainWorkspace'
    assets = json.loads((frontend / 'workspace-assets.json').read_text())
    require(all(sha(frontend / name) == digest.lower() for name, digest in assets['files'].items()), 'bundled-frontend-integrity')
    require("connect-src 'none'" in (frontend / 'index.html').read_text(), 'production-csp')
    require(b'http://127.0.0.1:5173'.decode().encode('utf-16-le') not in (package / 'desktop/PersonalAiWorkspace.Desktop.dll').read_bytes(), 'no-dev-origin-release-assembly')
    info = run(['java', '-XshowSettings:properties', '-version'])
    java = Path(re.search(r'^\s*java.home = (.+)$', info.stderr, re.MULTILINE).group(1).strip()) / 'bin/java.exe'
    require(java.is_file(), 'real-java21-resolution')
    evidence = root / '.verification'; evidence.mkdir(exist_ok=True)
    project = root / 'desktop/acceptance/PersonalAiWorkspace.ProductAcceptance/PersonalAiWorkspace.ProductAcceptance.csproj'
    built = run(['dotnet', 'build', project, '-c', 'Release', '-p:PackageDirectory=' + str(package), '-o', evidence / 'm5e-driver'])
    if built.returncode:
        (evidence / 'm5e-driver-build.log').write_text(built.stdout + built.stderr, encoding='utf-8')
    require(built.returncode == 0, 'driver-built-against-shipped-assemblies-no-product-build')
    driver = evidence / 'm5e-driver'
    for name in ('PersonalAiWorkspace.Desktop.dll', 'PersonalAiWorkspace.Core.dll'):
        require(sha(driver / name) == sha(package / 'desktop' / name), 'driver-shipped-assembly-exact')
    unique = uuid.uuid4().hex
    suffix = str(int(unique[:7], 16))
    context = 'ORBIT-' + str(int(unique[:6], 16) % 900 + 100)
    memory_code = 'QUARTZ-' + str(int(unique[6:12], 16) % 900 + 100)
    cases = dict(title='M5E-TITLE-' + unique, body='Synthetic private context ' + unique,
                 edited='The synthetic project code is ' + memory_code + '. ' + unique,
                 ask='Reply with OK. Synthetic verification ' + unique,
                 context=context, turn1='For this test, the project codename is ' + context + '. Remember the project codename for my next question. Reply with just OK.',
                 turn2='What is the project codename stated in my previous message? Reply with only that codename.',
                 memoryCode=memory_code, memoryAsk='What is the synthetic project code in the selected reference? Reply with only that code.',
                 nextAsk='Reply with the word READY. Synthetic next operation ' + unique,
                 ime='本地工作区测试星河' + suffix, imeSuffix=suffix)
    markers = [value for key, value in cases.items() if unique in value or key in ('context', 'memoryCode', 'ime', 'turn1')]
    with tempfile.TemporaryDirectory(prefix='workspace-m5e-') as temporary:
        temporary = Path(temporary)
        copied = temporary / 'Portable 产品 with spaces'; shutil.copytree(package, copied)
        require(all(sha(copied / entry['path']) == entry['sha256'] for entry in manifest['files']), 'space-and-non-ascii-relocation-exact')
        state = temporary / 'private-state'; data = temporary / 'workspace-data'
        auth = state / 'Auth/client-token'; backup = temporary / 'synthetic.workspace-backup.json'; restored = temporary / 'restored-data'
        # Runtime invocation in this acceptance receives only a loopback counting relay.
        # Captures stay in RAM; real Ollama serves every provider request.
        base_env = dict(os.environ, WORKSPACE_OLLAMA_BASEURL='http://127.0.0.1:11435')
        restricted = dict(base_env, PATH=str(java.parent) + ';' + str(ps.parent) + ';' + str(Path(os.environ['SystemRoot']) / 'System32'))
        runtime_pid = None; runtime_process = None; ollama_process = None; desktop_pid = None; tokens = []; logs = []
        captured = []; capture_lock = threading.Lock(); parity = False; expected = None
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [ctypes.c_uint, ctypes.c_bool, ctypes.c_uint]; kernel.OpenProcess.restype = ctypes.c_void_p
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        kernel.TerminateProcess.argtypes = [ctypes.c_void_p, ctypes.c_uint]

        def terminate_pid(pid):
            if pid is None:
                return
            handle = kernel.OpenProcess(1 | 0x100000, False, pid)
            if handle:
                try:
                    kernel.TerminateProcess(handle, 0)
                finally:
                    kernel.CloseHandle(handle)

        def stop_runtime():
            nonlocal runtime_pid, runtime_process
            if runtime_process is not None:
                runtime_process.terminate()
                runtime_process.wait(timeout=15); runtime_process = None
            else:
                terminate_pid(runtime_pid)
            runtime_pid = None
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                try:
                    json_get('http://127.0.0.1:8765/actuator/health'); time.sleep(.1)
                except OSError:
                    return
            raise RuntimeError('test-runtime-stop')

        def start_runtime(directory, bad_model=False):
            nonlocal runtime_process, runtime_pid
            log = temporary / ('runtime-' + str(len(logs)) + '.log'); logs.append(log)
            arguments = [java, '-jar', copied / manifest['runtimeArtifact'], '--workspace.security.token-file=' + str(auth),
                         '--workspace.data-directory=' + str(directory), '--workspace.ollama.base-url=http://127.0.0.1:11435']
            if bad_model:
                arguments.append('--workspace.translate.model=m5e-nonexistent-' + unique)
            with log.open('wb') as output:
                runtime_process = subprocess.Popen(list(map(str, arguments)), cwd=state, env=base_env, stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
            runtime_pid = runtime_process.pid
            until = time.monotonic() + 45
            while time.monotonic() < until and runtime_process.poll() is None:
                try:
                    if json_get('http://127.0.0.1:8765/actuator/health/readiness')['status'] == 'UP':
                        tokens.append(auth.read_text().strip()); return
                except OSError:
                    time.sleep(.1)
            raise RuntimeError('packaged-jar-restart')

        def launch(check_only=False, env=None):
            command = [ps, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', copied / 'release/start-release.ps1',
                       '-StateDirectory', state, '-DataDirectory', data]
            if check_only:
                command.append('-CheckOnly')
            # Windows child processes can inherit capture pipes even with their
            # own redirected streams. Use fixture-owned files, so EOF never
            # depends on the long-lived Java/Desktop children exiting.
            output_path = temporary / ('launcher-' + str(len(logs)) + '.log'); logs.append(output_path)
            error_path = temporary / ('launcher-' + str(len(logs)) + '.log'); logs.append(error_path)
            with output_path.open('wb') as output, error_path.open('wb') as error:
                completed = subprocess.run(list(map(str, command)), env=env or restricted, stdout=output, stderr=error,
                                           creationflags=flags, timeout=100)
            return subprocess.CompletedProcess(command, completed.returncode, output_path.read_text('utf-8', errors='replace'), error_path.read_text('utf-8', errors='replace'))

        class Relay(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_GET(self):
                self.forward()
            def do_POST(self):
                self.forward()
            def forward(self):
                connection = http.client.HTTPConnection('127.0.0.1', 11434, timeout=180)
                body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
                try:
                    if self.path == '/api/chat':
                        value = json.loads(body)
                        with capture_lock:
                            captured.append(value.get('messages', []))
                    connection.request(self.command, self.path, body, {k: v for k, v in self.headers.items() if k.lower() not in ('host', 'connection')})
                    response = connection.getresponse(); data_bytes = response.read(2 * 1024 * 1024)
                    self.send_response(response.status); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data_bytes))); self.end_headers(); self.wfile.write(data_bytes)
                except Exception:
                    try:
                        self.send_response(503); self.end_headers()
                    except OSError:
                        pass
                finally:
                    connection.close()

        class Controls(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_POST(self):
                nonlocal expected, parity
                try:
                    if self.path == '/maintenance':
                        expected = logical(data); stop_runtime(); start_runtime(temporary / 'maintenance-data')
                    elif self.path == '/restored':
                        stop_runtime(); start_runtime(restored); parity = logical(restored) == expected
                    else:
                        raise RuntimeError('unknown-control')
                    self.send_response(200); self.end_headers(); self.wfile.write(b'{}')
                except Exception:
                    self.send_response(500); self.end_headers()

        relay = ThreadingHTTPServer(('127.0.0.1', 11435), Relay)
        control = ThreadingHTTPServer(('127.0.0.1', 18768), Controls)
        for server in (relay, control):
            threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            nojava = dict(restricted, PATH=str(ps.parent) + ';' + str(Path(os.environ['SystemRoot']) / 'System32'))
            result = launch(True, nojava); require(result.returncode == 1 and 'Java 21 runtime is required' in result.stdout, 'java-missing-controlled-fixture')
            fake = temporary / 'fake-tools'; fake.mkdir()
            (fake / 'java.cmd').write_text('@echo off\necho java.specification.version = 17 1>&2\nexit /b 0\n')
            result = launch(True, dict(nojava, PATH=str(fake) + ';' + nojava['PATH']))
            require(result.returncode == 1 and 'Java major must be 21' in result.stdout, 'wrong-java-major-controlled-fixture')
            # LocalAppData fixture hides installed Ollama without modifying it.
            unavailable_env = dict(restricted, LOCALAPPDATA=str(temporary / 'empty-local-app-data'), M5E_FIXTURE_FUNCTIONS=str(copied / 'release/release-functions.ps1'))
            result = ps_run("$ErrorActionPreference='Stop'; . $env:M5E_FIXTURE_FUNCTIONS; try { Resolve-ReleaseOllama $false | Out-Null; exit 2 } catch { if ($_.Exception.Message -eq 'Ollama is unavailable. Install Ollama and the configured model, then retry.') { Write-Host 'Ollama is unavailable'; exit 1 }; exit 3 }", unavailable_env)
            require(result.returncode == 1 and 'Ollama is unavailable' in result.stdout, 'ollama-unavailable-controlled-fixture-no-user-service-stop')
            class Occupied(BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass
                def do_GET(self):
                    self.send_response(200); self.end_headers(); self.wfile.write(b'{"status":"NOT_RUNTIME"}')
            occupied = ThreadingHTTPServer(('127.0.0.1', 8765), Occupied)
            threading.Thread(target=occupied.serve_forever, daemon=True).start()
            try:
                result = launch(True); require(result.returncode == 1 and 'Port 8765 is occupied' in result.stdout, 'unknown-port-service-fails-closed')
                require(json_get('http://127.0.0.1:8765/')['status'] == 'NOT_RUNTIME', 'unknown-service-not-killed')
            finally:
                occupied.shutdown(); occupied.server_close()
            try:
                tags = json_get('http://127.0.0.1:11434/api/tags')
            except OSError:
                ollama = shutil.which('ollama') or str(Path(os.environ['LOCALAPPDATA']) / 'Programs/Ollama/ollama.exe')
                log = temporary / 'ollama.log'; logs.append(log)
                with log.open('wb') as output:
                    ollama_process = subprocess.Popen([ollama, 'serve'], stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
                until = time.monotonic() + 30
                while True:
                    require(time.monotonic() < until and ollama_process.poll() is None, 'test-owned-ollama-start')
                    try:
                        tags = json_get('http://127.0.0.1:11434/api/tags'); break
                    except OSError:
                        time.sleep(.2)
            require(any(model.get('name') == 'qwen3.5:4b' for model in tags['models']), 'real-installed-configured-model')
            before = {entry['path']: sha(copied / entry['path']) for entry in manifest['files']}
            result = launch()
            (evidence / 'm5e-launcher-evidence.json').write_text(json.dumps(dict(exitCode=result.returncode, safeOutput=result.stdout), indent=2), encoding='utf-8')
            require(result.returncode == 0 and 'Ready: Personal AI Workspace requested' in result.stdout, 'release-launcher-without-build-tools')
            runtime_pid = int(ps_run("(Get-NetTCPConnection -State Listen -LocalPort 8765).OwningProcess").stdout.strip())
            desktop_pid = int(ps_run("(Get-Process PersonalAiWorkspace.Desktop).Id").stdout.strip())
            require(auth.is_file() and (data / 'memory.db').is_file(), 'private-state-outside-payload')
            tokens.append(auth.read_text().strip())
            require(tokens[-1] not in result.stdout + result.stderr, 'launcher-no-credential-output')
            # Actual EXE entry/lifecycle under restricted PATH. Driver tools are build-time only.
            env = dict(base_env, M5E_DESKTOP_PID=str(desktop_pid), M5E_DESKTOP_EXE=str(copied / 'desktop/PersonalAiWorkspace.Desktop.exe'),
                       M5E_STATE=str(state), M5E_PROGRESS=str(temporary / 'progress.json'))
            actual = run(['dotnet', driver / 'PersonalAiWorkspace.ProductAcceptance.dll', '--entry'], env, 90)
            report_entry = json.loads(actual.stdout)
            (evidence / 'm5e-entry-evidence.json').write_text(json.dumps(report_entry, indent=2), encoding='utf-8')
            require(actual.returncode == 0 and report_entry['result'] == 'PASS', 'actual-package-entry-' + report_entry['check'])
            desktop_pid = None
            # Add only the acceptance executable metadata to this disposable copy,
            # retaining every shipped DLL and every bundled production asset exactly.
            for name in ('PersonalAiWorkspace.ProductAcceptance.dll', 'PersonalAiWorkspace.ProductAcceptance.deps.json', 'PersonalAiWorkspace.ProductAcceptance.runtimeconfig.json'):
                shutil.copy2(driver / name, copied / 'desktop' / name)
            env.update(M5E_CASES=json.dumps(cases), M5E_TOKEN_FILE=str(auth), M5E_BACKUP_FILE=str(backup),
                       M5E_RESTORE_TARGET=str(restored), M5E_CONTROL='http://127.0.0.1:18768')
            accepted = run(['dotnet', copied / 'desktop/PersonalAiWorkspace.ProductAcceptance.dll'], env, 900)
            report = json.loads(accepted.stdout)
            (evidence / 'm5e-product-evidence.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
            require(accepted.returncode == 0 and report['result'] == 'PASS', 'packaged-product-' + report['check'])
            require(report['realWindowsPinyin'], 'real-pinyin-not-synthetic-composition')
            require(parity, 'workspace-recovery-all-source-fields-exact')
            with capture_lock:
                provider_checks = [dict(messages=len(messages), currentTurn2=bool(messages) and messages[-1].get('content') == cases['turn2'],
                    earlierContext=any(context in msg.get('content', '') for msg in messages[:-1]),
                    currentMemoryAsk=bool(messages) and messages[-1].get('content') == cases['memoryAsk'],
                    hasExplicitMemory=any(memory_code in msg.get('content', '') for msg in messages)) for messages in captured]
                (evidence / 'm5e-provider-checks-evidence.json').write_text(json.dumps(provider_checks, indent=2), encoding='utf-8')
                require(any(messages[-1].get('content') == cases['ime'] for messages in captured), 'exact-pinyin-provider-user')
                require(any(messages[-1].get('content') == cases['turn2'] and any(context in msg.get('content', '') for msg in messages[:-1]) for messages in captured), 'real-two-turn-provider-context')
                # Existing Memory Ask serializes question + untrusted references
                # into one USER envelope; ordinary Ask keeps the exact raw USER.
                memory_candidates = []
                for messages in captured:
                    try:
                        envelope = json.loads(messages[-1].get('content', ''))
                    except (ValueError, TypeError):
                        continue
                    if isinstance(envelope, dict) and envelope.get('question') == cases['memoryAsk'] and len(envelope.get('memory', [])) == 1:
                        reference = envelope['memory'][0]
                        require(reference.get('title') == cases['title'] and reference.get('content') == cases['edited'], 'provider-exact-explicit-memory-snapshot')
                        memory_candidates.append(messages)
                require(len(memory_candidates) == 1, 'explicit-memory-provider-request-bound-to-question')
                memory_messages = memory_candidates[0]
                next_messages = next(messages for messages in captured if messages[-1].get('content') == cases['nextAsk'])
                require(any(memory_code in msg.get('content', '') for msg in memory_messages), 'explicit-memory-provider-context')
                require(all(memory_code not in msg.get('content', '') for msg in next_messages), 'next-provider-request-no-memory')
            for name in ('PersonalAiWorkspace.ProductAcceptance.dll', 'PersonalAiWorkspace.ProductAcceptance.deps.json', 'PersonalAiWorkspace.ProductAcceptance.runtimeconfig.json'):
                (copied / 'desktop' / name).unlink()
            stop_runtime(); start_runtime(temporary / 'missing-model-data', True)
            # Model absence is a controlled warning; Main Workspace still opens.
            # Reuse the already completed driver event instead of altering the user's WinCred.
            result = launch(); require(result.returncode == 0 and 'configured model is unavailable' in result.stdout, 'missing-model-controlled-message-main-workspace-opens')
            desktop_pid = int(ps_run("(Get-Process PersonalAiWorkspace.Desktop).Id").stdout.strip())
            env['M5E_DESKTOP_PID'] = str(desktop_pid)
            actual = run(['dotnet', driver / 'PersonalAiWorkspace.ProductAcceptance.dll', '--entry'], env, 90)
            require(actual.returncode == 0, 'missing-model-does-not-force-legacy-or-reset-state'); desktop_pid = None
            require(json_get('http://127.0.0.1:8765/actuator/health/readiness')['status'] == 'UP', 'existing-runtime-not-stopped-on-desktop-exit')
            readiness = json_get('http://127.0.0.1:8765/api/v1/providers/readiness', tokens[-1])
            require(readiness['available'] and not readiness['modelAvailable'], 'missing-model-isolated-runtime-fixture')
            require(before == {name: sha(copied / name) for name in before}, 'payload-unchanged-after-product-flow')
            udf = Path(os.environ['LOCALAPPDATA']) / 'PersonalAiWorkspace/MainWorkspaceWebView2'
            needles = [v.encode(encoding) for v in markers + tokens for encoding in ('utf-8', 'utf-16-le')]
            udf_files = [p for p in udf.rglob('*') if p.is_file()]
            require(all(not any(n in p.read_bytes() for n in needles) for p in udf_files), 'full-fresh-marker-udf-audit')
            for log in logs + list((state / 'Logs').glob('*.log')):
                require(not any(n in log.read_bytes() for n in needles), 'private-runtime-ollama-log-audit')
            for entry in manifest['files']:
                require(not any(n in (package / entry['path']).read_bytes() for n in needles), 'package-fresh-markers-zero')
            audited = subprocess.run(
                ['python', '-X', 'utf8', str(root / 'scripts/privacy-audit.py')], input=json.dumps(dict(secrets=tokens, memoryMarkers=markers)),
                cwd=root, capture_output=True, encoding='utf-8', creationflags=flags, timeout=180)
            require(audited.returncode == 0, 'full-source-generated-evidence-archive-privacy-audit')
            privacy = json.loads(audited.stdout)
            (evidence / 'm5e-privacy-evidence.json').write_text(json.dumps(privacy, indent=2), encoding='utf-8')
            result = dict(result='PASS', packageChecks=len(manifest['files']), checks=sorted(set(checks)),
                          actualExeEntry=True, sameShippedAssemblies=True, realWindowsPinyin=True, realOllama=True,
                          workspaceParity=True, udfFiles=len(udf_files), unexpectedPrivacyMatches=0,
                          buildToolsRequiredAtRuntime=False, spaceAndNonAsciiRelocation=True)
            (evidence / 'm5e-package-evidence.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
            print(json.dumps(result))
        finally:
            terminate_pid(desktop_pid)
            stop_runtime()
            if ollama_process is not None:
                ollama_process.terminate(); ollama_process.wait(timeout=15)
            for server in (relay, control):
                server.shutdown(); server.server_close()


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print(json.dumps(dict(result='FAIL', check=stage)))
        raise SystemExit(1)
