import io.github.qianlixunbai.workspace.conversation.ConversationStore;
import io.github.qianlixunbai.workspace.knowledge.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.persistence.*;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;

/** Opt-in real Windows source/backup acceptance; no database server or model calls. */
public final class SqliteMigrationLongPathProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !Set.of("baseline", "baseline-corrected", "fixed").contains(args[0])) throw new IllegalArgumentException();
        boolean baseline = args[0].startsWith("baseline");
        Path root = WindowsNativeGate.profileRoot(), token = root.resolve("credentials/client-token");
        for (String domain : List.of("workspace", "knowledge")) {
            // Distinguish source-open from backup-destination failures. All outputs are new.
            for (boolean longSource : List.of(true, false)) {
                String name = args[0] + "-" + domain + (longSource ? "-source" : "-backup");
                Path data = path(root, name, longSource), backupParent = path(root, name + "-out", !longSource);
                check(!Files.exists(data) && !Files.exists(backupParent), "fresh synthetic paths");
                PrivateMemoryDirectory.directory(backupParent);
                Path source, backup;
                if (domain.equals("workspace")) {
                    try (var memory = new MemoryStore(data, token); var conversations = new ConversationStore(memory.databaseFile())) {
                        var item = memory.create(MemoryItem.Type.PROJECT_NOTE, "Synthetic migration", "保留 exact # %23 & 😀\n");
                        memory.update(item.id(), item.revision(), item.type(), item.title(), item.content() + "revision two");
                        var conversation = conversations.create("Synthetic migration conversation");
                        var turn = conversations.createTurnWithUserMessage(conversation.id(), "Synthetic user");
                        conversations.completeTurnWithAssistantMessage(conversation.id(), turn.id(), "Synthetic answer");
                    }
                    source = data.resolve("memory.db"); backup = backupParent.resolve("memory.db");
                } else {
                    try (var store = new KnowledgeStore(data, token); var ingestion = new KnowledgeIngestion(store)) {
                        byte[] bytes = "# Synthetic migration\n保留 source # %23 & 😀\n".getBytes(StandardCharsets.UTF_8);
                        var job = ingestion.upload(UUID.randomUUID().toString(), null, null, "synthetic.md", bytes.length, new ByteArrayInputStream(bytes));
                        long deadline = System.nanoTime() + 10_000_000_000L;
                        while (Set.of("PENDING", "PARSING").contains(store.job(job.requestId()).state()) && System.nanoTime() < deadline) Thread.sleep(10);
                        check(store.job(job.requestId()).state().equals("READY"), "synthetic import ready");
                    }
                    source = data.resolve("knowledge/knowledge.db"); backup = backupParent.resolve("snapshot/knowledge.db");
                }
                check((source.toString().length() > 260) == longSource, "source length");
                check((backup.toString().length() > 260) != longSource, "backup length");
                var before = digest(source); var sourceRows = rows(source);
                var calls = new AtomicInteger();
                DataSource unavailable = (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, values) -> {
                    if (method.getName().equals("getConnection")) { calls.incrementAndGet(); throw new SQLException("Synthetic target deliberately unavailable", "08001"); }
                    throw new AssertionError("Unexpected DataSource method");
                });
                stopped(() -> migrate(domain, source, backup, unavailable));
                check(Arrays.equals(before, digest(source)) && sourceRows.equals(rows(source)), "source unchanged");
                if (baseline) {
                    check(calls.get() == 0, "baseline never reaches target");
                    check(!Files.exists(backup) || Files.size(backup) == 0, "baseline no usable backup");
                } else {
                    check(calls.get() == 1, "real source validation reaches target once");
                    check(sourceRows.equals(rows(backup)), "all source rows retained in backup");
                    if (domain.equals("knowledge")) {
                        try (var paths = Files.walk(source.getParent().resolve("sources"))) {
                            for (Path file : paths.filter(Files::isRegularFile).toList())
                                check(Arrays.equals(digest(file), digest(backup.getParent().resolve(source.getParent().relativize(file)))), "source artifact retained");
                        }
                    }
                    byte[] retained = digest(backup);
                    stopped(() -> migrate(domain, source, backup, unavailable));
                    check(calls.get() == 1 && Arrays.equals(retained, digest(backup)), "existing backup never overwritten");
                    // Failure released source write exclusion; no data write in this transaction.
                    try (var db = DriverManager.getConnection(SqliteFileUrl.forFile(source)); var s = db.createStatement()) {
                        s.execute("BEGIN IMMEDIATE"); s.execute("ROLLBACK");
                    }
                    check(Arrays.equals(before, digest(source)), "rollback leaves source bytes unchanged");
                }
                System.out.println(args[0].toUpperCase(Locale.ROOT) + "_PASS domain=" + domain + " sourceLength=" + source.toString().length()
                    + " backupLength=" + backup.toString().length() + " targetAttempts=" + calls.get() + " backupRetained=" + (!baseline));
            }
        }
    }
    private static void migrate(String domain, Path source, Path backup, DataSource target) throws IOException {
        if (domain.equals("workspace")) SqliteWorkspaceMigration.migrate(source, backup, target);
        else SqliteKnowledgeMigration.migrate(source.getParent(), backup.getParent(), target);
    }
    private static Path path(Path root, String name, boolean longPath) {
        // Short names keep the control below MAX_PATH even in the packaged host's Known Folder.
        Path path = root.resolve("evidence").resolve(Integer.toHexString(name.hashCode()));
        if (longPath) for (int i = 0; i < 12; i++) path = path.resolve("synthetic-" + "x".repeat(65));
        return longPath ? path.resolve("data %23#&中文") : path;
    }
    private static List<List<String>> rows(Path file) throws Exception {
        var config = new SQLiteConfig(); config.setReadOnly(true);
        var result = new ArrayList<List<String>>();
        try (var db = DriverManager.getConnection(SqliteFileUrl.forFile(file), config.toProperties()); var s = db.createStatement()) {
            try (var r = s.executeQuery("PRAGMA integrity_check")) { check(r.next() && r.getString(1).equals("ok") && !r.next(), "integrity"); }
            var tables = new ArrayList<String>();
            try (var r = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) { while (r.next()) tables.add(r.getString(1)); }
            for (String table : tables) {
                check(table.matches("[a-z_]+"), "synthetic table name"); result.add(List.of(table));
                int columns;
                try (var r = s.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0")) { columns = r.getMetaData().getColumnCount(); }
                String order = java.util.stream.IntStream.rangeClosed(1, columns).mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(","));
                try (var r = s.executeQuery("SELECT * FROM \"" + table + "\" ORDER BY " + order)) {
                    while (r.next()) { var row = new ArrayList<String>(); for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.add(r.getString(i)); result.add(row); }
                }
            }
        }
        return result;
    }
    private static byte[] digest(Path file) throws Exception { return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)); }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void stopped(Work work) throws Exception { try { work.run(); throw new AssertionError("Expected target failure"); } catch (IOException expected) { } }
    private static void check(boolean passed, String stage) { if (!passed) throw new AssertionError(stage); }
}
