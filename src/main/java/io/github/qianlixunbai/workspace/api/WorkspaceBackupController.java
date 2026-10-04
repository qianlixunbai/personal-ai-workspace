package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.backup.WorkspaceBackupService;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.util.Base64;

@RestController
@RequestMapping("/api/v1/workspace/backup")
public class WorkspaceBackupController {
    private final WorkspaceBackupService service;
    public WorkspaceBackupController(WorkspaceBackupService service) { this.service = service; }
    private static void nativeOnly() {
        if (!ClientIdentity.current().clientType().equals("native")) throw new WorkspaceException(ErrorCode.POLICY_DENIED,"WORKSPACE_BACKUP");
    }
    @GetMapping
    void export(HttpServletResponse response) throws IOException {
        nativeOnly(); response.setContentType("application/json");
        service.export(response.getOutputStream());
    }
    @PostMapping(value="/validate",consumes="application/json")
    WorkspaceBackupService.Metadata validate(HttpServletRequest request) throws IOException {
        nativeOnly(); return service.validate(request.getInputStream());
    }
    @PostMapping(value="/restore",consumes="application/json")
    WorkspaceBackupService.Metadata restore(HttpServletRequest request) throws IOException {
        nativeOnly(); String target;
        try {
            String encoded=request.getHeader("X-Workspace-Restore-Target");
            if(encoded==null || encoded.length()>32768 || !encoded.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            target=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(encoded))).toString();
            if(target.length()>8192) throw new IllegalArgumentException();
        } catch(Exception ignored) { throw new WorkspaceException(ErrorCode.WORKSPACE_RESTORE_FAILED,"WORKSPACE_BACKUP"); }
        return service.restore(request.getInputStream(),target);
    }
}
