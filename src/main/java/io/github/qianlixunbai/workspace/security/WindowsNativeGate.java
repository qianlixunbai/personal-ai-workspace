package io.github.qianlixunbai.workspace.security;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Development-only admission. The reviewed local JVM/.NET/build remain the initial trust
 * assumption; these pinned identities are not publisher authentication. No production fallback. */
public final class WindowsNativeGate {
    public static final String DLL_HASH = "5a7ff949f6d93d86491eb5b26b1cfc60051168a60622650224b89995ac420023";
    private static final String NAME = "PersonalAiWorkspace.NativeBootstrap";
    private static final Map<String, String> BOOTSTRAP = Map.of(
        ".exe", "0e7fc8765413aff6568b788023e705897721c1cb3365b7996b75a3188edfcbe4",
        ".dll", "e434a3653f23774dc5b1a98b253ed413504d4e91db137aab14f41a5464deb50d",
        ".deps.json", "27af3a8da9f3b6155d86236c59339053f077e3ad33724e8f5e40281fb0375885",
        ".runtimeconfig.json", "3e965cd7cfd553c2ff4e842d8d8019aaae2da0a21d92ff85a87573dd8d1b95c6");
    private static Path admittedRoot;
    private static FileChannel owningJarPin;
    private static Object owningJarKey;
    private static Path owningJar;
    private static String owningJarHash;
    private static Class<?> isolatedClass;
    private static final String ISOLATED_CLASS = "io.github.qianlixunbai.workspace.provider.ollama.V1IsolatedOllamaInstance";
    private static final String BUILD_MARKER = "META-INF/workspace-runtime.properties";
    private WindowsNativeGate() {}

    public static synchronized void admit() throws IOException {
        if (!isWindowsPlatform() || admittedRoot != null) return;
        try {
            if (!WindowsPrivateAcl.isWindows() || !"amd64".equals(System.getProperty("os.arch"))
                    || Runtime.version().feature() != 21)
                throw new IOException();
            if (System.getProperty("jnidispatch.path") != null) throw new IOException();
            rejectOverrides();
            String runId = System.getProperty("workspace.native.development-profile", "");
            if (!runId.matches("[0-9a-f]{32}")) throw new IOException();
            Path helper = localPath(System.getProperty("workspace.native.bootstrap", ""));
            if (!helper.getFileName().toString().equals(NAME + ".exe")) throw new IOException();
            // Pin the entire framework-dependent payload, not only the apphost.
            for (var entry : BOOTSTRAP.entrySet())
                checkHash(helper.resolveSibling(NAME + entry.getKey()), entry.getValue());
            Admission before = verify(helper, runId);
            require("jna.boot.library.name", "jnidispatch");
            require("jna.boot.library.path", before.dll.getParent().toString());
            for (String key : List.of("jna.nosys", "jna.noclasspath", "jna.nounpack")) require(key, "true");
            // Resource identity is checked without initializing any JNA class.
            try (var resource = WindowsNativeGate.class.getClassLoader().getResourceAsStream(
                    "com/sun/jna/win32-x86-64/jnidispatch.dll")) {
                if (resource == null || !hash(resource.readNBytes(273409)).equals(DLL_HASH)) throw new IOException();
            }
            // A READ_DATA handle excludes write/delete between verification and the actual load.
            // The same-SID/admin/kernel threat remains outside the reviewed account boundary.
            try (var pin = FileChannel.open(before.dll, StandardOpenOption.READ,
                    ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)) {
                checkHash(before.dll, DLL_HASH);
                if (!before.equals(verify(helper, runId))) throw new IOException();
                LoadedDispatch.verify(before.dll, before.identity);
                if (!before.equals(verify(helper, runId))) throw new IOException();
            }
            WindowsPrivateAcl.holdDevelopmentProfile(before.root);
            admittedRoot = before.root;
        } catch (Exception | LinkageError failure) {
            // No native messages, paths, account names, child output or credentials in diagnostics.
            throw new IOException("Windows native admission STOP (Development-only)");
        }
    }

    public static synchronized Path profileRoot() throws IOException {
        admit();
        if (admittedRoot == null) throw new IOException("Development Profile required");
        return admittedRoot;
    }

