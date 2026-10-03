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
    'memory-title-private-marker', 'memory-content-private-marker', 'memory-query-private-marker',
    'synthetic-corrupt-private-bytes', 'private SQL failure',
    'batch-input-private-marker', 'batch-output-private-marker', 'settings-output-private-marker',
    'unexpected-private-marker', 'duplicate-key-private-marker', 'input-private-marker',
    'output-private-marker', 'malformed-private-marker', 'raw-error-private-marker']
privacy_needles = [encoded for value in private_bodies if value for encoded in encodings(value)]
patterns = [re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{64,}-----END'),
            re.compile(rb'gh[pousr]_[A-Za-z0-9]{30,}'), re.compile(rb'AKIA[A-Z0-9]{16}'),
            re.compile(rb'sk-(?:proj-)?[A-Za-z0-9_-]{32,}'),
            re.compile(rb'br1\.[0-9a-f-]{36}\.[A-Za-z0-9_-]{43}')]
matches = []
checks = archives = 0


def scan(data, label, patterns_on, privacy_on, depth=0):
    global checks, archives
    checks += 1
    if any(n in data for n in needles):
        matches.append(label + ':actual-secret')
    if patterns_on and any(p.search(data) for p in patterns):
        matches.append(label + ':secret-pattern')
    if privacy_on and any(n in data for n in privacy_needles):
        matches.append(label + ':private-body')
    if depth < 4 and zipfile.is_zipfile(io.BytesIO(data)):
        archives += 1
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for entry in archive.infolist():
                if not entry.is_dir():
                    scan(archive.read(entry), label + '!' + entry.filename, patterns_on, privacy_on, depth + 1)


files = set(root / name for name in sources)
for folder in ['target', 'desktop', '.verification']:
    files.update(p for p in (root / folder).rglob('*') if p.is_file() and p.resolve() not in token_paths and p.suffix != '.png')
for path in sorted(files):
    if path.is_file():
        label = path.relative_to(root).as_posix()
        # Synthetic fixtures intentionally contain source text; only product logs/evidence must exclude it.
        is_test_xml = path.suffix == '.xml' and 'surefire-reports' in path.parts
        is_private_output = path.suffix == '.log' or ('surefire-reports' in path.parts and not is_test_xml) or ('evidence' in path.name and path.suffix == '.json')
        scan(path.read_bytes(), label, label in sources, is_private_output)
        if is_test_xml:
            # Test names such as GenerationSettings legitimately contain the synthetic word "Settings".
            # Inspect captured stdout/stderr and failures for body leaks; secrets still scan the whole XML above.
            for node in ET.parse(path).getroot().iter():
                if node.tag in ['system-out', 'system-err', 'failure', 'error']:
                    scan(ET.tostring(node, encoding='utf-8'), label + ':' + node.tag, False, True)
artifacts = [name for name in tracked if re.search(r'(^|/)(target|bin|obj|\.runtime|\.verification|\.vs|TestResults)(/|$)|\.(log|jar|dll|exe|zip|trx)$', name)]
ignore_ok = all(subprocess.run(['git', '-C', str(root), 'check-ignore', '-q', name]).returncode == 0 for name in [
    '.runtime/client-token', '.verification/browser-batch-smoke-evidence.json', 'target/personal-ai-workspace-0.1.0.jar',
    'desktop/src/PersonalAiWorkspace.Desktop/bin/check.dll', 'desktop/src/PersonalAiWorkspace.Desktop/obj/check.json',
    'desktop/tests/PersonalAiWorkspace.Desktop.Tests/TestResults/check.trx'])
report = dict(result='PASS' if not matches and not artifacts and ignore_ok else 'FAIL', sourceFiles=len(sources),
              files=len(files), byteAndArchiveChecks=checks, archives=archives, actualNativeCredentials=len(native),
              ephemeralSecrets=len(payload.get('secrets', [])), matches=len(matches), trackedBuildArtifacts=len(artifacts), ignorePassed=ignore_ok)
print(json.dumps(report, indent=2))
if report['result'] != 'PASS':
    print(json.dumps(dict(matchedFiles=matches, artifacts=artifacts)))
    sys.exit(1)
