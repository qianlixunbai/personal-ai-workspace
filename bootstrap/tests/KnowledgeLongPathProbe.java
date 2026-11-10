import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.knowledge.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.model.ModelProfile;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.task.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in, synthetic-only Windows storage probe; run save and reopen in separate JVMs. */
public final class KnowledgeLongPathProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final KnowledgeScope SCOPE = new KnowledgeScope(ID.toString(), "1");
    private static final String QUESTION = "Synthetic only: 长路径 # %23 & 😀";

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("baseline", "save", "complete-save", "reopen").contains(args[0])) throw new IllegalArgumentException();
        Path root = WindowsNativeGate.profileRoot(), evidence = Path.of(args[1]);
        String phase = args[0];
        if (phase.equals("baseline")) {
            Path data = data(root, "baseline", true);
            try (var history = new KnowledgeAnswerHistory(data, root.resolve("credentials/client-token"))) {
                throw new AssertionError("Old history unexpectedly opened");
            } catch (WorkspaceException expected) {
                check(expected.error().code() == ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE);
                check(Files.size(database(data)) == 0);
                System.out.println("BASELINE_HISTORY_FAILED_PRESERVED length=" + database(data).toString().length());
            }
            return;
        }
        for (String name : List.of("short", "long")) {
            Path data = data(root, name, name.equals("long")), file = database(data);
            Path receipt = evidence.resolve(name + "-receipt.json");
            boolean write = phase.equals("save") || (phase.equals("complete-save") && name.equals("long"));
            byte[] before = !write ? Files.readAllBytes(file) : null;
            if (write) check(!Files.exists(file) && !Files.exists(receipt));
            try (var history = new KnowledgeAnswerHistory(data, root.resolve("credentials/client-token"))) {
                if (write) {
                    var profile = new ModelProfile("chat.balanced", "ollama", "synthetic-unused", ModelProfile.Locality.LOCAL,
                            "test-v1", 8192, 2048, 0.4, 3000).publicInfo();
                    history.begin(ID, QUESTION, "fixture", profile, "knowledge-answer-v2", SCOPE);
                    history.finish(ID, TaskStatus.SUCCEEDED, new TaskResult.KnowledgeAnswer("合成持久化答案 😀", List.of()), null);
                    Files.writeString(receipt, JSON.writeValueAsString(history.get(ID)), StandardOpenOption.CREATE_NEW);
                }
                var actual = history.get(ID);
                // TaskView.result is Object: compare the persisted JSON, avoiding untyped Map equality.
                check(JSON.readTree(Files.readString(receipt)).equals(JSON.valueToTree(actual)) && history.list(0).total() == 1);
                check(actual.question().equals(QUESTION) && actual.scope().equals(SCOPE));
                try (var second = new KnowledgeAnswerHistory(data, root.resolve("credentials/client-token"))) {
                    throw new AssertionError("Second history writer accepted");
                } catch (WorkspaceException locked) { check(locked.error().code() == ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE); }
            }
            if (before != null) check(Arrays.equals(before, Files.readAllBytes(file)));
            for (String suffix : List.of("-journal", "-wal", "-shm")) check(!Files.exists(file.resolveSibling("history.db" + suffix)));
            if (name.equals("short")) {
                check(file.toString().length() < 260);
                var readOnly = new org.sqlite.SQLiteConfig(); readOnly.setReadOnly(true);
                try (var db = DriverManager.getConnection("jdbc:sqlite:" + file, readOnly.toProperties());
                     var s = db.createStatement(); var r = s.executeQuery("PRAGMA quick_check")) {
                    check(r.next() && r.getString(1).equals("ok") && !r.next());
                }
            }
            System.out.println("HISTORY_" + phase.toUpperCase(Locale.ROOT) + "_PASS case=" + name + " length=" + file.toString().length());
        }
        // Minimal impact check for moving Memory's unchanged URL builder to the shared helper.
        Path memory = data(root, "memory", true);
        try (var store = new MemoryStore(memory, root.resolve("credentials/client-token"))) {
            if (!phase.equals("reopen")) store.create(MemoryItem.Type.PREFERENCE, "Synthetic only", QUESTION);
            var page = store.list(MemoryItem.Status.ACTIVE, null, null, 0, 20);
            check(page.total() == 1 && page.items().getFirst().content().equals(QUESTION));
            try (var db = store.openReadSnapshot(); var s = db.createStatement(); var r = s.executeQuery("PRAGMA quick_check")) {
                check(r.next() && r.getString(1).equals("ok") && !r.next());
            }
        }
        System.out.println("MEMORY_SHARED_URL_" + phase.toUpperCase(Locale.ROOT) + "_PASS pid=" + ProcessHandle.current().pid());
    }
    private static Path data(Path root, String name, boolean longPath) {
        Path path = root.resolve("evidence").resolve(name);
        if (longPath) {
            String leaf = "data %23#&中文";
            int padding = 1100 - database(path.resolve(leaf)).toString().length();
            while (padding > 0) { int count = Math.min(70, padding - 1); path = path.resolve("x".repeat(count)); padding -= count + 1; }
            path = path.resolve(leaf);
            check(database(path).toString().length() == 1100);
        }
        return path;
    }
    private static Path database(Path data) { return data.resolve("knowledge-answer-history/history.db"); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Synthetic long-path check failed"); }
}