    /** Absence grants no exception. Presence always requires the original real Profile gate first. */
    public static synchronized Class<?> isolatedDevelopmentClass() throws IOException {
        ClassLoader loader = WindowsNativeGate.class.getClassLoader();
        var classes = Collections.list(loader.getResources(ISOLATED_CLASS.replace('.', '/') + ".class"));
        var markers = Collections.list(loader.getResources(BUILD_MARKER));
        if (classes.isEmpty() && markers.isEmpty()) return null;
        if (markers.size() != 1 || classes.size() > 1) throw new IOException("Runtime build identity STOP");
        Properties marker;
        try (var stream = markers.getFirst().openStream()) { marker = readBuildMarker(stream); }
        if (!"WORKSPACE_RUNTIME_V1".equals(marker.getProperty("format"))) throw new IOException("Runtime build identity STOP");
        if (classes.isEmpty()) {
            if (!"default".equals(marker.getProperty("kind"))) throw new IOException("Runtime build identity STOP");
            return null;
        }
        admit(); // Never use a marker, fixture or caller-supplied expected digest to skip NativeGate.
        if (owningJarPin != null) {
            if (!Objects.equals(owningJarKey, Files.readAttributes(owningJar,
                    java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())
                    || !owningJarPin.isOpen() || owningJarHash == null)
                throw new IOException("Runtime owning JAR changed");
            return isolatedClass;
        }
        if (!"v1-isolated-development".equals(marker.getProperty("kind"))
                || marker.size() != 10 || !marker.getProperty("sourceSha", "").matches("[a-f0-9]{64}")
                || !marker.getProperty("contractSha", "").matches("[a-f0-9]{64}")) throw new IOException("Runtime build identity STOP");
        FileChannel pin = null;
        try {
            Class<?> dev = Class.forName(ISOLATED_CLASS, false, loader);
            if (!loader.getClass().getName().equals("org.springframework.boot.loader.launch.LaunchedClassLoader")) throw new IOException();
            dev.getMethod("verifyMarker", Properties.class).invoke(null, marker);
            List<Class<?>> owners = List.of(dev, WindowsNativeGate.class,
                Class.forName("io.github.qianlixunbai.workspace.provider.ollama.OllamaProvider", false, loader),
                Class.forName("io.github.qianlixunbai.workspace.provider.ollama.OllamaVisionEnvironment", false, loader));
            Path jar = jarContainer(dev.getProtectionDomain().getCodeSource().getLocation().toString());
            for (Path parent = jar; parent != null; parent = parent.getParent())
                if (!parent.equals(parent.toRealPath()) || Files.isSymbolicLink(parent)) throw new IOException();
            var before = Files.readAttributes(jar, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.fileKey() == null) throw new IOException();
            pin = FileChannel.open(jar, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE);
            Map<Class<?>, String> keys = Map.of(dev,"classSha",WindowsNativeGate.class,"gateSha",owners.get(2),"providerSha",owners.get(3),"environmentSha");
            try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
                Set<String> names = new HashSet<>();
                for (var entry : Collections.list(zip.entries()))
                    if (!names.add(entry.getName().toLowerCase(Locale.ROOT)) || entry.getName().contains("\\")
                            || entry.getName().contains("/../") || entry.getName().contains("//")) throw new IOException();
                for (Class<?> owner : owners) {
                    String resource = owner.getName().replace('.', '/') + ".class";
                    var matches = Collections.list(loader.getResources(resource));
                    if (owner.getClassLoader() != loader || matches.size() != 1
                            || !jar.equals(jarContainer(owner.getProtectionDomain().getCodeSource().getLocation().toString()))
                            || !jar.equals(jarContainer(matches.getFirst().toString()))) throw new IOException();
                    var entry = zip.getEntry("BOOT-INF/classes/" + resource);
                    if (entry == null || entry.getSize() < 1 || entry.getSize() > 1024 * 1024) throw new IOException();
                    try (var actual = zip.getInputStream(entry); var loaded = matches.getFirst().openStream()) {
                        byte[] bytes = actual.readNBytes(1024 * 1024 + 1);
                        if (!Arrays.equals(bytes, loaded.readNBytes(1024 * 1024 + 1))
                                || !hash(bytes).equals(marker.getProperty(keys.get(owner)))) throw new IOException();
                    }
                }
                if (!jar.equals(jarContainer(markers.getFirst().toString()))) throw new IOException();
                var entry = zip.getEntry(BUILD_MARKER);
                if (entry == null || entry.getSize() > 16384) throw new IOException();
                try (var stream = zip.getInputStream(entry)) {
                    Properties actual = readBuildMarker(stream);
                    if (!marker.equals(actual)) throw new IOException();
                }
            }
            String fullHash = hashChannel(pin);
            if (!Objects.equals(before.fileKey(), Files.readAttributes(jar,
                    java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())) throw new IOException();
            owningJar = jar; owningJarKey = before.fileKey(); owningJarHash = fullHash; owningJarPin = pin; isolatedClass = dev;
            pin = null;
            return dev;
        } catch (Exception | LinkageError failure) {
            throw new IOException("Runtime owning JAR STOP");
        } finally { if (pin != null) pin.close(); }
    }
    private static Properties readBuildMarker(java.io.InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(16385);
        if (bytes.length > 16384) throw new IOException("Runtime marker STOP");
        Properties marker = new Properties();
        for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\n", -1)) {
            if (line.isEmpty()) continue;
            if (!line.matches("[a-z][a-zA-Z]*=[A-Za-z0-9_.:/-]+")) throw new IOException("Runtime marker STOP");
            int separator = line.indexOf('=');
            if (marker.put(line.substring(0, separator), line.substring(separator + 1)) != null) throw new IOException("Runtime marker STOP");
        }
        return marker;
    }
    private static Path jarContainer(String url) throws IOException {
        // Boot 4 nested URLs use jar:nested:/...jar/!BOOT-INF/classes/!/; JDK uses jar:file:...!/.
        String value = url;
        if (value.startsWith("jar:")) value = value.substring(4);
        if (value.startsWith("nested:")) value = "file:" + value.substring(7);
        int boundary = value.indexOf("/!");
        if (boundary < 0) boundary = value.indexOf("!/");
        if (boundary >= 0) value = value.substring(0, boundary);
        try {
            Path path = Path.of(java.net.URI.create(value)).toAbsolutePath().normalize();
            if (!path.toString().endsWith(".jar") || !path.equals(path.toRealPath())) throw new IOException();
            return path;
        } catch (IllegalArgumentException bad) { throw new IOException("Runtime owning JAR STOP"); }
    }
    private static String hashChannel(FileChannel channel) throws IOException {
        try {
            var sha = MessageDigest.getInstance("SHA-256"); var buffer = java.nio.ByteBuffer.allocate(65536);
            long position = 0; int n;
            while ((n = channel.read(buffer, position)) != -1) { position += n; buffer.flip(); sha.update(buffer); buffer.clear(); }
            return HexFormat.of().formatHex(sha.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(); }
    }

    /** Exact Profile runtime arguments. Called before Spring; no arbitrary state/port overrides. */
    public static void requireRuntimeArguments(String[] args) throws IOException {
        if (!isWindowsPlatform()) return;
        Path root = profileRoot();
        List<String> expected = List.of("--server.address=127.0.0.1", "--server.port=18765",
            "--workspace.security.token-file=" + root.resolve("credentials/client-token"),
            "--workspace.data-directory=" + root.resolve("data"),
            "--workspace.model-state-directory=" + root.resolve("model-state"));
        if (!Arrays.asList(args).equals(expected)) throw new IOException("Development Profile arguments STOP");
    }

    private static boolean isWindowsPlatform() throws IOException {
        if (System.getProperty("java.nio.file.spi.DefaultFileSystemProvider") != null)
            throw new IOException("Windows native admission STOP (Development-only)");
        // os.name is caller-overridable. The JDK's actual filesystem must still enforce the gate.
        return FileSystems.getDefault().getClass().getName().equals("sun.nio.fs.WindowsFileSystem");
    }

    private static void rejectOverrides() throws IOException {
        for (String name : System.getenv().keySet()) {
            String upper = name.toUpperCase(Locale.ROOT);
            if (Set.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").contains(upper)
                    || upper.startsWith("DOTNET_") || upper.startsWith("COREHOST_")
                    || upper.startsWith("COMPLUS_") || upper.startsWith("COR_")
                    || upper.startsWith("SPRING_") || upper.startsWith("WORKSPACE_")
                    || upper.startsWith("SERVER_")) throw new IOException();
        }
        for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments())
            if (option.startsWith("-javaagent") || option.startsWith("-agent")
                    || option.startsWith("-Xbootclasspath") || option.startsWith("--patch-module")) throw new IOException();
        for (String key : System.getProperties().stringPropertyNames())
            if (key.startsWith("spring.") || key.startsWith("server.")
                    || key.equals("java.system.class.loader")
                    || key.startsWith("workspace.") && !Set.of("workspace.native.bootstrap",
                        "workspace.native.development-profile").contains(key)) throw new IOException();
    }

