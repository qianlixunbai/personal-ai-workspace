package io.github.qianlixunbai.workspace.capability.translate;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.model.*;
import io.github.qianlixunbai.workspace.policy.*;
import io.github.qianlixunbai.workspace.provider.*;
import io.github.qianlixunbai.workspace.task.*;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;

@Service
public class TranslateService {
    private final ProfileResolver profiles;
    private final ProviderRegistry providers;
    private final ProviderPolicy policy;
    private final TaskManager tasks;

    public TranslateService(ProfileResolver profiles, ProviderRegistry providers, ProviderPolicy policy, TaskManager tasks) {
        this.profiles = profiles; this.providers = providers; this.policy = policy; this.tasks = tasks;
    }

    public TaskView submit(TranslateRequest request) {
        ModelProfile profile = profiles.resolve(request.profile() == null ? "translate.fast" : request.profile());
        // Conservative UTF-8 byte ceiling reserves output and 512 tokens for instructions/template.
        if (request.text().length() > profile.maxTextCharacters()
                || request.text().getBytes(StandardCharsets.UTF_8).length > profile.contextBudget() - profile.outputBudget() - 512) {
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "INPUT_BUDGET");
        }
        Provider provider = providers.resolve(profile.provider());
        policy.verify(profile, provider, PrivacyMode.LOCAL_ONLY);
        if (!provider.capabilities().contains(Provider.Capability.TEXT_GENERATION))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        Provider.ProviderExecution execution = new Provider.ProviderExecution(profile, PrivacyMode.LOCAL_ONLY,
                TranslatePrompt.system(request.sourceLanguage(), request.targetLanguage()), request.text());
        return tasks.submit(profile, TranslatePrompt.VERSION, cancellation -> {
            policy.verify(profile, provider, PrivacyMode.LOCAL_ONLY);
            return provider.execute(execution, cancellation);
        });
    }
}
