package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.knowledge.*;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Base64;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final KnowledgeStore store;private final KnowledgeIngestion ingestion;
    public KnowledgeController(KnowledgeStore store,KnowledgeIngestion ingestion){this.store=store;this.ingestion=ingestion;}
    static void nativeOnly(){if(!ClientIdentity.current().clientType().equals("native"))throw new WorkspaceException(ErrorCode.POLICY_DENIED,"KNOWLEDGE");}
    @GetMapping("/documents") KnowledgeStore.Page list(@RequestParam(defaultValue="ACTIVE") String status,@RequestParam(defaultValue="0") int page){nativeOnly();return store.list(status,page);}
    @GetMapping("/documents/{id}") KnowledgeStore.Detail detail(@PathVariable String id){nativeOnly();return store.detail(id);}
    @GetMapping("/imports/{id}") KnowledgeStore.Job outcome(@PathVariable String id){nativeOnly();return store.job(id);}
    @PostMapping(value="/imports",consumes="application/octet-stream") KnowledgeStore.Job upload(HttpServletRequest request)throws IOException {
        nativeOnly();long size;
        try{String length=request.getHeader("X-Knowledge-Size");if(length==null||!length.matches("[1-9][0-9]{0,7}"))throw new IllegalArgumentException();size=Long.parseLong(length);}
        catch(Exception e){throw new WorkspaceException(ErrorCode.KNOWLEDGE_INVALID_SOURCE,"KNOWLEDGE");}
        return ingestion.upload(request.getHeader("X-Knowledge-Request"),request.getHeader("X-Knowledge-Document"),request.getHeader("X-Knowledge-Version"),
                decode(request.getHeader("X-Knowledge-Filename"),320),size,request.getInputStream());
    }
    public record Mutation(String expectedMetadataVersion) {}
    @PostMapping("/documents/{id}/archive") KnowledgeDocument archive(@PathVariable String id,@RequestBody Mutation m){nativeOnly();return store.lifecycle(id,m.expectedMetadataVersion(),"ARCHIVED");}
    @PostMapping("/documents/{id}/restore") KnowledgeDocument restore(@PathVariable String id,@RequestBody Mutation m){nativeOnly();return store.lifecycle(id,m.expectedMetadataVersion(),"ACTIVE");}
    public record Deleted(boolean deleted){}
    @DeleteMapping("/documents/{id}") Deleted delete(@PathVariable String id,@RequestBody Mutation m){nativeOnly();store.delete(id,m.expectedMetadataVersion());return new Deleted(true);}
    public record Cancel(String documentId){}
    @PostMapping("/imports/{id}/cancel") KnowledgeStore.Job cancel(@PathVariable String id,@RequestBody Cancel m){nativeOnly();return store.cancel(id,m.documentId());}
    @GetMapping("/documents/{id}/preview") KnowledgeStore.Preview preview(@PathVariable String id,@RequestParam String revision,@RequestParam(defaultValue="0") int offset){nativeOnly();return store.preview(id,revision,offset);}
    static String decode(String encoded,int maximum){try{
        if(encoded==null||encoded.length()>maximum*8||!encoded.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
        String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(encoded))).toString();
        if(value.length()>maximum)throw new IllegalArgumentException();
        if(!Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)).equals(encoded))throw new IllegalArgumentException();return value;
    }catch(Exception e){throw new WorkspaceException(ErrorCode.KNOWLEDGE_INVALID_SOURCE,"KNOWLEDGE");}}
}
