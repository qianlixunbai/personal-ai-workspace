"""Repository/build/evidence audit. Ephemeral secrets and log-only bodies arrive via stdin.

Never print or persist the values being searched. Archive contents are inspected in memory.
"""
from pathlib import Path
import io
import json
import re
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
payload = json.load(sys.stdin) if not sys.stdin.isatty() else {}


def git_files(*args):
    return [n for n in subprocess.check_output(['git', '-C', str(root), 'ls-files', '-z', *args]).decode().split('\0') if n]


tracked = git_files()
sources = set(tracked + git_files('--others', '--exclude-standard'))
secrets = set(payload.get('secrets', []))
# M5C fixtures provide fresh unique title/user/assistant/failed-user/Memory/IME
# markers only through stdin. Search all source/build/archive bytes for these,
# including ignored outputs; keep their values out of reports and diagnostics.
conversation_markers = payload.get('conversationMarkers', [])
if not isinstance(conversation_markers, list) or any(not isinstance(value, str) or not value for value in conversation_markers):
    raise ValueError('Conversation marker input must be a nonempty-string list')
secrets.update(conversation_markers)
native = set()
token_paths = set()
for folder in ['.runtime', '.verification', 'target']:
    for path in (root / folder).rglob('client-token'):
        value = path.read_text('utf-8-sig').strip()
        if re.fullmatch(r'[A-Za-z0-9_-]{43}', value):
            native.add(value)
            token_paths.add(path.resolve())
secrets.update(native)


def encodings(value):
    # PowerShell/.NET can pipe UTF-16 surrogate pairs as separate JSON characters.
    # Check original UTF-16, normalized UTF-8 and the escaped JSON representation.
    utf16 = value.encode('utf-16-le', errors='surrogatepass')
    normalized = utf16.decode('utf-16-le', errors='replace')
    return [utf16, normalized.encode('utf-8'), json.dumps(value, ensure_ascii=True)[1:-1].encode('ascii')]


needles = [encoded for value in secrets if value for encoded in encodings(value)]
private_bodies = payload.get('logBodies', []) + [
    'M4C-HISTORY-731', 'M4C-MEMORY-492', 'Synthetic M4C terminal user',
    'synthetic workspace HTTP title', 'synthetic workspace HTTP content', 'synthetic workspace HTTP answer',
    'ORBIT-731', 'QUARTZ-492', 'VECTOR-268', 'CURRENT_USER', 'OLD_ASSISTANT',
    'Synthetic interrupted user instruction',
    'conversation-private-title', 'conversation-private-user', 'conversation-private-assistant',
    'private-memory-title', 'private-memory-context', 'private-memory-question',
    'Synthetic Project Context', 'What is the synthetic project codename?',
    'memory-title-private-marker', 'memory-content-private-marker', 'memory-query-private-marker',
    'synthetic-corrupt-private-bytes', 'private SQL failure',
    'batch-input-private-marker', 'batch-output-private-marker', 'settings-output-private-marker',
    'unexpected-private-marker', 'duplicate-key-private-marker', 'input-private-marker',
    'output-private-marker', 'malformed-private-marker', 'raw-error-private-marker']
privacy_needles = [encoded for value in private_bodies if value for encoded in encodings(value)]
# The existing Browser fixture translates the public word "Settings". It is also
# approved shell IA, so its presence in static frontend assets is expected. Keep
# every supplied body in log/evidence scans, and all unique personal markers and
# actual secrets in frontend scans; do not exempt Runtime/UDF artifacts.
frontend_privacy_needles = [encoded for value in private_bodies if value and value != 'Settings' for encoded in encodings(value)]
patterns = [re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{64,}-----END'),
            re.compile(rb'gh[pousr]_[A-Za-z0-9]{30,}'), re.compile(rb'AKIA[A-Z0-9]{16}'),
            re.compile(rb'sk-(?:proj-)?[A-Za-z0-9_-]{32,}'),
            re.compile(rb'br1\.[0-9a-f-]{36}\.[A-Za-z0-9_-]{43}')]
matches = []
checks = archives = 0


def scan(data, label, patterns_on, privacy_on, depth=0, frontend=False):
    global checks, archives
    checks += 1
    if any(n in data for n in needles):
        matches.append(label + ':actual-secret')
    if patterns_on and any(p.search(data) for p in patterns):
        matches.append(label + ':secret-pattern')
    if privacy_on and any(n in data for n in (frontend_privacy_needles if frontend else privacy_needles)):
        matches.append(label + ':private-body')
    if depth < 4 and zipfile.is_zipfile(io.BytesIO(data)):
        archives += 1
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for entry in archive.infolist():
                if not entry.is_dir():
                    scan(archive.read(entry), label + '!' + entry.filename, patterns_on, privacy_on, depth + 1, frontend)


