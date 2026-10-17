"""Focused independent JVM observations; never starts Workspace/Spring/Desktop/models."""
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import queue
import struct
import subprocess
import sys
import threading
import uuid
import winreg
import zipfile

evidence = Path(sys.argv[1]).resolve()
artifact = Path(sys.argv[2]).resolve()
jar_hash = "b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0"
dll_hash = "5a7ff949f6d93d86491eb5b26b1cfc60051168a60622650224b89995ac420023"
entry = "com/sun/jna/win32-x86-64/jnidispatch.dll"
kernel = ctypes.WinDLL("kernel32", use_last_error=True)
advapi = ctypes.WinDLL("advapi32", use_last_error=True)
userenv = ctypes.WinDLL("userenv", use_last_error=True)
kernel.GetCurrentProcess.restype = wintypes.HANDLE
kernel.GetCurrentThread.restype = wintypes.HANDLE
advapi.OpenThreadToken.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.BOOL, ctypes.POINTER(wintypes.HANDLE)]
advapi.OpenProcessToken.argtypes = [wintypes.HANDLE, wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE)]
userenv.GetUserProfileDirectoryW.argtypes = [wintypes.HANDLE, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
kernel.CloseHandle.argtypes = [wintypes.HANDLE]
token = wintypes.HANDLE()
if not advapi.OpenThreadToken(kernel.GetCurrentThread(), 8, True, ctypes.byref(token)):
    if ctypes.get_last_error() != 1008 or not advapi.OpenProcessToken(kernel.GetCurrentProcess(), 8, ctypes.byref(token)):
        raise RuntimeError("TOKEN_API_FAILED")
try:
    size = wintypes.DWORD(32768)
    profile_buffer = ctypes.create_unicode_buffer(size.value)
    if not userenv.GetUserProfileDirectoryW(token, profile_buffer, ctypes.byref(size)):
        raise RuntimeError("PROFILE_API_FAILED")
    profile = Path(profile_buffer.value)
finally:
    kernel.CloseHandle(token)

fs = json.loads((evidence / "filesystem-results.json").read_text(encoding="utf-8-sig"))
assert fs["result"] == "PASS"
fixture = profile / (".personal-ai-workspace-native-bootstrap-fixture-" + fs["positiveFixtureId"])
native_directory = fixture / "jna-5.17.0-win32-x86-64"
native_file = native_directory / "jnidispatch.dll"
jar_bytes = artifact.read_bytes()
assert hashlib.sha256(jar_bytes).hexdigest() == jar_hash
with zipfile.ZipFile(artifact) as source:
    dll = source.read(entry)
assert len(dll) == 273408 and hashlib.sha256(dll).hexdigest() == dll_hash
assert native_file.read_bytes() == dll

# Fixed-JAR snapshot and decoys are only harness artifacts inside the new private fixture.
probe_root = fixture / ("jvm-probe-" + uuid.uuid4().hex)
probe_root.mkdir()  # must be new; never reuse an old failed probe
for directory in ("classes", "system-decoy", "invalid", "temp", "classpath-file"):
    (probe_root / directory).mkdir()
jar = probe_root / "jna-5.17.0.jar"
jar.write_bytes(jar_bytes)
(probe_root / "system-decoy/jnidispatch.dll").write_bytes(dll)
(probe_root / "invalid/jnidispatch.dll").write_bytes(b"FIXTURE_INVALID_PE_NO_EXECUTABLE_CODE")
resource = probe_root / "classpath-file" / entry
resource.parent.mkdir(parents=True)
resource.write_bytes(dll)

for variable in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
    if os.environ.get(variable):
        raise RuntimeError("EXTERNAL_JVM_OPTIONS_PRESENT")
settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True, timeout=20, check=True)
java_home = next(line.split("=", 1)[1].strip() for line in settings.stderr.splitlines() if line.strip().startswith("java.home ="))
java = Path(java_home) / "bin/java.exe"
javac = Path(java_home) / "bin/javac.exe"
compiled = subprocess.run([str(javac), "-d", str(probe_root / "classes"), str(Path(__file__).with_name("TrustedNativeProbe.java"))],
                          capture_output=True, timeout=30)
assert compiled.returncode == 0, "FIXTURE_COMPILATION_FAILED"

kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
kernel.OpenProcess.restype = wintypes.HANDLE
kernel.K32EnumProcessModules.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.HMODULE), wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
kernel.K32GetModuleFileNameExW.argtypes = [wintypes.HANDLE, wintypes.HMODULE, wintypes.LPWSTR, wintypes.DWORD]
kernel.GetSystemDirectoryW.argtypes = [wintypes.LPWSTR, wintypes.UINT]
system_buffer = ctypes.create_unicode_buffer(32768)
assert kernel.GetSystemDirectoryW(system_buffer, len(system_buffer))
system32 = Path(system_buffer.value)

def modules(pid):
    handle = kernel.OpenProcess(0x410, False, pid)
    if not handle:
        raise RuntimeError("MODULE_QUERY_FAILED")
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
            result.append(file)
        return result
    finally:
        kernel.CloseHandle(handle)

