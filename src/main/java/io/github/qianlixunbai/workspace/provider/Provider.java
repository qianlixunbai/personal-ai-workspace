package io.github.qianlixunbai.workspace.provider;

import io.github.qianlixunbai.workspace.model.ModelProfile;
import io.github.qianlixunbai.workspace.policy.PrivacyMode;
import io.github.qianlixunbai.workspace.task.Cancellation;
import java.util.Set;

public interface Provider {
    enum Capability { TEXT_GENERATION }
    String id();
    ModelProfile.Locality locality();
    Set<Capability> capabilities();
    String execute(ProviderExecution execution, Cancellation cancellation);
    ProviderReadiness readiness(ModelProfile profile);

    record ProviderExecution(ModelProfile profile, PrivacyMode privacyMode, String system, String input) {
        @Override public String toString() { return "ProviderExecution[redacted]"; }
    }
    record ProviderReadiness(String provider, String profile, boolean available,
                             boolean modelAvailable, io.github.qianlixunbai.workspace.common.ApiError error) {}
}
