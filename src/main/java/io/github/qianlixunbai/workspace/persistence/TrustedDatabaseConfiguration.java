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
import java.sql.Connection;
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
        return load(false);
    }

    public static PropertySource<?> loadFinance() throws IOException { return load(true); }

    private static PropertySource<?> load(boolean finance) throws IOException {
        Path root = WindowsNativeGate.profileRoot();
        Path data = root.resolve("data"), file = data.resolve(finance ? "finance.dpapi" : "database.dpapi");
        byte[] encrypted = null, plaintext = null;
        try {
            verifyDirectory(root); verifyDirectory(data);
            PrivateMemoryDirectory.validateLocation(data, root.resolve("credentials/client-token"));
            Object rootKey = key(root, true), dataKey = key(data, true);
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return new Source(null, finance);
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
                return parse(plaintext, finance);
            } finally { Arrays.fill(buffer.array(), (byte) 0); }
        } catch (Failure controlled) { throw controlled; }
        catch (IOException | RuntimeException | LinkageError rejected) { throw new Failure(Reason.CONFIGURATION_UNTRUSTED); }
        finally {
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Native provisioning only; no HTTP or WebView configuration payload. Never overwrite or repair existing state. */
    public static void create(byte[] utf8Configuration) throws IOException {
        create(utf8Configuration, false);
    }

    public static void createFinance(byte[] utf8Configuration) throws IOException { create(utf8Configuration, true); }

    private static void create(byte[] utf8Configuration, boolean finance) throws IOException {
        Path root = WindowsNativeGate.profileRoot();
        byte[] encrypted = null;
        try {
            parse(utf8Configuration, finance); // Reject unsupported fields before protecting or writing.
            Path data = root.resolve("data"), file = data.resolve(finance ? "finance.dpapi" : "database.dpapi");
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

    /** Maintenance reads only the authority's fixed Workspace configuration. This does not
     * prove stopped writers or authorize a backup. No arbitrary source path or Finance input. */
    static RecoverySource freezeRecoverySource(MaintenanceExclusion exclusion) throws IOException {
        Path root = WindowsNativeGate.maintenanceProfileRoot(exclusion);
        verifyDirectory(root); verifyDirectory(root.resolve("data"));
        PrivateMemoryDirectory.validateLocation(root.resolve("data"), root.resolve("credentials/client-token"));
        WindowsPrivateAcl.verify(root.resolve("data/database.dpapi"), false);
        RecoverySource source = pinRecoverySource(root);
        try {
            WindowsPrivateAcl.verify(root.resolve("data/database.dpapi"), false);
            WindowsNativeGate.maintenanceProfileRoot(exclusion);
            source.requireUnchanged();
            return source;
        } catch (IOException | RuntimeException | Error failure) { source.close(); throw failure; }
    }

    // Private OS primitive seam for the explicit fresh-cluster probe. It does not set or mock
    // NativeGate state. Production can reach this only through the gated factory above.
    private static RecoverySource pinRecoverySource(Path root) throws IOException {
        var source = new RecoverySource(root);
        try { source.open(); return source; }
        catch (Exception | LinkageError rejected) { source.close(); throw new Failure(Reason.CONFIGURATION_UNTRUSTED); }
    }

    /** A pinned source binding, never a maintenance capability or a writable configuration.
     * Credentials stay here; callers supply only temporary administrator credentials. */
    public static final class RecoverySource implements AutoCloseable {
        private final Path root, data, file;
        private final List<com.sun.jna.platform.win32.WinNT.HANDLE> directories = new ArrayList<>();
        private String rootIdentity, dataIdentity, configurationIdentity, configurationSha256;
        private FileChannel pin;
        private Values values;
        private DatabaseIdentity databaseIdentity;
        private boolean closed;
        private RecoverySource(Path root) { this.root = root; data = root.resolve("data"); file = data.resolve("database.dpapi"); }
        public record DatabaseIdentity(String systemIdentifier, long databaseOid, long applicationRoleOid) {
            @Override public String toString() { return "RecoveryDatabaseIdentity[private]"; }
        }
        private void open() throws Exception {
            if (!root.equals(root.toAbsolutePath().normalize())) throw new IOException();
            NativeProfileRecovery.requireOrdinaryProfile(root);
            PrivateMemoryDirectory.noLinks(file);
            // Gate already pins ancestors. These additional no-delete handles retain root/data
            // for the configuration lifetime, including the private synthetic OS probe.
            for (Path directory : List.of(root, data)) {
                var handle = com.sun.jna.platform.win32.Kernel32.INSTANCE.CreateFile("\\\\?\\" + directory,
                    0x20081, 3, null, 3, 0x02200000, null);
                if (com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw new IOException();
                directories.add(handle);
                Identity.read(directory, true);
            }
            rootIdentity = Identity.read(root, true); dataIdentity = Identity.read(data, true);
            pin = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE);
            configurationIdentity = Identity.read(file, false);
            byte[] encrypted = readEncrypted(), plaintext = null;
            try {
                configurationSha256 = sha256(encrypted);
                plaintext = Protection.unprotect(encrypted);
                values = ((Source) parse(plaintext, false)).getSource().values;
            } finally { Arrays.fill(encrypted, (byte) 0); if (plaintext != null) Arrays.fill(plaintext, (byte) 0); }
            requireUnchanged();
        }
        private byte[] readEncrypted() throws IOException {
            ByteBuffer bytes = ByteBuffer.allocate(LIMIT + 1);
            try {
                pin.position(0);
                while (bytes.hasRemaining() && pin.read(bytes) != -1) { }
                if (bytes.position() < 1 || bytes.position() > LIMIT) throw new IOException();
                return Arrays.copyOf(bytes.array(), bytes.position());
            } finally { Arrays.fill(bytes.array(), (byte) 0); }
        }
        private static String sha256(byte[] bytes) throws IOException {
            try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(); }
        }
        public void requireUnchanged() throws IOException {
            if (closed || pin == null || !pin.isOpen() || values == null) throw new Failure(Reason.CONFIGURATION_UNTRUSTED);
            NativeProfileRecovery.requireOrdinaryProfile(root);
            PrivateMemoryDirectory.noLinks(file);
            if (!rootIdentity.equals(Identity.read(root, true)) || !dataIdentity.equals(Identity.read(data, true))
                    || !configurationIdentity.equals(Identity.read(file, false))) throw new Failure(Reason.CONFIGURATION_UNTRUSTED);
            byte[] encrypted = readEncrypted();
            try { if (!configurationSha256.equals(sha256(encrypted))) throw new Failure(Reason.CONFIGURATION_UNTRUSTED); }
            finally { Arrays.fill(encrypted, (byte) 0); }
        }
        public Path profileRoot() throws IOException { requireUnchanged(); return root; }
        public String database() throws IOException { requireUnchanged(); return values.database; }
        public int port() throws IOException { requireUnchanged(); return values.port; }
        public String configurationIdentity() throws IOException { requireUnchanged(); return configurationIdentity; }
        public String configurationSha256() throws IOException { requireUnchanged(); return configurationSha256; }
        public DatabaseIdentity databaseIdentity() throws IOException {
            requireUnchanged(); if (databaseIdentity == null) throw new Failure(Reason.CONNECTION_FAILED); return databaseIdentity;
        }

        /** Authenticate using the protected app credentials, and correlate that live backend
         * on the administrator connection BEFORE any role DDL. A matching URL alone is not
         * identity. App credentials never need pg_control_system privileges or new grants. */
        public void verifyAdministrator(Connection administrator) throws IOException {
            requireUnchanged();
            var source = new org.postgresql.ds.PGSimpleDataSource();
            source.setURL(values.url); source.setUser(values.username); source.setPassword(values.password);
            source.setCurrentSchema("workspace,pg_catalog"); source.setConnectTimeout(3); source.setSocketTimeout(15);
            try (var app = source.getConnection()) {
                app.setReadOnly(true); app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); app.setAutoCommit(false);
                try (var statement = app.createStatement()) { statement.execute("SET LOCAL statement_timeout='10s'"); }
                PostgresqlWorkspace.verify(app);
                try (var statement = app.createStatement(); var rows = statement.executeQuery(
                        "SELECT version,success FROM workspace.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")) {
                    for (String version : List.of("1", "2"))
                        if (!rows.next() || !version.equals(rows.getString(1)) || !rows.getBoolean(2)) throw new IOException();
                    if (rows.next()) throw new IOException();
                }
                long databaseOid, roleOid; int backendPid, clientPort;
                try (var statement = app.createStatement(); var row = statement.executeQuery(
                        "SELECT pg_catalog.pg_backend_pid(),pg_catalog.inet_client_port(),d.oid,r.oid "
                        + "FROM pg_catalog.pg_database d,pg_catalog.pg_roles r WHERE d.datname=current_database() "
                        + "AND r.rolname=session_user AND current_user=session_user AND r.rolcanlogin "
                        + "AND current_setting('server_version_num')::int/10000=17 AND pg_catalog.inet_server_addr()='127.0.0.1' "
                        + "AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_auth_members m WHERE m.member=r.oid)")) {
                    if (!row.next()) throw new IOException();
                    backendPid = row.getInt(1); clientPort = row.getInt(2); databaseOid = row.getLong(3); roleOid = row.getLong(4);
                    if (row.next() || backendPid <= 0 || clientPort <= 0 || databaseOid <= 0 || roleOid <= 0) throw new IOException();
                }
                try (var statement = administrator.prepareStatement(
                        "SELECT c.system_identifier::text FROM pg_catalog.pg_control_system() c,pg_catalog.pg_stat_activity a "
                        + "WHERE current_database()=? AND pg_catalog.inet_server_port()=? AND pg_catalog.inet_server_addr()='127.0.0.1' "
                        + "AND a.pid=? AND a.datid=? AND a.usesysid=? AND a.usename=? AND a.client_port=? "
                        + "AND a.client_addr='127.0.0.1' AND a.backend_type='client backend'")) {
                    statement.setQueryTimeout(10); statement.setString(1, values.database); statement.setInt(2, values.port);
                    statement.setInt(3, backendPid); statement.setLong(4, databaseOid); statement.setLong(5, roleOid);
                    statement.setString(6, values.username); statement.setInt(7, clientPort);
                    try (var row = statement.executeQuery()) {
                        if (!row.next()) throw new IOException();
                        String system = row.getString(1);
                        if (row.next() || system == null || Long.parseUnsignedLong(system) == 0) throw new IOException();
                        var observed = new DatabaseIdentity(system, databaseOid, roleOid);
                        if (databaseIdentity != null && !databaseIdentity.equals(observed)) throw new IOException();
                        databaseIdentity = observed;
                    }
                }
                app.rollback();
                requireUnchanged();
            } catch (Exception rejected) { throw new Failure(Reason.CONFIGURATION_UNTRUSTED); }
        }
        @Override public void close() throws IOException {
            if (closed) return; closed = true; values = null;
            try { if (pin != null) pin.close(); }
            finally {
                for (int i = directories.size() - 1; i >= 0; i--)
                    com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(directories.get(i));
                directories.clear();
            }
        }
        @Override public String toString() { return "RecoverySource[private; not maintenance admission]"; }
    }

    static PropertySource<?> parse(byte[] bytes) throws Failure { return parse(bytes, false); }

    public static PropertySource<?> parseFinance(byte[] bytes) throws Failure { return parse(bytes, true); }

    private static PropertySource<?> parse(byte[] bytes, boolean finance) throws Failure {
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
            return new Source(new Values("jdbc:postgresql://127.0.0.1:" + node.path("port").intValue() + "/" + database,
                username, password, database, node.path("port").intValue()), finance);
        } catch (Failure controlled) { throw controlled; }
        catch (IOException | RuntimeException invalid) { throw new Failure(Reason.CONFIGURATION_UNTRUSTED); }
    }

    public static boolean trusted(Environment environment) {
        return environment instanceof ConfigurableEnvironment configurable
            && configurable.getPropertySources().get(NAME) instanceof Source source && source.getSource().values != null;
    }

    /** Called by an initializer before bean definitions / conditional DataSources are evaluated. */
    public static void install(ConfigurableEnvironment environment, PropertySource<?> source) throws Failure {
        if (!(source instanceof Source local) || local.finance) throw new Failure(Reason.CONFIGURATION_UNTRUSTED);
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

    public static void installFinance(ConfigurableEnvironment environment, PropertySource<?> source) throws Failure {
        if (!(source instanceof Source local) || !local.finance) throw new Failure(Reason.CONFIGURATION_UNTRUSTED);
        // Native startup owns the switch. No file means disabled, regardless of ambient Spring properties.
        if (local.getSource().values != null && Objects.equals(local.getSource().values.url,
                environment.getProperty("workspace.persistence.postgresql.url")))
            throw new Failure(Reason.CONFIGURATION_UNTRUSTED);
        environment.getPropertySources().addFirst(source);
    }

    private record Values(String url, String username, String password, String database, int port) {
        @Override public String toString() { return "DatabaseConfiguration[private]"; }
    }
    private static final class State {
        final Values values;
        State(Values values) { this.values = values; }
        @Override public String toString() { return "DatabaseConfiguration[private]"; }
    }
    private static final class Source extends PropertySource<State> {
        final boolean finance;
        Source(Values values) { this(values, false); }
        Source(Values values, boolean finance) { super(finance ? "trustedWindowsFinance" : NAME, new State(values)); this.finance = finance; }
        @Override public Object getProperty(String name) {
            if (finance) {
                if (name.equals("workspace.finance.enabled")) return source.values != null;
                if (name.equals("workspace.finance.flyway.enabled")) return false;
                if (source.values == null) return null;
                return switch (name) {
                    case "workspace.finance.datasource.url" -> source.values.url;
                    case "workspace.finance.datasource.username" -> source.values.username;
                    case "workspace.finance.datasource.password" -> source.values.password;
                    default -> null;
                };
            }
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

    public static final class Identity {
        interface Paths extends com.sun.jna.win32.StdCallLibrary {
            int GetFinalPathNameByHandleW(com.sun.jna.platform.win32.WinNT.HANDLE file, char[] path, int length, int flags);
        }
        public static String read(Path path, boolean directory) throws IOException {
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
