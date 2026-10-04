package io.github.qianlixunbai.workspace.provider.ollama;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import io.github.qianlixunbai.workspace.model.ModelProfile;
import io.github.qianlixunbai.workspace.policy.ProviderPolicy;
import io.github.qianlixunbai.workspace.task.Cancellation;
import io.github.qianlixunbai.workspace.provider.Provider;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Component
public final class OllamaProvider implements Provider {
    private final RuntimeProperties.Ollama settings;
    private final ProviderPolicy policy;
    private final HttpClient client;
    private final URI base;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public OllamaProvider(RuntimeProperties properties, ProviderPolicy policy) {
        this.settings = properties.ollama();
        this.policy = policy;
        URI uri = settings.baseUrl();
        // A literal loopback allowlist prevents DNS rebinding and arbitrary URL injection.
        if (!"http".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || uri.getPort() < 1
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                || !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))) {
            throw new IllegalArgumentException("Ollama requires an explicit local HTTP endpoint");
        }
        base = URI.create("http://127.0.0.1:" + uri.getPort());
        client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    public List<Proxy> select(URI ignored) { return List.of(Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) {}
                }).build();
    }

    public String id() { return "ollama"; }
    public ModelProfile.Locality locality() { return ModelProfile.Locality.LOCAL; }
    public Set<Capability> capabilities() { return Set.of(Capability.TEXT_GENERATION); }

    @PreDestroy
    public void close() { client.shutdownNow(); }

    public String execute(ProviderExecution execution, Cancellation cancellation) {
        policy.verify(execution.profile(), this, execution.privacyMode());
        cancellation.check();
        ensureModel(execution.profile(), cancellation);
        ModelProfile p = execution.profile();
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", execution.system()));
        for (var message : execution.messages()) messages.add(Map.of("role", message.role(), "content", message.content()));
        byte[] payload = json.writeValueAsBytes(Map.of("model", p.model(), "stream", false, "think", false,
                "messages", messages,
                "options", Map.of("num_ctx", p.contextBudget(), "num_predict", p.outputBudget(),
                        "temperature", p.temperature())));
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/api/chat"))
                .timeout(settings.requestTimeout()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        // Policy is verified at the last application boundary before model egress.
        policy.verify(p, this, execution.privacyMode());
        HttpResponse<byte[]> response = exchange(request, settings.requestTimeout(), cancellation);
        checkStatus(response);
        JsonNode body = parse(response.body());
        JsonNode message = body.path("message");
        JsonNode content = message.path("content");
        if (!body.path("done").isBoolean() || !body.path("done").asBoolean(false) || !content.isString() || content.asString().isBlank()
                || !message.path("role").asString("").equals("assistant")
                || body.has("error") || !body.path("model").asString("").equals(p.model())
                || !body.path("done_reason").asString("stop").equals("stop")
                || message.path("tool_calls").size() > 0) {
            throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
        }
        cancellation.check();
        return content.asString();
    }

    public ProviderReadiness readiness(ModelProfile profile) {
        try {
            policy.verify(profile, this, io.github.qianlixunbai.workspace.policy.PrivacyMode.LOCAL_ONLY);
            ensureModel(profile, new Cancellation());
            return new ProviderReadiness(id(), profile.id(), true, true, null);
        } catch (WorkspaceException failure) {
            return new ProviderReadiness(id(), profile.id(), failure.error().code() == ErrorCode.MODEL_UNAVAILABLE,
                    false, failure.error());
        }
    }

    private void ensureModel(ModelProfile profile, Cancellation cancellation) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/api/tags"))
                .timeout(settings.healthTimeout()).GET().build();
        HttpResponse<byte[]> response = exchange(request, settings.healthTimeout(), cancellation);
        checkStatus(response);
        JsonNode models = parse(response.body()).path("models");
        if (!models.isArray()) throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
        for (JsonNode model : models) {
            if (!model.path("name").isString()) throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
            if (profile.model().equals(model.path("name").asString(""))) return;
        }
        throw new WorkspaceException(ErrorCode.MODEL_UNAVAILABLE, "MODEL");
    }

    private HttpResponse<byte[]> exchange(HttpRequest request, Duration timeout, Cancellation cancellation) {
        cancellation.check();
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request,
                ignored -> new LimitedBodySubscriber(settings.maxResponseBytes()));
        try {
            cancellation.attach(() -> future.cancel(true));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw new WorkspaceException(ErrorCode.TASK_TIMEOUT, "PROVIDER");
        } catch (CancellationException failure) {
            throw new WorkspaceException(ErrorCode.TASK_CANCELLED, "CANCELLATION");
        } catch (InterruptedException failure) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new WorkspaceException(ErrorCode.TASK_CANCELLED, "CANCELLATION");
        } catch (ExecutionException failure) {
            // Java HttpClient may wrap cancellation as an exceptional completion.
            cancellation.check();
            throw classify(failure.getCause());
        } finally {
            cancellation.detach();
        }
    }

    static WorkspaceException classify(Throwable cause) {
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof WorkspaceException controlled) return controlled;
            if (cause instanceof CancellationException)
                return new WorkspaceException(ErrorCode.TASK_CANCELLED, "CANCELLATION");
            if (cause instanceof HttpConnectTimeoutException)
                return new WorkspaceException(ErrorCode.TASK_TIMEOUT, "CONNECT");
            if (cause instanceof HttpTimeoutException)
                return new WorkspaceException(ErrorCode.TASK_TIMEOUT, "PROVIDER");
        }
        return new WorkspaceException(ErrorCode.PROVIDER_UNAVAILABLE, "PROVIDER");
    }

    private void checkStatus(HttpResponse<byte[]> response) {
        if (response.statusCode() == 404) throw new WorkspaceException(ErrorCode.MODEL_UNAVAILABLE, "MODEL");
        if (response.statusCode() >= 500) throw new WorkspaceException(ErrorCode.PROVIDER_UNAVAILABLE, "PROVIDER");
        if (response.statusCode() != 200) throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode parsed = json.readTree(body);
            if (parsed == null || !parsed.isObject()) throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
            return parsed;
        }
        catch (tools.jackson.core.JacksonException failure) {
            throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
        }
    }
}
