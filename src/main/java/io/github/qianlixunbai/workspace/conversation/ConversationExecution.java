package io.github.qianlixunbai.workspace.conversation;

import io.github.qianlixunbai.workspace.capability.TextTaskSubmission;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.model.ProfileResolver;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import io.github.qianlixunbai.workspace.task.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static io.github.qianlixunbai.workspace.conversation.Conversation.*;

@Service
public class ConversationExecution {
    private final ConversationStore store;
    private final MemoryStore memory;
    private final ProfileResolver profiles;
    private final TextTaskSubmission text;
    private final TaskManager tasks;
    public ConversationExecution(ConversationStore store, MemoryStore memory, ProfileResolver profiles, TextTaskSubmission text, TaskManager tasks) {
        this.store = store; this.memory = memory; this.profiles = profiles; this.text = text; this.tasks = tasks;
    }
    public record Request(String message, List<MemoryReference> memories) {
        @Override public String toString() { return "ConversationSend[redacted]"; }
    }
    public record Accepted(UUID conversationId, UUID turnId, UUID taskId, TaskStatus status, int memoryCount,
                           List<Long> admittedSequences, int inputCharacters, int inputBytes) {
        public Accepted { admittedSequences = List.copyOf(admittedSequences); }
    }
    public Accepted submit(UUID conversationId, Request request) {
        if (!ClientIdentity.current().equals(ClientIdentity.NATIVE)) throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CONVERSATION");
        if (request == null) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "CONVERSATION");
        ConversationLimits.content(request.message());
        if (request.memories() != null && !request.memories().isEmpty()) MemoryReference.validate(request.memories());
        var refs = request.memories() == null ? List.<MemoryReference>of() : List.copyOf(request.memories());
        var snapshot = refs.isEmpty() ? List.<MemorySnapshot>of() : memory.snapshotForAsk(refs);
        var reservation = text.reserve("conversation", "chat.balanced", ConversationContext.VERSION);
        try {
            // The model gate precedes all profile/context validation and durable USER mutation.
            var profile = reservation.profile();
            ConversationContext.assemble(profile, request.message(), snapshot, List.of());
            UUID taskId = UUID.randomUUID();
            Turn turn = store.createTurnWithUserMessage(conversationId, request.message(), taskId, refs);
            try {
                var context = ConversationContext.assemble(profile, request.message(), snapshot, store.successfulHistory(conversationId));
                var prepared = text.prepareConversation(reservation, ConversationContext.SYSTEM, context.messages());
                reservation.transferToTask();
                var accepted = tasks.submit(taskId, ClientIdentity.NATIVE_OWNER, "conversation", prepared.profile(), ConversationContext.VERSION,
                        prepared.work(), (id, status, result, error) -> store.finalizeExecution(conversationId, turn.id(), id,
                                switch (status) {
                                    case SUCCEEDED -> TurnStatus.SUCCEEDED;
                                    case CANCELLED -> TurnStatus.CANCELLED;
                                    case TIMED_OUT -> TurnStatus.TIMED_OUT;
                                    default -> TurnStatus.FAILED;
                                }, status == TaskStatus.SUCCEEDED ? (String) result : null,
                                status == TaskStatus.FAILED ? failure(error == null ? ErrorCode.INTERNAL_ERROR : error.code()) : null), reservation);
                return new Accepted(conversationId, turn.id(), taskId, accepted.status(), context.memoryCount(),
                        context.admittedSequences(), context.inputCharacters(), context.inputBytes());
            } catch (RuntimeException rejected) {
                reservation.close();
                ErrorCode code = rejected instanceof WorkspaceException controlled ? controlled.error().code() : ErrorCode.INTERNAL_ERROR;
                store.finalizeExecution(conversationId, turn.id(), taskId, TurnStatus.FAILED, null, failure(code));
                if (rejected instanceof WorkspaceException controlled) throw controlled;
                throw new WorkspaceException(ErrorCode.INTERNAL_ERROR, "SUBMISSION");
            }
        } finally { reservation.closeUnlessTransferred(); }
    }
    private static FailureCode failure(ErrorCode code) {
        return switch (code) {
            case PROVIDER_UNAVAILABLE -> FailureCode.PROVIDER_UNAVAILABLE;
            case MODEL_UNAVAILABLE -> FailureCode.MODEL_UNAVAILABLE;
            case QUEUE_FULL -> FailureCode.QUEUE_FULL;
            case POLICY_DENIED -> FailureCode.POLICY_DENIED;
            case CONVERSATION_STORAGE_UNAVAILABLE -> FailureCode.STORAGE_UNAVAILABLE;
            default -> FailureCode.EXECUTION_FAILED;
        };
    }
}
