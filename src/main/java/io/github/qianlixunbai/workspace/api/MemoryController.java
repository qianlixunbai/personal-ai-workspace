package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {
    private final MemoryStore store;
    public MemoryController(MemoryStore store) { this.store = store; }
    public record Save(MemoryItem.Type type, String title, String content) {
        @Override public String toString() { return "MemorySave[type=" + type + "]"; }
    }
    public record Update(Long expectedRevision, MemoryItem.Type type, String title, String content) {
        @Override public String toString() { return "MemoryUpdate[expectedRevision=" + expectedRevision + ",type=" + type + "]"; }
    }
    public record Revision(Long expectedRevision) {}
    private static void nativeOnly() {
        if (!ClientIdentity.current().clientType().equals("native"))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "MEMORY");
    }
    private static long revision(Long value) {
        if (value == null || value < 1) throw new WorkspaceException(ErrorCode.MEMORY_INVALID, "MEMORY");
        return value;
    }
    @PostMapping("/items")
    ResponseEntity<MemoryItem> create(@RequestBody Save body) {
        nativeOnly(); MemoryItem item = store.create(body.type(), body.title(), body.content());
        return ResponseEntity.created(URI.create("/api/v1/memory/items/" + item.id())).body(item);
    }
    @GetMapping("/items/{id}")
    MemoryItem get(@PathVariable UUID id) { nativeOnly(); return store.get(id); }
    @GetMapping("/items")
    MemoryStore.Page list(@RequestParam(required = false) MemoryItem.Status status,
                          @RequestParam(required = false) MemoryItem.Type type,
                          @RequestParam(required = false) String query,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "" + MemoryLimits.DEFAULT_PAGE_SIZE) int limit) {
        nativeOnly(); return store.list(status, type, query, page, limit);
    }
    @PutMapping("/items/{id}")
    MemoryItem update(@PathVariable UUID id, @RequestBody Update body) {
        nativeOnly(); return store.update(id, revision(body.expectedRevision()), body.type(), body.title(), body.content());
    }
    @PostMapping("/items/{id}/archive")
    MemoryItem archive(@PathVariable UUID id, @RequestBody Revision body) {
        nativeOnly(); return store.archive(id, revision(body.expectedRevision()));
    }
    @PostMapping("/items/{id}/restore")
    MemoryItem restore(@PathVariable UUID id, @RequestBody Revision body) {
        nativeOnly(); return store.restore(id, revision(body.expectedRevision()));
    }
    @DeleteMapping("/items/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @RequestBody Revision body) {
        nativeOnly(); store.delete(id, revision(body.expectedRevision())); return ResponseEntity.noContent().build();
    }
    @PostMapping("/index/rebuild")
    ResponseEntity<Void> rebuild() {
        nativeOnly(); store.rebuildSearchIndex(); return ResponseEntity.noContent().build();
    }
}
