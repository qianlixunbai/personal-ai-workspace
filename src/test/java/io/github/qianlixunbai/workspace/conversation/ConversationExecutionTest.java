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
        execution=new ConversationExecution(store,memory,profiles,new TextTaskSubmission(profiles,new ProviderRegistry(List.of(provider)),new ProviderPolicy(),tasks),tasks);
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
    @Test void providerFailureIsTerminalWithoutAssistantAndExplicitResendCreatesNewTurn() throws Exception {
        var c=store.create(null); work=cancel->{throw new WorkspaceException(ErrorCode.PROVIDER_UNAVAILABLE,"PROVIDER");};
        var failed=send(c.id(),"FAILED_USER"); assertEquals(TaskStatus.FAILED,terminal(failed.taskId()).status());
        assertEquals(FailureCode.PROVIDER_UNAVAILABLE,turn(c.id(),0).failureCode()); assertNull(turn(c.id(),0).assistantMessage());
        assertEquals(1,calls.get()); work=cancel->"OK";
        var next=send(c.id(),"NEW_USER"); terminal(next.taskId()); assertNotEquals(failed.turnId(),next.turnId()); assertTrue(next.admittedSequences().isEmpty());
        assertEquals(List.of("NEW_USER"),received.get().messages().stream().map(Provider.ChatMessage::content).toList());
        code(ErrorCode.CONVERSATION_CONFLICT,()->store.completeTurnWithAssistantMessage(c.id(),failed.turnId(),"late"));
        assertEquals(2,calls.get());
    }
    @Test void queueFullAndClosedManagerPreserveNewUserWithFailedOutcome() throws Exception {
        var gate=new CountDownLatch(1); var entered=new CountDownLatch(1);
        work=cancel->{entered.countDown();try{gate.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return "OK";};
        var a=store.create(null);var b=store.create(null);var c=store.create(null);
        try {
            send(a.id(),"A");assertTrue(entered.await(1,TimeUnit.SECONDS));send(b.id(),"B");
            code(ErrorCode.QUEUE_FULL,()->send(c.id(),"QUEUE_FULL_USER"));
            assertEquals(TurnStatus.FAILED,turn(c.id(),0).status());assertEquals(FailureCode.QUEUE_FULL,turn(c.id(),0).failureCode());
            assertEquals("QUEUE_FULL_USER",turn(c.id(),0).userMessage().content());assertNull(turn(c.id(),0).assistantMessage());
        } finally { gate.countDown(); }
        tasks.close();var d=store.create(null);code(ErrorCode.QUEUE_FULL,()->send(d.id(),"CLOSED_USER"));assertEquals(TurnStatus.FAILED,turn(d.id(),0).status());
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
    @Test void archiveAndRenameDuringExecutionPreserveAdmittedContextAndAllowSuccessfulCompletion() throws Exception {
        var entered=new CountDownLatch(1);var gate=new CountDownLatch(1);
        work=cancel->{entered.countDown();try{gate.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return "OK";};
        var c=store.create("OLD_TITLE");var accepted=send(c.id(),"USER");assertTrue(entered.await(1,TimeUnit.SECONDS));
        store.rename(c.id(),"NEW_TITLE");store.archive(c.id());gate.countDown();assertEquals(TaskStatus.SUCCEEDED,terminal(accepted.taskId()).status());
        assertEquals(TurnStatus.SUCCEEDED,turn(c.id(),0).status());assertEquals(List.of("USER"),received.get().messages().stream().map(Provider.ChatMessage::content).toList());
        var before=store.detail(c.id(),0,10).conversation().updatedAt();tasks.get(accepted.taskId());assertEquals(before,store.detail(c.id(),0,10).conversation().updatedAt());
    }
    @Test void queueAndExecutionTimeoutPersistWithoutAssistantAndLateResult() throws Exception {
        close();initialize(Duration.ofMillis(80),Duration.ofMillis(180),Duration.ofSeconds(2));
        var gate=new CountDownLatch(1);var entered=new CountDownLatch(1);
        work=cancel->{entered.countDown();try{gate.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return "LATE";};
        var a=store.create(null);var b=store.create(null);var running=send(a.id(),"A");assertTrue(entered.await(1,TimeUnit.SECONDS));var queued=send(b.id(),"B");
        try {
            assertEquals(TaskStatus.TIMED_OUT,terminal(queued.taskId()).status());assertEquals(TaskStatus.TIMED_OUT,terminal(running.taskId()).status());
            assertEquals(TurnStatus.TIMED_OUT,turn(a.id(),0).status());assertEquals(TurnStatus.TIMED_OUT,turn(b.id(),0).status());
            assertNull(turn(a.id(),0).assistantMessage());assertNull(turn(b.id(),0).assistantMessage());
        } finally {gate.countDown();}
        code(ErrorCode.CONVERSATION_CONFLICT,()->store.finalizeExecution(a.id(),running.turnId(),running.taskId(),TurnStatus.SUCCEEDED,"LATE",null));
    }
    @Test void assistantStorageFailureRollsBackAndTaskCannotClaimSuccess() throws Exception {
        var c=store.create(null);
        sql("CREATE TRIGGER fault BEFORE INSERT ON conversation_messages WHEN new.role='ASSISTANT' BEGIN SELECT RAISE(ABORT,'private fault'); END");
        var accepted=send(c.id(),"USER");var failed=terminal(accepted.taskId());
        assertEquals(TaskStatus.FAILED,failed.status());assertEquals(ErrorCode.CONVERSATION_STORAGE_UNAVAILABLE,failed.error().code());
        assertEquals(TurnStatus.FAILED,turn(c.id(),0).status());assertEquals(FailureCode.STORAGE_UNAVAILABLE,turn(c.id(),0).failureCode());assertNull(turn(c.id(),0).assistantMessage());
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
    @Test void explicitRevisionSnapshotSurvivesEditAndDeleteWithoutReferenceLifecycleConflict() throws Exception {
        var item=memory.create(MemoryItem.Type.PROJECT_NOTE,"MEMORY_TITLE","MEMORY_A");var c=store.create(null);
        var entered=new CountDownLatch(1);var gate=new CountDownLatch(1);
        work=cancel->{entered.countDown();try{gate.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return "OK";};
        var first=execution.submit(c.id(),new ConversationExecution.Request("USER",List.of(new MemoryReference(item.id(),1))));assertTrue(entered.await(1,TimeUnit.SECONDS));
        var changed=memory.update(item.id(),1,MemoryItem.Type.PROJECT_NOTE,"changed","MEMORY_B");memory.delete(item.id(),changed.revision());
        gate.countDown();assertEquals(TaskStatus.SUCCEEDED,terminal(first.taskId()).status());
        assertTrue(received.get().messages().getFirst().content().contains("MEMORY_A"));assertFalse(received.get().messages().getFirst().content().contains("MEMORY_B"));
        assertEquals(item.id(),turn(c.id(),0).memories().getFirst().memoryId());
        var second=send(c.id(),"NEXT");terminal(second.taskId());assertEquals(0,second.memoryCount());assertTrue(turn(c.id(),1).memories().isEmpty());
        code(ErrorCode.MEMORY_SELECTION_STALE,()->execution.submit(c.id(),new ConversationExecution.Request("USER",List.of(new MemoryReference(item.id(),1)))));
        assertEquals(2,store.detail(c.id(),0,10).totalTurns());
    }
    @Test void taskRetentionExpiryCannotDeleteDurableConversation() throws Exception {
        close();initialize(Duration.ofSeconds(2),Duration.ofSeconds(2),Duration.ofMillis(50));
        var c=store.create(null);var accepted=send(c.id(),"RETAINED_USER");terminal(accepted.taskId());var saved=store.detail(c.id(),0,10);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(true) {
            try {tasks.get(accepted.taskId());}
            catch(WorkspaceException expired){assertEquals(ErrorCode.TASK_NOT_FOUND,expired.error().code());break;}
            assertTrue(System.nanoTime()<end);Thread.sleep(10);
        }
        assertEquals(saved,store.detail(c.id(),0,10));
    }
    @Test void policyAndUnexpectedSubmissionFailuresAreSanitizedAndUserRemains() {
        var settings=TestSettings.withTasks(TestSettings.tasks(Duration.ofSeconds(2),Duration.ofSeconds(2),Duration.ofSeconds(2)));
        var profiles=new ProfileResolver(settings);var registry=new ProviderRegistry(List.of());
        var denied=new ConversationExecution(store,memory,profiles,new TextTaskSubmission(profiles,registry,new ProviderPolicy(),tasks),tasks);
        var c=store.create(null);code(ErrorCode.POLICY_DENIED,()->denied.submit(c.id(),new ConversationExecution.Request("USER",List.of())));
        assertEquals(FailureCode.POLICY_DENIED,turn(c.id(),0).failureCode());assertNull(turn(c.id(),0).assistantMessage());
        var broken=new TextTaskSubmission(profiles,registry,new ProviderPolicy(),tasks) {
            @Override public Prepared prepareConversation(String system,List<Provider.ChatMessage> messages){throw new IllegalStateException("raw secret provider body");}
        };
        var failed=new ConversationExecution(store,memory,profiles,broken,tasks);var d=store.create(null);
        var error=assertThrows(WorkspaceException.class,()->failed.submit(d.id(),new ConversationExecution.Request("USER",List.of())));
        assertEquals(ErrorCode.INTERNAL_ERROR,error.error().code());assertNull(error.getCause());assertFalse(error.toString().contains("raw secret"));
        assertEquals(TurnStatus.FAILED,turn(d.id(),0).status());assertEquals(0,calls.get());
    }
    @Test void totalStorageOutageNeverClaimsSuccessAndLeavesPendingForFailClosedRecovery() throws Exception {
        var c=store.create(null);work=cancel->{store.close();return "RESPONSE";};
        var accepted=send(c.id(),"USER");var outcome=terminal(accepted.taskId());assertEquals(TaskStatus.FAILED,outcome.status());
        assertEquals(ErrorCode.CONVERSATION_STORAGE_UNAVAILABLE,outcome.error().code());assertNull(outcome.result());
        store=new ConversationStore(memory.databaseFile());assertEquals(TurnStatus.PENDING,turn(c.id(),0).status());assertNull(turn(c.id(),0).assistantMessage());
        assertEquals(1,store.reconcilePending());assertEquals(TurnStatus.FAILED,turn(c.id(),0).status());assertEquals(1,calls.get());
    }
}
