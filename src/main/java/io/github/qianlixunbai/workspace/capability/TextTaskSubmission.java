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
    private final ActiveModelManager models;
    public TextTaskSubmission(ProfileResolver profiles, ProviderRegistry providers, ProviderPolicy policy, TaskManager tasks, ActiveModelManager models) {
        this.profiles = profiles; this.providers = providers; this.policy = policy; this.tasks = tasks; this.models = models;
    }
    public TaskView submit(String capability, String profileId, String promptVersion, String system, String input) {
        return submit(capability, profileId, promptVersion, system, input, input == null ? 0 : input.length(), output -> output);
    }
    public TaskView submitStructured(String capability, String profileId, String promptVersion, String system, String input,
                                     ResponseFormat format) {
        if (!ClientIdentity.NATIVE.equals(ClientIdentity.current()))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        var reservation = reserve(capability, profileId, promptVersion);
        try { return submitReserved(reservation, capability, system, input, input == null ? 0 : input.length(), output -> output, format); }
        finally { reservation.closeUnlessTransferred(); }
    }
    public TaskView submitMapped(String capability, String profileId, String promptVersion, String system, String input,
                                 int textCharacters, Function<String, TaskResult> mapping) {
        return submit(capability, profileId, promptVersion, system, input, textCharacters, mapping);
    }
    private TaskView submit(String capability, String profileId, String promptVersion, String system, String input,
                            int textCharacters, Function<String, ?> mapping) {
        var reservation = reserve(capability, profileId, promptVersion);
        try {
            return submitReserved(reservation, capability, system, input, textCharacters, mapping);
        } finally { reservation.closeUnlessTransferred(); }
    }
    public ActiveModelManager.Reservation reserve(String capability, String profileId, String promptVersion) {
        var client = ClientIdentity.current();
        if (!client.allowedCapabilities().contains(capability))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        return models.reserve(profileId, promptVersion);
    }
    public TaskView submitReserved(ActiveModelManager.Reservation reservation, String capability, String system, String input) {
        return submitReserved(reservation, capability, system, input, input == null ? 0 : input.length(), output -> output);
    }
    public TaskView submitReserved(ActiveModelManager.Reservation reservation, String capability, String system, String input,
                                   int textCharacters, Function<String, ?> mapping) {
        return submitReserved(reservation, capability, system, input, textCharacters, mapping, ResponseFormat.TEXT);
    }
    private TaskView submitReserved(ActiveModelManager.Reservation reservation, String capability, String system, String input,
                                   int textCharacters, Function<String, ?> mapping, ResponseFormat format) {
        return submitReserved(reservation,capability,system,input,textCharacters,mapping,format,java.util.UUID.randomUUID(),null,()->{});
    }
    public TaskView submitPersistent(ActiveModelManager.Reservation reservation, String capability, String system, String input,
                                    Function<String, ?> mapping, java.util.UUID id, TaskManager.Completion completion, Runnable admission) {
        return submitReserved(reservation,capability,system,input,input.length(),mapping,ResponseFormat.TEXT,id,completion,admission);
    }
    private TaskView submitReserved(ActiveModelManager.Reservation reservation, String capability, String system, String input,
                                   int textCharacters, Function<String, ?> mapping, ResponseFormat format, java.util.UUID id,
                                   TaskManager.Completion completion, Runnable admission) {
        var client = ClientIdentity.current();
        if (!client.allowedCapabilities().contains(capability)) throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        ModelProfile profile = reservation.profile();
        // Worst-case UTF-8 input bytes conservatively stand in for tokens; reserve template and output.
        if (!fitsInput(profile, input, textCharacters))
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "INPUT_BUDGET");
        if (system.getBytes(StandardCharsets.UTF_8).length > 512)
            throw new WorkspaceException(ErrorCode.INTERNAL_ERROR, "PROMPT_BUDGET");
        Provider provider = providers.resolve(profile.provider());
        policy.verify(profile, provider, PrivacyMode.LOCAL_ONLY);
        if (!provider.capabilities().contains(Provider.Capability.TEXT_GENERATION))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        if (format != ResponseFormat.TEXT && !provider.capabilities().contains(Provider.Capability.STRUCTURED_OUTPUT))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "OUTPUT_CONTRACT");
        Provider.ProviderExecution execution = new Provider.ProviderExecution(profile, PrivacyMode.LOCAL_ONLY, system, input,
                List.of(new Provider.ChatMessage("user", input)), reservation, format);
        admission.run(); // Durable pending record must commit before any provider execution.
        reservation.transferToTask();
        try { return tasks.submit(id, client.clientId(), capability, profile, reservation.promptVersion(), cancellation -> {
            policy.verify(profile, provider, PrivacyMode.LOCAL_ONLY);
            String output = provider.execute(execution, cancellation);
            if (output == null || output.isBlank()
                    || output.getBytes(StandardCharsets.UTF_8).length > profile.outputBudget() * 4)
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "OUTPUT_BUDGET");
            return mapping.apply(output);
        }, completion, reservation); }
        catch (RuntimeException rejected) {
            reservation.close();
            if(completion!=null) completion.finish(id,TaskStatus.FAILED,null,
                    rejected instanceof WorkspaceException w ? w.error() : ApiError.of(ErrorCode.INTERNAL_ERROR,"ADMISSION"));
            throw rejected;
        }
    }
    /** Uses the submission budget against the actual serialized candidate; submission revalidates. */
    public boolean fitsInput(String profileId, String input) {
        return fitsInput(profiles.resolve(profileId), input, input == null ? 0 : input.length());
    }
    public boolean fitsInput(ActiveModelManager.Reservation reservation, String input) {
        return fitsInput(reservation.profile(), input, input == null ? 0 : input.length());
    }
    private static boolean fitsInput(ModelProfile profile, String input, int textCharacters) {
        return input != null && !input.isBlank() && textCharacters <= profile.maxTextCharacters()
                && input.getBytes(StandardCharsets.UTF_8).length <= profile.contextBudget() - profile.outputBudget() - 512;
    }
    public Provider.ProviderReadiness readiness(String capability, String profileId) {
        return cacheReadiness(capability, profileId, false).readiness();
    }
    public ActiveModelManager.ReadinessSnapshot cacheReadiness(String capability, String profileId, boolean legacyBrowser) {
        if (!ClientIdentity.current().allowedCapabilities().contains(capability))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        ModelProfile profile = profiles.resolve(profileId);
        Provider provider = providers.resolve(profile.provider());
        policy.verify(profile, provider, PrivacyMode.LOCAL_ONLY);
        if (!provider.capabilities().contains(Provider.Capability.TEXT_GENERATION))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        return models.cacheReadiness(profileId, legacyBrowser);
    }
    public record Prepared(ModelProfile profile, TaskManager.Work work) {
        @Override public String toString() { return "PreparedExecution[redacted]"; }
    }
    /** Prepare through the existing profile/policy/provider stack; no work starts at admission. */
    public Prepared prepareConversation(ActiveModelManager.Reservation reservation, String system, List<Provider.ChatMessage> messages) {
        if (!ClientIdentity.current().equals(ClientIdentity.NATIVE)) throw new WorkspaceException(ErrorCode.POLICY_DENIED, "CAPABILITY");
        ModelProfile profile = reservation.profile();
        String serialized = JsonMapper.builder().build().writeValueAsString(messages);
        if (messages.isEmpty() || serialized.length() > profile.maxTextCharacters()
                || serialized.getBytes(StandardCharsets.UTF_8).length > profile.contextBudget() - profile.outputBudget() - 512)
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "CONTEXT_BUDGET");
        if (system.getBytes(StandardCharsets.UTF_8).length > 512) throw new WorkspaceException(ErrorCode.INTERNAL_ERROR, "PROMPT_BUDGET");
        Provider provider = providers.resolve(profile.provider());
        var execution = new Provider.ProviderExecution(profile, PrivacyMode.LOCAL_ONLY, system, serialized, messages, reservation);
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
