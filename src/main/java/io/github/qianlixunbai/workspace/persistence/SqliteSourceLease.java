package io.github.qianlixunbai.workspace.persistence;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.nio.file.ExtendedOpenOption;
import io.github.qianlixunbai.workspace.knowledge.SqliteKnowledgeMigration;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Live kernel-enforced exclusion of the admitted SQLite truth files, independent of writer
 * registration. Does not prove model exit, authorize configuration/activation, or freeze PostgreSQL.
 * A persisted fingerprint is evidence only: it cannot recreate this live ownership. */
public final class SqliteSourceLease implements AutoCloseable {
    public record Fingerprint(String rootIdentity, Map<String, String> files) {
        public Fingerprint { files = Map.copyOf(files); }
        @Override public String toString() { return "SqliteSourceFingerprint[private]"; }
    }
    private final MaintenanceExclusion exclusion;
    private final Path root, data;
    private final List<DirectoryPin> directories = new ArrayList<>();
    private final Map<Path, FilePin> files = new LinkedHashMap<>();
    private final Fingerprint fingerprint;
    private MaintenanceConfiguration.Publication publication;
    private boolean closed;

    /** Only an actual NativeGate-consumed exclusion can select the canonical source. No path,
     * receipt, caller boolean or source ID is accepted as authority. Existing files are never repaired. */
    public static SqliteSourceLease acquire(MaintenanceExclusion exclusion) throws IOException {
        Path root = WindowsNativeGate.maintenanceProfileRoot(exclusion);
        return new SqliteSourceLease(exclusion, root);
    }

    private SqliteSourceLease(MaintenanceExclusion exclusion, Path root) throws IOException {
        this.exclusion = exclusion; this.root = root; data = root.resolve("data");
        try {
            pinDirectory(data); pinDirectory(data.resolve("knowledge"));
            requireSqliteSource();
            pinFile(data.resolve("memory.db")); pinFile(data.resolve("knowledge/knowledge.db"));
            // Pin the metadata first. Only then derive exact immutable-source names from it.
            pinDirectory(data.resolve("knowledge/sources"));
            try (var knowledge = readOnly(data.resolve("knowledge/knowledge.db"));
                 var s = knowledge.createStatement(); var rows = s.executeQuery("SELECT doc_id,revision FROM revisions ORDER BY doc_id,revision")) {
                String last = null; int count = 0;
                while (rows.next()) {
                    if (++count > 10000) throw stopped();
                    String id = rows.getString(1); long revision = rows.getLong(2);
                    if (id == null || !UUID.fromString(id).toString().equals(id) || revision < 1 || revision > 10) throw stopped();
                    Path directory = data.resolve("knowledge/sources").resolve(id);
                    if (!id.equals(last)) { pinDirectory(directory); last = id; }
                    pinFile(directory.resolve(revision + ".source"));
                }
            }
            try (var memory = readOnly(data.resolve("memory.db"));
                 var knowledge = readOnly(data.resolve("knowledge/knowledge.db"));
                 var workspaceSnapshot = new SqliteWorkspaceMigration.Snapshot(memory);
                 var knowledgeSnapshot = new SqliteKnowledgeMigration.Snapshot(knowledge, data.resolve("knowledge"))) {
                // Existing validators reject pending turns/jobs, unknown schemas and mismatched originals.
                knowledgeSnapshot.verifyFiles();
            }
            requireHandles();
            Map<String, String> digests = new TreeMap<>();
            for (var entry : files.entrySet())
                digests.put(data.relativize(entry.getKey()).toString().replace('\\', '/'), entry.getValue().digest());
            fingerprint = new Fingerprint(TrustedDatabaseConfiguration.Identity.read(root, true), digests);
        } catch (Exception failure) { close(); throw stopped(); }
    }

    public synchronized Fingerprint fingerprint() throws IOException { requireHeld(); return fingerprint; }

    public synchronized void requireHeld() throws IOException {
        requireHandles();
        if (!fingerprint.rootIdentity.equals(TrustedDatabaseConfiguration.Identity.read(root, true))) throw stopped();
        for (var entry : files.entrySet())
            if (!entry.getValue().digest().equals(fingerprint.files.get(data.relativize(entry.getKey()).toString().replace('\\', '/'))))
                throw stopped();
    }

    private void requireHandles() throws IOException {
        if (closed || !root.equals(WindowsNativeGate.maintenanceProfileRoot(exclusion))) throw stopped();
        requireSqliteSource();
        for (var pin : directories) pin.verify();
        for (var pin : files.values()) pin.verify();
    }

    private void requireSqliteSource() throws IOException {
        if(publication==null)absent(data.resolve("database.dpapi"));
        else publication.requireConfiguration(root);
        for (String name : List.of("memory.db", "knowledge/knowledge.db"))
            for (String suffix : List.of("-journal", "-wal", "-shm")) absent(data.resolve(name + suffix));
    }

    /** Only the same live owner's verified publication may change the absent-config invariant.
     * The source files stay pinned; neither a digest supplied by a caller nor a saved intent
     * can manufacture this state. The owner retains and closes the publication capability. */
    synchronized void retainPublication(MaintenanceConfiguration.Publication published)throws IOException {
        if(closed||publication!=null||published==null)throw stopped();
        published.requireHeld(root);publication=published;requireHeld();
    }

