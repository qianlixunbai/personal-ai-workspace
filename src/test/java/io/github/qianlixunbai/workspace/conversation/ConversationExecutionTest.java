package io.github.qianlixunbai.workspace.conversation;

import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.capability.TextTaskSubmission;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.model.*;
import io.github.qianlixunbai.workspace.policy.*;
import io.github.qianlixunbai.workspace.provider.*;
import io.github.qianlixunbai.workspace.task.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.conversation.Conversation.*;

class ConversationExecutionTest {
    @TempDir Path temp;
    MemoryStore memory; ConversationStore store; TaskManager tasks; ConversationExecution execution;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<Provider.ProviderExecution> received = new AtomicReference<>();
    java.util.function.Function<Cancellation,String> work = c -> "ASSISTANT_MARKER";
    @BeforeEach void open() { initialize(Duration.ofSeconds(2),Duration.ofSeconds(2),Duration.ofSeconds(2)); }
    void initialize(Duration queue, Duration timeout, Duration retention) {
        memory=new MemoryStore(temp.resolve("data"),temp.resolve("auth/token")); store=new ConversationStore(memory.databaseFile());
        var settings=TestSettings.withTasks(TestSettings.tasks(queue,timeout,retention)); tasks=new TaskManager(settings);
        var profiles=new ProfileResolver(settings);
        Provider provider=new Provider() {
            public String id() { return "ollama"; }
            public ModelProfile.Locality locality() { return ModelProfile.Locality.LOCAL; }
            public Set<Capability> capabilities() { return Set.of(Capability.TEXT_GENERATION); }
            public String execute(ProviderExecution request,Cancellation cancellation) { calls.incrementAndGet(); received.set(request); return work.apply(cancellation); }
            public ProviderReadiness readiness(ModelProfile profile) { throw new AssertionError(); }
        };
        execution=new ConversationExecution(store,memory,profiles,new TextTaskSubmission(profiles,new ProviderRegistry(List.of(provider)),new ProviderPolicy(),tasks,TestSettings.models(settings)),tasks);
    }
    @AfterEach void close() { tasks.close(); store.close(); memory.close(); }
    ConversationExecution.Accepted send(UUID id,String text) { return execution.submit(id,new ConversationExecution.Request(text,List.of())); }
    TaskView terminal(UUID id) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime()<end) { var view=tasks.get(id); if(view.status()!=TaskStatus.QUEUED&&view.status()!=TaskStatus.RUNNING) return view; Thread.sleep(5); }
        throw new AssertionError("Task failed to terminate");
    }
    Turn turn(UUID id,int index) { return store.detail(id,0,10).turns().get(index); }
    void sql(String sql) throws Exception { try(var db=DriverManager.getConnection("jdbc:sqlite:"+memory.databaseFile());var s=db.createStatement()){s.execute(sql);} }
    static void code(ErrorCode code,org.junit.jupiter.api.function.Executable action) { assertEquals(code,assertThrows(WorkspaceException.class,action).error().code()); }
    @Test void userIsDurableBeforeProviderAndSuccessIsAtomicAndRetainedIndependentOfTasks() throws Exception {
        var c=store.create("TITLE_NOT_CONTEXT");
        work=cancel->{
            try(var other=new ConversationStore(memory.databaseFile())) {
                var pending=other.detail(c.id(),0,10).turns().getFirst(); assertEquals(TurnStatus.PENDING,pending.status());
                assertEquals("CURRENT_USER",pending.userMessage().content()); assertNull(pending.assistantMessage());
            }
            return "ASSISTANT_MARKER";
        };
        var first=send(c.id(),"CURRENT_USER"); assertEquals(TaskStatus.SUCCEEDED,terminal(first.taskId()).status());
        var successful=turn(c.id(),0); assertEquals(TurnStatus.SUCCEEDED,successful.status()); assertNotNull(successful.assistantMessage());
        work=cancel->"SECOND_ASSISTANT";
        var second=send(c.id(),"SECOND_USER"); terminal(second.taskId());
        assertEquals(List.of(1L),second.admittedSequences());
        assertEquals(List.of("CURRENT_USER","ASSISTANT_MARKER","SECOND_USER"),received.get().messages().stream().map(Provider.ChatMessage::content).toList());
        assertFalse(received.get().toString().contains("CURRENT_USER"));
        tasks.close(); assertEquals(2,store.detail(c.id(),0,10).totalTurns()); assertEquals(successful,turn(c.id(),0));
    }
    @Test void cancellationRejectsLateSuccessAndArchiveDoesNotCancelAndPendingBlocksDelete() throws Exception {
        var entered=new CountDownLatch(1);var gate=new CountDownLatch(1);
        work=cancel->{entered.countDown();try{gate.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return "LATE";};
        var c=store.create(null);var accepted=send(c.id(),"USER");assertTrue(entered.await(1,TimeUnit.SECONDS));
        code(ErrorCode.CONVERSATION_CONFLICT,()->send(c.id(),"OVERLAP"));
        code(ErrorCode.CONVERSATION_CONFLICT,()->store.delete(c.id()));
        store.archive(c.id());assertEquals(TaskStatus.RUNNING,tasks.get(accepted.taskId()).status());
        code(ErrorCode.CONVERSATION_CONFLICT,()->send(c.id(),"ARCHIVED"));
        assertEquals(TaskStatus.CANCELLED,tasks.cancel(accepted.taskId()).status());gate.countDown();
        assertEquals(TurnStatus.CANCELLED,turn(c.id(),0).status());assertNull(turn(c.id(),0).assistantMessage());
        code(ErrorCode.CONVERSATION_CONFLICT,()->store.finalizeExecution(c.id(),accepted.turnId(),accepted.taskId(),TurnStatus.SUCCEEDED,"LATE",null));
        store.delete(c.id());
    }
    @Test void taskTurnOwnershipDuplicateCompletionAndRestartFailClosed() throws Exception {
        var c=store.create(null);var d=store.create(null);UUID taskId=UUID.randomUUID();
        var t=store.createTurnWithUserMessage(c.id(),"INTERRUPTED",taskId,List.of());
        code(ErrorCode.CONVERSATION_CONFLICT,()->store.finalizeExecution(c.id(),t.id(),UUID.randomUUID(),TurnStatus.SUCCEEDED,"WRONG",null));
        code(ErrorCode.CONVERSATION_NOT_FOUND,()->store.finalizeExecution(d.id(),t.id(),taskId,TurnStatus.SUCCEEDED,"WRONG",null));
        store.close();store=new ConversationStore(memory.databaseFile());assertEquals(1,store.reconcilePending());assertEquals(0,store.reconcilePending());
        assertEquals(TurnStatus.FAILED,turn(c.id(),0).status());assertEquals(FailureCode.EXECUTION_INTERRUPTED,turn(c.id(),0).failureCode());assertEquals(0,calls.get());
        code(ErrorCode.CONVERSATION_CONFLICT,()->store.finalizeExecution(c.id(),t.id(),taskId,TurnStatus.SUCCEEDED,"LATE",null));
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+memory.databaseFile());var s=db.createStatement()) {
            assertThrows(SQLException.class,()->s.execute("UPDATE conversation_turns SET status='PENDING'"));
        }
        store.delete(c.id());
    }
}
