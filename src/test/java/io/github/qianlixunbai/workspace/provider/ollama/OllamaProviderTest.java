package io.github.qianlixunbai.workspace.provider.ollama;

import com.sun.net.httpserver.*;
import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import io.github.qianlixunbai.workspace.model.*;
import io.github.qianlixunbai.workspace.model.ActiveModelManager.Action;
import io.github.qianlixunbai.workspace.policy.*;
import io.github.qianlixunbai.workspace.provider.*;
import io.github.qianlixunbai.workspace.task.Cancellation;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class OllamaProviderTest {
    private HttpServer server;
    private ExecutorService executor;
    private OllamaProvider provider;
    private final AtomicInteger tagStatus = new AtomicInteger(200), chatStatus = new AtomicInteger(200);
    private final AtomicReference<String> tags = new AtomicReference<>(OllamaFixtures.tags("test-model:latest", "a".repeat(64)));
    private final AtomicReference<String> show = new AtomicReference<>(OllamaFixtures.show());
    private final AtomicReference<String> version = new AtomicReference<>("{\"version\":\"0.40.0\"}");
    private final AtomicReference<String> chat = new AtomicReference<>(OllamaFixtures.completed("test-model:latest:local", "你好"));
    private final AtomicReference<String> payload = new AtomicReference<>();
    private final AtomicReference<String> showPayload = new AtomicReference<>(), tagsAfterShow = new AtomicReference<>();
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final CountDownLatch chatEntered = new CountDownLatch(1);
    private volatile long delay;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/version", e -> respond(e, 200, version.get()));
        server.createContext("/api/show", e -> {
            showPayload.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String changed = tagsAfterShow.getAndSet(null); if (changed != null) tags.set(changed);
            respond(e, 200, show.get());
        });
        server.createContext("/api/tags", e -> respond(e, tagStatus.get(), tags.get()));
        server.createContext("/api/chat", e -> {
            chatCalls.incrementAndGet();
            payload.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            chatEntered.countDown();
            try { Thread.sleep(delay); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            respond(e, chatStatus.get(), chat.get());
        });
        server.start();
        provider = new OllamaProvider(TestSettings.settings(URI.create("http://127.0.0.1:" + server.getAddress().getPort())), new ProviderPolicy());
    }
    @AfterEach void close() { provider.close(); server.stop(0); executor.shutdownNow(); }

    @Test void ordinaryProviderRejectsReservedIsolatedEndpoint() {
        for (String endpoint : List.of("http://127.0.0.1:11435", "http://localhost:11435/"))
            assertThrows(IllegalArgumentException.class, () -> new OllamaProvider(
                    TestSettings.settings(URI.create(endpoint)), new ProviderPolicy()));
    }

    @Test void checkedMetadataTransactionHoldsOneSessionAndRejectsOwnerBeforeHttp() throws Exception {
        var reads=new AtomicInteger();var checks=new AtomicInteger();var opens=new AtomicInteger();var closes=new AtomicInteger();
        var deny=new AtomicBoolean();var denyAfterResponse=new AtomicBoolean();var received=new CopyOnWriteArrayList<String>();
        for(String path:List.of("/api/version","/api/tags","/api/show")) {
            server.removeContext(path);
            server.createContext(path,e->{
                // This is fake owner evidence, not a Windows process-identity assertion.
                assertEquals(reads.get()*2+1,checks.get());reads.incrementAndGet();received.add(path);
                respond(e,200,path.equals("/api/version")?version.get():path.equals("/api/tags")?tags.get():show.get());
            });
        }
        URI endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        var checked=new OllamaProvider(TestSettings.settings(endpoint),new ProviderPolicy(),null,(uri,cancel,deadline)->{
            int generation=opens.incrementAndGet();
            return new OllamaVisionEnvironment.ServiceSession(){
                java.net.URI endpoint(){return uri;}
                String generation(){return "fake/"+generation;}
                void verifyConnected(Socket socket,Cancellation cancellation,long until)throws java.io.IOException{
                    cancellation.check();
                    assertTrue(socket.isConnected());assertEquals(server.getAddress().getPort(),socket.getPort());
                    assertTrue(socket.getLocalPort()>0);assertEquals("127.0.0.1",socket.getLocalAddress().getHostAddress());
                    if(deny.get())throw new java.io.IOException("fake owner drift");
                    int check=checks.incrementAndGet();
                    if(denyAfterResponse.get() && check%2==0)throw new java.io.IOException("fake post-response generation drift");
                }
                void verifyMetadata(JsonNode metadata,Cancellation cancellation){}
                OllamaProvider.VisionContext liveContext(String identity,Cancellation cancellation){return null;}
                public void close(){closes.incrementAndGet();}
            };
        });
        try {
            assertEquals("a".repeat(64),checked.admitLocal("test-model:latest",8192,new Cancellation()).digest());
            assertEquals(List.of("/api/version","/api/tags","/api/show","/api/tags"),received);
            assertEquals(8,checks.get());assertEquals(1,opens.get());assertEquals(1,closes.get());
            denyAfterResponse.set(true);
            assertThrows(WorkspaceException.class,()->checked.admitLocal("test-model:latest",8192,new Cancellation()));
            assertEquals(5,reads.get());assertEquals(2,closes.get());
            denyAfterResponse.set(false);
            deny.set(true);var execution=execution();
            assertThrows(WorkspaceException.class,()->checked.execute(execution,new Cancellation()));
            assertEquals(5,reads.get());assertEquals(0,chatCalls.get());assertEquals(3,closes.get());
            org.mockito.Mockito.verify(execution.reservation(),org.mockito.Mockito.never()).beforeSend();
        } finally {checked.close();}
    }

    @Test void realHttpParsingAndGenerationSettings() {
        assertEquals("你好", provider.execute(execution(), new Cancellation()));
        var body = JsonMapper.builder().build().readTree(payload.get());
        assertEquals("test-model:latest:local", body.path("model").asString());
        assertEquals("ggml", body.path("runner").asString());
        assertFalse(body.path("stream").asBoolean());
        assertFalse(body.path("think").asBoolean());
        assertEquals(8192, body.path("options").path("num_ctx").asInt());
        assertEquals(2048, body.path("options").path("num_predict").asInt());
        assertTrue(provider.readiness(TestSettings.profile()).modelAvailable());
    }

    @Test void checkedSocketWriteSeparatesZeroSendCancellationFromUncertainAttempt() throws Exception {
        // Real Reservation and real loopback writes. Only persistence is modelled; no Windows/Profile API runs.
        var transport=org.mockito.Mockito.mock(OllamaProvider.class);
        var store=org.mockito.Mockito.mock(ModelStateStore.class);
        var armed=new AtomicBoolean();var guardHook=new AtomicReference<Runnable>(() -> {});
        org.mockito.Mockito.doAnswer(call->{armed.set(true);guardHook.get().run();return null;}).when(store).armGuard();
        org.mockito.Mockito.doAnswer(call->{armed.set(false);return null;}).when(store).clearGuard();
        org.mockito.Mockito.when(transport.admitLocal(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(String.class),org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any(Cancellation.class)))
            .thenReturn(new OllamaProvider.LocalModelEvidence("test-model:latest","ggml","a".repeat(64),8192,false));
        org.mockito.Mockito.when(transport.execute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
            .thenAnswer(call->{Provider.ProviderExecution request=call.getArgument(0);request.reservation().beforeSend();
                request.reservation().trustedCompletion();request.reservation().providerExited();return "OK";});
        var settings=TestSettings.settings(URI.create("http://127.0.0.1:1"));
        var worker=Executors.newSingleThreadExecutor();
        try(var models=new ActiveModelManager(new ProfileResolver(settings),transport,settings,store);
                var listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            for(boolean duringGuard:List.of(true,false)){
                try(var lease=models.reserve("translate.fast","fixture");var socket=new Socket("127.0.0.1",listener.getLocalPort());
                        var peer=listener.accept()){
                    peer.setSoTimeout(2000);var cancel=new Cancellation();var delegates=new AtomicInteger();
                    cancel.attach(()->{try{socket.close();}catch(java.io.IOException ignored){}});
                    guardHook.set(duringGuard?cancel::cancel:()->{});
                    lease.prepareCheckedSend(null);
                    assertTrue(armed.get());assertFalse(models.status().uncertain());
                    if(!duringGuard)cancel.cancel();
                    var raw=socket.isClosed()?java.io.OutputStream.nullOutputStream():socket.getOutputStream();
                    var output=new OllamaProvider.CheckedOutput(new java.io.OutputStream(){
                        public void write(int value)throws java.io.IOException{delegates.incrementAndGet();raw.write(value);}
                        public void write(byte[] b,int off,int len)throws java.io.IOException{delegates.incrementAndGet();raw.write(b,off,len);}
                    },lease,cancel::check);
                    assertThrows(WorkspaceException.class,()->output.write("POST /fixture HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII)));
                    assertEquals(0,delegates.get());assertEquals(-1,peer.getInputStream().read()); // Actual peer received zero bytes.
                    lease.close();assertEquals(1,models.status().reserved()); // Exit must still own the prepared guard.
                    lease.providerExited();assertFalse(armed.get());assertFalse(models.status().uncertain());
                    assertEquals(0,models.status().reserved());
                }
            }
            guardHook.set(()->{});
            try(var lease=models.reserve("translate.fast","fixture");var socket=new Socket("127.0.0.1",listener.getLocalPort());
                    var peer=listener.accept()){
                peer.setSoTimeout(2000);var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);var delegates=new AtomicInteger();
                var raw=socket.getOutputStream();
                var output=new OllamaProvider.CheckedOutput(new java.io.OutputStream(){
                    public void write(int value)throws java.io.IOException{write(new byte[]{(byte)value},0,1);}
                    public void write(byte[] bytes,int offset,int length)throws java.io.IOException{
                        assertTrue(armed.get());delegates.incrementAndGet();raw.write(bytes,offset,1);entered.countDown();
                        try{if(!finish.await(5,TimeUnit.SECONDS))throw new java.io.IOException("fixture timeout");}
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}
                        throw new java.io.IOException("failure after possible partial send");
                    }
                },lease,()->{});
                lease.prepareCheckedSend(null);
                var writing=worker.submit(()->assertThrows(java.io.IOException.class,()->output.write(new byte[]{'P','O','S','T'})));
                try{
                    assertTrue(entered.await(5,TimeUnit.SECONDS));
                    lease.close();lease.providerExited(); // Concurrent cleanup cannot release a live write/guard.
                    assertEquals(1,models.status().reserved());assertTrue(armed.get());
                }finally{finish.countDown();}
                writing.get(5,TimeUnit.SECONDS);
                assertTrue(models.status().uncertain());assertTrue(armed.get());assertEquals(0,models.status().reserved());
                assertEquals('P',peer.getInputStream().read());assertEquals(1,delegates.get());
                assertThrows(WorkspaceException.class,()->output.write('X'));assertEquals(1,delegates.get());
            }
        }finally{worker.shutdownNow();}
    }
    @Test void cachedPromptMetricPreservesTrustedCompletionAndStrictResponseValidation() {
        version.set("{\"version\":\"0.40.1\"}");
        var json = JsonMapper.builder().build();
        var response = (tools.jackson.databind.node.ObjectNode) json.readTree(chat.get());
        response.put("prompt_eval_cached_count", 12);
        String completed = json.writeValueAsString(response);
        chat.set(completed);
        var accepted = execution();
        assertEquals("你好", provider.execute(accepted, new Cancellation()));
        org.mockito.Mockito.verify(accepted.reservation()).trustedCompletion();
        org.mockito.Mockito.verify(accepted.reservation()).providerExited();
        for (String invalid : java.util.List.of(
                completed.replace("\"prompt_eval_cached_count\":12", "\"prompt_eval_cached_count\":null"),
                completed.replace("\"prompt_eval_cached_count\":12", "\"prompt_eval_cached_count\":-1"),
                completed.replace("\"prompt_eval_cached_count\":12", "\"prompt_eval_cached_count\":9223372036854775808"),
                completed.replace("\"done\":true", "\"done\":false"),
                completed.replace("test-model:latest:local", "other:latest:local"),
                completed.replace("\"prompt_eval_cached_count\":12", "\"prompt_eval_cached_count\":12,\"remote_host\":\"https://remote.example\""),
                completed.replace("\"prompt_eval_cached_count\":12", "\"prompt_eval_cached_count\":12,\"unknown_metric\":0"))) {
            chat.set(invalid);
            var rejected = execution();
            assertThrows(WorkspaceException.class, () -> provider.execute(rejected, new Cancellation()));
            org.mockito.Mockito.verify(rejected.reservation(), org.mockito.Mockito.never()).trustedCompletion();
            org.mockito.Mockito.verify(rejected.reservation()).providerExited();
        }
    }
    @Test void installedDefaultMetadataRequiresStableSupportedVersionAndPositiveLocalSource() throws Exception {
        var json = JsonMapper.builder().build();
        tools.jackson.databind.JsonNode fixture;
        try (var resource = getClass().getResourceAsStream("/ollama/qwen35-local-v0.40.0.json")) {
            fixture = json.readTree(resource);
        }
        tags.set(json.writeValueAsString(java.util.Map.of("models", java.util.List.of(fixture.path("tag")))));
        show.set(json.writeValueAsString(fixture.path("show")));
        var evidence = provider.admitLocal("qwen3.5:4b", 8192, new Cancellation());
        assertEquals("a".repeat(64), evidence.digest()); assertEquals(262144, evidence.contextLimit());
        for (String requirement : java.util.List.of("0.40.1", "0.41.0", "1.0.0", "unknown", "0.17.1-cloud", "0.017.1")) {
            var rejected = fixture.path("show").deepCopy();
            ((tools.jackson.databind.node.ObjectNode) rejected).put("requires", requirement);
            show.set(json.writeValueAsString(rejected));
            assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class,
                    () -> provider.admitLocal("qwen3.5:4b", 8192, new Cancellation())).error().code());
        }
        version.set("{\"version\":\"0.40.1\"}");
        show.set(json.writeValueAsString(fixture.path("show")));
        assertEquals(evidence, provider.admitLocal("qwen3.5:4b", 8192, new Cancellation()));
        var patchRequirement = fixture.path("show").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) patchRequirement).put("requires", "0.40.1");
        show.set(json.writeValueAsString(patchRequirement));
        assertEquals(evidence, provider.admitLocal("qwen3.5:4b", 8192, new Cancellation()));
        assertEquals(java.util.List.of(evidence), provider.catalog(8192, new Cancellation()));
        ((tools.jackson.databind.node.ObjectNode) patchRequirement).put("requires", "0.40.2");
        show.set(json.writeValueAsString(patchRequirement));
        assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class,
                () -> provider.admitLocal("qwen3.5:4b", 8192, new Cancellation())).error().code());
        show.set(json.writeValueAsString(fixture.path("show")));
        for (String rejectedVersion : java.util.List.of("{\"version\":\"0.40.2\"}", "{\"version\":\"0.41.0\"}",
                "{\"version\":\"0.40.1-rc1\"}", "{\"version\":\"0.40.1+build\"}",
                "{\"version\":null}", "{\"version\":401}", "{\"version\":\"0.40.1\",\"unknown\":true}")) {
            version.set(rejectedVersion);
            var denied = assertThrows(WorkspaceException.class,
                    () -> provider.admitLocal("qwen3.5:4b", 8192, new Cancellation()));
            assertEquals(ErrorCode.POLICY_DENIED, denied.error().code());
            assertEquals("MODEL_VERSION", denied.error().phase());
        }
        version.set("{\"version\":\"0.40.1\"}");
        assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class,
                () -> provider.admitLocal("qwen3.5:4b", 262145, new Cancellation())).error().code());
        tags.set(json.writeValueAsString(java.util.Map.of("models", java.util.List.of(fixture.path("tag"))))
                .replace("\"model\":\"qwen3.5:4b\"", "\"model\":\"other:latest\""));
        assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class,
                () -> provider.admitLocal("qwen3.5:4b", 8192, new Cancellation())).error().code());
        tags.set(json.writeValueAsString(java.util.Map.of("models", java.util.List.of(fixture.path("tag")))));
        var nonLocal = fixture.path("show").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) nonLocal).put("modelfile", "FROM https://remote.example/model\n");
        show.set(json.writeValueAsString(nonLocal));
        assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class,
                () -> provider.admitLocal("qwen3.5:4b", 8192, new Cancellation())).error().code());
        for (String remote : java.util.List.of("remote_host", "remote_model", "source", "runner")) {
            var rejected = fixture.path("show").deepCopy();
            ((tools.jackson.databind.node.ObjectNode) rejected).put(remote, "unknown");
            show.set(json.writeValueAsString(rejected));
            assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class,
                    () -> provider.admitLocal("qwen3.5:4b", 8192, new Cancellation())).error().code());
        }
        assertEquals(0, chatCalls.get()); // Metadata compatibility never grants load/inference or Vision execution.
    }
    @Test void manifestListIdentityRemainsBoundAcrossAdmissionOperationsAndRestart() throws Exception {
        var json = JsonMapper.builder().build();
        JsonNode fixture;
        try (var resource = getClass().getResourceAsStream("/ollama/qwen35-manifest-list-v0.40.1.json")) { fixture = json.readTree(resource); }
        String model = "test-model:latest", digest = "a".repeat(64), parent = "e".repeat(64);
        var settings = TestSettings.settings(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        var properties = new RuntimeProperties(settings.security(),
                new RuntimeProperties.Ollama(settings.ollama().baseUrl(),
                        Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(3), 8192),
                settings.tasks(), settings.translate(), settings.summarize(), settings.ask());
        provider.close(); provider = new OllamaProvider(properties, new ProviderPolicy());
        version.set(json.writeValueAsString(fixture.path("version")));
        String safeTags = json.writeValueAsString(fixture.path("tags")), safeShow = json.writeValueAsString(fixture.path("ggml"));
        tags.set(safeTags); show.set(safeShow);
        var evidence = provider.admitLocal(model, null, digest, 8192, new Cancellation());
        assertEquals("ggml", evidence.runner()); assertEquals(digest, evidence.digest());
        assertEquals(List.of(evidence), provider.catalog(8192, new Cancellation())); // Projector sibling is never a fallback.
        assertEquals("ggml", json.readTree(showPayload.get()).path("runner").asString());
        assertEquals(model + ":local", json.readTree(showPayload.get()).path("model").asString());
        assertThrows(WorkspaceException.class, () -> provider.admitLocal(model, "llamacpp", digest, 8192, new Cancellation()));
        assertThrows(WorkspaceException.class, () -> provider.admitLocal(model, null, parent, 8192, new Cancellation()));
        for (String invalid : java.util.List.of(
                safeShow.replace("\"runner\":\"ggml\"", "\"runner\":\"llamacpp\""),
                safeShow.replace("sha256:" + digest, "sha256:" + "d".repeat(64)),
                safeShow.replace("\"selected\":true", "\"selected\":false"),
                safeShow.replaceFirst("\\{", "{\"projector_info\":{\"general.architecture\":\"clip\"},"),
                safeShow.replace("/isolated/models/blobs/sha256-" + "b".repeat(64), "https://remote.example/model"))) {
            show.set(invalid);
            assertThrows(WorkspaceException.class, () -> provider.admitLocal(model, "ggml", digest, 8192, new Cancellation()));
        }
        show.set(safeShow);
        var missingSummaries = (ObjectNode) fixture.path("ggml").deepCopy(); missingSummaries.remove("manifests");
        show.set(json.writeValueAsString(missingSummaries));
        assertThrows(WorkspaceException.class, () -> provider.admitLocal(model, "ggml", digest, 8192, new Cancellation()));
        var remote = (ObjectNode) fixture.path("ggml").deepCopy(); remote.put("remote_host", "https://remote.example");
        show.set(json.writeValueAsString(remote));
        assertThrows(WorkspaceException.class, () -> provider.admitLocal(model, "ggml", digest, 8192, new Cancellation()));
        show.set(json.writeValueAsString(fixture.path("llamacpp")));
        assertThrows(WorkspaceException.class, () -> provider.admitLocal(model, "llamacpp", "c".repeat(64), 8192, new Cancellation()));
        show.set(safeShow);
        tags.set(safeTags.replace("c".repeat(64), digest)); // Existing disk format cannot distinguish this mapping.
        assertThrows(WorkspaceException.class, () -> provider.admitLocal(model, null, digest, 8192, new Cancellation()));
        tags.set(safeTags.replace("llamacpp", "ggml")); // Runner addressing cannot distinguish two GGML children.
        assertThrows(WorkspaceException.class, () -> provider.admitLocal(model, "ggml", digest, 8192, new Cancellation()));
        tags.set(safeTags); tagsAfterShow.set(safeTags.replace(digest, "d".repeat(64)));
        assertEquals(ErrorCode.MODEL_IDENTITY_CHANGED, assertThrows(WorkspaceException.class,
                () -> provider.admitLocal(model, "ggml", digest, 8192, new Cancellation())).error().code());
        tags.set(safeTags);
        assertEquals(0, chatCalls.get());

        var ps = new AtomicReference<>("{\"models\":[]}"); var releasePayload = new AtomicReference<String>();
        server.createContext("/api/ps", e -> respond(e, 200, ps.get()));
        server.createContext("/api/generate", e -> {
            releasePayload.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(e, 200, json.writeValueAsString(java.util.Map.of("model", model + ":local", "created_at", "2026-10-10T00:00:00Z", "response", "", "done", true, "done_reason", "unload")));
        });
        var row = new java.util.HashMap<String, Object>(); row.put("name", model); row.put("model", model); row.put("digest", parent); row.put("runner", "ggml");
        ps.set(json.writeValueAsString(java.util.Map.of("models", java.util.List.of(row))));
        assertEquals(ActiveModelManager.Fact.UNKNOWN, provider.residency(model, "ggml", digest, new Cancellation()));
        row.put("digest", digest); row.remove("runner");
        ps.set(json.writeValueAsString(java.util.Map.of("models", java.util.List.of(row))));
        assertEquals(ActiveModelManager.Fact.UNKNOWN, provider.residency(model, "ggml", digest, new Cancellation()));
        row.put("runner", "ggml");
        ps.set(json.writeValueAsString(java.util.Map.of("models", java.util.List.of(row))));
        assertEquals(ActiveModelManager.Fact.TRUE, provider.residency(model, "ggml", digest, new Cancellation()));
        row.put("runner", "llamacpp");
        ps.set(json.writeValueAsString(java.util.Map.of("models", java.util.List.of(row))));
        assertEquals(ActiveModelManager.Fact.UNKNOWN, provider.residency(model, "ggml", digest, new Cancellation()));
        row.put("digest", parent);
        ps.set(json.writeValueAsString(Map.of("models", List.of(row))));
        assertEquals(ActiveModelManager.Fact.UNKNOWN, provider.residency(model, "ggml", digest, new Cancellation()));
        var exact = new HashMap<>(row); exact.put("runner", "ggml"); exact.put("digest", digest);
        ps.set(json.writeValueAsString(Map.of("models", List.of(row, exact))));
        assertEquals(ActiveModelManager.Fact.TRUE, provider.residency(model, "ggml", digest, new Cancellation()));
        ps.set("{\"models\":[]}");

        // Mock persistence avoids credentials, ACL changes and all real Profile state.
        var store = org.mockito.Mockito.mock(ModelStateStore.class);
        var selection = new AtomicReference<ModelStateStore.Selection>();
        org.mockito.Mockito.when(store.selection()).thenAnswer(ignored -> selection.get());
        org.mockito.Mockito.when(store.commit(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> { var committed = new ModelStateStore.Selection((long) call.getArgument(0) + 1, "ollama", call.getArgument(1), call.getArgument(2)); selection.set(committed); return committed; });
        var profiles = new ProfileResolver(properties);
        try (var manager = new ActiveModelManager(profiles, provider, properties, store)) {
            var entry = manager.catalog().models().getFirst();
            var wrongRunner = new ActiveModelManager.Intent(
                    Action.SWITCH, entry.handle(), model, "llamacpp", digest, 0, null, null, null, null, false);
            assertEquals(ErrorCode.MODEL_CATALOG_STALE, assertThrows(WorkspaceException.class, () -> manager.manage(wrongRunner)).error().code());
            assertEquals(0, chatCalls.get());
            var switched = manager.manage(modelIntent(manager, Action.SWITCH));
            assertEquals("ggml", switched.activeRunner()); assertEquals(digest, switched.activeDigest());
            assertEquals("ggml", json.readTree(payload.get()).path("runner").asString());
            try (var lease = manager.reserve("translate.fast", "test")) {
                assertEquals("ggml", lease.model().runner());
                assertEquals("你好", provider.execute(new Provider.ProviderExecution(lease.profile(), PrivacyMode.LOCAL_ONLY, "Translate", "hello", java.util.List.of(new Provider.ChatMessage("user", "hello")), lease), new Cancellation()));
            }
            manager.manage(modelIntent(manager, Action.RELEASE));
            assertEquals("ggml", json.readTree(releasePayload.get()).path("runner").asString());
            var validated = manager.manage(modelIntent(manager, Action.VALIDATE));
            assertEquals(1, validated.selectionRevision()); assertEquals("ggml", validated.activeRunner()); assertTrue(validated.ready());
            var oldIntent = modelIntent(manager, Action.SWITCH);
            int sent = chatCalls.get(); tags.set(safeTags.replace(digest, "d".repeat(64)));
            assertEquals(ErrorCode.MODEL_IDENTITY_CHANGED, assertThrows(WorkspaceException.class, () -> manager.reserve("translate.fast", "test")).error().code());
            assertEquals(sent, chatCalls.get()); assertEquals(digest, selection.get().digest());
            show.set(safeShow.replace(digest, "d".repeat(64)));
            assertEquals(ErrorCode.MODEL_IDENTITY_CHANGED, assertThrows(WorkspaceException.class, () -> manager.manage(oldIntent)).error().code());
            assertEquals(sent, chatCalls.get());
            var reselected = manager.manage(modelIntent(manager, Action.SWITCH));
            assertEquals(2, reselected.selectionRevision()); assertEquals("d".repeat(64), reselected.activeDigest());
        }
        try (var restarted = new ActiveModelManager(profiles, provider, properties, store)) {
            var status = restarted.managementStatus();
            assertEquals("ggml", status.configuredRunner()); assertEquals("d".repeat(64), status.configuredDigest());
        }
        tags.set(safeTags); show.set(safeShow);
        var legacy = org.mockito.Mockito.mock(ModelStateStore.class);
        org.mockito.Mockito.when(legacy.selection()).thenReturn(new ModelStateStore.Selection(1, "ollama", model, parent));
        org.mockito.Mockito.when(legacy.validationRequired()).thenReturn(new ModelStateStore.ValidationRequired(1, "ollama", model, parent));
        org.mockito.Mockito.when(legacy.unresolved()).thenReturn(true);
        int sent = chatCalls.get();
        try (var guarded = new ActiveModelManager(profiles, provider, properties, legacy)) {
            var status = guarded.managementStatus();
            assertEquals(parent, status.configuredDigest()); assertNull(status.configuredRunner()); assertTrue(status.uncertain()); assertTrue(status.validationRequired());
            assertEquals(ErrorCode.MODEL_EXECUTION_UNCERTAIN, assertThrows(WorkspaceException.class, () -> guarded.reserve("translate.fast", "test")).error().code());
            assertEquals(ErrorCode.MODEL_IDENTITY_CHANGED, assertThrows(WorkspaceException.class,
                    () -> guarded.manage(modelIntent(guarded, Action.RECOVER))).error().code());
        }
        assertEquals(sent, chatCalls.get());
        assertTrue(org.mockito.Mockito.mockingDetails(legacy).getInvocations().stream().allMatch(call ->
                Set.of("selection", "validationRequired", "unresolved", "close").contains(call.getMethod().getName())));
    }
    private static ActiveModelManager.Intent modelIntent(ActiveModelManager manager,
            ActiveModelManager.Action action) {
        var status = manager.managementStatus(); var entry = manager.catalog().models().getFirst();
        return new ActiveModelManager.Intent(action, entry.handle(), entry.model(), entry.runner(), entry.digest(),
                status.selectionRevision(), status.activeModel(), status.activeRunner(), status.activeDigest(),
                action == Action.RECOVER ? status.recoveryGeneration() : null,
                action == Action.RELEASE || action == Action.RECOVER);
    }
    @Test void unavailableMissingAndRawErrorAreControlled() {
        tagStatus.set(503); tags.set("private secret raw error /internal/path");
        assertCode(ErrorCode.PROVIDER_UNAVAILABLE);
        assertEquals(0, chatCalls.get());
        assertFalse(provider.readiness(TestSettings.profile()).available());
        tagStatus.set(200); tags.set("{\"models\":[]}");
        assertCode(ErrorCode.MODEL_UNAVAILABLE);
        assertTrue(provider.readiness(TestSettings.profile()).available());
        assertFalse(provider.readiness(TestSettings.profile()).modelAvailable());
        tags.set(OllamaFixtures.tags("test-model:latest", "a".repeat(64)));
        chatStatus.set(500); chat.set("private secret raw error /internal/path");
        WorkspaceException failure = assertCode(ErrorCode.PROVIDER_UNAVAILABLE);
        assertFalse(failure.error().message().contains("secret"));
        assertNull(failure.getCause());
    }
    @Test void malformedOversizeIncompleteAndRedirectsAreRejected() {
        for (String bad : new String[]{"not-json", "null", "{} {}", "x".repeat(2048),
                "{\"done\":false,\"message\":{\"content\":\"partial\"}}",
                "{\"model\":\"test-model:latest\",\"done\":true,\"done_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"truncated\"}}"}) {
            chat.set(bad); assertCode(ErrorCode.PROVIDER_RESPONSE_INVALID);
        }
        chatStatus.set(302);
        assertCode(ErrorCode.PROVIDER_RESPONSE_INVALID);
    }
    @Test void providerTimeout() {
        delay = 1500;
        WorkspaceException timeout = assertCode(ErrorCode.TASK_TIMEOUT);
        assertEquals("PROVIDER", timeout.error().phase());
    }
    @Test void inFlightCancellation() throws Exception {
        delay = 1500;
        Cancellation cancellation = new Cancellation();
        CompletableFuture<WorkspaceException> result = CompletableFuture.supplyAsync(() ->
                assertThrows(WorkspaceException.class, () -> provider.execute(execution(), cancellation)));
        assertTrue(chatEntered.await(1, TimeUnit.SECONDS));
        cancellation.cancel();
        assertEquals(ErrorCode.TASK_CANCELLED, result.get(1, TimeUnit.SECONDS).error().code());
    }
    @Test void connectionAndRequestTimeoutClassification() {
        assertEquals("CONNECT", OllamaProvider.classify(new CompletionException(
                new java.net.http.HttpConnectTimeoutException("raw sensitive message"))).error().phase());
        assertEquals("PROVIDER", OllamaProvider.classify(new java.net.http.HttpTimeoutException("private")).error().phase());
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, OllamaProvider.classify(new java.net.ConnectException("private")).error().code());
    }
    @Test void finalEgressPolicyRejectsCloudBeforeHttp() {
        var p = TestSettings.profile();
        var cloud = new io.github.qianlixunbai.workspace.model.ModelProfile(p.id(), p.provider(), p.model(),
                io.github.qianlixunbai.workspace.model.ModelProfile.Locality.CLOUD, p.version(), p.contextBudget(), p.outputBudget(), p.temperature(), p.maxTextCharacters());
        assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class, () -> provider.execute(
                new Provider.ProviderExecution(cloud, PrivacyMode.CLOUD_OPTIONAL, "private", "private"), new Cancellation())).error().code());
        assertEquals(0, chatCalls.get());

        var mismatched = execution();
        org.mockito.Mockito.when(mismatched.reservation().model()).thenReturn(new ActiveModelManager.ExecutionModel("alias:latest", "ggml", "a".repeat(64), 0));
        assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class,
                () -> provider.execute(mismatched, new Cancellation())).error().code());
        assertEquals(0, chatCalls.get());
    }
    private WorkspaceException assertCode(ErrorCode code) {
        WorkspaceException failure = assertThrows(WorkspaceException.class, () -> provider.execute(execution(), new Cancellation()));
        assertEquals(code, failure.error().code()); return failure;
    }
    private Provider.ProviderExecution execution() {
        return new Provider.ProviderExecution(TestSettings.profile(), PrivacyMode.LOCAL_ONLY, "Translate only", "hello",
                java.util.List.of(new Provider.ChatMessage("user", "hello")), TestSettings.reservation(TestSettings.profile(), "test"));
    }
    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
