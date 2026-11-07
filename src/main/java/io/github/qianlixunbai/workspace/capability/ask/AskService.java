package io.github.qianlixunbai.workspace.capability.ask;

import io.github.qianlixunbai.workspace.capability.TextTaskSubmission;
import io.github.qianlixunbai.workspace.task.TaskView;
import org.springframework.stereotype.Service;

@Service
public class AskService {
    private final TextTaskSubmission tasks;
    public AskService(TextTaskSubmission tasks) { this.tasks = tasks; }
    public TaskView submit(AskRequest request) {
        if (request.outputContract() != null) {
            if (!"memory-candidate-v1".equals(request.outputContract()))
                throw new io.github.qianlixunbai.workspace.common.WorkspaceException(
                        io.github.qianlixunbai.workspace.common.ErrorCode.INVALID_REQUEST, "OUTPUT_CONTRACT");
            return tasks.submitStructured("ask", request.profile() == null ? "chat.balanced" : request.profile(),
                    "memory-candidate-v1", AskPrompt.SYSTEM, request.question(),
                    io.github.qianlixunbai.workspace.provider.ResponseFormat.MEMORY_CANDIDATE);
        }
        return tasks.submit("ask", request.profile() == null ? "chat.balanced" : request.profile(),
                AskPrompt.VERSION, AskPrompt.SYSTEM, request.question());
    }
}