    private record Admission(Path root, Path dll, String identity) {}

    private static Admission verify(Path helper, String runId) throws Exception {
        Process child = new ProcessBuilder(helper.toString(), "VerifyProfileNative", runId)
            .directory(helper.getParent().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        child.getOutputStream().close();
        try {
            // The reviewed helper emits one bounded line. A full pipe times out, never hangs admission.
            if (!child.waitFor(15, TimeUnit.SECONDS) || child.exitValue() != 0) throw new IOException();
            byte[] bytes = child.getInputStream().readNBytes(16385);
            if (bytes.length > 16384) throw new IOException();
            String[] fields = new String(bytes, StandardCharsets.UTF_8).strip().split("\t", -1);
            if (fields.length != 7 || !fields[0].equals("WINDOWS_PROFILE_V2")
                    || !fields[1].equals("DEVELOPMENT_ONLY") || !fields[2].equals(runId)
                    || !fields[5].matches("[0-9a-f]{16}:[0-9a-f]{32}") || !fields[6].equals(DLL_HASH))
                throw new IOException();
            Path root = localPath(new String(Base64.getDecoder().decode(fields[3]), StandardCharsets.UTF_8));
            Path dll = localPath(new String(Base64.getDecoder().decode(fields[4]), StandardCharsets.UTF_8));
            if (!root.getFileName().toString().equals(runId)
                    || !root.getParent().getFileName().toString().equals("Profiles")
                    || !root.getParent().getParent().getFileName().toString().equals("PersonalAIWorkspace.Development")
                    || !dll.equals(root.resolve("jna-5.17.0-win32-x86-64/jnidispatch.dll"))) throw new IOException();
            return new Admission(root, dll, fields[5]);
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
            child.getInputStream().close();
        }
    }

    private static Path localPath(String text) throws IOException {
        if (!text.matches("[A-Za-z]:\\\\.*") || text.length() > 30000) throw new IOException();
        Path path = Path.of(text);
        if (!path.equals(path.toAbsolutePath().normalize()) || !path.equals(path.toRealPath())) throw new IOException();
        return path;
    }
    private static void require(String key, String value) throws IOException {
        if (!value.equals(System.getProperty(key))) throw new IOException();
    }
    private static void checkHash(Path path, String expected) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 8 * 1024 * 1024
                || !hash(Files.readAllBytes(path)).equals(expected)) throw new IOException();
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // Initialized only after the JDK-only admission above. Inspect the OS module, not a PASS flag.
    private static final class LoadedDispatch {
        interface Modules extends com.sun.jna.win32.StdCallLibrary {
            boolean K32EnumProcessModules(com.sun.jna.platform.win32.WinNT.HANDLE process,
                com.sun.jna.Pointer[] modules, int bytes, com.sun.jna.ptr.IntByReference needed);
            int GetModuleFileNameW(com.sun.jna.Pointer module, char[] path, int size);
        }
        static void verify(Path expected, String expectedIdentity) throws Exception {
            if (com.sun.jna.Native.POINTER_SIZE != 8 || !com.sun.jna.Native.VERSION.equals("5.17.0"))
                throw new IOException();
            var kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE;
            Modules modules = com.sun.jna.Native.load("kernel32", Modules.class);
            var handles = new com.sun.jna.Pointer[1024];
            var needed = new com.sun.jna.ptr.IntByReference();
            if (!modules.K32EnumProcessModules(kernel.GetCurrentProcess(), handles, handles.length * 8, needed)
                    || needed.getValue() > handles.length * 8 || needed.getValue() % 8 != 0) throw new IOException();
            int dispatchCount = 0;
            for (int i = 0; i < needed.getValue() / 8; i++) {
                char[] buffer = new char[32768];
                int length = modules.GetModuleFileNameW(handles[i], buffer, buffer.length);
                if (length == 0 || length >= buffer.length) throw new IOException();
                Path actual = Path.of(new String(buffer, 0, length));
                String name = actual.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.equals("jnidispatch.dll") || name.startsWith("jna") && name.endsWith(".dll")) {
                    if (!actual.equals(expected)) throw new IOException();
                    dispatchCount++;
                }
            }
            if (dispatchCount != 1 || !expected.toString().equals(System.getProperty("jnidispatch.path")))
                throw new IOException();
            var handle = kernel.CreateFile(expected.toString(), 0x20081, 1, null, 3, 0x00200000, null);
            if (com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw new IOException();
            try {
                var id = new com.sun.jna.platform.win32.WinBase.FILE_ID_INFO();
                if (!kernel.GetFileInformationByHandleEx(handle, 18, id.getPointer(),
                        new com.sun.jna.platform.win32.WinDef.DWORD(id.size()))) throw new IOException();
                var p = id.getPointer();
                String identity = String.format(Locale.ROOT, "%016x:%016x%016x", p.getLong(0), p.getLong(8), p.getLong(16));
                if (!identity.equals(expectedIdentity)) throw new IOException();
                checkHash(expected, DLL_HASH);
            } finally { if (!kernel.CloseHandle(handle)) throw new IOException(); }
        }
    }
}
