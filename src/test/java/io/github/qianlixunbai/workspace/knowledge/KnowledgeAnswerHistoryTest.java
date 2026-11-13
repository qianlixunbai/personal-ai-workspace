package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.api.TaskController;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import io.github.qianlixunbai.workspace.task.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeAnswerHistoryTest {
    @TempDir Path temporary;
    private Path data(){return temporary.resolve("data");}
    private KnowledgeAnswerHistory open(){return new KnowledgeAnswerHistory(data(),temporary.resolve("auth/token"));}
    private void begin(KnowledgeAnswerHistory h,UUID id){h.begin(id," ".repeat(121)+"问题😀 <script>fixture</script>","budget",TestSettings.ask().publicInfo(),"knowledge-answer-v2");}
    private TaskResult.KnowledgeAnswer answer(){return new TaskResult.KnowledgeAnswer("合成答案",List.of(new TaskResult.KnowledgeCitation(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","fixture.md","1","MARKDOWN",2,8,1,2,"标题",
            new TaskResult.KnowledgeLocator("MARKDOWN_SECTION_LINES",1,2,0,10,"line-1"))));}
    @Test void reopenRestoresExactReceiptAndReconcilesInterruptedWithoutExecution() {
        UUID done=UUID.randomUUID(),pending=UUID.randomUUID();var answer=answer();TaskView saved;
        try(var h=open()){begin(h,done);h.finish(done,TaskStatus.SUCCEEDED,answer,null);saved=h.get(done).task();begin(h,pending);}
        var manager=new TaskManager(TestSettings.settings(URI.create("http://127.0.0.1:1")));
        try(var h=open()) {
            assertEquals(saved,h.get(done).task());assertEquals(2,h.list(0).total());
            assertTrue(h.list(0).items().stream().allMatch(x->x.question().startsWith("问题")));
            assertTrue(h.get(done).question().startsWith(" ".repeat(121))); // Only the list preview is trimmed.
            assertEquals(answer,h.get(done).task().result());assertTrue(h.get(done).question().contains("😀"));
            assertEquals(TaskStatus.FAILED,h.get(pending).task().status());assertEquals(ErrorCode.KNOWLEDGE_INTERRUPTED,h.get(pending).task().error().code());
            var controller=new TaskController(manager,h);assertEquals(saved,controller.get(done));assertEquals(saved,controller.cancel(done));
            var browser=new ClientIdentity("browser-fixture","browser","fixture",null,Instant.EPOCH,Set.of("translate"));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(browser,null,List.of()));
            try{assertEquals(ErrorCode.TASK_NOT_FOUND,assertThrows(WorkspaceException.class,()->controller.get(done)).error().code());}
            finally{SecurityContextHolder.clearContext();}
            h.delete(done);assertEquals(ErrorCode.TASK_NOT_FOUND,assertThrows(WorkspaceException.class,()->h.get(done)).error().code());
        } finally { manager.close(); }
        try(var h=open()){assertEquals(1,h.list(0).total());assertEquals(TaskStatus.FAILED,h.get(pending).task().status());}
    }
}
