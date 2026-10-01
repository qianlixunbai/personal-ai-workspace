package io.github.qianlixunbai.workspace.provider;

import io.github.qianlixunbai.workspace.common.*;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ProviderRegistry {
    private final Map<String, Provider> providers;
    public ProviderRegistry(List<Provider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(Provider::id, Function.identity()));
    }
    public Provider resolve(String id) {
        Provider provider = providers.get(id);
        if (provider == null) throw new WorkspaceException(ErrorCode.POLICY_DENIED, "PROVIDER_SELECTION");
        return provider;
    }
}
