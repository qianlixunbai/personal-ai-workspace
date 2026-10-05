"""One isolated K2 production flow; fresh query/source canaries never enter evidence.

The WPF driver types a genuine Windows Pinyin query with physical key events.
Index missing/corrupt and backup restart controls belong only to this fixture.
"""
from contextlib import closing
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import base64
import hashlib
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


def require(value, name):
    global stage
    stage = name
    if not value:
        raise RuntimeError(name)


def logical(directory):
    with closing(sqlite3.connect(directory / 'knowledge/knowledge.db')) as db:
        documents = db.execute('SELECT id,title,status,version,current_revision,created,updated FROM documents ORDER BY id').fetchall()
        revisions = db.execute('SELECT * FROM revisions ORDER BY doc_id,revision').fetchall()
    sources = {str(p.relative_to(directory / 'knowledge/sources')): hashlib.sha256(p.read_bytes()).hexdigest()
               for p in (directory / 'knowledge/sources').rglob('*.source')}
    return documents, revisions, sources


def main():
    require(os.name == 'nt', 'windows-required')
    for port in (8765, 18768):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    project = root / 'desktop/acceptance/PersonalAiWorkspace.KnowledgeSearchAcceptance/PersonalAiWorkspace.KnowledgeSearchAcceptance.csproj'
    built = subprocess.run(['dotnet', 'build', str(project), '-c', 'Release', '-p:FrontendSkipBuild=true'], cwd=root,
                           capture_output=True, creationflags=flags, timeout=90)
    require(built.returncode == 0, 'release-acceptance-build')
    settings = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True,
                              text=True, creationflags=flags, timeout=15)
    home = re.search(r'^\s*java\.home = (.+)$', settings.stderr, re.MULTILINE)
    require(home is not None, 'java-resolution')
    java = Path(home.group(1).strip()) / 'bin/java.exe'
    evidence = root / '.verification'
    evidence.mkdir(exist_ok=True)
    unique = uuid.uuid4().hex
    query = 'k2query' + unique
    marker = 'K2source' + unique
    with tempfile.TemporaryDirectory(prefix='workspace-k2-smoke-') as directory:
        temporary = Path(directory).resolve()
        require(temporary.parent == Path(tempfile.gettempdir()).resolve() and temporary.name.startswith('workspace-k2-smoke-'), 'verified-test-owned-location')
        source, target = temporary / 'source', temporary / 'restored'
        auth = temporary / 'auth/client-token'
        runtime = None
        logs = []
        token = ''
        restored = False
        cases = {'TITLE': 'budget-' + unique + '.txt', 'HEADING': 'heading-' + unique + '.md', 'BODY': 'body-' + unique + '.txt', 'QUERY': query}

        def stop():
            nonlocal runtime
            if runtime is not None and runtime.poll() is None:
                runtime.terminate()
                try:
                    runtime.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    runtime.kill(); runtime.wait(timeout=10)
            runtime = None

        def start(data):
            nonlocal runtime, token
            log = temporary / ('runtime-' + str(len(logs)) + '.log'); logs.append(log)
            with log.open('wb') as output:
                runtime = subprocess.Popen([str(java), '-jar', str(root / 'target/personal-ai-workspace-0.1.0.jar'),
                    '--server.port=8765', '--workspace.security.token-file=' + str(auth),
                    '--workspace.data-directory=' + str(data), '--workspace.ollama.base-url=http://127.0.0.1:18769'],
                    cwd=root, stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
            until = time.monotonic() + 40
            while True:
                require(runtime.poll() is None and time.monotonic() < until, 'isolated-runtime-startup')
                try:
                    with opener.open('http://127.0.0.1:8765/actuator/health/readiness', timeout=1) as response:
                        if response.status == 200 and auth.is_file():
                            token = auth.read_text('ascii').strip(); return
                except OSError:
                    time.sleep(.1)

        def api(method, path, payload=None, credential=None, extra=None, expected_status=200, binary=False):
            headers = {'Authorization': 'Bearer ' + (token if credential is None else credential), 'Content-Type': 'application/octet-stream' if binary else 'application/json'}
            if credential == '':
                del headers['Authorization']
            headers.update(extra or {})
            body = payload if binary else json.dumps(payload).encode('utf-8') if payload is not None else None
            request = urllib.request.Request('http://127.0.0.1:8765' + path, method=method, data=body, headers=headers)
            try:
                response = opener.open(request, timeout=20)
            except urllib.error.HTTPError as e:
                response = e
            with response:
                require(response.status == expected_status, 'controlled-api-status')
                data = response.read()
                return data if binary else json.loads(data) if data else {}

        def ready():
            until = time.monotonic() + 20
            while api('GET', '/api/v1/knowledge/search/status')['state'] != 'READY':
                require(time.monotonic() < until, 'index-ready'); time.sleep(.1)

        def upload(name, text):
            data = text.encode('utf-8')
            job = api('POST', '/api/v1/knowledge/imports', data, binary=True,
                extra={'X-Knowledge-Request': str(uuid.uuid4()), 'X-Knowledge-Filename': base64.urlsafe_b64encode(name.encode()).rstrip(b'=').decode(), 'X-Knowledge-Size': str(len(data))})
            until = time.monotonic() + 10
            while job['state'] in ('PENDING', 'PARSING'):
                require(time.monotonic() < until, 'source-admission-ready'); time.sleep(.05)
                job = api('GET', '/api/v1/knowledge/imports/' + job['requestId'])
            require(job['state'] == 'READY', 'source-admission-ready')

        def browser():
            origin = 'chrome-extension://' + 'e' * 32
            pairing = api('POST', '/api/v1/security/pairings', {'origin': origin, 'displayName': 'K2 Acceptance', 'userApproved': True})
            metadata = {'Origin': origin, 'Sec-Fetch-Site': 'none', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Dest': 'empty'}
            exchange = {'pairingId': pairing['pairingId'], 'pairingSecret': pairing['pairingSecret']}
            client = api('POST', '/api/v1/security/pairings/exchange', exchange, credential='', extra=metadata)
            for method, suffix in [('POST', ''), ('GET', '/status'), ('POST', '/rebuild')]:
                api(method, '/api/v1/knowledge/search' + suffix, None if method == 'GET' else {}, credential=client['credential'], extra=metadata, expected_status=403)
            api('DELETE', '/api/v1/security/clients/' + client['client']['clientId'], expected_status=204)
            return {'denied': True}

        class Control(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                nonlocal restored
                result = {}
                try:
                    action = self.path.strip('/')
                    if action in ('missing', 'corrupt'):
                        stop()
                        database = source / 'knowledge/index/lexical.db'
                        require(database.resolve().parent == (source / 'knowledge/index').resolve() and database.is_file(), 'owned-derived-index-target')
                        if action == 'missing':
                            database.unlink()
                        else:
                            database.write_bytes(b'corrupt derived index')
                        start(source); ready()
                    elif action == 'restore':
                        ready(); truth = logical(source); before = api('POST', '/api/v1/knowledge/search', {'query': 'replacementonly', 'limit': 10})
                        backup = api('GET', '/api/v1/knowledge/backup', binary=True)
                        encoded = base64.urlsafe_b64encode(str(target).encode()).rstrip(b'=').decode()
                        api('POST', '/api/v1/knowledge/backup/restore', backup, binary=True, extra={'X-Knowledge-Restore-Target': encoded})
                        require(not (target / 'knowledge/index').exists(), 'backup-excludes-index')
                        require(logical(target) == truth, 'restored-knowledge-source-parity')
                        stop(); start(target); ready()
                        after = api('POST', '/api/v1/knowledge/search', {'query': 'replacementonly', 'limit': 10})
                        restored = before == after; result = {'exact': restored}
                    elif action == 'browser':
                        result = browser()
                    else:
                        raise RuntimeError('unknown-control')
                    self.send_response(200); self.end_headers(); self.wfile.write(json.dumps(result).encode())
                except Exception as e:
                    (evidence / 'k2-control-failure.json').write_text(json.dumps({'check': stage, 'failureType': type(e).__name__}), encoding='utf-8')
                    self.send_response(500); self.end_headers(); self.wfile.write(b'{}')

        server = ThreadingHTTPServer(('127.0.0.1', 18768), Control)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            start(source)
            upload(cases['TITLE'], 'originalonly 预算\n' + query + ' ' + marker)
            upload(cases['HEADING'], '# budget 预算\n' + query + ' ' + marker)
            upload(cases['BODY'], 'budget 预算\n' + query + ' <script>literal</script> ' + marker)
            ready()
            env = dict(os.environ, K2_TOKEN_FILE=str(auth), K2_CASES=json.dumps(cases), K2_PROGRESS=str(evidence / 'k2-safe-progress.json'))
            exe = project.parent / 'bin/Release/net10.0-windows/PersonalAiWorkspace.KnowledgeSearchAcceptance.exe'
            accepted = subprocess.run([str(exe)], cwd=root, env=env, capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=240)
            report = json.loads(accepted.stdout)
            (evidence / 'k2-attempt.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
            require(accepted.returncode == 0 and report.get('result') == 'PASS', 'real-acceptance-' + report.get('check', 'unknown'))
            require(report['realWindowsPinyin'] and restored, 'mandatory-pinyin-and-restored-search-parity')
        finally:
            server.shutdown(); server.server_close(); stop()
        private = [query, marker, token, str(source), str(target), str(auth)] + [cases[key] for key in ('TITLE', 'HEADING', 'BODY')]
        needles = [encoded for value in private for encoded in (value.encode('utf-8'), value.encode('utf-16-le'), json.dumps(value, ensure_ascii=True)[1:-1].encode('ascii'))]
        require(not any(needle in log.read_bytes() for log in logs for needle in needles), 'fresh-query-source-runtime-log-privacy')
        udf = Path(os.environ['LOCALAPPDATA']) / 'PersonalAiWorkspace/MainWorkspaceWebView2'
        udf_checked = 0
        for path in udf.rglob('*') if udf.is_dir() else []:
            if path.is_file():
                require(not any(needle in path.read_bytes() for needle in needles), 'query-source-udf-privacy'); udf_checked += 1
        audit = subprocess.run(['python', str(root / 'scripts/privacy-audit.py')], cwd=root, input=json.dumps({'secrets': [token], 'knowledgeMarkers': [query, marker], 'logBodies': private}),
            capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=120)
        audit_report = json.loads(audit.stdout)
        require(audit.returncode == 0 and audit_report['result'] == 'PASS', 'source-build-evidence-package-privacy')
        report.update(privacyAudit=audit_report, freshRuntimeLogs=len(logs), freshUdfFiles=udf_checked, noOllamaDependency=True, backupIndexExcluded=True, restoredSearchParity=True)
    report.update(runtimeStopped=True, temporaryDirectoryRemoved=not temporary.exists())
    (evidence / 'k2-knowledge-search-evidence.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'result': 'PASS', 'productionChecks': len(report['checks']), 'coveragePoints': 22, 'independentExecutions': 1, 'realWindowsPinyin': True, 'privacyMatches': audit_report['matches']}))


if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        print(json.dumps({'result': 'FAIL', 'check': stage, 'failureType': type(e).__name__}))
        raise SystemExit(1)
