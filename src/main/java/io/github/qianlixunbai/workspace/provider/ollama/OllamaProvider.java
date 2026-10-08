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
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

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
        var reservation = execution.reservation();
        if (reservation == null || !execution.profile().equals(reservation.profile()))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "MODEL_RESERVATION");
        try {
            var evidence = admitLocal(execution.profile().model(), execution.profile().contextBudget(), cancellation);
            if (!evidence.digest().equals(reservation.model().digest()))
                throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
        } catch (WorkspaceException rejected) {
            reservation.admissionFailed(rejected.error().code()); throw rejected;
        }
        ModelProfile p = execution.profile();
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", execution.system()));
        for (var message : execution.messages()) messages.add(Map.of("role", message.role(), "content", message.content()));
        // v0.40.0's explicit local source rejects a cloud-backed replacement before forwarding inference.
        String requestModel = p.model() + ":local";
        byte[] payload = json.writeValueAsBytes(Map.of("model", requestModel, "stream", false, "think", false,
                "messages", messages,
                "options", Map.of("num_ctx", p.contextBudget(), "num_predict", p.outputBudget(),
                        "temperature", p.temperature())));
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/api/chat"))
                .timeout(settings.requestTimeout()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        // Policy is verified at the last application boundary before model egress.
        policy.verify(p, this, execution.privacyMode());
        try {
            HttpResponse<byte[]> response = exchange(request, settings.requestTimeout(), cancellation, reservation::beforeSend);
            checkStatus(response);
            JsonNode body = parse(response.body());
            OllamaModelAdmission.noRemote(body);
            JsonNode message = body.path("message");
            if (!message.isObject()) throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
            for (String field : message.propertyNames()) if (!Set.of("role", "content", "thinking", "images", "tool_calls", "tool_name", "tool_call_id").contains(field))
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
            if (message.has("tool_calls") && !message.path("tool_calls").isArray())
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
            JsonNode content = message.path("content");
            Set<String> responseFields = Set.of("model", "remote_model", "remote_host", "created_at", "message", "done", "done_reason",
                    "total_duration", "load_duration", "prompt_eval_count", "prompt_eval_duration", "eval_count", "eval_duration");
            for (String field : body.propertyNames()) if (!responseFields.contains(field))
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
            if (!body.path("created_at").isString() || !body.path("done_reason").isString())
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
            try { java.time.OffsetDateTime.parse(body.path("created_at").asString()); }
            catch (java.time.format.DateTimeParseException invalid) { throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE"); }
            for (String metric : List.of("total_duration", "load_duration", "prompt_eval_count", "prompt_eval_duration", "eval_count", "eval_duration")) {
                if (body.has(metric) && (!body.path(metric).isIntegralNumber() || !body.path(metric).canConvertToLong() || body.path(metric).asLong() < 0))
                    throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
            }
            if (!body.path("done").isBoolean() || !body.path("done").asBoolean(false) || !content.isString()
                    || !message.path("role").asString("").equals("assistant")
                    || body.has("error") || !body.path("model").asString("").equals(requestModel)
                    || !Set.of("stop", "length").contains(body.path("done_reason").asString("stop"))) {
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
            }
            // A complete trusted operation may have unusable content; those are distinct facts.
            reservation.trustedCompletion();
            if (content.asString().isBlank() || !body.path("done_reason").asString("stop").equals("stop")
                    || message.path("tool_calls").size() > 0)
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
            cancellation.check();
            return content.asString();
        } finally { reservation.providerExited(); }
    }

    public ProviderReadiness readiness(ModelProfile profile) {
        try {
            policy.verify(profile, this, io.github.qianlixunbai.workspace.policy.PrivacyMode.LOCAL_ONLY);
            admitLocal(profile.model(), profile.contextBudget(), new Cancellation());
            return new ProviderReadiness(id(), profile.id(), true, true, null);
        } catch (WorkspaceException failure) {
            return new ProviderReadiness(id(), profile.id(), failure.error().code() == ErrorCode.MODEL_UNAVAILABLE,
                    false, failure.error());
        }
    }

    public record LocalModelEvidence(String model, String digest, long contextLimit) {
        @Override public String toString() { return "LocalModelEvidence[private]"; }
    }
    public LocalModelEvidence admitLocal(String model, int contextBudget, Cancellation cancellation) {
        JsonNode version = metadata("/api/version", null, cancellation);
        if (version.size() != 1 || !version.path("version").isString()
                || !OllamaModelAdmission.VERSION.equals(version.path("version").asString()))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "MODEL_VERSION");
        JsonNode before = OllamaModelAdmission.installed(metadata("/api/tags", null, cancellation), model);
        JsonNode show = metadata("/api/show", json.writeValueAsBytes(Map.of("model", model + ":local", "verbose", false)), cancellation);
        JsonNode after = OllamaModelAdmission.installed(metadata("/api/tags", null, cancellation), model);
        var evidence = OllamaModelAdmission.verify(model, before, show, after, contextBudget);
        return new LocalModelEvidence(evidence.model(), evidence.digest(), evidence.contextLimit());
    }
    private JsonNode metadata(String path, byte[] payload, Cancellation cancellation) {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(settings.healthTimeout());
        if (payload == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        HttpResponse<byte[]> response = exchange(builder.build(), settings.healthTimeout(), cancellation);
        checkStatus(response);
        return parse(response.body());
    }

    private HttpResponse<byte[]> exchange(HttpRequest request, Duration timeout, Cancellation cancellation) {
        return exchange(request, timeout, cancellation, () -> {});
    }
    private HttpResponse<byte[]> exchange(HttpRequest request, Duration timeout, Cancellation cancellation, Runnable beforeSend) {
        cancellation.check();
        beforeSend.run();
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
