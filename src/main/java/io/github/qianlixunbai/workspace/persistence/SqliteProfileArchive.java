package io.github.qianlixunbai.workspace.persistence;

import io.github.qianlixunbai.workspace.backup.OpenPgpStreams;
import io.github.qianlixunbai.workspace.knowledge.KnowledgeAnswerHistory;
import io.github.qianlixunbai.workspace.knowledge.KnowledgeLimits;
import io.github.qianlixunbai.workspace.knowledge.SqliteKnowledgeMigration;
import io.github.qianlixunbai.workspace.memory.MemoryStore;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Offline SQLite Profile data, using the same authenticated OpenPGP stream as existing backups. */
public final class SqliteProfileArchive {
    private static final String LITERAL = "workspace-sqlite-profile-v1.zip";
    static final String HISTORY = "knowledge-answer-history/history.db";
    private static final Set<String> DATABASES = Set.of("memory.db", "knowledge/knowledge.db", HISTORY);
    private static final Pattern SOURCE = Pattern.compile(
            "knowledge/sources/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(?:[1-9]|10)\\.source");
    private static final long MAX_PAYLOAD = 40L << 30;
    private static final long MAX_PACKAGE = MAX_PAYLOAD + (MAX_PAYLOAD / 100);

    private SqliteProfileArchive() {}

    record Summary(long memory, long conversations, long messages, long documents, long revisions, long answers) {
        Map<String, Object> reply() {
            return Map.of("stage", "VERIFIED", "memory", memory, "conversations", conversations, "messages", messages,
                    "documents", documents, "revisions", revisions, "answers", answers);
        }
    }

    static void writeFile(ZipOutputStream zip, String name, FileChannel source) throws Exception {
        require(source.size() <= limit(name));
        zip.putNextEntry(new ZipEntry(name));
        source.position(0);
        require(OpenPgpStreams.transfer(Channels.newInputStream(source), zip, limit(name)) == source.size());
        zip.closeEntry();
    }

    private static long limit(String name) throws IOException {
        return switch (name) {
            case "memory.db" -> 36L << 30;
            case "knowledge/knowledge.db" -> 1L << 30;
            case HISTORY -> 256L << 20;
            default -> {
                require(SOURCE.matcher(name).matches());
                yield KnowledgeLimits.SOURCE_BYTES;
            }
        };
    }

    private static Summary backup(MaintenanceExclusion exclusion, Path staging, char[] password) throws Exception {
        var root = WindowsNativeGate.maintenanceProfileRoot(exclusion);
        MaintenanceStartupFence.requireNoPendingOperation(root);
        try (var model = new MaintenanceModelState(root);
             var source = SqliteSourceLease.acquireForArchive(exclusion)) {
            var summary = verify(root.resolve("data"));
            Path encrypted = staging.resolve("backup.pgp");
            WindowsPrivateAcl.createFile(encrypted);
            try (var file = FileChannel.open(encrypted, StandardOpenOption.WRITE, NOFOLLOW_LINKS)) {
                OpenPgpStreams.encryptZip(Channels.newOutputStream(file), password, LITERAL, MAX_PAYLOAD,
                        zip -> source.writeArchive(zip, staging));
                file.force(true);
            }
            model.requireSnapshot();
            return summary;
        }
    }

    private static Summary restore(Path encrypted, Path destination, char[] password) throws Exception {
        require(encrypted.isAbsolute() && encrypted.normalize().equals(encrypted));
        try (var file = FileChannel.open(encrypted, StandardOpenOption.READ, NOFOLLOW_LINKS,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
            require(file.size() > 0 && file.size() <= MAX_PACKAGE);
            OpenPgpStreams.profile(file);
            file.position(0);
            OpenPgpStreams.decryptBundle(Channels.newInputStream(file), password, LITERAL, in -> extract(in, destination));
            return verify(destination);
        }
    }

    private static void extract(InputStream input, Path destination) throws Exception {
        WindowsPrivateAcl.directory(destination);
        Set<String> files = new HashSet<>();
        long remaining = MAX_PAYLOAD;
        var shield = new FilterInputStream(input) { @Override public void close() {} };
        try (var zip = new ZipInputStream(shield)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                require(!entry.isDirectory() && files.size() < KnowledgeLimits.REVISIONS + DATABASES.size() && files.add(name));
                long limit = Math.min(remaining, limit(name));
                require(entry.getSize() <= limit);
                Path file = destination.resolve(name);
                Path parent = destination;
                for (Path part : destination.relativize(file.getParent())) {
                    if (part.toString().isEmpty()) continue;
                    parent = parent.resolve(part);
                    WindowsPrivateAcl.directory(parent);
                }
                WindowsPrivateAcl.createFile(file);
                try (var output = FileChannel.open(file, StandardOpenOption.WRITE, NOFOLLOW_LINKS)) {
                    long bytes = OpenPgpStreams.transfer(zip, Channels.newOutputStream(output), limit);
                    require(bytes > 0 && bytes == entry.getSize());
                    remaining -= bytes;
                    output.force(true);
                }
            }
            // Consume any remaining ZIP directory bytes before OpenPGP verifies the final tag.
            OpenPgpStreams.transfer(input, OutputStream.nullOutputStream(), 4L << 20);
        }
        require(files.containsAll(DATABASES));
        WindowsPrivateAcl.directory(destination.resolve("knowledge/sources"));
    }

