package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.conversation.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationStore store;
    public ConversationController(ConversationStore store) { this.store = store; }
    public record Title(String title) { @Override public String toString() { return "ConversationTitle[redacted]"; } }
    private static void nativeOnly() {
        if (!ClientIdentity.current().clientType().equals("native")) throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CONVERSATION");
    }
    private static UUID id(String text) {
        try {
            UUID id = UUID.fromString(text);
            if (!id.toString().equals(text) || id.equals(new UUID(0, 0))) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException ignored) { throw new WorkspaceException(ErrorCode.CONVERSATION_INVALID, "CONVERSATION"); }
    }
    @PostMapping
    ResponseEntity<Conversation> create(@RequestBody Title body) {
        nativeOnly(); var result = store.create(body.title());
        return ResponseEntity.created(URI.create("/api/v1/conversations/" + result.id())).body(result);
    }
    @GetMapping
    Conversation.Page list(@RequestParam(required = false) Conversation.Status status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int limit) {
        nativeOnly(); return store.list(status, page, limit);
    }
    @GetMapping("/{conversationId}")
    Conversation.Detail detail(@PathVariable String conversationId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int limit) {
        nativeOnly(); return store.detail(id(conversationId), page, limit);
    }
    @PatchMapping("/{conversationId}")
    Conversation rename(@PathVariable String conversationId, @RequestBody Title body) {
        nativeOnly(); return store.rename(id(conversationId), body.title());
    }
    @PostMapping("/{conversationId}/archive")
    Conversation archive(@PathVariable String conversationId) { nativeOnly(); return store.archive(id(conversationId)); }
    @PostMapping("/{conversationId}/unarchive")
    Conversation unarchive(@PathVariable String conversationId) { nativeOnly(); return store.unarchive(id(conversationId)); }
    @DeleteMapping("/{conversationId}")
    ResponseEntity<Void> delete(@PathVariable String conversationId) {
        nativeOnly(); store.delete(id(conversationId)); return ResponseEntity.noContent().build();
    }
}
