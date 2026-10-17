package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.persistence.StorageStatusService;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/storage")
public final class StorageStatusController {
    private final StorageStatusService storage;
    public StorageStatusController(StorageStatusService storage){this.storage=storage;}
    @GetMapping("/status")
    public StorageStatusService.Status status(HttpServletRequest request) {
        if(!ClientIdentity.current().clientType().equals("native"))throw new WorkspaceException(ErrorCode.POLICY_DENIED,"STORAGE");
        if(request.getQueryString()!=null)throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"STORAGE");
        return storage.inspect();
    }
}
