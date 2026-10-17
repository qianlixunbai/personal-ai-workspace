package io.github.qianlixunbai.workspace.provider.ollama;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import io.github.qianlixunbai.workspace.model.ModelProfile;
import io.github.qianlixunbai.workspace.model.ModelStateStore;
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
import java.io.*;

@Component
public final class OllamaProvider implements Provider {
    private final RuntimeProperties.Ollama settings;
    private final ProviderPolicy policy;
    private final HttpClient client;
    private final URI base;
    private final OllamaVisionEnvironment.Instance isolated;
    @FunctionalInterface interface SessionFactory {
        OllamaVisionEnvironment.ServiceSession open(URI endpoint, Cancellation cancellation, long deadline) throws Exception;
    }
    private final SessionFactory sessions;
    private final ThreadLocal<OllamaVisionEnvironment.ServiceSession> session = new ThreadLocal<>();
    private final ScheduledExecutorService transportTimer;
    public interface VisionTrust {
        void verifyIdentity(int port, JsonNode show, Cancellation cancellation);
        VisionContext currentContext(String identity);
        /** Independent native sampling; never implemented as a read of the admission cache. */
        VisionContext liveContext(String identity, Cancellation cancellation);
    }
    public record VisionContext(io.github.qianlixunbai.workspace.capability.vision.VisionTokenBudget.Evidence evidence,
                                String payloadDigest, String preprocessingDigest, String templateDigest, String processGeneration) {
        public VisionContext(io.github.qianlixunbai.workspace.capability.vision.VisionTokenBudget.Evidence evidence,String payloadDigest,String preprocessingDigest,String templateDigest) {
            this(evidence,payloadDigest,preprocessingDigest,templateDigest,"fixture");
        }
    }
    private final VisionTrust visionTrust;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    @org.springframework.beans.factory.annotation.Autowired
    public OllamaProvider(RuntimeProperties properties, ProviderPolicy policy) {
        this(properties, policy, null);
    }
    /** In-process trusted evidence boundary. Never populated from an HTTP request or model metadata. */
    public OllamaProvider(RuntimeProperties properties, ProviderPolicy policy, VisionTrust visionTrust) {
        this(properties, policy, visionTrust, null);
    }
    // Trusted model-free socket fixture seam. It cannot create or grant a development instance.
    OllamaProvider(RuntimeProperties properties, ProviderPolicy policy, VisionTrust visionTrust, SessionFactory fixtureSessions) {
        this.settings = properties.ollama();
        this.policy = policy;
        isolated = OllamaVisionEnvironment.developmentInstance();
        URI uri = settings.baseUrl();
        // A literal loopback allowlist prevents DNS rebinding and arbitrary URL injection.
        if (!"http".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || uri.getPort() < 1 || uri.getPort() == 11435
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                || !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))) {
            throw new IllegalArgumentException("Ollama requires an explicit local HTTP endpoint");
        }
        if (isolated != null && !uri.equals(URI.create("http://127.0.0.1:11434")))
            throw new IllegalArgumentException("Isolated instance requires unchanged ordinary configuration");
        base = isolated == null ? URI.create("http://127.0.0.1:" + uri.getPort()) : isolated.endpoint();
        sessions = isolated == null ? fixtureSessions : (endpoint, cancellation, deadline) ->
            OllamaVisionEnvironment.openSession(isolated, cancellation, deadline);
        transportTimer = sessions == null ? null : Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "isolated-transport-deadline"); thread.setDaemon(true); return thread;
        });
        this.visionTrust = visionTrust == null ? new NativeVisionTrust() : visionTrust;
        client = sessions != null ? null : HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    public List<Proxy> select(URI ignored) { return List.of(Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) {}
                }).build();
    }
    private final class NativeVisionTrust implements VisionTrust {
        private volatile VisionContext context;
        public void verifyIdentity(int port, JsonNode show, Cancellation cancellation) {
            if (isolated == null) {
                OllamaVisionEnvironment.verify(port, show, cancellation);
                context = OllamaVisionEnvironment.currentContext(port, visionIdentity(), cancellation);
            } else {
                var held = session.get();
                if (held == null) throw new WorkspaceException(ErrorCode.MODEL_UNAVAILABLE,"ISOLATED_SESSION");
                held.verifyMetadata(show, cancellation);
                context = held.liveContext(visionIdentity(), cancellation);
            }
        }
        public VisionContext currentContext(String identity) {
            var current = context; return current != null && identity.equals(current.evidence().identity()) ? current : null;
        }
        public VisionContext liveContext(String identity, Cancellation cancellation) {
            if (isolated == null) return OllamaVisionEnvironment.currentContext(base.getPort(), identity, cancellation);
            var held = session.get(); return held == null ? null : held.liveContext(identity, cancellation);
        }
    }
    private static String visionIdentity() { return OllamaVisionAdmission.MODEL+"/"+OllamaVisionAdmission.RUNNER+"/"+OllamaVisionAdmission.CHILD; }
    private <T> T inSession(Cancellation cancellation, java.util.function.Supplier<T> operation) {
        if (sessions == null || session.get() != null) return operation.get();
        try (var held = sessions.open(base, cancellation, System.nanoTime() + Duration.ofSeconds(120).toNanos())) {
            if (!base.equals(held.endpoint())) throw new IOException();
            session.set(held); return operation.get();
        } catch (WorkspaceException failure) { throw failure; }
        catch (Exception | LinkageError failure) { throw new WorkspaceException(ErrorCode.MODEL_UNAVAILABLE,"ISOLATED_SESSION"); }
        finally { session.remove(); }
    }

    public String id() { return "ollama"; }
    public ModelProfile.Locality locality() { return ModelProfile.Locality.LOCAL; }
    public Set<Capability> capabilities() { return Set.of(Capability.TEXT_GENERATION); }

    @PreDestroy
    public void close() { if (client != null) client.shutdownNow(); if (transportTimer != null) transportTimer.shutdownNow(); }

    public String execute(ProviderExecution execution, Cancellation cancellation) {
        return executeChat(execution, cancellation, null);
    }
    public VisionContext verifyVisionBudget(io.github.qianlixunbai.workspace.model.ActiveModelManager.ExecutionModel model, String question) {
        if (!OllamaVisionAdmission.candidate(model.model(), model.runner(), model.digest()))
            throw new WorkspaceException(ErrorCode.MODEL_UNAVAILABLE,"VISION_MODEL");
        String identity=model.model()+"/"+model.runner()+"/"+model.digest();
        VisionContext context=visionTrust.currentContext(identity);
        io.github.qianlixunbai.workspace.capability.vision.VisionTokenBudget.verify(context==null?null:context.evidence(),identity,
            context==null?null:context.payloadDigest(),context==null?null:context.preprocessingDigest(),context==null?null:context.templateDigest(),question);
        return context;
    }
    public String executeVision(ProviderExecution execution, byte[] image, Cancellation cancellation) {
        if (image==null || image.length==0 || image.length>4*1024*1024 || execution.reservation()==null
                || execution.profile().contextBudget()!=8192 || execution.profile().outputBudget()!=1024)
            throw new WorkspaceException(ErrorCode.POLICY_DENIED,"VISION_EXECUTION");
        verifyVisionBudget(execution.reservation().model(),execution.input());
        String output=executeChat(execution,cancellation,image);
        if(output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192)
            throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID,"VISION_OUTPUT");
        return output;
    }
    private String executeChat(ProviderExecution execution, Cancellation cancellation, byte[] image) {
        return inSession(cancellation, () -> executeChatInSession(execution, cancellation, image));
    }
    private String executeChatInSession(ProviderExecution execution, Cancellation cancellation, byte[] image) {
        policy.verify(execution.profile(), this, execution.privacyMode());
        cancellation.check();
        var reservation = execution.reservation();
        if (reservation == null || !execution.profile().equals(reservation.profile())
                || !execution.profile().model().equals(reservation.model().model()))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "MODEL_RESERVATION");
        try {
            var evidence = admitLocal(execution.profile().model(), reservation.model().runner(), reservation.model().digest(), execution.profile().contextBudget(), cancellation);
            if (!evidence.digest().equals(reservation.model().digest()))
                throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
        } catch (WorkspaceException rejected) {
            reservation.admissionFailed(rejected.error().code()); throw rejected;
        }
        ModelProfile p = execution.profile();
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", execution.system()));
        for (var message : execution.messages()) messages.add(image==null ? Map.of("role", message.role(), "content", message.content())
            : Map.of("role",message.role(),"content",message.content(),"images",List.of(image)));
        // v0.40.0's explicit local source rejects a cloud-backed replacement before forwarding inference.
        String requestModel = OllamaVisionAdmission.candidate(p.model(),reservation.model().runner(),reservation.model().digest())
            ? "sha256-"+reservation.model().digest()+":local" : p.model() + ":local";
        byte[] payload = json.writeValueAsBytes(Map.of("model", requestModel, "runner", reservation.model().runner(), "stream", false, "think", false,
                "messages", messages,
                "options", Map.of("num_ctx", p.contextBudget(), "num_predict", p.outputBudget(),
                        "temperature", p.temperature())));
        Duration requestBudget=image==null ? settings.requestTimeout() : Duration.ofSeconds(120);
        if(image!=null && payload.length>6*1024*1024) throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"VISION_PAYLOAD");
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/api/chat"))
                .timeout(requestBudget).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        // Policy is verified at the last application boundary before model egress.
        policy.verify(p, this, execution.privacyMode());
        try {
            HttpResponse<byte[]> response = exchange(request, requestBudget, cancellation, new SendBoundary(reservation, image==null ? null : () -> {
                    String identity=reservation.model().model()+"/"+reservation.model().runner()+"/"+reservation.model().digest();
                    VisionContext live=visionTrust.liveContext(identity,cancellation);
                    var cached=verifyVisionBudget(reservation.model(),execution.input());
                    if(live==null || !live.equals(cached)) throw new WorkspaceException(ErrorCode.VISION_UNVERIFIED,"VISION_LIVE_IDENTITY");
                    cancellation.check();
                }));
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
                    "total_duration", "load_duration", "prompt_eval_count", "prompt_eval_cached_count", "prompt_eval_duration", "eval_count", "eval_duration");
            for (String field : body.propertyNames()) if (!responseFields.contains(field))
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
            if (!body.path("created_at").isString() || !body.path("done_reason").isString())
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE");
            try { java.time.OffsetDateTime.parse(body.path("created_at").asString()); }
            catch (java.time.format.DateTimeParseException invalid) { throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE"); }
            for (String metric : List.of("total_duration", "load_duration", "prompt_eval_count", "prompt_eval_cached_count", "prompt_eval_duration", "eval_count", "eval_duration")) {
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
            if(image!=null) {
                var evidence=verifyVisionBudget(reservation.model(),execution.input()).evidence();
                if(body.path("eval_count").asLong(0)>1024 || body.path("prompt_eval_count").asLong(0)>
                    (long)evidence.textBound()+evidence.templateBound()+evidence.imageBound()) {
                    reservation.visionPolicyDrift();throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID,"VISION_TOKEN_DRIFT");
                }
            }
            if (content.asString().isBlank() || !body.path("done_reason").asString("stop").equals("stop")
                    || message.path("tool_calls").size() > 0
                    || image!=null && message.has("images") && (!message.path("images").isArray() || message.path("images").size()!=0))
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

    public record LocalModelEvidence(String model, String runner, String digest, long contextLimit, boolean providerDeclaredVision) {
        @Override public String toString() { return "LocalModelEvidence[private]"; }
    }
    public LocalModelEvidence admitLocal(String model, int contextBudget, Cancellation cancellation) {
        return admitLocal(model, null, null, contextBudget, cancellation);
    }
    /** A persisted model/digest must identify exactly one runner. Never reinterpret a parent digest. */
    public LocalModelEvidence admitLocal(String model, String runner, String digest, int contextBudget, Cancellation cancellation) {
        return inSession(cancellation, () -> admitInSession(model, runner, digest, contextBudget, cancellation));
    }
    private LocalModelEvidence admitInSession(String model, String runner, String digest, int contextBudget, Cancellation cancellation) {
        JsonNode version = metadata("/api/version", null, cancellation);
        if (version.size() != 1 || !version.path("version").isString()
                || !OllamaModelAdmission.SUPPORTED_VERSIONS.contains(version.path("version").asString()))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "MODEL_VERSION");
        JsonNode beforeTags = metadata("/api/tags", null, cancellation);
        JsonNode before = OllamaModelAdmission.installed(beforeTags, model,
                runner == null && digest == null ? "ggml" : runner, digest);
        String selectedRunner = OllamaModelAdmission.runner(before.path("details"));
        boolean visionCandidate=OllamaVisionAdmission.candidate(model,selectedRunner,OllamaModelAdmission.digest(before.path("digest")));
        if (isolated != null && !visionCandidate) throw new WorkspaceException(ErrorCode.POLICY_DENIED,"ISOLATED_MODEL");
        if (!"ggml".equals(selectedRunner) && !visionCandidate) throw new WorkspaceException(ErrorCode.POLICY_DENIED, "MODEL_RUNNER");
        JsonNode show = metadata("/api/show", json.writeValueAsBytes(Map.of("model", model + ":local", "runner", selectedRunner, "verbose", false)), cancellation);
        JsonNode afterTags = metadata("/api/tags", null, cancellation);
        JsonNode after = OllamaModelAdmission.installed(afterTags, model, selectedRunner,
                OllamaModelAdmission.digest(before.path("digest")));
        for (JsonNode observed : List.of(beforeTags, afterTags)) {
            int children = 0;
            for (JsonNode row : observed.path("models")) if (model.equals(row.path("name").asString())) children++;
            if (children > 1 && show.path("manifests").size() == 0)
                throw new WorkspaceException(ErrorCode.POLICY_DENIED, "MODEL_MANIFESTS");
        }
        if(visionCandidate) {
            OllamaVisionAdmission.verify(before,show,after,version.path("version").asString(),contextBudget);
            visionTrust.verifyIdentity(base.getPort(),show,cancellation);
            return new LocalModelEvidence(model,selectedRunner,OllamaVisionAdmission.CHILD,262144,true);
        }
        var evidence = OllamaModelAdmission.verify(model, before, show, after, contextBudget, version.path("version").asString());
        boolean vision = false;
        for (JsonNode capability : show.path("capabilities")) vision |= "vision".equals(capability.asString());
        return new LocalModelEvidence(evidence.model(), evidence.runner(), evidence.digest(), evidence.contextLimit(), vision);
    }

    /** Read-only, bounded, strict catalog. Never use /api/ps as evidence of external idleness. */
    public List<LocalModelEvidence> catalog(int contextBudget, Cancellation cancellation) {
        return inSession(cancellation, () -> catalogInSession(contextBudget, cancellation));
    }
    private List<LocalModelEvidence> catalogInSession(int contextBudget, Cancellation cancellation) {
        JsonNode tags = metadata("/api/tags", null, cancellation);
        if (tags.size() != 1 || !tags.path("models").isArray() || tags.path("models").size() > 256)
            throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "MODEL_CATALOG");
        Set<String> names = new HashSet<>();
        List<LocalModelEvidence> result = new ArrayList<>();
        for (JsonNode item : tags.path("models")) {
            if (!item.isObject() || !item.path("name").isString() || !item.path("model").isString()
                    || !item.path("name").asString().equals(item.path("model").asString())
                    || !names.add(item.path("name").asString() + "/" + OllamaModelAdmission.runner(item.path("details"))))
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "MODEL_CATALOG");
            String model = item.path("name").asString();
            if (!ModelStateStore.validModel(model)) continue;
            try {
                var evidence = admitLocal(model, OllamaModelAdmission.runner(item.path("details")),
                        OllamaModelAdmission.digest(item.path("digest")), contextBudget, cancellation);
                if (!evidence.digest().equals(OllamaModelAdmission.digest(item.path("digest"))))
                    throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL_CATALOG");
                result.add(evidence);
            } catch (WorkspaceException denied) {
                if (denied.error().code() != ErrorCode.POLICY_DENIED) throw denied;
                // Existing supported-version/source policy excludes unsafe candidates, without loading them.
            }
            if (result.size() > 64) throw new WorkspaceException(ErrorCode.MODEL_CATALOG_LIMIT_EXCEEDED, "MODEL_CATALOG");
        }
        return List.copyOf(result);
    }

    public io.github.qianlixunbai.workspace.model.ActiveModelManager.Fact residency(String model, String runner, String digest, Cancellation cancellation) {
        JsonNode ps = metadata("/api/ps", null, cancellation);
        if (ps.size() != 1 || !ps.path("models").isArray() || ps.path("models").size() > 256)
            throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "MODEL_RESIDENCY");
        Set<String> names = new HashSet<>(); boolean found = false, unknown = false;
        for (JsonNode item : ps.path("models")) {
            if (!item.isObject() || !item.path("name").isString() || !item.path("model").isString()
                    || !item.path("name").asString().equals(item.path("model").asString())
                    || !names.add(item.path("name").asString() + "/" + item.path("runner").asString("")))
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "MODEL_RESIDENCY");
            for (String field : item.propertyNames())
                if (!Set.of("name", "model", "size", "digest", "details", "expires_at", "size_vram", "context_length", "runner").contains(field))
                    throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "MODEL_RESIDENCY");
            OllamaModelAdmission.noRemote(item);
            String observed = OllamaModelAdmission.digest(item.path("digest"));
            if (model.equals(item.path("name").asString())) {
                // No parent-child translation is justified by a name match, even for our selected runner.
                if (runner.equals(item.path("runner").asString("")) && digest.equals(observed)) found = true;
                else unknown = true;
            }
        }
        return found ? io.github.qianlixunbai.workspace.model.ActiveModelManager.Fact.TRUE
                : unknown ? io.github.qianlixunbai.workspace.model.ActiveModelManager.Fact.UNKNOWN
                : io.github.qianlixunbai.workspace.model.ActiveModelManager.Fact.FALSE;
    }

    /** Fixed v0.40.0 single-model unload protocol; guarded exactly like inference. */
    public void release(io.github.qianlixunbai.workspace.model.ActiveModelManager.Reservation lease, int contextBudget, Cancellation cancellation) {
        inSession(cancellation, () -> { releaseInSession(lease, contextBudget, cancellation); return null; });
    }
    private void releaseInSession(io.github.qianlixunbai.workspace.model.ActiveModelManager.Reservation lease, int contextBudget, Cancellation cancellation) {
        var evidence = admitLocal(lease.model().model(), lease.model().runner(), lease.model().digest(), contextBudget, cancellation);
        if (!lease.model().digest().equals(evidence.digest())) throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL_RELEASE");
        policy.verify(lease.profile(), this, io.github.qianlixunbai.workspace.policy.PrivacyMode.LOCAL_ONLY);
        String requestModel = OllamaVisionAdmission.candidate(lease.model().model(),lease.model().runner(),lease.model().digest())
            ? "sha256-"+lease.model().digest()+":local" : lease.model().model() + ":local";
        var request = HttpRequest.newBuilder(base.resolve("/api/generate")).timeout(settings.requestTimeout())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(
                        json.writeValueAsBytes(Map.of("model", requestModel, "runner", lease.model().runner(), "stream", false, "keep_alive", 0)))).build();
        try {
            var response = exchange(request, settings.requestTimeout(), cancellation, new SendBoundary(lease, null));
            checkStatus(response); JsonNode body = parse(response.body()); OllamaModelAdmission.noRemote(body);
            if (body.size() != 5 || !body.path("model").isString() || !requestModel.equals(body.path("model").asString())
                    || !body.path("created_at").isString() || !body.path("response").isString() || !body.path("response").asString().isEmpty()
                    || !body.path("done").isBoolean() || !body.path("done").asBoolean() || !"unload".equals(body.path("done_reason").asString("")))
                throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "MODEL_RELEASE");
            try { java.time.OffsetDateTime.parse(body.path("created_at").asString()); }
            catch (java.time.format.DateTimeParseException invalid) { throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "MODEL_RELEASE"); }
            lease.trustedCompletion();
        } finally { lease.providerExited(); }
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
        return exchange(request, timeout, cancellation, null);
    }
    private record SendBoundary(io.github.qianlixunbai.workspace.model.ActiveModelManager.Reservation lease, Runnable liveIdentity) {
        void prepare(boolean checked) {
            if (checked) lease.prepareCheckedSend(liveIdentity);
            else if (liveIdentity == null) lease.beforeSend();
            else lease.visionBeforeSend(liveIdentity);
        }
    }
    private HttpResponse<byte[]> exchange(HttpRequest request, Duration timeout, Cancellation cancellation, SendBoundary boundary) {
        cancellation.check();
        if (sessions != null) {
            if (session.get() == null) return inSession(cancellation, () -> exchange(request, timeout, cancellation, boundary));
            return checkedExchange(request, timeout, cancellation, boundary);
        }
        if (boundary != null) boundary.prepare(false);
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

    /** One new IPv4 socket per exchange; Apache owns HTTP framing, never connection identity. */
    private HttpResponse<byte[]> checkedExchange(HttpRequest request, Duration timeout, Cancellation cancellation, SendBoundary boundary) {
        var held = session.get();
        long deadline = System.nanoTime() + timeout.toNanos();
        var operator = new CheckedOperator(held, cancellation, deadline, boundary);
        var factory = org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory.builder()
            .http1Config(org.apache.hc.core5.http.config.Http1Config.custom().setMaxLineLength(16384).setMaxHeaderCount(64).setMaxEmptyLineCount(1).build()).build();
        var manager = new org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager(operator, factory);
        var apache = org.apache.hc.client5.http.impl.classic.HttpClients.custom().setConnectionManager(manager)
            .setRoutePlanner(new org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner(org.apache.hc.client5.http.impl.DefaultSchemePortResolver.INSTANCE))
            .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableContentCompression()
            .disableAuthCaching().disableConnectionState()
            .setDefaultAuthSchemeRegistry(org.apache.hc.core5.http.config.RegistryBuilder.<org.apache.hc.client5.http.auth.AuthSchemeFactory>create().build())
            .build();
        org.apache.hc.core5.http.ClassicHttpResponse response = null;
        var expired = new java.util.concurrent.atomic.AtomicBoolean();
        var timer = transportTimer.schedule(() -> { expired.set(true); operator.abort(); manager.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE); },
            timeout.toNanos(), TimeUnit.NANOSECONDS);
        try {
            if (!request.uri().getScheme().equals("http") || !request.uri().getHost().equals(base.getHost())
                    || request.uri().getPort() != base.getPort() || request.uri().getQuery() != null) throw new IOException();
            var wire = new org.apache.hc.client5.http.classic.methods.HttpUriRequestBase(request.method(), request.uri());
            wire.setVersion(org.apache.hc.core5.http.HttpVersion.HTTP_1_1);
            wire.setHeader("Accept-Encoding", "identity");
            wire.setConfig(org.apache.hc.client5.http.config.RequestConfig.custom().setAuthenticationEnabled(false)
                .setResponseTimeout(org.apache.hc.core5.util.Timeout.ofMilliseconds(Math.max(1, timeout.toMillis()))).build());
            if (request.bodyPublisher().isPresent()) {
                var bytes = new ByteArrayOutputStream();
                request.bodyPublisher().get().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                    public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                    public void onNext(java.nio.ByteBuffer buffer) {
                        if ((long) bytes.size() + buffer.remaining() > 6 * 1024 * 1024) throw new IllegalStateException();
                        byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
                    }
                    public void onError(Throwable failure) { throw new IllegalStateException(); }
                    public void onComplete() {}
                });
                wire.setEntity(new org.apache.hc.core5.http.io.entity.ByteArrayEntity(bytes.toByteArray(), org.apache.hc.core5.http.ContentType.APPLICATION_JSON));
            }
            cancellation.attach(operator::abort);
            response = apache.executeOpen(new org.apache.hc.core5.http.HttpHost("http", "127.0.0.1", base.getPort()), wire, null);
            if (response.containsHeader("Content-Encoding") || response.containsHeader("Trailer") || response.getEntity() == null
                    || response.getEntity().getContentLength() > settings.maxResponseBytes()) throw new IOException();
            var body = new ByteArrayOutputStream();
            InputStream stream = response.getEntity().getContent(); byte[] buffer = new byte[8192]; int n;
            while (true) {
                operator.check(); operator.socket.setSoTimeout(operator.remainingMillis());
                n = stream.read(buffer); if (n < 0) break;
                if ((long) body.size() + n > settings.maxResponseBytes()) throw new IOException();
                body.write(buffer, 0, n);
            }
            operator.check(); held.verifyConnected(operator.socket, cancellation, deadline);
            return new CheckedResponse(request, response.getCode(), body.toByteArray());
        } catch (WorkspaceException rejected) { throw rejected; }
        catch (Exception failure) {
            cancellation.check();
            if (expired.get() || System.nanoTime() >= deadline || failure instanceof SocketTimeoutException)
                throw new WorkspaceException(ErrorCode.TASK_TIMEOUT,"PROVIDER");
            throw new WorkspaceException(ErrorCode.PROVIDER_UNAVAILABLE,"ISOLATED_TRANSPORT");
        } finally {
            timer.cancel(false); operator.abort(); manager.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
            if (response != null) try { response.close(); } catch (IOException ignored) {}
            apache.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE); cancellation.detach();
        }
    }
    private record CheckedResponse(HttpRequest request, int statusCode, byte[] body) implements HttpResponse<byte[]> {
        public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name,value) -> true); }
        public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
    /** The actual socket-output delegation, shared with the model-free sending-boundary fixture. */
    static final class CheckedOutput extends OutputStream {
        private final OutputStream raw;
        private final io.github.qianlixunbai.workspace.model.ActiveModelManager.Reservation lease;
        private final Runnable finalCheck;
        CheckedOutput(OutputStream raw,io.github.qianlixunbai.workspace.model.ActiveModelManager.Reservation lease,Runnable finalCheck) {
            this.raw=raw;this.lease=lease;this.finalCheck=finalCheck;
        }
        public void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
        public void write(byte[] bytes,int offset,int length) throws IOException {
            Objects.checkFromIndexSize(offset,length,bytes.length);
            if(length==0)return;
            if(lease==null){finalCheck.run();raw.write(bytes,offset,length);}
            else lease.checkedWrite(finalCheck,raw,bytes,offset,length);
        }
        public void flush() throws IOException { raw.flush(); }
        public void close() throws IOException { raw.close(); }
    }
    private final class CheckedOperator implements org.apache.hc.client5.http.io.HttpClientConnectionOperator {
        private final OllamaVisionEnvironment.ServiceSession held;
        private final Cancellation cancellation;
        private final long deadline;
        private final SendBoundary boundary;
        private volatile Socket socket;
        private boolean connected;
        CheckedOperator(OllamaVisionEnvironment.ServiceSession held, Cancellation cancellation, long deadline, SendBoundary boundary) {
            this.held=held; this.cancellation=cancellation; this.deadline=deadline; this.boundary=boundary;
        }
        void check() throws IOException {
            cancellation.check();
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new SocketTimeoutException();
        }
        int remainingMillis() throws IOException {
            check(); return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
        }
        void abort() { var current=socket; if(current!=null) try {current.close();} catch(IOException ignored) {} }
        public void connect(org.apache.hc.client5.http.io.ManagedHttpClientConnection connection, org.apache.hc.core5.http.HttpHost host,
                InetSocketAddress local, org.apache.hc.core5.util.TimeValue timeout, org.apache.hc.core5.http.io.SocketConfig config,
                org.apache.hc.core5.http.protocol.HttpContext context) throws IOException {
            check();
            if (connected || local != null || !host.getSchemeName().equals("http") || !host.getHostName().equals("127.0.0.1")
                    || host.getPort()!=base.getPort() || host.getAddress()!=null) throw new IOException();
            connected=true; socket=new Socket(Proxy.NO_PROXY) {
                @Override public OutputStream getOutputStream() throws IOException {
                    return new CheckedOutput(super.getOutputStream(),boundary==null?null:boundary.lease(),() -> {
                        try { check(); if (socket.isClosed()) throw new SocketException("Closed checked socket"); }
                        catch (IOException failure) { throw new UncheckedIOException(failure); }
                    });
                }
            };
            cancellation.attach(this::abort); check();
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),base.getPort()),
                Math.min(remainingMillis(), (int)Math.max(1,Math.min(Integer.MAX_VALUE,settings.connectTimeout().toMillis()))));
            socket.setSoTimeout(remainingMillis());
            held.verifyConnected(socket,cancellation,deadline); // Must finish before bind or any HTTP bytes.
            check();
            connection.bind(socket);
            check();
            if (boundary != null) boundary.prepare(true);
            check();
        }
        public void upgrade(org.apache.hc.client5.http.io.ManagedHttpClientConnection connection, org.apache.hc.core5.http.HttpHost host,
                org.apache.hc.core5.http.protocol.HttpContext context) throws IOException { throw new IOException("Unexpected isolated upgrade"); }
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
