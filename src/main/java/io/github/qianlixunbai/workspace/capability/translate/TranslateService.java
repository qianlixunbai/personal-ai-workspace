package io.github.qianlixunbai.workspace.capability.translate;

import io.github.qianlixunbai.workspace.capability.TextTaskSubmission;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.task.TaskView;
import org.springframework.stereotype.Service;

@Service
public class TranslateService {
    private final TextTaskSubmission tasks;
    public TranslateService(TextTaskSubmission tasks) { this.tasks = tasks; }
    public TaskView submit(TranslateRequest request) {
        if (!request.isExactlyOneInput()) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "INPUT");
        String profile = request.profile() == null ? "translate.fast" : request.profile();
        if (request.items() != null) {
            String input = TranslateBatch.input(request.items());
            return tasks.submitMapped("translate", profile, TranslateBatchPrompt.VERSION,
                    TranslateBatchPrompt.system(request.sourceLanguage(), request.targetLanguage()), input,
                    request.items().stream().mapToInt(item -> item.text().length()).sum(),
                    output -> TranslateBatch.result(output, request.items()));
        }
        return tasks.submit("translate", profile,
                TranslatePrompt.VERSION, TranslatePrompt.system(request.sourceLanguage(), request.targetLanguage()), request.text());
    }
    public TranslateReadiness readiness() { return readiness(false); }
    public TranslateReadiness readiness(boolean identityV1) {
        boolean browser = !io.github.qianlixunbai.workspace.security.ClientIdentity.current().clientType().equals("native");
        var snapshot = tasks.cacheReadiness("translate", "translate.fast", browser && !identityV1);
        var ready = snapshot.readiness();
        boolean available = ready.available() && ready.modelAvailable() && ready.error() == null;
        return new TranslateReadiness(available, available ? null : new TranslateReadiness.Error(ErrorCode.PROVIDER_UNAVAILABLE),
                available && identityV1 ? new TranslateReadiness.CacheIdentity(1,
                        new TranslateReadiness.Identity(snapshot.profile(), TranslatePrompt.VERSION),
                        new TranslateReadiness.Identity(snapshot.profile(), TranslateBatchPrompt.VERSION)) : null);
    }
}
