package io.github.qianlixunbai.workspace.capability;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.model.*;
import io.github.qianlixunbai.workspace.policy.*;
import io.github.qianlixunbai.workspace.provider.*;
import io.github.qianlixunbai.workspace.task.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

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
        return submit(capability, profileId, promptVersion, system, input, input == null ? 0 : input.length(), output -> output);
    }
    public TaskView submitMapped(String capability, String profileId, String promptVersion, String system, String input,
                                 int textCharacters, Function<String, TaskResult> mapping) {
        return submit(capability, profileId, promptVersion, system, input, textCharacters, mapping);
    }
    private TaskView submit(String capability, String profileId, String promptVersion, String system, String input,
                            int textCharacters, Function<String, ?> mapping) {
        var client = ClientIdentity.current();
        if (!client.allowedCapabilities().contains(capability))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        ModelProfile profile = profiles.resolve(profileId);
        // Worst-case UTF-8 input bytes conservatively stand in for tokens; reserve template and output.
        if (!fitsInput(profile, input, textCharacters))
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "INPUT_BUDGET");
        if (system.getBytes(StandardCharsets.UTF_8).length > 512)
            throw new WorkspaceException(ErrorCode.INTERNAL_ERROR, "PROMPT_BUDGET");
        Provider provider = providers.resolve(profile.provider());
        policy.verify(profile, provider, PrivacyMode.LOCAL_ONLY);
        if (!provider.capabilities().contains(Provider.Capability.TEXT_GENERATION))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        Provider.ProviderExecution execution = new Provider.ProviderExecution(profile, PrivacyMode.LOCAL_ONLY, system, input);
        return tasks.submit(client.clientId(), capability, profile, promptVersion, cancellation -> {
            policy.verify(profile, provider, PrivacyMode.LOCAL_ONLY);
            String output = provider.execute(execution, cancellation);
            if (output == null || output.isBlank()
                    || output.getBytes(StandardCharsets.UTF_8).length > profile.outputBudget() * 4)
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "OUTPUT_BUDGET");
            return mapping.apply(output);
        });
    }
    /** Uses the submission budget against the actual serialized candidate; submission revalidates. */
    public boolean fitsInput(String profileId, String input) {
        return fitsInput(profiles.resolve(profileId), input, input == null ? 0 : input.length());
    }
    private static boolean fitsInput(ModelProfile profile, String input, int textCharacters) {
        return input != null && !input.isBlank() && textCharacters <= profile.maxTextCharacters()
                && input.getBytes(StandardCharsets.UTF_8).length <= profile.contextBudget() - profile.outputBudget() - 512;
    }
    public Provider.ProviderReadiness readiness(String capability, String profileId) {
        if (!ClientIdentity.current().allowedCapabilities().contains(capability))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        ModelProfile profile = profiles.resolve(profileId);
        Provider provider = providers.resolve(profile.provider());
        policy.verify(profile, provider, PrivacyMode.LOCAL_ONLY);
        if (!provider.capabilities().contains(Provider.Capability.TEXT_GENERATION))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        return provider.readiness(profile);
    }
    public record Prepared(ModelProfile profile, TaskManager.Work work) {
        @Override public String toString() { return "PreparedExecution[redacted]"; }
    }
    /** Prepare through the existing profile/policy/provider stack; no work starts at admission. */
    public Prepared prepareConversation(String system, List<Provider.ChatMessage> messages) {
        if (!ClientIdentity.current().equals(ClientIdentity.NATIVE)) throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        ModelProfile profile = profiles.resolve("chat.balanced");
        String serialized = JsonMapper.builder().build().writeValueAsString(messages);
        if (messages.isEmpty() || serialized.length() > profile.maxTextCharacters()
                || serialized.getBytes(StandardCharsets.UTF_8).length > profile.contextBudget() - profile.outputBudget() - 512)
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "CONTEXT_BUDGET");
        if (system.getBytes(StandardCharsets.UTF_8).length > 512) throw new WorkspaceException(ErrorCode.INTERNAL_ERROR, "PROMPT_BUDGET");
        Provider provider = providers.resolve(profile.provider());
        var execution = new Provider.ProviderExecution(profile, PrivacyMode.LOCAL_ONLY, system, serialized, messages);
        policy.verify(profile, provider, PrivacyMode.LOCAL_ONLY);
        if (!provider.capabilities().contains(Provider.Capability.TEXT_GENERATION)) throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        return new Prepared(profile, cancellation -> {
            policy.verify(profile, provider, PrivacyMode.LOCAL_ONLY);
            if (!provider.capabilities().contains(Provider.Capability.TEXT_GENERATION)) throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
            String output = provider.execute(execution, cancellation);
            if (output == null || output.isBlank() || output.getBytes(StandardCharsets.UTF_8).length > profile.outputBudget() * 4)
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "OUTPUT_BUDGET");
            try { io.github.qianlixunbai.workspace.conversation.ConversationLimits.content(output); }
            catch (WorkspaceException invalid) { throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "OUTPUT_VALIDATION"); }
            return output;
        });
    }
}
