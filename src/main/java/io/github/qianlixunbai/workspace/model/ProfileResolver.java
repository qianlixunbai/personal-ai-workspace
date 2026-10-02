package io.github.qianlixunbai.workspace.model;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ProfileResolver {
    private final Map<String, ModelProfile> profiles;
    public ProfileResolver(RuntimeProperties properties) {
        validate(properties.translate(), "translate.fast");
        validate(properties.summarize(), "summarize.fast");
        validate(properties.ask(), "chat.balanced");
        profiles = Map.of("translate.fast", properties.translate(),
                "summarize.fast", properties.summarize(), "chat.balanced", properties.ask());
    }
    private static void validate(ModelProfile profile, String id) {
        if (!profile.id().equals(id) || profile.outputBudget() + 512 >= profile.contextBudget())
            throw new IllegalArgumentException("Invalid capability profile budgets or identity");
    }
    public ModelProfile resolve(String id) {
        ModelProfile profile = profiles.get(id);
        if (profile == null) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "PROFILE");
        return profile;
    }
}
