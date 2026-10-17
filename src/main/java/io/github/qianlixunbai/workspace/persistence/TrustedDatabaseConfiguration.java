package io.github.qianlixunbai.workspace.persistence;

import com.sun.nio.file.ExtendedOpenOption;
import io.github.qianlixunbai.workspace.memory.PrivateMemoryDirectory;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import org.springframework.core.env.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Device-local configuration, never part of a portable backup. No caller-supplied path or fallback. */
public final class TrustedDatabaseConfiguration {
    private static final String NAME = "trustedWindowsDatabase";
    private static final int LIMIT = 16384;
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private TrustedDatabaseConfiguration() {}

    public enum Reason { POSTGRESQL_NOT_CONFIGURED, CONFIGURATION_UNTRUSTED, CREDENTIAL_UNAVAILABLE,
        CONNECTION_FAILED, AUTHENTICATION_FAILED, SCHEMA_INITIALIZATION_REQUIRED, SCHEMA_INCOMPATIBLE, ROLE_REJECTED }
    public static final class Failure extends IOException {
        private final Reason reason;
        Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }

    /** Admission must succeed before examining even the existence of the configuration file. */
    public static PropertySource<?> load() throws IOException {
        Path root = WindowsNativeGate.profileRoot();
        Path data = root.resolve("data"), file = data.resolve("database.dpapi");
        byte[] encrypted = null, plaintext = null;
        try {
            verifyDirectory(root); verifyDirectory(data);
            PrivateMemoryDirectory.validateLocation(data, root.resolve("credentials/client-token"));
            Object rootKey = key(root, true), dataKey = key(data, true);
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return new Source(null);
            PrivateMemoryDirectory.noLinks(file);
            Object fileKey = key(file, false);
            ByteBuffer buffer = ByteBuffer.allocate(LIMIT + 1);
            try (var pin = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                    ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)) {
                WindowsPrivateAcl.verify(file, false);
                while (buffer.hasRemaining() && pin.read(buffer) != -1) { }
                if (buffer.position() < 1 || buffer.position() > LIMIT) throw new IOException();
                verifyDirectory(root); verifyDirectory(data); WindowsPrivateAcl.verify(file, false);
                PrivateMemoryDirectory.noLinks(file);
                if (!rootKey.equals(key(root, true)) || !dataKey.equals(key(data, true))
                        || !fileKey.equals(key(file, false))) throw new IOException();
                encrypted = Arrays.copyOf(buffer.array(), buffer.position());
                plaintext = Protection.unprotect(encrypted);
                return parse(plaintext);
            } finally { Arrays.fill(buffer.array(), (byte) 0); }
        } catch (Failure controlled) { throw controlled; }
        catch (IOException | RuntimeException | LinkageError rejected) { throw new Failure(Reason.CONFIGURATION_UNTRUSTED); }
        finally {
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Internal provisioning only; no CLI, HTTP or WebView entry. Never overwrite or repair existing state. */
    public static void create(byte[] utf8Configuration) throws IOException {
        Path root = WindowsNativeGate.profileRoot();
        byte[] encrypted = null;
        try {
            parse(utf8Configuration); // Reject unsupported fields before protecting or writing.
            Path data = root.resolve("data"), file = data.resolve("database.dpapi");
            verifyDirectory(root); verifyDirectory(data);
            PrivateMemoryDirectory.validateLocation(data, root.resolve("credentials/client-token"));
            Object rootKey = key(root, true), dataKey = key(data, true);
            encrypted = Protection.protect(utf8Configuration);
            if (encrypted.length < 1 || encrypted.length > LIMIT) throw new IOException();
            verifyDirectory(root); verifyDirectory(data);
            if (!rootKey.equals(key(root, true)) || !dataKey.equals(key(data, true))) throw new IOException();
            WindowsPrivateAcl.createFile(file); // CREATE_NEW with a protected current-user-only descriptor.
            Object fileKey = key(file, false);
            try (var pin = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
                    ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)) {
                WindowsPrivateAcl.verify(file, false);
                ByteBuffer buffer = ByteBuffer.wrap(encrypted);
                while (buffer.hasRemaining()) pin.write(buffer);
                pin.force(true);
                WindowsPrivateAcl.verify(file, false);
                if (!rootKey.equals(key(root, true)) || !dataKey.equals(key(data, true))
                        || !fileKey.equals(key(file, false))) throw new IOException();
            }
            // Interrupted writes remain fail-closed; never delete a file whose ownership is uncertain.
        } catch (Failure controlled) { throw controlled; }
        catch (IOException | RuntimeException | LinkageError rejected) { throw new Failure(Reason.CONFIGURATION_UNTRUSTED); }
        finally { if (encrypted != null) Arrays.fill(encrypted, (byte) 0); }
    }

