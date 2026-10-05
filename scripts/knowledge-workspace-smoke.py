"""One isolated K1 production WPF/WebView2/React/Runtime acceptance flow.

Test-only controls hold HTTP streams and kill the owned Runtime to exercise
durable recovery. No fixture controls or credentials enter product code.
Evidence contains check names/counts only, never source bodies or paths.
"""
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import base64
import hashlib
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
    project = root / 'desktop/acceptance/PersonalAiWorkspace.KnowledgeAcceptance/PersonalAiWorkspace.KnowledgeAcceptance.csproj'
    built = subprocess.run(['dotnet', 'build', str(project), '-c', 'Release', '-p:FrontendSkipBuild=true'], cwd=root,
                           capture_output=True, creationflags=flags, timeout=120)
    require(built.returncode == 0, 'release-acceptance-build')
    settings = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True,
                              text=True, creationflags=flags, timeout=15)
    home = re.search(r'^\s*java\.home = (.+)$', settings.stderr, re.MULTILINE)
    require(home is not None, 'java-resolution')
    java = Path(home.group(1).strip()) / 'bin/java.exe'
    evidence = root / '.verification'
    evidence.mkdir(exist_ok=True)
    unique = uuid.uuid4().hex
    markers = ['K1-' + name + '-' + unique for name in ('txt-body', 'md-body', 'revision-body', 'delete-body')]
    udf = Path(os.environ['LOCALAPPDATA']) / 'PersonalAiWorkspace/MainWorkspaceWebView2'
    with tempfile.TemporaryDirectory(prefix='workspace-k1-smoke-') as directory:
        temporary = Path(directory).resolve()
        require(temporary.parent == Path(tempfile.gettempdir()).resolve() and temporary.name.startswith('workspace-k1-smoke-'), 'test-owned-location')
        source, maintenance, target = (temporary / name for name in ('source', 'maintenance', 'restored'))
        target.mkdir()
        auth = temporary / 'auth/client-token'
        backup = temporary / 'knowledge.knowledge-backup'
        external = temporary / 'external'
        external.mkdir()
        cases = {name: str(external / (name.lower() + '-' + unique + ('.md' if name == 'MD' else '.txt')))
                 for name in ('TXT', 'MD', 'INVALID', 'OVERSIZE', 'CHANGED', 'DELETE')}
        cases.update(TXT_NORMALIZED=markers[0] + '\nsecond line', MD_FIRST='# First\n' + markers[1] + '\n',
                     MD_SECOND='# Second\n<script>synthetic-only</script>', CHANGED_NORMALIZED=markers[2] + '\nchanged line')
        Path(cases['TXT']).write_bytes(b'\xef\xbb\xbf' + cases['TXT_NORMALIZED'].replace('\n', '\r\n').encode('utf-8'))
        Path(cases['MD']).write_bytes((cases['MD_FIRST'] + cases['MD_SECOND']).encode('utf-8'))
        Path(cases['CHANGED']).write_bytes(cases['CHANGED_NORMALIZED'].encode('utf-8'))
        Path(cases['DELETE']).write_bytes(markers[3].encode('utf-8'))
        Path(cases['INVALID']).write_bytes(b'\xc3\x28')
        with Path(cases['OVERSIZE']).open('wb') as file:
            file.truncate(8 * 1024 * 1024 + 1)
        runtime = None
        logs, held = [], []
        token = ''
        expected = None
        restored_parity = False
        queue_full = False

        def stop(hard=False):
            nonlocal runtime
            if runtime is not None and runtime.poll() is None:
                runtime.kill() if hard else runtime.terminate()
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
                    '--workspace.data-directory=' + str(data)], cwd=root, stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
            until = time.monotonic() + 40
            while True:
                require(runtime.poll() is None and time.monotonic() < until, 'isolated-runtime-startup')
                try:
                    with opener.open('http://127.0.0.1:8765/actuator/health/readiness', timeout=1) as response:
                        if response.status == 200 and auth.is_file():
                            token = auth.read_text('ascii').strip(); return
                except OSError:
                    time.sleep(.1)

        def api(method, path, payload=None, credential=None, extra=None, expected_status=200):
            headers = {'Authorization': 'Bearer ' + (token if credential is None else credential), 'Content-Type': 'application/json'}
            if credential == '':
                del headers['Authorization']
            headers.update(extra or {})
            request = urllib.request.Request('http://127.0.0.1:8765' + path, method=method,
                data=json.dumps(payload).encode('utf-8') if payload is not None else None, headers=headers)
            try:
                response = opener.open(request, timeout=15)
            except urllib.error.HTTPError as e:
                response = e
            with response:
                require(response.status == expected_status, 'fixture-http-status')
                body = response.read(65536)
                return json.loads(body) if body else None

        def pending(name, update=None):
            request_id = str(uuid.uuid4())
            body = ('Synthetic held stream ' + name + ' ' + unique).encode('utf-8')
            connection = http.client.HTTPConnection('127.0.0.1', 8765, timeout=20)
            connection.putrequest('POST', '/api/v1/knowledge/imports')
            headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/octet-stream',
                       'Content-Length': str(len(body)), 'X-Knowledge-Request': request_id, 'X-Knowledge-Size': str(len(body)),
                       'X-Knowledge-Filename': base64.urlsafe_b64encode(name.encode()).decode().rstrip('=')}
            if update:
                headers.update({'X-Knowledge-Document': update['documentId'], 'X-Knowledge-Version': update['metadataVersion']})
            for key, value in headers.items():
                connection.putheader(key, value)
            connection.endheaders(); connection.send(body[:1])
            held.append((connection, body[1:], request_id))
            until = time.monotonic() + 10
            while True:
                with closing(sqlite3.connect(source / 'knowledge/knowledge.db')) as db:
                    if db.execute('SELECT count(*) FROM jobs WHERE id=?', (request_id,)).fetchone()[0]:
                        return request_id
                require(time.monotonic() < until, 'durable-stream-admission'); time.sleep(.05)

        def browser_check():
            origin = 'chrome-extension://' + 'k' * 32
            pairing = api('POST', '/api/v1/security/pairings', {'origin': origin, 'displayName': 'Synthetic K1 acceptance', 'userApproved': True})
            exchange = api('POST', '/api/v1/security/pairings/exchange',
                {'pairingId': pairing['pairingId'], 'pairingSecret': pairing['pairingSecret']}, credential='',
                extra={'Origin': origin, 'Sec-Fetch-Site': 'none', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Dest': 'empty'})
            credential = exchange['credential']
            browser_headers = {'Origin': origin, 'Sec-Fetch-Site': 'none', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Dest': 'empty'}
            for method, path in [('GET', '/api/v1/knowledge/documents'), ('POST', '/api/v1/knowledge/imports'),
                                 ('GET', '/api/v1/knowledge/backup'), ('POST', '/api/v1/knowledge/backup/validate'),
                                 ('POST', '/api/v1/knowledge/backup/restore')]:
                api(method, path, {} if method == 'POST' else None, credential, browser_headers, 403)
            api('GET', '/api/v1/knowledge/documents', credential=credential,
                extra={key: value for key, value in browser_headers.items() if key != 'Origin'}, expected_status=403)
            api('DELETE', '/api/v1/security/clients/' + exchange['client']['clientId'], expected_status=204)
            return {'denied': True}

        class Controls(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                nonlocal expected, restored_parity, queue_full
                result = {}
                try:
                    action = self.path.rsplit('/', 1)[-1]
                    if action == 'queue':
                        for index in range(5):
                            pending('queued-' + str(index) + '.txt')
                        body = b'Synthetic sixth stream'
                        request = urllib.request.Request('http://127.0.0.1:8765/api/v1/knowledge/imports', data=body,
                            headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/octet-stream',
                                     'X-Knowledge-Request': str(uuid.uuid4()), 'X-Knowledge-Size': str(len(body)),
                                     'X-Knowledge-Filename': 'c2l4dGgudHh0'})
                        try:
                            opener.open(request, timeout=10).close()
                        except urllib.error.HTTPError as e:
                            queue_full = e.code == 429 and json.loads(e.read())['code'] == 'KNOWLEDGE_QUEUE_FULL'
                        require(queue_full, 'bounded-sixth-admission')
                    elif action == 'release':
                        for connection, remainder, _ in held:
                            connection.send(remainder)
                            response = connection.getresponse(); response.read(65536); connection.close()
                        held.clear()
                        until = time.monotonic() + 15
                        while any(d['processingState'] in ('PENDING', 'PARSING') for d in api('GET', '/api/v1/knowledge/documents')['items']):
                            require(time.monotonic() < until, 'held-streams-terminal'); time.sleep(.05)
                        for doc in api('GET', '/api/v1/knowledge/documents')['items']:
                            if doc['title'].startswith('queued-'):
                                api('DELETE', '/api/v1/knowledge/documents/' + doc['documentId'], {'expectedMetadataVersion': doc['metadataVersion']})
                        result = {'queueFull': queue_full}
                    elif action == 'interrupt':
                        doc = next(d for d in api('GET', '/api/v1/knowledge/documents')['items'] if d['title'] == Path(cases['TXT']).name)
                        job = pending('interrupted-update.txt', doc)
                        pending('interrupted-new.txt')
                        # Explicit crash-window fixture: durable job-owned candidate, no READY row.
                        with closing(sqlite3.connect(source / 'knowledge/knowledge.db')) as db:
                            db.execute("UPDATE jobs SET state='PARSING' WHERE id=?", (job,)); db.commit()
                        candidate = source / 'knowledge/sources' / doc['documentId'] / '3.source'
                        candidate.write_bytes(b'Synthetic unpublished candidate')
                        stop(hard=True)
                        for connection, _, _ in held:
                            connection.close()
                        held.clear()
                    elif action == 'restart':
                        start(source)
                        doc = next(d for d in api('GET', '/api/v1/knowledge/documents')['items'] if d['title'] == 'interrupted-new.txt')
                        require(doc['processingState'] == 'INTERRUPTED' and doc['currentReadyRevision'] is None, 'new-job-interrupted-not-ready')
                        api('DELETE', '/api/v1/knowledge/documents/' + doc['documentId'], {'expectedMetadataVersion': doc['metadataVersion']})
                    elif action == 'snapshot':
                        expected = logical(source)
                        result = {'stagingClean': not any((source / 'knowledge/staging').iterdir())}
                    elif action == 'offline':
                        stop(); source.rename(temporary / 'unavailable-source'); start(maintenance)
                    elif action == 'restored':
                        stop(); start(target)
                    elif action == 'parity':
                        restored_parity = logical(target) == expected
                        result = {'exact': restored_parity}
                    elif action == 'browser':
                        result = browser_check()
                    else:
                        raise RuntimeError('unknown-fixture-control')
                    self.send_response(200); self.end_headers(); self.wfile.write(json.dumps(result).encode())
                except Exception as e:
                    (temporary / 'control-failure.json').write_text(json.dumps({'stage': stage, 'type': type(e).__name__}), encoding='utf-8')
                    self.send_response(500); self.end_headers(); self.wfile.write(b'{}')

        server = ThreadingHTTPServer(('127.0.0.1', 18768), Controls)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            start(source)
            env = os.environ.copy()
            env.update(K1_CASES=json.dumps(cases), K1_TOKEN_FILE=str(auth), K1_PROGRESS=str(temporary / 'progress.json'),
                       K1_SOURCE=str(source), K1_BACKUP=str(backup), K1_TARGET=str(target))
            dll = project.parent / 'bin/Release/net10.0-windows/PersonalAiWorkspace.KnowledgeAcceptance.dll'
            accepted = subprocess.run(['dotnet', str(dll)], cwd=root, env=env, capture_output=True, text=True,
                                      encoding='utf-8', creationflags=flags, timeout=600)
            report = json.loads(accepted.stdout)
            if accepted.returncode != 0 or report.get('result') != 'PASS':
                if (temporary / 'control-failure.json').is_file():
                    report['fixtureFailure'] = json.loads((temporary / 'control-failure.json').read_text('utf-8'))
                (evidence / 'k1-last-failed-attempt.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
            require(accepted.returncode == 0 and report.get('result') == 'PASS', 'real-acceptance-' + report.get('check', 'unknown'))
            require(restored_parity, 'knowledge-recovery-exact-parity')
            private = markers + [token] + list(cases[name] for name in ('TXT', 'MD', 'INVALID', 'OVERSIZE', 'CHANGED', 'DELETE'))
            private += [Path(cases[name]).name for name in ('TXT', 'MD', 'INVALID', 'OVERSIZE', 'CHANGED', 'DELETE')]
            private += [str(path) for path in (source, maintenance, target, backup)]
            require(not any(value in accepted.stderr for value in private), 'harness-stderr-privacy')
        finally:
            server.shutdown(); server.server_close(); stop()
            for connection, _, _ in held:
                connection.close()
        needles = [encoded for value in private for encoded in
                   (value.encode('utf-8'), value.encode('utf-16-le'), json.dumps(value, ensure_ascii=True)[1:-1].encode('ascii'))]
        require(not any(needle in log.read_bytes() for log in logs for needle in needles), 'fresh-runtime-log-privacy')
        udf_checked = 0
        for path in udf.rglob('*') if udf.is_dir() else []:
            if path.is_file():
                data = path.read_bytes(); udf_checked += 1
                require(not any(needle in data for needle in needles), 'fresh-knowledge-udf-privacy')
        audit = subprocess.run(['python', str(root / 'scripts/privacy-audit.py')], cwd=root,
            input=json.dumps({'secrets': [token] + private[5:], 'knowledgeMarkers': markers}),
            capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=120)
        audit_report = json.loads(audit.stdout)
        require(audit.returncode == 0 and audit_report['result'] == 'PASS', 'knowledge-source-build-evidence-package-privacy')
        report.update(privacyAudit=audit_report, freshRuntimeLogs=len(logs), freshUdfFiles=udf_checked,
                      syntheticFixtures=True, crashWindow='held HTTP PENDING plus test-owned PARSING candidate; owned Runtime hard-stop',
                      sourceUnavailableDuringRestore=True, originalExternalSourcesRemoved=True,
                      temporaryPrivateData='owned fixture originals/databases/backups only; staging empty before backup')
    report.update(runtimeStopped=True, temporaryDirectoryRemoved=not temporary.exists())
    (evidence / 'k1-knowledge-evidence.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'result': 'PASS', 'productionChecks': len(report['checks']), 'coveragePoints': 21,
                      'independentExecutions': 1, 'privacyMatches': audit_report['matches']}))


if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        print(json.dumps({'result': 'FAIL', 'check': stage, 'failureType': type(e).__name__}))
        raise SystemExit(1)
