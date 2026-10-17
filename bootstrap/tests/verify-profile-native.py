"""One integrated Windows admission flow; no Workspace/Spring/HTTP/Desktop/Chrome/model launch."""
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import uuid

evidence = Path(sys.argv[1]).resolve()
tools = json.loads((evidence / 'tools.local.json').read_text(encoding='utf-8-sig'))
state = json.loads((evidence / 'test-profile.local.json').read_text(encoding='utf-8-sig'))
repo = Path(__file__).resolve().parents[2]
native = Path(state['Root']) / 'jna-5.17.0-win32-x86-64'
expected = native / 'jnidispatch.dll'
dll_hash = '5a7ff949f6d93d86491eb5b26b1cfc60051168a60622650224b89995ac420023'
jar = next(Path(p) for p in tools['classpath'].split(';') if Path(p).name == 'jna-5.17.0.jar')

kernel = ctypes.WinDLL('kernel32', use_last_error=True)
kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
kernel.OpenProcess.restype = wintypes.HANDLE
kernel.CloseHandle.argtypes = [wintypes.HANDLE]
kernel.K32EnumProcessModules.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.HMODULE), wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
kernel.K32GetModuleFileNameExW.argtypes = [wintypes.HANDLE, wintypes.HMODULE, wintypes.LPWSTR, wintypes.DWORD]

def dispatch_modules(pid):
    handle = kernel.OpenProcess(0x410, False, pid)
    assert handle, 'MODULE_QUERY_FAILED'
    try:
        buffer = (wintypes.HMODULE * 2048)()
        used = wintypes.DWORD()
        assert kernel.K32EnumProcessModules(handle, buffer, ctypes.sizeof(buffer), ctypes.byref(used))
        assert used.value <= ctypes.sizeof(buffer)
        result = []
        for module in buffer[:used.value // ctypes.sizeof(wintypes.HMODULE)]:
            path = ctypes.create_unicode_buffer(32768)
            assert kernel.K32GetModuleFileNameExW(handle, module, path, len(path))
            file = Path(path.value)
            if file.name.lower().startswith(('jna', 'jnidispatch')) and file.suffix.lower() == '.dll':
                result.append(file)
        return result
    finally:
        kernel.CloseHandle(handle)

def new_corrupt_fixture():
    run = uuid.uuid4().hex
    prepared = subprocess.run([tools['pwsh'], '-NoProfile', '-File', str(repo / 'scripts/verification/mmf3-profile-state.ps1'),
        '-Mode', 'Prepare', '-RunId', run, '-OptIn'], capture_output=True, text=True, timeout=20, check=True)
    fixture = json.loads(prepared.stdout)
    (evidence / 'corrupt-profile.local.json').write_text(json.dumps(fixture, indent=2), encoding='utf-8')
    provision = subprocess.run([tools['helper'], 'ProvisionProfileNative', run, str(jar)], capture_output=True, text=True, timeout=20, check=True)
    assert json.loads(provision.stdout)['result'] == 'PASS'
    dll = Path(fixture['Root']) / 'jna-5.17.0-win32-x86-64/jnidispatch.dll'
    data = bytearray(dll.read_bytes())
    data[-1] ^= 1
    dll.write_bytes(data)  # Only this new negative fixture; leave failure evidence, never repair it.
    return run, dll.parent

# A valid DLL in system-search/cwd is a decoy. The gate must refuse fallback before JNA loads.
decoy = Path(state['Evidence']) / ('decoy-' + uuid.uuid4().hex)
decoy.mkdir()
(decoy / 'jnidispatch.dll').write_bytes(expected.read_bytes())
fake = Path(state['Evidence']) / ('fake-helper-' + uuid.uuid4().hex)
fake.mkdir()
(fake / 'PersonalAiWorkspace.NativeBootstrap.exe').write_bytes(b'NON_EXECUTABLE_FIXTURE')
controls = {'workspace.native.development-profile': state['RunId'], 'workspace.native.bootstrap': tools['helper'],
    'jna.boot.library.name': 'jnidispatch', 'jna.boot.library.path': str(native),
    'jna.nosys': 'true', 'jna.noclasspath': 'true', 'jna.nounpack': 'true',
    'java.library.path': str(decoy), 'java.io.tmpdir': state['Evidence'], 'jna.tmpdir': state['Evidence']}
corrupt_run, corrupt_native = new_corrupt_fixture()
cases = [
    ('VALID', {}, {}, True),
    ('FALLBACK_ENABLED', {'jna.nosys': 'false'}, {}, False),
    ('MISSING_PROFILE', {'workspace.native.development-profile': uuid.uuid4().hex}, {}, False),
    ('CORRUPT_DLL', {'workspace.native.development-profile': corrupt_run, 'jna.boot.library.path': str(corrupt_native)}, {}, False),
    ('UNREVIEWED_HELPER', {'workspace.native.bootstrap': str(fake / 'PersonalAiWorkspace.NativeBootstrap.exe')}, {}, False),
    ('EXTERNAL_JVM_OPTIONS', {}, {'JAVA_TOOL_OPTIONS': '-Dmmf3.synthetic=true'}, False),
    ('PRELOADED_MARKER', {'jnidispatch.path': str(expected)}, {}, False),
    ('SPOOFED_OS_NAME', {'os.name': 'Linux'}, {}, False),
]
rows = []
for name, changes, environment, success in cases:
    properties = {**controls, **changes}
    java = Path(tools['javaHome']) / 'bin/java.exe'
    command = [str(java), *['-D' + k + '=' + v for k, v in properties.items()], '-cp',
        str(evidence) + ';' + tools['classpath'], 'ProfileNativeGateProbe']
    process = subprocess.Popen(command, cwd=decoy, env={**os.environ, **environment},
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    lines = queue.Queue()
    def pump(stream):
        for line in stream:
            lines.put(line.strip())
        lines.put('EOF')
    threading.Thread(target=pump, args=(process.stdout,), daemon=True).start()
    try:
        assert lines.get(timeout=20) == 'PRELOAD_READY'
        assert not dispatch_modules(process.pid), 'JNA_LOADED_BEFORE_GATE'
        process.stdin.write('1'); process.stdin.flush()
        outcome = lines.get(timeout=30)
        assert lines.get(timeout=20) == 'READY'
        modules = dispatch_modules(process.pid)
        if success:
            assert outcome == 'ADMITTED_DEVELOPMENT_ONLY' and modules == [expected], (name, outcome, len(modules))
            assert hashlib.sha256(modules[0].read_bytes()).hexdigest() == dll_hash
        else:
            assert outcome == 'STOP' and not modules, (name, outcome, len(modules))
        process.stdin.write('2'); process.stdin.flush(); process.stdin.close()
        code = process.wait(timeout=20)
        assert code == (0 if success else 10)
        rows.append({'case': name, 'result': 'PASS', 'outcome': outcome,
            'dispatch': 'PRIVATE_PROFILE_NATIVE' if modules else 'NONE', 'exit': code})
        print(name + '_PASS', flush=True)
    finally:
        if process.poll() is None:
            process.kill(); process.wait(timeout=10)

(evidence / 'native-gate-results.json').write_text(json.dumps({'result': 'PASS', 'trust': 'Development-only',
    'cases': rows, 'runtimeStarted': False, 'modelRequests': 0}, indent=2), encoding='utf-8')