    private static void verifyDirectory(Path path) throws IOException {
        PrivateMemoryDirectory.noLinks(path); WindowsPrivateAcl.verify(path, true);
    }
    private static Object key(Path path, boolean directory) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if ((directory ? !attributes.isDirectory() : !attributes.isRegularFile())
                || attributes.isSymbolicLink() || attributes.isOther()) throw new IOException();
        // JDK Windows BasicFileAttributes.fileKey() is null. Use the real volume/file identity.
        return Identity.read(path, directory);
    }

    static PropertySource<?> parse(byte[] bytes) throws Failure {
        try {
            if (bytes == null || bytes.length < 1 || bytes.length > LIMIT
                    || bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf)
                throw new IOException();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var node = JSON.readTree(text);
            var fields = Set.of("format", "backend", "host", "port", "database", "username", "password");
            if (!node.isObject() || node.size() != fields.size()) throw new IOException();
            for (String field : fields) if (!node.has(field)) throw new IOException();
            if (!node.path("format").isIntegralNumber() || !node.path("format").canConvertToInt() || node.path("format").intValue() != 1
                    || !node.path("backend").isString() || !node.path("backend").asString().equals("postgresql")
                    || !node.path("host").isString() || !node.path("host").asString().equals("127.0.0.1")
                    || !node.path("port").isIntegralNumber() || !node.path("port").canConvertToInt()
                    || node.path("port").intValue() < 1 || node.path("port").intValue() > 65535) throw new IOException();
            for (String field : List.of("database", "username"))
                if (!node.path(field).isString() || !node.path(field).asString().matches("[a-z][a-z0-9_]{0,62}")) throw new IOException();
            String database = node.path("database").asString(), username = node.path("username").asString();
            if (Set.of("postgres", "template0", "template1").contains(database)
                    || username.equals("postgres") || username.startsWith("pg_")) throw new IOException();
            if (!node.path("password").isString() || node.path("password").asString().isBlank())
                throw new Failure(Reason.CREDENTIAL_UNAVAILABLE);
            String password = node.path("password").asString();
            if (password.length() > 1024 || password.chars().anyMatch(c -> c == 0 || c == '\r' || c == '\n')) throw new IOException();
            return new Source(new Values("jdbc:postgresql://127.0.0.1:" + node.path("port").intValue() + "/" + database, username, password));
        } catch (Failure controlled) { throw controlled; }
        catch (IOException | RuntimeException invalid) { throw new Failure(Reason.CONFIGURATION_UNTRUSTED); }
    }

    public static boolean trusted(Environment environment) {
        return environment instanceof ConfigurableEnvironment configurable
            && configurable.getPropertySources().get(NAME) instanceof Source source && source.getSource().values != null;
    }

    /** Called by an initializer before bean definitions / conditional DataSources are evaluated. */
    public static void install(ConfigurableEnvironment environment, PropertySource<?> source) throws Failure {
        if (!(source instanceof Source)) throw new Failure(Reason.CONFIGURATION_UNTRUSTED);
        environment.getPropertySources().addFirst(source);
        if (!trusted(environment))
            for (String field : List.of("url", "username", "password"))
                if (!environment.getProperty("workspace.persistence.postgresql." + field, "").isBlank())
                    throw new Failure(Reason.CONFIGURATION_UNTRUSTED);
        if (environment.getProperty("workspace.persistence.backend", "sqlite").equals("postgresql")) {
            if (!trusted(environment)) throw new Failure(Reason.POSTGRESQL_NOT_CONFIGURED);
            var finance = new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(javax.sql.DataSource.class);
            var status = new StorageStatusService(environment, finance).inspect();
            Reason reason = switch (status.connection()) {
                case "AUTHENTICATION_FAILED" -> Reason.AUTHENTICATION_FAILED;
                case "CONNECTION_FAILED" -> Reason.CONNECTION_FAILED;
                case "ROLE_REJECTED" -> Reason.ROLE_REJECTED;
                case "CONNECTED" -> status.workspaceSchema().equals("INITIALIZATION_REQUIRED")
                    || status.knowledgeSchema().equals("INITIALIZATION_REQUIRED") ? Reason.SCHEMA_INITIALIZATION_REQUIRED
                    : !status.workspaceSchema().equals("V1_V2_READY") || !status.knowledgeSchema().equals("READY") ? Reason.SCHEMA_INCOMPATIBLE : null;
                default -> Reason.CONFIGURATION_UNTRUSTED;
            };
            if (reason != null) throw new Failure(reason);
        }
    }

    private record Values(String url, String username, String password) {
        @Override public String toString() { return "DatabaseConfiguration[private]"; }
    }
    private static final class State {
        final Values values;
        State(Values values) { this.values = values; }
        @Override public String toString() { return "DatabaseConfiguration[private]"; }
    }
    private static final class Source extends PropertySource<State> {
        Source(Values values) { super(NAME, new State(values)); }
        @Override public Object getProperty(String name) {
            if (source.values == null) return null;
            return switch (name) {
                case "workspace.persistence.backend" -> "postgresql";
                case "workspace.persistence.postgresql.url" -> source.values.url;
                case "workspace.persistence.postgresql.username" -> source.values.username;
                case "workspace.persistence.postgresql.password" -> source.values.password;
                default -> null;
            };
        }
        @Override public String toString() { return "TrustedWindowsDatabase[private]"; }
    }

    // No JNA / Crypt32 initialization before profileRoot() has completed the existing gate.
    static final class Protection {
        static byte[] unprotect(byte[] bytes) throws Failure {
            try { return com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(bytes, null, 1, null); }
            catch (RuntimeException | LinkageError rejected) { throw new Failure(Reason.CREDENTIAL_UNAVAILABLE); }
        }
        static byte[] protect(byte[] bytes) throws Failure {
            try { return com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(bytes, null, 1, "Workspace database v1", null); }
            catch (RuntimeException | LinkageError rejected) { throw new Failure(Reason.CREDENTIAL_UNAVAILABLE); }
        }
    }

    static final class Identity {
        interface Paths extends com.sun.jna.win32.StdCallLibrary {
            int GetFinalPathNameByHandleW(com.sun.jna.platform.win32.WinNT.HANDLE file, char[] path, int length, int flags);
        }
        static String read(Path path, boolean directory) throws IOException {
            var kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE;
            var handle = kernel.CreateFile("\\\\?\\" + path, 0x80, 3, null, 3, 0x02200000, null);
            if (com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw new IOException();
            try {
                var tag = new com.sun.jna.platform.win32.WinBase.FILE_ATTRIBUTE_TAG_INFO();
                if (!kernel.GetFileInformationByHandleEx(handle, 9, tag.getPointer(), new com.sun.jna.platform.win32.WinDef.DWORD(tag.size()))) throw new IOException();
                tag.read();
                if ((tag.FileAttributes & 0x400) != 0 || ((tag.FileAttributes & 0x10) != 0) != directory || kernel.GetFileType(handle) != 1) throw new IOException();
                char[] finalPath = new char[32768];
                int size = com.sun.jna.Native.load("kernel32", Paths.class).GetFinalPathNameByHandleW(handle, finalPath, finalPath.length, 0);
                if (size < 1 || size >= finalPath.length || !new String(finalPath, 0, size).equalsIgnoreCase("\\\\?\\" + path)) throw new IOException();
                var id = new com.sun.jna.platform.win32.WinBase.FILE_ID_INFO();
                if (!kernel.GetFileInformationByHandleEx(handle, 18, id.getPointer(), new com.sun.jna.platform.win32.WinDef.DWORD(id.size()))) throw new IOException();
                var info = new com.sun.jna.platform.win32.WinBase.FILE_STANDARD_INFO();
                if (!kernel.GetFileInformationByHandleEx(handle, 1, info.getPointer(), new com.sun.jna.platform.win32.WinDef.DWORD(info.size()))) throw new IOException();
                info.read();
                if (!directory && info.NumberOfLinks != 1) throw new IOException();
                return HexFormat.of().formatHex(id.getPointer().getByteArray(0, id.size()));
            } finally { kernel.CloseHandle(handle); }
        }
    }
}
