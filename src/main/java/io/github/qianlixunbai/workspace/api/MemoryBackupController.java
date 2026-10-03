package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/memory/backup")
public class MemoryBackupController {
    private final MemoryStore store;
    private final MemoryBackupService service;
    public MemoryBackupController(MemoryStore store, MemoryBackupService service) { this.store = store; this.service = service; }
    private static void nativeOnly() {
        if (!ClientIdentity.current().clientType().equals("native")) throw new WorkspaceException(ErrorCode.POLICY_DENIED, "MEMORY");
    }
    @GetMapping
    ResponseEntity<byte[]> export() {
        nativeOnly();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(MemoryBackup.export(store).bytes());
    }
    @PostMapping(value = "/restore", consumes = MediaType.APPLICATION_JSON_VALUE)
    MemoryBackup.Metadata restore(@RequestBody byte[] payload) {
        nativeOnly();
        var request = MemoryBackup.readRestore(payload);
        // Native credential represents the same-account Desktop; Browser credentials never reach this parser or filesystem.
        return service.restore(request.backup(), request.targetDirectory());
    }
}
