package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.knowledge.KnowledgeBackup;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/knowledge/backup")
public class KnowledgeBackupController {
    private final KnowledgeBackup backup;
    public KnowledgeBackupController(KnowledgeBackup backup){this.backup=backup;}
    @GetMapping void export(HttpServletResponse response)throws IOException{KnowledgeController.nativeOnly();response.setContentType("application/octet-stream");backup.export(response.getOutputStream());}
    @PostMapping(value="/validate",consumes="application/octet-stream") KnowledgeBackup.Metadata validate(HttpServletRequest request)throws IOException{KnowledgeController.nativeOnly();return backup.validate(request.getInputStream());}
    @PostMapping(value="/restore",consumes="application/octet-stream") KnowledgeBackup.Metadata restore(HttpServletRequest request)throws IOException{
        KnowledgeController.nativeOnly();String target=KnowledgeController.decode(request.getHeader("X-Knowledge-Restore-Target"),8192);return backup.restore(request.getInputStream(),target);
    }
}
