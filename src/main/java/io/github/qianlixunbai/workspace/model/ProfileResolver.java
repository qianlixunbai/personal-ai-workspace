package io.github.qianlixunbai.workspace.model;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import org.springframework.stereotype.Component;

@Component
public class ProfileResolver {
    private final ModelProfile profile;
    public ProfileResolver(RuntimeProperties properties) {
        profile = properties.translate();
        if (!profile.id().equals("translate.fast") || profile.outputBudget() + 512 >= profile.contextBudget()) {
            throw new IllegalArgumentException("Invalid translate profile budgets or identity");
        }
    }
    public ModelProfile resolve(String id) {
        if (!profile.id().equals(id)) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "PROFILE");
        return profile;
    }
}
