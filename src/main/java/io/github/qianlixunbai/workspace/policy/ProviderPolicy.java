package io.github.qianlixunbai.workspace.policy;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.model.ModelProfile;
import io.github.qianlixunbai.workspace.provider.Provider;
import org.springframework.stereotype.Component;

@Component
public class ProviderPolicy {
    public void verify(ModelProfile profile, Provider provider, PrivacyMode mode) {
        // M0 denies cloud for every policy mode. No fallback or provider retry routing.
        if (mode == null || profile.locality() != ModelProfile.Locality.LOCAL
                || provider.locality() != ModelProfile.Locality.LOCAL
                || !profile.provider().equals(provider.id()) || !provider.id().equals("ollama")) {
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "POLICY");
        }
    }
}
