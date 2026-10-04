"""Isolated M5D Release React/WPF/Runtime/SQLite acceptance and recovery.

Physical Windows Pinyin is used for both production Memory fields. Fixture
controls exist only in this runner. Evidence retains check names/counts/timings.
"""
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
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


def require(value, name):
    global stage
    stage = name
    if not value:
        raise RuntimeError(name)


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
    parser.add_argument('--skip-ime', action='store_true', help='Run remaining gates only; closingGate stays PARTIAL.')
    args = parser.parse_args()
    require(os.name == 'nt', 'windows-required')
    for port in (8765, 18767):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    project = root / 'desktop/acceptance/PersonalAiWorkspace.MemorySettingsAcceptance/PersonalAiWorkspace.MemorySettingsAcceptance.csproj'
    built = subprocess.run(['dotnet', 'build', str(project), '-c', 'Release', '-p:FrontendSkipBuild=true'], cwd=root,
                           capture_output=True, creationflags=flags, timeout=120)
    require(built.returncode == 0, 'release-acceptance-build')
    settings = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True,
                              text=True, creationflags=flags, timeout=15)
    home = re.search(r'^\s*java\.home = (.+)$', settings.stderr, re.MULTILINE)
    require(home is not None, 'java-resolution')
    java = Path(home.group(1).strip()) / 'bin/java.exe'
    unique = uuid.uuid4().hex
    cases = {key: 'M5D-' + key.upper() + '-' + unique for key in
             ('title', 'content', 'dirty', 'stale', 'missing', 'external', 'restoredDraft', 'query', 'pageQuery', 'archivedTitle', 'archivedContent', 'conversation')}
    phrase = ''.join(chr(n) for n in (26412, 22320, 24037, 20316, 21306, 27979, 35797, 26143, 27827))
    cases.update(imeTitleSuffix=str(int(unique[:7], 16)), imeContentSuffix=str(int(unique[7:14], 16)))
    cases.update(imeTitle=phrase + cases['imeTitleSuffix'], imeContent=phrase + cases['imeContentSuffix'])
    markers = [value for key, value in cases.items() if not key.endswith('Suffix')]
    evidence = root / '.verification'
    evidence.mkdir(exist_ok=True)
    screenshots = evidence
    udf = Path(os.environ['LOCALAPPDATA']) / 'PersonalAiWorkspace/MainWorkspaceWebView2'
    with tempfile.TemporaryDirectory(prefix='workspace-m5d-smoke-') as directory:
        temporary = Path(directory).resolve()
        require(temporary.parent == Path(tempfile.gettempdir()).resolve() and temporary.name.startswith('workspace-m5d-smoke-'), 'test-owned-location')
        auth = temporary / 'auth/client-token'
        source, maintenance = temporary / 'source', temporary / 'maintenance'
        memory_target, workspace_target = temporary / 'memory-restored', temporary / 'workspace-restored'
        memory_file, workspace_file = temporary / 'memory-backup.json', temporary / 'workspace-backup.json'
        runtime = None
        logs = []
        token = ''
        expected = None
        parity = {'memory': False, 'workspace': False}

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

        def api(method, path, payload=None):
            request = urllib.request.Request('http://127.0.0.1:8765' + path, method=method,
                data=json.dumps(payload).encode('utf-8') if payload is not None else None,
                headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
            with opener.open(request, timeout=15) as response:
                body = response.read(1024 * 1024)
                return json.loads(body) if body else None

        class Controls(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                nonlocal expected
                try:
                    action = self.path.rsplit('/', 1)[-1]
                    if action == 'stop':
                        stop()
                    elif action == 'source':
                        start(source)
                    elif action == 'maintenance':
                        expected = logical(source); stop(); start(maintenance)
                    elif action in ('memory-restored', 'workspace-restored'):
                        stop(); target = memory_target if action == 'memory-restored' else workspace_target
                        # Memory-only restoration creates schema v1. Start the real
                        # Runtime to apply its existing v1 -> v3 migration before
                        # comparing the shared logical source tables.
                        start(target)
                        restored = logical(target)
                        if action == 'memory-restored':
                            require(restored['memory_items'] == expected['memory_items'], 'memory-recovery-all-source-fields-exact'); parity['memory'] = True
                        else:
                            require(restored == expected, 'workspace-recovery-all-source-fields-exact'); parity['workspace'] = True
                    elif action == 'empty':
                        while True:
                            items = api('GET', '/api/v1/memory/items?status=ACTIVE&limit=20&page=0&query=')['items']
                            if not items:
                                break
                            for item in items:
                                api('DELETE', '/api/v1/memory/items/' + item['id'], {'expectedRevision': item['revision']})
                    else:
                        raise RuntimeError('unknown-fixture-control')
                    self.send_response(200); self.end_headers(); self.wfile.write(b'{}')
                except Exception:
                    self.send_response(500); self.end_headers(); self.wfile.write(b'{}')

        server = ThreadingHTTPServer(('127.0.0.1', 18767), Controls)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            start(source)
            for index in range(21):
                api('POST', '/api/v1/memory/items', {'type': 'PROJECT_NOTE', 'title': cases['pageQuery'] + '-' + str(index), 'content': 'Synthetic pagination fixture'})
            for index, query in enumerate((cases['query'], cases['query'].lower(), '%_literal', 'OR')):
                api('POST', '/api/v1/memory/items', {'type': 'PREFERENCE' if index % 2 == 0 else 'PROJECT_NOTE', 'title': query, 'content': query})
            archived = api('POST', '/api/v1/memory/items', {'type': 'PREFERENCE', 'title': cases['archivedTitle'], 'content': cases['archivedContent']})
            api('POST', '/api/v1/memory/items/' + archived['id'] + '/archive', {'expectedRevision': archived['revision']})
            env = os.environ.copy()
            env.update(M5D_CASES=json.dumps(cases), M5D_TOKEN_FILE=str(auth), M5D_PROGRESS=str(temporary / 'progress.json'),
                       M5D_MEMORY_FILE=str(memory_file), M5D_WORKSPACE_FILE=str(workspace_file), M5D_MEMORY_TARGET=str(memory_target),
                       M5D_WORKSPACE_TARGET=str(workspace_target), M5D_SCREENSHOTS=str(screenshots))
            env['M5D_SKIP_IME'] = '1' if args.skip_ime else '0'
            dll = project.parent / 'bin/Release/net10.0-windows/PersonalAiWorkspace.MemorySettingsAcceptance.dll'
            accepted = subprocess.run(['dotnet', str(dll)], cwd=root, env=env, capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=900)
            report = json.loads(accepted.stdout)
            if accepted.returncode != 0 or report.get('result') != 'PASS':
                (evidence / 'm5d-last-failed-attempt.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
            require(accepted.returncode == 0 and report.get('result') == 'PASS', 'real-acceptance-' + report.get('check', 'unknown'))
            require(all(parity.values()), 'both-backup-recovery-parities')
            require(not any(value in accepted.stderr for value in markers + [token]), 'harness-stderr-privacy')
        finally:
            server.shutdown(); server.server_close(); stop()
        needles = []
        for value in markers + [token]:
            needles.extend((value.encode('utf-8'), value.encode('utf-16-le'), json.dumps(value, ensure_ascii=True)[1:-1].encode('ascii')))
        require(not any(needle in log.read_bytes() for log in logs for needle in needles), 'fresh-runtime-log-privacy')
        udf_files = list(udf.rglob('*')) if udf.is_dir() else []
        udf_checked = 0
        for path in udf_files:
            if path.is_file():
                try:
                    data = path.read_bytes()
                except OSError:
                    require(False, 'fresh-memory-udf-unreadable-file')
                udf_checked += 1
                require(not any(needle in data for needle in needles), 'fresh-memory-udf-privacy')
        audit = subprocess.run(['python', str(root / 'scripts/privacy-audit.py')], cwd=root,
            input=json.dumps({'secrets': [token], 'memoryMarkers': markers}), capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=120)
        require(audit.returncode == 0 and json.loads(audit.stdout)['result'] == 'PASS', 'repository-build-archive-evidence-privacy')
        report.update(syntheticOnly=True, sourceUnavailableDuringRestore=True, memoryRecoveryExact=True, workspaceRecoveryExact=True,
                      runtimeLogPrivacy=True, udfPrivacy=True, udfFilesChecked=udf_checked, unexpectedMatches=0, privacyAudit=json.loads(audit.stdout))
    report.update(temporaryDataRemoved=True, runtimeStopped=True, closingGate='LOCAL_ACCEPTANCE_PASS' if report['realWindowsPinyin'] else 'PARTIAL')
    (evidence / 'm5d-memory-settings-evidence.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'result': 'FAIL', 'check': str(error) if type(error) is RuntimeError else stage}))
        raise SystemExit(1) from None