    static long verifyHistory(Path data) throws Exception {
        try (var history = SqliteSourceLease.readOnly(data.resolve(HISTORY))) {
            return KnowledgeAnswerHistory.verifyArchive(history);
        }
    }

    private static Summary verify(Path data) throws Exception {
        try (var memory = SqliteSourceLease.readOnly(data.resolve("memory.db"));
             var knowledge = SqliteSourceLease.readOnly(data.resolve("knowledge/knowledge.db"))) {
            MemoryStore.verifyArchiveSchema(memory);
            try (var workspace = new SqliteWorkspaceMigration.Snapshot(memory);
                 var documents = new SqliteKnowledgeMigration.Snapshot(knowledge, data.resolve("knowledge"))) {
                documents.verifyFiles();
                try (var sources = Files.walk(data.resolve("knowledge/sources"))) {
                    long fileCount = sources.filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS)).count();
                    require(fileCount == documents.expected().rows().get("revisions"));
                }
                var rows = workspace.expected.rows();
                return new Summary(rows.get("memory_items"), rows.get("conversations"), rows.get("conversation_messages"),
                        documents.expected().rows().get("documents"), documents.expected().rows().get("revisions"), verifyHistory(data));
            }
        }
    }

    public static int run(String[] args, InputStream rawInput, PrintStream rawOutput) {
        var input = new DataInputStream(rawInput);
        var output = new DataOutputStream(rawOutput);
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(System.out);
        char[] password = null;
        String stageCode = "NATIVE_GATE";
        try {
            WindowsNativeGate.admit();
            MaintenanceRuntime.reply(output, Map.of("stage", "ARCHIVE_HELLO", "pid", ProcessHandle.current().pid(),
                    "profile", System.getProperty("workspace.native.development-profile", "")));
            var request = MaintenanceRuntime.readFrame(input, Set.of("action", "handle", "operation", "file"));
            String action = (String) request.get("action");
            String operation = (String) request.get("operation");
            String file = (String) request.get("file");
            require(operation.matches("[0-9a-f]{32}"));
            try (var exclusion = WindowsNativeGate.receiveMaintenanceExclusion((Long) request.get("handle"))) {
                WindowsNativeGate.requireMaintenanceArguments(args, exclusion);
                Path root = WindowsNativeGate.maintenanceProfileRoot(exclusion);
                Path stage = root.resolve("evidence/profile-archive-" + operation);
                WindowsPrivateAcl.verify(stage, true);
                stageCode = "PASSWORD";
                int length = input.readUnsignedShort();
                require(length >= 16 && length <= 1024);
                password = new char[length];
                for (int i = 0; i < length; i++) password[i] = input.readChar();
                require(input.read() == -1);
                if ("BACKUP".equals(action)) {
                    require(file.isEmpty());
                    stageCode = "SOURCE_BACKUP";
                    MaintenanceRuntime.reply(output, backup(exclusion, stage, password).reply());
                } else {
                    require("RESTORE".equals(action));
                    stageCode = "ARCHIVE_VALIDATION";
                    MaintenanceRuntime.reply(output, restore(Path.of(file), stage.resolve("verified"), password).reply());
                }
            }
            return 0;
        } catch (Exception | LinkageError failure) {
            try { MaintenanceRuntime.reply(output, Map.of("stage", "STOP", "code", stageCode)); } catch (IOException ignored) {}
            return 2;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private static void require(boolean value) throws IOException {
        if (!value) throw new IOException("SQLite Profile archive rejected");
    }
}
