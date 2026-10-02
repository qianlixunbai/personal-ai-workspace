package io.github.qianlixunbai.workspace.capability.ask;

import io.github.qianlixunbai.workspace.capability.TextTaskSubmission;
import io.github.qianlixunbai.workspace.task.TaskView;
import org.springframework.stereotype.Service;

@Service
public class AskService {
    private final TextTaskSubmission tasks;
    public AskService(TextTaskSubmission tasks) { this.tasks = tasks; }
    public TaskView submit(AskRequest request) {
        return tasks.submit("ask", request.profile() == null ? "chat.balanced" : request.profile(),
                AskPrompt.VERSION, AskPrompt.SYSTEM, request.question());
    }
}
