package io.github.qianlixunbai.workspace.capability.translate;

import io.github.qianlixunbai.workspace.capability.TextTaskSubmission;
import io.github.qianlixunbai.workspace.task.TaskView;
import org.springframework.stereotype.Service;

@Service
public class TranslateService {
    private final TextTaskSubmission tasks;
    public TranslateService(TextTaskSubmission tasks) { this.tasks = tasks; }
    public TaskView submit(TranslateRequest request) {
        return tasks.submit("translate", request.profile() == null ? "translate.fast" : request.profile(),
                TranslatePrompt.VERSION, TranslatePrompt.system(request.sourceLanguage(), request.targetLanguage()), request.text());
    }
}
