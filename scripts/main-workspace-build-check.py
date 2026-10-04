"""Build/distribution gates; corrupts only generated manifest inside a restore guard."""
from pathlib import Path
import json
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
project = "desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj"
manifest = root / "desktop/frontend/dist/workspace-assets.json"
checks = []


def run(arguments, success, text=None):
    result = subprocess.run(arguments, cwd=root, capture_output=True, timeout=120)
    output = (result.stdout + result.stderr).decode('utf-8', errors='replace')
    if (result.returncode == 0) != success or text and text not in output:
        raise RuntimeError('build-gate')


def main():
    build = ['dotnet', 'build', project, '-c', 'Release', '-p:FrontendSkipBuild=true']
    run(build + ['-p:MainWorkspaceDev=true'], False, 'MainWorkspaceDev is Debug-only')
    checks.append('release-rejects-development-loading')
    original = manifest.read_bytes()
    try:
        manifest.unlink()
        run(build, False, 'Main Workspace assets missing')
        checks.append('missing-production-assets-fail-build')
        changed = json.loads(original)
        changed['bridgeVersion'] = 999
        manifest.write_text(json.dumps(changed), encoding='utf-8')
        run(build, False, 'Main Workspace assets missing, modified or incompatible')
        checks.append('incompatible-assets-fail-build')
    finally:
        manifest.write_bytes(original)
    run(['dotnet', 'build', project, '-c', 'Debug', '-p:FrontendSkipBuild=true', '-p:MainWorkspaceDev=true'], True)
    checks.append('explicit-debug-development-mode-compiles')
    # Default pipeline rebuilds with npm ci, and restores the production artifacts after all negative gates.
    run(['dotnet', 'publish', project, '-c', 'Release', '-o', '.verification/m5a-desktop-publish'], True)
    checks.append('default-npm-ci-build-and-desktop-publish')
    dll = (root / '.verification/m5a-desktop-publish/PersonalAiWorkspace.Desktop.dll').read_bytes()
    if 'http://127.0.0.1:5173'.encode('utf-16-le') in dll:
        raise RuntimeError('development-origin-in-release')
    checks.append('development-origin-absent-from-release-assembly')
    assets = root / '.verification/m5a-desktop-publish/MainWorkspace'
    if not (assets / 'index.html').is_file() or not (assets / 'workspace-assets.json').is_file():
        raise RuntimeError('published-assets-missing')
    packaged = json.loads((assets / 'workspace-assets.json').read_text())
    if not all((assets / name).is_file() for name in packaged['files']):
        raise RuntimeError('published-assets-incomplete')
    checks.append('published-html-js-css-manifest-complete')
    report = dict(result='PASS', checks=checks)
    (root / '.verification/m5a-build-evidence.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps(dict(result='FAIL', check=str(error) if type(error) is RuntimeError else 'build-check-unavailable')))
        sys.exit(1)
