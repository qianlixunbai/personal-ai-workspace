"""Isolated Release WPF/WebView2/React/Runtime/SQLite/real Ollama M5C gates.

Only test-owned processes/data/credentials are touched. Bodies, provider captures,
unique markers and backups stay in the temporary fixture; evidence is counts only.
Fixture control is in this runner, never in a production app or Runtime endpoint.
"""
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
import base64
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
import urllib.error
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
    markers = {key: 'M5C-' + key.upper() + '-' + unique for key in
               ('title', 'context', 'user', 'answer', 'memory', 'memoryTitle', 'failedUser', 'capacityTitle', 'crash')}
    markers['context'] = 'ORBIT-' + unique[:8]
    markers['memory'] = 'QUARTZ-' + unique[8:16]
    ime = ''.join(chr(n) for n in (26412, 22320, 24037, 20316, 21306, 27979, 35797, 26143, 27827)) + '731'
    cases = dict(markers, ime=ime,
                 turn1='The synthetic code is ' + markers['context'] + '. Remember it. Reply with exactly that code.',
                 turn2='What was the synthetic code I asked you to remember? Return only that code.',
                 memoryAsk='What is the synthetic project marker in the explicitly selected Memory? Return only the marker.',
                 nextAsk='What is two plus three? Reply briefly.',
                 cancel='Reply briefly to this cancellation fixture. ' + unique,
                 failure='Reply briefly to this failure fixture. ' + markers['failedUser'],
                 reload='Reply with exactly ' + markers['answer'] + '. Reload fixture.',
                 reopen='Reply briefly to this reopen fixture. ' + unique,
                 archivePending='Reply briefly to this archived pending fixture. ' + unique,
                 restart='Reply briefly to this startup interruption fixture. ' + unique,
                 continued='Reply briefly with the word ready.',
                 recovered='In this restored conversation, what synthetic code did I ask you to remember earlier? Return only that code.')
    lock = threading.RLock()
    held = threading.Event()
    held.set()
    mode = 'normal'
    generation = 0
    provider_chats = 0
    actual_provider = set()
    admissions = []
    outputs = set()
    requests = []
    admission_times = []
    read_times = {'list': [], 'detail': [], 'pendingDetail': []}
    memory_injection_verified = False
    controls = []
    recovery_equal = False
    restart_no_replay = False
    runtime = None
    token = ''
    data = None
    logs = []

    def logical(directory):
        tables = dict(conversations='id,title,status,created_at,updated_at',
                      conversation_turns='id,conversation_id,sequence,status,created_at,updated_at,failure_code',
                      conversation_messages='id,turn_id,role,content,created_at',
                      conversation_memory_selections='turn_id,position,memory_id,revision',
                      memory_items='id,type,title,content,status,revision,source,created_at,updated_at')
        with closing(sqlite3.connect(directory / 'memory.db')) as db:
            return {table: db.execute('SELECT ' + fields + ' FROM ' + table + ' ORDER BY 1,2').fetchall() for table, fields in tables.items()}

    def direct(method, path, payload=None, headers=None):
        request = urllib.request.Request('http://127.0.0.1:18766' + path, data=payload, method=method,
                                         headers=dict({'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}, **(headers or {})))
        with opener.open(request, timeout=120) as response:
            return response.read(32 * 1024 * 1024)

    class Relay(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            self.forward()

        def do_POST(self):
            if self.server.server_port == 11435 and self.path.startswith('/__test/'):
                self.control(self.path.rsplit('/', 1)[1])
            else:
                self.forward()

        def do_PATCH(self):
            self.forward()

        def do_PUT(self):
            self.forward()

        def do_DELETE(self):
            self.forward()

        def respond(self, status, body=b'{}', headers=()):
            self.send_response(status)
            for name, value in headers:
                if name.lower() not in ('content-length', 'transfer-encoding', 'connection'):
                    self.send_header(name, value)
            if not any(name.lower() == 'content-type' for name, _ in headers):
                self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def control(self, action):
            nonlocal mode, generation, data, recovery_equal, restart_no_replay
            try:
                controls.append(action)
                if action == 'hold':
                    mode = 'hold'; held.clear()
                elif action == 'fail-provider':
                    mode = 'fail'; held.set()
                elif action == 'release':
                    mode = 'normal'; held.set()
                elif action == 'restart':
                    before = provider_chats
                    with closing(sqlite3.connect(data / 'memory.db')) as db:
                        require(db.execute("SELECT count(*) FROM conversation_turns WHERE status='PENDING'").fetchone()[0] == 1, 'restart-actual-durable-pending')
                    generation += 1
                    stop(kill=True)
                    mode = 'normal'; held.set()
                    start(data)
                    require(provider_chats == before, 'runtime-startup-zero-provider-replay')
                    restart_no_replay = True
                elif action in ('capacity-first', 'primary-first'):
                    title = cases['capacityTitle'] if action == 'capacity-first' else cases['title']
                    with closing(sqlite3.connect(data / 'memory.db')) as db:
                        db.execute('UPDATE conversations SET updated_at=(SELECT max(updated_at)+1 FROM conversations) WHERE title=?', (title,))
                        db.commit()
                elif action == 'recover':
                    before = logical(data)
                    backup = direct('GET', '/api/v1/workspace/backup')
                    stop()
                    original = data
                    unavailable = temporary / 'original-unavailable'
                    require(original.parent == temporary and unavailable.parent == temporary, 'task-owned-source-move-boundary')
                    original.rename(unavailable)
                    require(not original.exists(), 'original-workspace-unavailable-before-restore')
                    start(temporary / 'maintenance')
                    target = temporary / 'restored'
                    target_header = base64.urlsafe_b64encode(str(target).encode()).rstrip(b'=').decode()
                    direct('POST', '/api/v1/workspace/backup/restore', backup, {'X-Workspace-Restore-Target': target_header})
                    stop()
                    before_calls = provider_chats
                    data = target; start(data)
                    recovery_equal = before == logical(data)
                    require(recovery_equal and provider_chats == before_calls, 'workspace-logical-equality-zero-startup-replay')
                else:
                    raise RuntimeError('unknown-fixture-control')
                self.respond(200)
            except Exception:
                self.respond(500)

        def forward(self):
            nonlocal provider_chats, memory_injection_verified
            provider = self.server.server_port == 11435
            connection = http.client.HTTPConnection('127.0.0.1', 11434 if provider else 18766, timeout=160)
            try:
                length = int(self.headers.get('Content-Length', '0'))
                require(0 <= length <= 1048576, 'relay-bounded-request')
                payload = self.rfile.read(length) if length else None
                parsed = json.loads(payload) if payload else None
                captured_generation = generation
                if provider and self.path == '/api/chat':
                    with lock:
                        provider_chats += 1
                        actual_provider.update(key for key, value in cases.items() if parsed['messages'][-1]['role'] == 'user' and parsed['messages'][-1]['content'] == value)
                        if parsed['messages'][-1]['content'] == cases['memoryAsk']:
                            memory_injection_verified = any(markers['memory'] in message['content'] for message in parsed['messages'][:-1])
                    if mode == 'hold':
                        held.wait(90)
                    if mode == 'fail' or captured_generation != generation:
                        self.respond(503); return
                if not provider:
                    with lock:
                        requests.append((self.command, self.path))
                headers = {name: value for name, value in self.headers.items() if name.lower() not in ('host', 'connection', 'content-length')}
                started = time.perf_counter()
                connection.request(self.command, self.path, body=payload, headers=headers)
                response = connection.getresponse(); body = response.read(1048577)
                require(len(body) <= 1048576, 'relay-bounded-response')
                if not provider and self.command == 'GET' and self.path.startswith('/api/v1/conversations'):
                    kind = 'list' if self.path.startswith('/api/v1/conversations?') else 'detail'
                    with lock:
                        read_times[kind].append(round((time.perf_counter() - started) * 1000, 2))
                        if kind == 'detail' and mode == 'hold':
                            read_times['pendingDetail'].append(round((time.perf_counter() - started) * 1000, 2))
                if not provider and self.command == 'POST' and self.path.endswith('/turns'):
                    key = next((key for key, value in cases.items() if parsed.get('message') == value), None)
                    with lock:
                        admission_times.append(round((time.perf_counter() - started) * 1000, 2))
                        if response.status == 202:
                            admission = json.loads(body)
                            admissions.append(dict(case=key, memoryCount=len(parsed.get('memories', [])), refs=parsed.get('memories', []), admittedSequences=admission.get('admittedSequences', [])))
                if provider and self.path == '/api/chat' and response.status == 200:
                    result = json.loads(body).get('message', {}).get('content')
                    if result:
                        outputs.add(result)
                self.respond(response.status, body, response.getheaders())
            except (OSError, ValueError, http.client.HTTPException, RuntimeError):
                try:
                    self.respond(503)
                except OSError:
                    pass
            finally:
                connection.close()

    runtime_relay = ThreadingHTTPServer(('127.0.0.1', 8765), Relay)
    provider_relay = ThreadingHTTPServer(('127.0.0.1', 11435), Relay)
    for server in (runtime_relay, provider_relay):
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
    project = root / 'desktop/acceptance/PersonalAiWorkspace.ConversationsWorkspaceAcceptance/PersonalAiWorkspace.ConversationsWorkspaceAcceptance.csproj'
    published = root / '.verification/m5c-acceptance-publish'
    built = subprocess.run(['dotnet', 'publish', str(project), '-c', 'Release', '-p:FrontendSkipBuild=true', '-o', str(published)], cwd=root, capture_output=True, creationflags=flags, timeout=120)
    require(built.returncode == 0, 'release-acceptance-publish')
    settings = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True, text=True, creationflags=flags)
    home = re.search(r'^\s*java\.home = (.+)$', settings.stderr, re.MULTILINE)
    require(home is not None, 'direct-java-executable')
    java = Path(home.group(1).strip()) / 'bin/java.exe'
    with tempfile.TemporaryDirectory(prefix='workspace-m5c-') as directory:
        temporary = Path(directory).resolve()
        require(temporary.parent == Path(tempfile.gettempdir()).resolve(), 'isolated-task-owned-root')
        token_file = temporary / 'auth/client-token'
        data = temporary / 'data'

        def stop(kill=False):
            nonlocal runtime
            if runtime is not None:
                runtime.kill() if kill else runtime.terminate()
                runtime.wait(timeout=20); runtime = None

        def start(directory):
            nonlocal runtime, token
            log_file = temporary / ('runtime-' + str(len(logs)) + '.log'); logs.append(log_file)
            with log_file.open('wb') as output:
                runtime = subprocess.Popen([str(java), '-jar', str(root / 'target/personal-ai-workspace-0.1.0.jar'), '--server.port=18766',
                    '--workspace.ollama.base-url=http://127.0.0.1:11435', '--workspace.security.token-file=' + str(token_file), '--workspace.data-directory=' + str(directory)], cwd=root, stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
            deadline = time.monotonic() + 40
            while runtime.poll() is None and time.monotonic() < deadline:
                try:
                    with opener.open('http://127.0.0.1:18766/actuator/health', timeout=1) as response:
                        if json.load(response).get('status') == 'UP':
                            token = token_file.read_text().strip(); return
                except OSError:
                    time.sleep(.2)
            raise RuntimeError('isolated-runtime-ready')

        try:
            start(data)
            now = int(time.time() * 1000)
            with closing(sqlite3.connect(data / 'memory.db')) as db:
                capacity_id = str(uuid.uuid4())
                for index in range(25):
                    cid = capacity_id if index == 0 else str(uuid.uuid4())
                    title = cases['capacityTitle'] if index == 0 else 'M5C-LIST-' + unique + '-' + str(index)
                    db.execute("INSERT INTO conversations VALUES(?,?,'ACTIVE',?,?)", (cid, title, now - 10000, now - 10000 + index))
                for sequence in range(1, 1001):
                    tid = str(uuid.uuid4())
                    db.execute("INSERT INTO conversation_turns(id,conversation_id,sequence,status,created_at,updated_at) VALUES(?,?,?,'SUCCEEDED',?,?)", (tid, capacity_id, sequence, now - 10000, now - 9999))
                    text = '\u0001' * 8192 if sequence > 990 else 'M5C-HISTORY-' + unique
                    for role in ('USER', 'ASSISTANT'):
                        db.execute('INSERT INTO conversation_messages VALUES(?,?,?,?,?)', (str(uuid.uuid4()), tid, role, text, now - 10000 if role == 'USER' else now - 9999))
                db.commit()
            env = dict(os.environ, M5C_TEST_TOKEN_FILE=str(token_file), M5C_TEST_CASES=json.dumps(cases), M5C_MANUAL_IME='1' if args.manual_ime else '0', M5C_TEST_PROGRESS=str(root / '.verification/m5c-safe-progress.json'))
            accepted = subprocess.run([str(published / 'PersonalAiWorkspace.ConversationsWorkspaceAcceptance.exe')], cwd=published, env=env, capture_output=True, creationflags=flags, timeout=1500)
            report = json.loads(next(line for line in reversed(accepted.stdout.decode('utf-8-sig').splitlines()) if line.startswith('{')))
            if accepted.returncode or report.get('result') != 'PASS':
                print(json.dumps(report)); raise RuntimeError('real-conversation-acceptance:' + report.get('check', 'unknown'))
            require(recovery_equal and restart_no_replay, 'real-recovery-and-restart-no-replay')
            for key in ('turn1', 'turn2', 'memoryAsk', 'nextAsk', 'cancel', 'failure', 'reload', 'reopen', 'archivePending', 'restart', 'continued', 'recovered'):
                require(sum(item['case'] == key for item in admissions) == 1, 'one-explicit-admission-' + key)
            require(all(item['memoryCount'] == 0 for item in admissions if item['case'] != 'memoryAsk'), 'default-per-turn-zero-memory')
            require(any(item['case'] == 'memoryAsk' and item['memoryCount'] == 1 and item['refs'][0]['revision'] == 1 for item in admissions), 'explicit-exact-memory-reference')
            require(memory_injection_verified, 'real-provider-explicit-memory-context-injected')
            require(all(key in actual_provider for key in ('turn1', 'turn2', 'nextAsk', 'reload', 'reopen', 'continued', 'recovered')), 'exact-current-provider-user-input')
            if report['realWindowsPinyin']:
                require('ime' in actual_provider and sum(item['case'] == 'ime' for item in admissions) == 1, 'real-pinyin-exact-provider-input')
            with closing(sqlite3.connect(data / 'memory.db')) as db:
                require(db.execute("SELECT count(*) FROM conversation_messages WHERE role='USER' AND content=?", (cases['ime'],)).fetchone()[0] == (1 if report['realWindowsPinyin'] else 0), 'exact-pinyin-durable-user')
            report['admissionAssertions'] = dict(noAutomaticReplay=True, exactProviderCurrentUser=True, explicitMemoryExactRevision=True, noMemoryCarryOver=True, workspaceLogicalEquality=True, runtimeStartupNoReplay=True)
            report['metrics']['runtimeAdmissionRoundTripMs'] = admission_times
            report['metrics']['runtimeConversationReadRoundTripMs'] = read_times
        finally:
            held.set(); stop()
            for server in (runtime_relay, provider_relay):
                server.shutdown(); server.server_close()
        unique_values = list(markers.values()) + [ime, 'M5C-HISTORY-' + unique, 'M5C-LIST-' + unique]
        values = unique_values + list(cases.values()) + [token] + [value for value in outputs if len(value) >= 16]
        needles = [encoded for value in values for encoded in (value.encode('utf-8'), value.encode('utf-16-le'), json.dumps(value, ensure_ascii=True)[1:-1].encode())]
        require(not any(needle in path.read_bytes() for path in logs for needle in needles), 'runtime-logs-no-private-content')
        udf = Path(os.environ['LOCALAPPDATA']) / 'PersonalAiWorkspace/MainWorkspaceWebView2'
        for attempt in range(20):
            scanned = 0; unreadable = []
            for path in udf.rglob('*'):
                if path.is_file():
                    try:
                        contents = path.read_bytes()
                    except OSError:
                        unreadable.append(path); continue
                    scanned += 1; require(not any(needle in contents for needle in needles), 'udf-no-retained-history-markers')
            if not unreadable:
                break
            time.sleep(.25)
        require(not unreadable, 'udf-all-files-readable')
        audit = subprocess.run(['python', '-X', 'utf8', str(root / 'scripts/privacy-audit.py')], cwd=root,
            input=json.dumps({'secrets': [token], 'conversationMarkers': unique_values, 'logBodies': values}).encode(), capture_output=True, creationflags=flags, timeout=120)
        require(audit.returncode == 0, 'source-packaged-assets-evidence-privacy-audit')
        report['repositoryPrivacyAudit'] = json.loads(audit.stdout)
        report['privacy'] = dict(result='PASS', udfFilesScanned=scanned, historicalMarkersScanned=True, temporaryBearerScanned=True, forensicErasureClaimed=False)
        report['closingGate'] = 'LOCAL_ACCEPTANCE_PASS' if report['realWindowsPinyin'] else 'PARTIAL_AWAITING_REAL_WINDOWS_IME_ACCEPTANCE'
        (root / '.verification/m5c-conversations-evidence.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        print(json.dumps(report))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps(dict(result='FAIL', check=str(error) if type(error) is RuntimeError else stage)))
        raise SystemExit(1) from None
