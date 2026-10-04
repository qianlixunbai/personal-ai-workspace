"""Release WPF/WebView2/production React -> isolated Runtime -> real Ollama.

All text, relay captures and credentials are test-owned and kept in memory. Only
counts/assertions/metrics are retained. --manual-ime waits for actual user Pinyin
input; automated input insertion and event tests are never counted as real IME.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from contextlib import closing
from pathlib import Path
import argparse
import http.client
import json
import os
import re
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
stage = 'configuration'


def require(value, check):
    global stage
    stage = check
    if not value:
        raise RuntimeError(check)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--manual-ime', action='store_true')
    args = parser.parse_args()
    require(os.name == 'nt', 'windows-required')
    for port in (8765, 18766, 11435):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    unique = uuid.uuid4().hex
    markers = {kind: 'M5B-' + kind.upper() + '-' + unique for kind in
               ('user', 'summary', 'translate', 'result', 'memory', 'title', 'route', 'crash')}
    # Build the synthetic Chinese phrase at run time; do not retain it in Git/evidence.
    ime = ''.join(chr(n) for n in (26412, 22320, 24037, 20316, 21306, 27979, 35797, 26143, 27827)) + '731'
    cases = dict(ask='Reply with exactly this synthetic test marker: ' + markers['result'] + '. ' + markers['user'],
                 result=markers['result'], summary='A synthetic library opens at nine and lends books for two weeks. ' + markers['summary'],
                 memory=markers['memory'], title=markers['title'], memoryAsk='What is the synthetic project marker? Return only the marker.',
                 nextAsk='What is two plus three? Give a brief answer.', translate='Hello, world! ' + markers['translate'],
                 cancel='Please translate this synthetic cancellation test into Chinese. ' + unique,
                 hostile='<script>alert(1)</script><img onerror=alert(1)> &lt;\u202e ' + unique,
                 route='Reply briefly with the word ready. ' + markers['route'], crash=markers['crash'], ime=ime)
    lock = threading.Lock()
    admitted = []
    exact_provider = set()
    outputs = set()
    failures = []
    submit_times = []

    class Relay(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            self.forward()

        def do_POST(self):
            self.forward()

        def do_PUT(self):
            self.forward()

        def do_DELETE(self):
            self.forward()

        def forward(self):
            provider = self.server.server_port == 11435
            connection = http.client.HTTPConnection('127.0.0.1', 11434 if provider else 18766, timeout=160)
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 <= length <= 1048576:
                    raise RuntimeError('relay-body-bound')
                payload = self.rfile.read(length) if length else None
                parsed = json.loads(payload) if payload else None
                expected_key = None
                memory_count = 0
                if self.command == 'POST' and self.path.endswith('/tasks') and parsed:
                    text = parsed.get('question', parsed.get('text'))
                    expected_key = next((key for key, value in cases.items() if text == value), None)
                    memory_count = len(parsed.get('memories', []))
                if provider and self.path == '/api/chat' and parsed:
                    user = [message['content'] for message in parsed['messages'] if message['role'] == 'user']
                    with lock:
                        exact_provider.update(key for key, value in cases.items() if value in user)
                headers = {name: value for name, value in self.headers.items()
                           if name.lower() not in ('host', 'connection', 'content-length')}
                started = time.perf_counter()
                connection.request(self.command, self.path, body=payload, headers=headers)
                response = connection.getresponse()
                body = response.read(1048577)
                if len(body) > 1048576:
                    raise RuntimeError('relay-response-bound')
                if not provider and self.path.endswith('/tasks') and self.command == 'POST':
                    with lock:
                        submit_times.append((time.perf_counter() - started) * 1000)
                        if response.status == 202:
                            admitted.append(dict(case=expected_key, memoryCount=memory_count))
                if not provider and '/api/v1/tasks/' in self.path and response.status == 200:
                    result = json.loads(body).get('result')
                    if result:
                        with lock:
                            outputs.add(result)
                self.send_response(response.status)
                for name, value in response.getheaders():
                    if name.lower() not in ('transfer-encoding', 'connection', 'content-length'):
                        self.send_header(name, value)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (OSError, ValueError, http.client.HTTPException, RuntimeError):
                with lock:
                    failures.append('relay-controlled-failure')
                try:
                    self.send_response(503)
                    self.send_header('Content-Type', 'application/json')
                    self.end_headers()
                    self.wfile.write(b'{}')
                except OSError:
                    pass
            finally:
                connection.close()

    runtime_relay = ThreadingHTTPServer(('127.0.0.1', 8765), Relay)
    provider_relay = ThreadingHTTPServer(('127.0.0.1', 11435), Relay)
    for server in (runtime_relay, provider_relay):
        threading.Thread(target=server.serve_forever, daemon=True).start()
    published = root / '.verification/m5b-acceptance-publish'
    project = root / 'desktop/acceptance/PersonalAiWorkspace.AssistantTranslateAcceptance/PersonalAiWorkspace.AssistantTranslateAcceptance.csproj'
    built = subprocess.run(['dotnet', 'publish', str(project), '-c', 'Release', '-p:FrontendSkipBuild=true', '-o', str(published)],
                           cwd=root, capture_output=True, creationflags=flags, timeout=120)
    require(built.returncode == 0, 'release-acceptance-build')
    settings = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True, text=True, creationflags=flags)
    home = re.search(r'^\s*java\.home = (.+)$', settings.stderr, re.MULTILINE)
    require(home is not None, 'direct-java-executable')
    java = Path(home.group(1).strip()) / 'bin/java.exe'
    with tempfile.TemporaryDirectory(prefix='workspace-m5b-') as temporary:
        temporary = Path(temporary)
        token_file = temporary / 'auth/client-token'
        log_file = temporary / 'runtime.log'
        with log_file.open('wb') as log:
            runtime = subprocess.Popen([str(java), '-jar', str(root / 'target/personal-ai-workspace-0.1.0.jar'),
                                        '--server.port=18766', '--workspace.ollama.base-url=http://127.0.0.1:11435',
                                        '--workspace.security.token-file=' + str(token_file),
                                        '--workspace.data-directory=' + str(temporary / 'data')],
                                       cwd=root, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 40
                ready = False
                while runtime.poll() is None and time.monotonic() < deadline:
                    try:
                        with opener.open('http://127.0.0.1:8765/actuator/health', timeout=1) as response:
                            ready = json.load(response).get('status') == 'UP'
                        if ready:
                            break
                    except OSError:
                        time.sleep(.2)
                require(ready, 'isolated-runtime-ready')
                token = token_file.read_text().strip()
                env = dict(os.environ, M5B_TEST_TOKEN_FILE=str(token_file), M5B_TEST_CASES=json.dumps(cases),
                           M5B_MANUAL_IME='1' if args.manual_ime else '0',
                           M5B_TEST_PROGRESS=str(root / '.verification/m5b-safe-progress.json'))
                accepted = subprocess.run([str(published / 'PersonalAiWorkspace.AssistantTranslateAcceptance.exe')],
                                          cwd=published, env=env, capture_output=True, creationflags=flags,
                                          timeout=1200 if args.manual_ime else 900)
                report = json.loads(next(line for line in reversed(accepted.stdout.decode('utf-8-sig').splitlines()) if line.startswith('{')))
                if accepted.returncode or report.get('result') != 'PASS':
                    print(json.dumps(report))
                    raise RuntimeError('business-acceptance:' + report.get('check', 'unknown'))
                with closing(sqlite3.connect(temporary / 'data/memory.db')) as db:
                    require(db.execute('SELECT count(*) FROM conversations').fetchone()[0] == 0, 'stateless-no-conversation-persistence')
                    require(db.execute('SELECT count(*) FROM memory_items').fetchone()[0] == 0, 'no-automatic-memory-save')
                require(all(any(row['case'] == key for row in admitted) for key in ('ask', 'summary', 'memoryAsk', 'nextAsk', 'translate', 'cancel', 'hostile', 'route')),
                        'exact-react-bridge-runtime-admissions')
                require(all(row['memoryCount'] == 0 for row in admitted if row['case'] in ('ask', 'summary', 'nextAsk', 'translate', 'route')),
                        'ordinary-admission-memory-count-zero')
                require(any(row['case'] == 'memoryAsk' and row['memoryCount'] == 1 for row in admitted), 'explicit-memory-admission-count-one')
                require(all(key in exact_provider for key in ('ask', 'summary', 'nextAsk', 'translate', 'hostile', 'route')), 'runtime-actual-provider-input-exact')
                if report['realWindowsPinyin']:
                    require(report['realWindowsPinyin'] and 'ime' in exact_provider and sum(row['case'] == 'ime' for row in admitted) == 1
                            and all(row['memoryCount'] == 0 for row in admitted if row['case'] == 'ime'),
                            'real-ime-exact-runtime-input')
                report['admissionAssertions'] = dict(exactRuntimeInput=True, ordinaryMemoryCountZero=True,
                                                    explicitMemoryCountOne=True, noConversationPersistence=True, noAutomaticSave=True,
                                                    realImeExactRuntimeInput=report['realWindowsPinyin'])
                report['metrics']['runtimeAdmissionRoundTripMs'] = [round(value, 2) for value in submit_times]
            finally:
                runtime.terminate(); runtime.wait(timeout=15)
                for server in (runtime_relay, provider_relay):
                    server.shutdown(); server.server_close()
        marker_values = list(markers.values()) + [ime, cases['hostile'], token] + list(outputs)
        # Empty/very short model outputs would overmatch browser metadata. All actual
        # output markers still scan; full answers >=16 characters scan as additional needles.
        marker_values = [value for value in marker_values if len(value) >= 16 or value == ime]
        needles = [encoded for value in marker_values for encoded in
                   (value.encode('utf-8'), value.encode('utf-16-le'), json.dumps(value, ensure_ascii=True)[1:-1].encode('ascii'))]
        require(not any(needle in log_file.read_bytes() for needle in needles), 'runtime-log-no-content-or-bearer')
        udf = Path(os.environ['LOCALAPPDATA']) / 'PersonalAiWorkspace/MainWorkspaceWebView2'
        scanned = 0
        unreadable = []
        for attempt in range(20):
            scanned = 0; unreadable = []
            for path in udf.rglob('*'):
                if not path.is_file():
                    continue
                try:
                    data = path.read_bytes()
                except OSError:
                    unreadable.append(path); continue
                scanned += 1
                require(not any(needle in data for needle in needles), 'udf-unexpected-content-or-bearer')
            if not unreadable:
                break
            time.sleep(.25)
        require(not unreadable, 'udf-all-files-readable')
        audit = subprocess.run(['python', '-X', 'utf8', str(root / 'scripts/privacy-audit.py')], cwd=root,
                               input=json.dumps({'secrets': [token] + list(markers.values()) + [ime], 'logBodies': marker_values}).encode(),
                               capture_output=True, creationflags=flags, timeout=120)
        require(audit.returncode == 0, 'source-assets-evidence-log-privacy-audit')
        report['repositoryPrivacyAudit'] = json.loads(audit.stdout)
        report['privacy'] = dict(result='PASS', udfFilesScanned=scanned, syntheticMarkerKinds=len(markers) + 1,
                                 bearerScanned=True, actualResultBodiesScanned=True, forensicErasureClaimed=False)
        report['closingGate'] = 'LOCAL_ACCEPTANCE_PASS' if report['realWindowsPinyin'] else 'PARTIAL_AWAITING_REAL_WINDOWS_IME_ACCEPTANCE'
        evidence = root / '.verification/m5b-assistant-translate-evidence.json'
        evidence.write_text(json.dumps(report, indent=2), encoding='utf-8')
        print(json.dumps(report))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps(dict(result='FAIL', check=str(error) if type(error) is RuntimeError else stage)))
        raise SystemExit(1) from None
