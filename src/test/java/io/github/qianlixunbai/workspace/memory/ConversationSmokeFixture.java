package io.github.qianlixunbai.workspace.memory;

import io.github.qianlixunbai.workspace.conversation.*;
import java.nio.file.*;
import java.util.UUID;

/** Test-only local fixture writer. Never part of the packaged Runtime or an HTTP endpoint. */
public final class ConversationSmokeFixture {
    public static void main(String[] args) {
        try {
            Path root = Path.of(args[0]).toAbsolutePath().normalize();
            if (!root.getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
                    || !root.getFileName().toString().startsWith("workspace-conversation-smoke-")) throw new IllegalArgumentException();
            boolean seed = args[1].equals("seed-m3");
            try (var memory = new MemoryStore(root.resolve("data"), root.resolve("auth/client-token"), !seed)) {
                if (seed) memory.create(MemoryItem.Type.PROJECT_NOTE, "Synthetic migration note", "Synthetic migration preserved");
                else try (var conversations = new ConversationStore(memory.databaseFile())) {
                    UUID id = UUID.fromString(args[2]);
                    var successful = conversations.createTurnWithUserMessage(id, "Synthetic user text 中文");
                    conversations.completeTurnWithAssistantMessage(id,successful.id(),"Synthetic assistant text 中文");
                    var timedOut = conversations.createTurnWithUserMessage(id,"Synthetic timeout question");
                    conversations.markTurnTerminated(id,timedOut.id(),Conversation.TurnStatus.TIMED_OUT);
                }
            }
        } catch (Exception ignored) { System.err.println("Synthetic fixture preparation failed."); System.exit(1); }
    }
}
