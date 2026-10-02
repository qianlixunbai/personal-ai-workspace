package io.github.qianlixunbai.workspace.capability.summarize;

import io.github.qianlixunbai.workspace.capability.TextTaskSubmission;
import io.github.qianlixunbai.workspace.task.TaskView;
import org.springframework.stereotype.Service;

@Service
public class SummarizeService {
    private final TextTaskSubmission tasks;
    public SummarizeService(TextTaskSubmission tasks) { this.tasks = tasks; }
    public TaskView submit(SummarizeRequest request) {
        return tasks.submit("summarize", request.profile() == null ? "summarize.fast" : request.profile(),
                SummarizePrompt.VERSION, SummarizePrompt.system(request.targetLanguage()), request.text());
    }
}
