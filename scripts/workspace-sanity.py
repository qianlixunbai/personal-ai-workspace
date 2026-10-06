"""One isolated WPF/WebView2/React/Runtime/Pinyin flow, with no Ollama or release package.

Default: build its prerequisites. --no-build: reuse the current suite/build outputs.
Requires an interactive Windows desktop, WebView2, zh-CN Pinyin, Java 21 and .NET 10.
"""
import argparse
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
project = root / 'desktop/acceptance/PersonalAiWorkspace.WorkspaceSanity/PersonalAiWorkspace.WorkspaceSanity.csproj'
classpath = root / 'target/workspace-sanity-classpath.txt'
flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
stage = 'configuration'


def require(value, name):
    global stage
    stage = name
    if not value:
        raise RuntimeError(name)


def build(command, name):
    global stage
    stage = name
    result = subprocess.run(command, cwd=root, capture_output=True, creationflags=flags, timeout=180)
    require(result.returncode == 0, name)


def stop(process):
    if process is not None and process.poll() is None:
        # Only this Popen's owned PID and descendants; never process-name/port based killing.
        subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True,
                       creationflags=flags, timeout=15)
        process.wait(timeout=15)


def main():
    global stage
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--no-build', action='store_true')
    args = parser.parse_args()
    require(os.name == 'nt', 'interactive-windows-required')
    if not args.no_build:
        build(['cmd.exe', '/d', '/c', str(root / 'mvnw.cmd'), '-q', '-DskipTests', 'compile',
               'dependency:build-classpath', '-DincludeScope=runtime', '-Dmdep.outputFile=' + str(classpath)], 'runtime-classpath-build')
        build(['dotnet', 'build', str(project), '-c', 'Release'], 'workspace-sanity-build')
    exe = project.parent / 'bin/Release/net10.0-windows/PersonalAiWorkspace.WorkspaceSanity.exe'
    require(exe.is_file() and classpath.is_file(), 'compiled-prerequisites')
    resolved = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True,
                              text=True, creationflags=flags, timeout=15)
    home = re.search(r'^\s*java\.home = (.+)$', resolved.stderr, re.MULTILINE)
    require(home is not None, 'actual-jvm-resolution')
    java = Path(home.group(1).strip()) / 'bin/java.exe'
    require(java.is_file(), 'actual-jvm-resolution')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 8765))  # Refuse to reuse or stop an existing Runtime.
    # Reserve an unlistening loopback endpoint: no real Ollama can receive any request.
    with socket.socket() as offline, tempfile.TemporaryDirectory(prefix='workspace-t0-sanity-') as directory:
        offline.bind(('127.0.0.1', 0))
        temporary = Path(directory).resolve()
        require(temporary.parent == Path(tempfile.gettempdir()).resolve() and temporary.name.startswith('workspace-t0-sanity-'),
                'verified-test-owned-location')
        auth = temporary / 'auth/client-token'
        progress = temporary / 'progress.json'
        runtime = driver = None
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            stage = 'isolated-runtime-startup'
            with (temporary / 'runtime.log').open('wb') as log:
                runtime = subprocess.Popen([str(java), '-cp', str(root / 'target/classes') + os.pathsep + classpath.read_text().strip(),
                    'io.github.qianlixunbai.workspace.PersonalAiWorkspaceApplication', '--server.port=8765',
                    '--workspace.security.token-file=' + str(auth), '--workspace.data-directory=' + str(temporary / 'data'),
                    '--workspace.ollama.base-url=http://127.0.0.1:' + str(offline.getsockname()[1])], cwd=root,
                    stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            until = time.monotonic() + 40
            while True:
                require(runtime.poll() is None and time.monotonic() < until, 'isolated-runtime-startup')
                try:
                    with opener.open('http://127.0.0.1:8765/actuator/health/readiness', timeout=1) as response:
                        if response.status == 200 and auth.is_file():
                            break
                except OSError:
                    time.sleep(.1)
            unique = uuid.uuid4().hex
            env = dict(os.environ, T0_TOKEN_FILE=str(auth), T0_PROGRESS=str(progress),
                       T0_MEMORY_TITLE='T0-' + unique, T0_MEMORY_CONTENT='Synthetic saved note ' + unique,
                       T0_SOURCE_TITLE='T0-' + unique + '.txt')
            stage = 'real-windows-flow'
            with (temporary / 'driver.json').open('wb') as output:
                driver = subprocess.Popen([str(exe)], cwd=root, env=env, stdout=output,
                                          stderr=subprocess.STDOUT, creationflags=flags)
                driver.wait(timeout=150)
            report = json.loads((temporary / 'driver.json').read_text(encoding='utf-8'))
            require(driver.returncode == 0 and report.get('result') == 'PASS', report.get('check', 'windows-flow-failed'))
        except Exception:
            if progress.is_file():
                stage = json.loads(progress.read_text(encoding='utf-8'))['check']
            raise
        finally:
            stop(driver)
            stop(runtime)
        require(runtime.poll() is not None, 'owned-runtime-stopped')
    print(json.dumps(dict(report, ownedRuntimeStopped=True, temporaryFilesRemoved=True, inferenceRequired=False)))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'result': 'FAIL', 'check': stage, 'failureType': type(error).__name__}))
        raise SystemExit(1)