def imports(data):
    pe = struct.unpack_from("<I", data, 0x3c)[0]
    section_count = struct.unpack_from("<H", data, pe + 6)[0]
    optional_size = struct.unpack_from("<H", data, pe + 20)[0]
    assert struct.unpack_from("<H", data, pe + 24)[0] == 0x20b
    table = pe + 24 + optional_size
    sections = [struct.unpack_from("<IIII", data, table + i * 40 + 8) for i in range(section_count)]
    def offset(rva):
        for virtual_size, address, size, raw in sections:
            if address <= rva < address + max(virtual_size, size):
                return raw + rva - address
        raise RuntimeError("UNMAPPED_PE_RVA")
    import_rva = struct.unpack_from("<I", data, pe + 24 + 112 + 8)[0]
    delay_rva = struct.unpack_from("<I", data, pe + 24 + 112 + 13 * 8)[0]
    position = offset(import_rva)
    names = []
    while any(data[position:position + 20]):
        name = offset(struct.unpack_from("<I", data, position + 12)[0])
        names.append(data[name:data.index(0, name)].decode("ascii").lower())
        position += 20
    return names, delay_rva

import_names, delay_rva = imports(dll)
assert set(import_names) == {"kernel32.dll", "psapi.dll"} and delay_rva == 0
known_dlls = set()
with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SYSTEM\CurrentControlSet\Control\Session Manager\KnownDLLs") as key:
    index = 0
    while True:
        try:
            _, value, kind = winreg.EnumValue(key, index)
        except OSError:
            break
        if kind == winreg.REG_SZ and value.lower().endswith(".dll"):
            known_dlls.add(value.lower())
        index += 1

controls = {"jna.boot.library.name": "jnidispatch", "jna.nosys": "true", "jna.noclasspath": "true", "jna.nounpack": "true"}
cases = [("VALID_BOOT", native_directory, True), ("MISSING_BOOT", probe_root / "missing", False),
         ("INVALID_BOOT", probe_root / "invalid", False)]
rows = []
for name, boot, expected_load in cases:
    props = {**controls, "jna.boot.library.path": str(boot), "java.library.path": str(probe_root / "system-decoy"),
             "jna.tmpdir": str(probe_root / "temp"), "java.io.tmpdir": str(probe_root / "temp")}
    classpath = os.pathsep.join(map(str, [probe_root / "classes", jar, probe_root / "classpath-file"]))
    process = subprocess.Popen([str(java), *["-D" + key + "=" + value for key, value in props.items()],
                                "-cp", classpath, "TrustedNativeProbe"], cwd=probe_root / "system-decoy",
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    lines = queue.Queue()
    def pump(stream):
        for line in stream:
            lines.put(line.strip())
        lines.put("EOF")
    reader = threading.Thread(target=pump, args=(process.stdout,), daemon=True)
    reader.start()
    try:
        assert lines.get(timeout=20) == "PRELOAD_READY"
        before = modules(process.pid)
        process.stdin.write("1")
        process.stdin.flush()
        outcome = lines.get(timeout=20)
        assert lines.get(timeout=20) == "READY"
        after = modules(process.pid)
        dispatch = [path for path in after if path.name.lower().startswith(("jna", "jnidispatch")) and path.suffix.lower() == ".dll"]
        if expected_load:
            assert outcome == "LOADED" and dispatch == [native_file]
            assert hashlib.sha256(dispatch[0].read_bytes()).hexdigest() == dll_hash
        else:
            assert outcome == "STOP" and not dispatch
        dependencies = []
        for dependency in import_names:
            actual = [path for path in after if path.name.lower() == dependency]
            if (expected_load and actual != [system32 / dependency]) or any(path != system32 / dependency for path in actual):
                diagnostic = {"case": name, "dependency": dependency, "actualCount": len(actual),
                              "roles": ["SYSTEM32" if path.parent == system32 else "OUTSIDE_SYSTEM32" for path in actual],
                              "knownDll": dependency in known_dlls, "modules": [path.name for path in after]}
                (evidence / "dependency-observation-blocker.json").write_text(json.dumps(diagnostic, indent=2), encoding="utf-8")
                raise RuntimeError("DEPENDENCY_PATH_UNPROVEN")
            dependencies.append({"name": dependency, "pathRole": "SYSTEM32" if actual else "NOT_LOADED",
                                 "preloadedByJvm": any(path.name.lower() == dependency for path in before),
                                 "knownDll": dependency in known_dlls})
        process.stdin.write("2")
        process.stdin.flush()
        process.stdin.close()
        code = process.wait(timeout=20)
        assert code == (0 if expected_load else 10)
        assert not list((probe_root / "temp").iterdir()), "UNEXPECTED_UNPACK_OR_CLEANUP"
        rows.append({"case": name, "result": "PASS", "actual": outcome, "exit": code,
                     "dispatchModule": "VERIFIED_PRIVATE_NATIVE" if dispatch else "NONE",
                     "dependencies": dependencies, "validSystemAndClasspathDecoys": True, "tempUnchanged": True})
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)

result = {"result": "PASS", "java": next(line.strip() for line in settings.stderr.splitlines() if "java.version =" in line),
          "jarSha256": jar_hash, "dllSha256": dll_hash, "imports": import_names, "delayImports": False,
          "cases": rows, "parentDllSearchPolicyChanged": False,
          "dependencySafety": "observed System32 imports; fixed fresh-JVM entry not yet production-enforced"}
(evidence / "jvm-results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print("FOCUSED_JVM_LOADER_PASS")