files = set(root / name for name in sources)
for folder in ['target', 'desktop', '.verification', '.runtime']:
    files.update(p for p in (root / folder).rglob('*') if p.is_file() and p.resolve() not in token_paths and p.suffix != '.png'
                 and 'node_modules' not in p.parts)
for path in sorted(files):
    if path.is_file():
        label = path.relative_to(root).as_posix()
        # Synthetic fixtures intentionally contain source text; only product logs/evidence must exclude it.
        is_test_xml = path.suffix == '.xml' and 'surefire-reports' in path.parts
        is_frontend_build = ('frontend' in path.parts and 'dist' in path.parts) or 'MainWorkspace' in path.parts
        is_private_output = is_frontend_build or path.suffix == '.log' or ('surefire-reports' in path.parts and not is_test_xml) or ('evidence' in path.name and path.suffix == '.json')
        scan(path.read_bytes(), label, label in sources, is_private_output, frontend=is_frontend_build)
        if is_test_xml:
            # Test names such as GenerationSettings legitimately contain the synthetic word "Settings".
            # Inspect captured stdout/stderr and failures for body leaks; secrets still scan the whole XML above.
            for node in ET.parse(path).getroot().iter():
                if node.tag in ['system-out', 'system-err', 'failure', 'error']:
                    scan(ET.tostring(node, encoding='utf-8'), label + ':' + node.tag, False, True)
artifacts = [name for name in tracked if re.search(r'(^|/)(target|bin|obj|node_modules|dist|\.runtime|\.verification|\.vs|TestResults)(/|$)|\.(log|jar|dll|exe|zip|trx|db|sqlite|sqlite3)(-(wal|shm|journal))?$', name)]
backup_artifacts = [name for name in tracked if re.search(r'(^|/)(workspace-backup|memory-backup)\.json$|\.(workspace-backup|memory-backup)\.json$', name)]
ignore_ok = all(subprocess.run(['git', '-C', str(root), 'check-ignore', '-q', name]).returncode == 0 for name in [
    'workspace-backup.json', 'check.workspace-backup.json', '.workspace-export-check', '.workspace-restore-check', '.workspace-validation-check',
    '.runtime/client-token', '.verification/browser-batch-smoke-evidence.json', 'target/personal-ai-workspace-0.1.0.jar',
    'desktop/frontend/node_modules/check.js', 'desktop/frontend/dist/check.js', 'desktop/frontend/coverage/check.json',
    'memory.db', 'memory.db-wal', 'memory.db-shm', 'memory.db-journal',
    'desktop/src/PersonalAiWorkspace.Desktop/bin/check.dll', 'desktop/src/PersonalAiWorkspace.Desktop/obj/check.json',
    'desktop/tests/PersonalAiWorkspace.Desktop.Tests/TestResults/check.trx'])
frontend_production = [p for p in (root / 'desktop/frontend/src').rglob('*') if p.suffix in ['.ts', '.tsx'] and '.test.' not in p.name and 'test' not in p.parts]
frontend_isolated = all(not re.search(r'\bfetch\s*\(|XMLHttpRequest|\bAuthorization\b|indexedDB|serviceWorker|\bcaches\s*\.|\bconsole\.|dangerouslySetInnerHTML|navigator\.clipboard|sessionStorage', p.read_text('utf-8'))
                       and (p.name == 'theme.ts' or not re.search(r'localStorage|Storage\.prototype', p.read_text('utf-8')))
                       for p in frontend_production)
bridge_sources = list((root / 'desktop/src/PersonalAiWorkspace.Desktop/Bridge').glob('*.cs'))
bridge_no_content_diagnostics = all(not re.search(r'Console\.|Debug\.Write|Trace\.Write|ILogger|LogInformation|LogError|LogWarning', p.read_text('utf-8')) for p in bridge_sources)
report = dict(result='PASS' if not matches and not artifacts and not backup_artifacts and ignore_ok and frontend_isolated and bridge_no_content_diagnostics else 'FAIL', sourceFiles=len(sources),
              files=len(files), byteAndArchiveChecks=checks, archives=archives, actualNativeCredentials=len(native),
              ephemeralSecrets=len(payload.get('secrets', [])), conversationMarkers=len(conversation_markers), matches=len(matches), trackedBuildArtifacts=len(artifacts), trackedBackupArtifacts=len(backup_artifacts), ignorePassed=ignore_ok,
              frontendNoDirectNetworkOrDomainStorage=frontend_isolated, bridgeNoContentDiagnostics=bridge_no_content_diagnostics)
print(json.dumps(report, indent=2))
if report['result'] != 'PASS':
    print(json.dumps(dict(matchedFiles=matches, artifacts=artifacts, backupArtifacts=backup_artifacts)))
    sys.exit(1)
