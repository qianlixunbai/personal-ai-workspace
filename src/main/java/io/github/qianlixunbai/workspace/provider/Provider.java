package io.github.qianlixunbai.workspace.provider;

import io.github.qianlixunbai.workspace.model.ModelProfile;
import io.github.qianlixunbai.workspace.policy.PrivacyMode;
import io.github.qianlixunbai.workspace.task.Cancellation;
import java.util.Set;
import java.util.List;

public interface Provider {
    enum Capability { TEXT_GENERATION }
    String id();
    ModelProfile.Locality locality();
    Set<Capability> capabilities();
    String execute(ProviderExecution execution, Cancellation cancellation);
    ProviderReadiness readiness(ModelProfile profile);

    record ChatMessage(String role, String content) {
        public ChatMessage {
            if (!Set.of("user", "assistant").contains(role) || content == null || content.isBlank()) throw new IllegalArgumentException("Invalid chat message");
        }
        @Override public String toString() { return "ChatMessage[redacted]"; }
    }
    record ProviderExecution(ModelProfile profile, PrivacyMode privacyMode, String system, String input, List<ChatMessage> messages) {
        public ProviderExecution { messages = List.copyOf(messages); }
        public ProviderExecution(ModelProfile profile, PrivacyMode privacyMode, String system, String input) {
            this(profile, privacyMode, system, input, List.of(new ChatMessage("user", input)));
        }
        @Override public String toString() { return "ProviderExecution[redacted]"; }
    }
    record ProviderReadiness(String provider, String profile, boolean available,
                             boolean modelAvailable, io.github.qianlixunbai.workspace.common.ApiError error) {}
}
