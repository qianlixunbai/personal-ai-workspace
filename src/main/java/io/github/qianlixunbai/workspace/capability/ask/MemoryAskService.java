package io.github.qianlixunbai.workspace.capability.ask;

import io.github.qianlixunbai.workspace.capability.TextTaskSubmission;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import io.github.qianlixunbai.workspace.task.TaskView;
import org.springframework.stereotype.Service;

@Service
public class MemoryAskService {
    private final TextTaskSubmission tasks;
    private final MemoryStore memory;
    public MemoryAskService(TextTaskSubmission tasks, MemoryStore memory) { this.tasks = tasks; this.memory = memory; }
    public TaskView submit(MemoryAskRequest request) {
        if (!ClientIdentity.current().equals(ClientIdentity.NATIVE))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        if (request == null || request.question() == null || request.question().isBlank()
                || request.profile() != null && !request.profile().equals("chat.balanced"))
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "MEMORY_SELECTION");
        var snapshot = memory.snapshotForAsk(request.memories());
        // Combined serialization, including wrapper/escaping, is the actual input and budget authority.
        return tasks.submit("ask", "chat.balanced", MemoryAskPrompt.VERSION, MemoryAskPrompt.SYSTEM,
                MemoryAskPrompt.input(request.question(), snapshot));
    }
}
