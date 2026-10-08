package io.github.qianlixunbai.workspace.health;

import io.github.qianlixunbai.workspace.model.*;
import io.github.qianlixunbai.workspace.provider.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProviderHealthController {
    private final ProfileResolver profiles;
    private final ProviderRegistry providers;
    private final ActiveModelManager models;
    public ProviderHealthController(ProfileResolver profiles, ProviderRegistry providers, ActiveModelManager models) {
        this.profiles = profiles; this.providers = providers; this.models = models;
    }
    @GetMapping("/api/v1/providers/readiness")
    public Provider.ProviderReadiness readiness() {
        ModelProfile profile = profiles.resolve("translate.fast");
        return models.readiness(profile.id());
    }
}