    /** The maintenance journal supplies its freshly reserved, private directory. No source write,
     * target connection or configuration operation occurs here. All backups use CREATE_NEW. */
    synchronized void backupTo(Path directory) throws IOException {
        requireHeld();
        if (!directory.getParent().getParent().equals(root.resolve("evidence/sqlite-maintenance"))) throw stopped();
        WindowsPrivateAcl.verify(directory, true);
        try {
            for (var entry : files.entrySet()) {
                Path destination = directory.resolve(data.relativize(entry.getKey()));
                for (Path part = directory; !part.equals(destination.getParent());) {
                    part = part.resolve(part.relativize(destination.getParent()).getName(0));
                    WindowsPrivateAcl.directory(part);
                }
                WindowsPrivateAcl.createFile(destination);
                if (entry.getKey().getFileName().toString().endsWith(".db")) {
                    try (var db = readOnly(entry.getKey())) {
                        if (db.unwrap(SQLiteConnection.class).getDatabase().backup("main", SqliteFileUrl.forNativeFile(destination), null) != 0)
                            throw stopped();
                    }
                } else {
                    try (var output = FileChannel.open(destination, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        var input = entry.getValue().channel;
                        long at = 0; var bytes = ByteBuffer.allocate(65536); int n;
                        while ((n = input.read(bytes, at)) != -1) {
                            at += n; bytes.flip(); while (bytes.hasRemaining()) output.write(bytes); bytes.clear();
                        }
                        output.force(true);
                    }
                }
                WindowsPrivateAcl.verify(destination, false);
            }
            // Logical SQLite backups need not have identical physical layouts; validate their full content.
            try (var m = readOnly(directory.resolve("memory.db")); var k = readOnly(directory.resolve("knowledge/knowledge.db"));
                 var a = new SqliteWorkspaceMigration.Snapshot(m); var b = new SqliteKnowledgeMigration.Snapshot(k, directory.resolve("knowledge"));
                 var sm = readOnly(data.resolve("memory.db")); var sk = readOnly(data.resolve("knowledge/knowledge.db"));
                 var c = new SqliteWorkspaceMigration.Snapshot(sm); var d = new SqliteKnowledgeMigration.Snapshot(sk, data.resolve("knowledge"))) {
                if (!a.expected.equals(c.expected) || !b.expected().equals(d.expected())) throw stopped();
            }
            requireHeld();
        } catch (Exception failure) { throw new IOException("Source backup stopped; preserve this attempt and all source files"); }
    }

    Path root() { return root; }
    boolean hasPublication() { return publication!=null; }
    static Connection readOnly(Path path) throws SQLException {
        var config = new SQLiteConfig(); config.setReadOnly(true); config.setBusyTimeout(1000);
        // Ordinary SQLite locking/change detection stays enabled; never assert immutable=1.
        return DriverManager.getConnection(SqliteFileUrl.forFile(path), config.toProperties());
    }
    private void pinDirectory(Path path) throws IOException { directories.add(new DirectoryPin(path)); }
    private void pinFile(Path path) throws IOException { files.put(path, new FilePin(path)); }
    static void absent(Path path) throws IOException {
        try { Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException absent) { return; }
        throw stopped(); // Unreadable, dangling link, hot journal and WAL never mean absent.
    }

    static final class DirectoryPin implements AutoCloseable {
        final Path path; final String identity; private HANDLE handle;
        DirectoryPin(Path path) throws IOException {
            this.path = path; WindowsPrivateAcl.verify(path, true);
            identity = TrustedDatabaseConfiguration.Identity.read(path, true);
            handle = Kernel32.INSTANCE.CreateFile("\\\\?\\" + path, 0x20081, 3, null, 3, 0x02200000, null);
            if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) { handle = null; throw stopped(); }
            try { verify(); } catch (IOException failure) { close(); throw failure; }
        }
        void verify() throws IOException {
            if (handle == null || Kernel32.INSTANCE.GetFileType(handle) != 1
                    || !identity.equals(TrustedDatabaseConfiguration.Identity.read(path, true))) throw stopped();
            WindowsPrivateAcl.verify(path, true);
        }
        public void close() { if (handle != null) { Kernel32.INSTANCE.CloseHandle(handle); handle = null; } }
    }
    static final class FilePin implements AutoCloseable {
        final Path path; final String identity; final FileChannel channel;
        FilePin(Path path) throws IOException {
            this.path = path; WindowsPrivateAcl.verify(path, false);
            identity = TrustedDatabaseConfiguration.Identity.read(path, false);
            channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                    ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE);
            try { verify(); } catch (IOException failure) { channel.close(); throw failure; }
        }
        void verify() throws IOException {
            if (!channel.isOpen() || !identity.equals(TrustedDatabaseConfiguration.Identity.read(path, false))) throw stopped();
            WindowsPrivateAcl.verify(path, false);
        }
        String digest() throws IOException {
            verify();
            try {
                var sha = MessageDigest.getInstance("SHA-256"); var buffer = ByteBuffer.allocate(65536); long at = 0; int n;
                while ((n = channel.read(buffer, at)) != -1) { at += n; buffer.flip(); sha.update(buffer); buffer.clear(); }
                return HexFormat.of().formatHex(sha.digest());
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(); }
        }
        public void close() throws IOException { channel.close(); }
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        for (var pin : files.values()) try { pin.close(); } catch (IOException ignored) { /* No capability survives closed=true. */ }
        for (int i = directories.size() - 1; i >= 0; i--) directories.get(i).close();
    }
    private static IOException stopped() { return new IOException("SQLite source ownership unavailable; maintenance remains blocked"); }
    @Override public String toString() { return "SqliteSourceLease[live-only]"; }
}
