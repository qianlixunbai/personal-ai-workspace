package io.github.qianlixunbai.workspace.capability;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.model.*;
import io.github.qianlixunbai.workspace.policy.*;
import io.github.qianlixunbai.workspace.provider.*;
import io.github.qianlixunbai.workspace.task.*;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;

/** Shared execution for the three concrete text capabilities; prompts remain capability-owned. */
@Service
public class TextTaskSubmission {
    private final ProfileResolver profiles;
    private final ProviderRegistry providers;
    private final ProviderPolicy policy;
    private final TaskManager tasks;
    public TextTaskSubmission(ProfileResolver profiles, ProviderRegistry providers, ProviderPolicy policy, TaskManager tasks) {
        this.profiles = profiles; this.providers = providers; this.policy = policy; this.tasks = tasks;
    }
    public TaskView submit(String capability, String profileId, String promptVersion, String system, String input) {
        ModelProfile profile = profiles.resolve(profileId);
        // Worst-case UTF-8 input bytes conservatively stand in for tokens; reserve template and output.
        if (input == null || input.isBlank() || input.length() > profile.maxTextCharacters()
                || input.getBytes(StandardCharsets.UTF_8).length > profile.contextBudget() - profile.outputBudget() - 512)
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "INPUT_BUDGET");
        if (system.getBytes(StandardCharsets.UTF_8).length > 512)
            throw new WorkspaceException(ErrorCode.INTERNAL_ERROR, "PROMPT_BUDGET");
        Provider provider = providers.resolve(profile.provider());
        policy.verify(profile, provider, PrivacyMode.LOCAL_ONLY);
        if (!provider.capabilities().contains(Provider.Capability.TEXT_GENERATION))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        Provider.ProviderExecution execution = new Provider.ProviderExecution(profile, PrivacyMode.LOCAL_ONLY, system, input);
        return tasks.submit(capability, profile, promptVersion, cancellation -> {
            policy.verify(profile, provider, PrivacyMode.LOCAL_ONLY);
            String output = provider.execute(execution, cancellation);
            if (output == null || output.isBlank()
                    || output.getBytes(StandardCharsets.UTF_8).length > profile.outputBudget() * 4)
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "OUTPUT_BUDGET");
            return output;
        });
    }
}
