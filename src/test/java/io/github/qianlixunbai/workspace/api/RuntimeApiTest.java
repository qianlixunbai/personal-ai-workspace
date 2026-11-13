package io.github.qianlixunbai.workspace.api;

import com.sun.net.httpserver.HttpServer;
import io.github.qianlixunbai.workspace.PersonalAiWorkspaceApplication;
import org.junit.jupiter.api.*;
import io.github.qianlixunbai.workspace.capability.translate.TranslatePrompt;
import io.github.qianlixunbai.workspace.capability.summarize.SummarizePrompt;
import io.github.qianlixunbai.workspace.capability.ask.AskPrompt;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = PersonalAiWorkspaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(io.github.qianlixunbai.workspace.web.WebApiFixture.class)
@ExtendWith(OutputCaptureExtension.class)
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class RuntimeApiTest {
    private static final Path ISOLATED_ROOT = temporaryMemoryDirectory();
    private static final Path TOKEN = ISOLATED_ROOT.resolve("credentials/client-token");
    private static final Path MEMORY = System.getProperty("knowledge.recovery.desktop.fixture") == null ? ISOLATED_ROOT.resolve("data")
            : io.github.qianlixunbai.workspace.knowledge.KnowledgeRecoveryDesktopFixture.data();
    private static Path temporaryMemoryDirectory() {
        try {
            String nativePhase = System.getProperty("memory.native.desktop.fixture");
            if (nativePhase != null) {
                assertTrue(java.util.Set.of("save", "reopen").contains(nativePhase));
                // Full-context Knowledge history uses the default Win32 SQLite VFS.
                // Keep this synthetic subtree short, including its journal suffix.
                Path root = io.github.qianlixunbai.workspace.security.WindowsNativeGate.profileRoot().resolve("evidence/m");
                if (nativePhase.equals("save")) assertFalse(Files.exists(root));
                else assertTrue(Files.isRegularFile(root.resolve("data/memory.db")));
                io.github.qianlixunbai.workspace.memory.PrivateMemoryDirectory.directory(root);
                return root;
            }
            if (System.getProperty("knowledge.recovery.desktop.fixture") != null)
                return io.github.qianlixunbai.workspace.knowledge.KnowledgeRecoveryDesktopFixture.root();
            if (io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.isWindows()) {
                // A normal Surefire JVM has no native authority. The opt-in Windows probe
                // supplies a NEW admitted Profile; never repair a generic temporary directory.
                Path root = io.github.qianlixunbai.workspace.security.WindowsNativeGate.profileRoot()
                        .resolve("evidence").resolve("api-memory-" + java.util.UUID.randomUUID());
                io.github.qianlixunbai.workspace.memory.PrivateMemoryDirectory.directory(root);
                return root;
            }
            return Files.createTempDirectory("workspace-api-memory-");
        }
        catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static final AtomicInteger MODE = new AtomicInteger();
    private static final AtomicInteger CHAT_CALLS = new AtomicInteger();
    private static final AtomicInteger PROBE_CALLS = new AtomicInteger();
    private static final AtomicReference<String> BATCH_OUTPUT = new AtomicReference<>();
    private static final AtomicReference<JsonNode> LAST_CHAT = new AtomicReference<>();
    private static final java.util.List<JsonNode> DESKTOP_CHATS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile CountDownLatch slowEntered, slowRelease, slowExited;
    private static final int MOCK_PORT = freePort();
    private static final int NATIVE_PORT = System.getProperty("memory.native.desktop.fixture") == null ? -1 : nativeMemoryPort();
    private static int nativeMemoryPort() {
        int port = freePort();
        assertTrue(port > 1024 && port != 18765 && port != 18895 && port != 8765);
        return port;
    }
    private HttpServer mock;
    private final java.util.ArrayList<String> clientIds = new java.util.ArrayList<>();
    private final java.util.ArrayList<String> privateValues = new java.util.ArrayList<>();

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @BeforeEach void resetFixture() throws Exception {
        MODE.set(0); CHAT_CALLS.set(0); PROBE_CALLS.set(0); BATCH_OUTPUT.set(null); LAST_CHAT.set(null);
        DESKTOP_CHATS.clear();
        slowEntered = slowRelease = slowExited = null;
        mock = HttpServer.create();
        mock.createContext("/api/version", exchange -> {
            try (exchange) {
                byte[] bytes = "{\"version\":\"0.40.0\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            }
        });
        mock.createContext("/api/show", exchange -> {
            try (exchange) {
                byte[] bytes = io.github.qianlixunbai.workspace.provider.ollama.OllamaFixtures.show().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            }
        });
        mock.createContext("/api/tags", exchange -> {
            int mode = MODE.get();
            String body = mode == 1 ? "raw-error-private-marker" : mode == 2 ? "{\"models\":[]}"
                    : io.github.qianlixunbai.workspace.provider.ollama.OllamaFixtures.tags("qwen3.5:4b", "a".repeat(64));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.sendResponseHeaders(mode == 1 ? 503 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        mock.createContext("/api/ps", exchange -> {
            try (exchange) { byte[] bytes = "{\"models\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
        });
        mock.createContext("/api/chat", exchange -> {
            JsonNode request = JsonMapper.builder().build().readTree(exchange.getRequestBody().readAllBytes());
            if (request.path("messages").get(0).path("content").asString().equals("Reply with OK only.")) {
                PROBE_CALLS.incrementAndGet();
                try (exchange) {
                    byte[] bytes = io.github.qianlixunbai.workspace.provider.ollama.OllamaFixtures.completed(request.path("model").asString(), "OK").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                }
                return;
            }
            CHAT_CALLS.incrementAndGet();
            LAST_CHAT.set(request);
            if (Boolean.getBoolean("memory.desktop.fixture")) DESKTOP_CHATS.add(request);
            if (MODE.get() == 5) {
                slowEntered.countDown();
                try { slowRelease.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            String output = MODE.get() == 4 ? "x".repeat(8193) :
                    BATCH_OUTPUT.get() == null ? "output-private-marker" : BATCH_OUTPUT.get();
            if (Boolean.getBoolean("memory.desktop.fixture")) output = request.toString().contains("Propose at most one concise durable memory")
                    ? "{\"candidate\":{\"type\":\"PREFERENCE\",\"title\":\"Amber concise replies\",\"content\":\"For project Amber, I prefer short bullet points.\",\"evidence\":\"I prefer short bullet points.\"}}"
                    : "Synthetic provider reply. No real model was called.";
            byte[] bytes = (MODE.get() == 3 ? "malformed-private-marker" :
                    io.github.qianlixunbai.workspace.provider.ollama.OllamaFixtures.completed(request.path("model").asString(), output)).getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (java.io.IOException ignored) { /* A cancelled HTTP future may close before the late response. */ }
            finally { if (slowExited != null) slowExited.countDown(); }
        });
    }
    private void startMock() throws Exception {
        mock.bind(new InetSocketAddress("127.0.0.1", MOCK_PORT), 0);
        mock.start();
    }
    @AfterEach void releaseFixtureAndCheckDiagnostics(CapturedOutput logs) throws Exception {
        try {
            if (slowRelease != null) slowRelease.countDown();
            if (slowEntered != null && slowEntered.getCount() == 0)
                assertTrue(slowExited.await(2, TimeUnit.SECONDS), "Provider handler did not finish");
            for (String id : clientIds)
                assertEquals(204, send("DELETE", "/api/v1/security/clients/" + id, null, true).statusCode());
            for (String value : List.of("input-private-marker", "output-private-marker", "raw-error-private-marker",
                    "malformed-private-marker", "internal-private-marker", token()))
                assertFalse(logs.getAll().contains(value), "Private value appeared in Runtime diagnostics");
            for (String value : privateValues)
                assertFalse(logs.getAll().contains(value), "Fixture secret/prompt appeared in Runtime diagnostics");
        } finally {
            mock.stop(0);
            MODE.set(0); BATCH_OUTPUT.set(null); LAST_CHAT.set(null);
        }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (NATIVE_PORT != -1) {
            registry.add("server.address", () -> "127.0.0.1");
            registry.add("server.port", () -> NATIVE_PORT);
        }
        registry.add("workspace.security.token-file", () -> TOKEN.toString());
        registry.add("workspace.data-directory", () -> MEMORY.toString());
        Path modelRoot = ISOLATED_ROOT.resolve("model-state-" + java.util.UUID.randomUUID());
        registry.add("workspace.model-state-directory", modelRoot::toString);
        registry.add("workspace.ollama.base-url", () -> "http://127.0.0.1:" + MOCK_PORT);
        registry.add("workspace.ollama.health-timeout", () -> "200ms");
        registry.add("workspace.ollama.connect-timeout", () -> "100ms");
    }
    @LocalServerPort int port;
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="memory.native.desktop.fixture", matches="save|reopen")
    void desktopNativeMemoryPersistsAcrossProcessesWithoutModel() throws Exception {
        String phase = System.getProperty("memory.native.desktop.fixture");
        assertEquals(NATIVE_PORT, port);
        var store = application.getBean(io.github.qianlixunbai.workspace.memory.MemoryStore.class);
        assertFalse(store.postgresql());
        assertEquals(MEMORY.resolve("memory.db"), store.databaseFile());
        var before = json.readTree(send("GET", "/api/v1/memory/items?query=&status=ACTIVE&page=0&limit=20", null, true).body());
        assertEquals(phase.equals("save") ? 0 : 1, before.path("total").asInt());
        Path ready = ISOLATED_ROOT.resolve(phase + "-ready.json"), done = ISOLATED_ROOT.resolve(phase + "-done");
        assertFalse(Files.exists(ready)); assertFalse(Files.exists(done)); token();
        Path pending = ISOLATED_ROOT.resolve(phase + "-ready.pending");
        io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.createFile(pending);
        Files.writeString(pending, json.writeValueAsString(Map.of("port", port, "pid", ProcessHandle.current().pid(),
                "tokenFile", TOKEN.toString(), "phase", phase, "database", store.databaseFile().toString(), "syntheticNativeMemory", true)));
        Files.move(pending, ready, StandardCopyOption.ATOMIC_MOVE);
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!Files.exists(done) && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(Files.exists(done)); assertEquals("PASS", Files.readString(done));
        var after = json.readTree(send("GET", "/api/v1/memory/items?query=&status=ACTIVE&page=0&limit=20", null, true).body());
        assertEquals(1, after.path("total").asInt());
        if (phase.equals("reopen")) assertEquals(before, after);
        assertFalse(activeModels.status().ready()); assertEquals(0, CHAT_CALLS.get()); assertEquals(0, PROBE_CALLS.get());
        // This opt-in path never starts even the synthetic provider.
        System.out.println("NATIVE_MEMORY_REAL_HTTP_SQLITE_NO_MODEL_PASS_" + phase);
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="conversation.search.desktop.fixture",matches="true")
    void desktopConversationSearchReadsSyntheticHistoryWithoutModelOrWrites() throws Exception {
        // Explicit opt-in, freshly provisioned native test Profile. No provider is started.
        assertEquals(0,conversations.list(io.github.qianlixunbai.workspace.conversation.Conversation.Status.ACTIVE,0,10).total());
        var active=conversations.create("合成活动会话"); var archived=conversations.create("合成归档会话");
        String query="检索目标😀%_";
        for(int i=1;i<=23;i++){
            var turn=conversations.createTurnWithUserMessage(active.id(),(i<=13?query:"合成普通记录")+" 活动消息 第 "+i+" 轮\n<script>只作文本</script>");
            conversations.completeTurnWithAssistantMessage(active.id(),turn.id(),"合成保存的回答 "+i+"，未调用模型。");
        }
        var old=conversations.createTurnWithUserMessage(archived.id(),query+" 归档历史，仅此一条。");
        conversations.markTurnTerminated(archived.id(),old.id(),io.github.qianlixunbai.workspace.conversation.Conversation.TurnStatus.CANCELLED);
        conversations.archive(archived.id());
        var before=List.of(conversations.detail(active.id(),0,10),conversations.detail(active.id(),1,10),conversations.detail(active.id(),2,10),conversations.detail(archived.id(),0,10));
        Path evidence=io.github.qianlixunbai.workspace.security.WindowsNativeGate.profileRoot().resolve("evidence");
        Path ready=evidence.resolve("desktop-ready.json"),done=evidence.resolve("desktop-done"),writing=evidence.resolve("desktop-ready-writing.json");
        assertFalse(Files.exists(ready));assertFalse(Files.exists(done));token();
        io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.createFile(writing);
        Files.writeString(writing,json.writeValueAsString(Map.of("port",port,"tokenFile",TOKEN.toString(),"syntheticConversationSearch",true,
                "activeId",active.id(),"archivedId",archived.id(),"query",query,"pid",ProcessHandle.current().pid())));
        Files.move(writing,ready,StandardCopyOption.ATOMIC_MOVE);
        long deadline=System.nanoTime()+Duration.ofSeconds(220).toNanos();
        while(!Files.exists(done)&&System.nanoTime()<deadline)Thread.sleep(100);
        assertTrue(Files.exists(done));assertEquals("PASS",Files.readString(done));
        assertEquals(before,List.of(conversations.detail(active.id(),0,10),conversations.detail(active.id(),1,10),conversations.detail(active.id(),2,10),conversations.detail(archived.id(),0,10)));
        assertEquals(1,conversations.list(io.github.qianlixunbai.workspace.conversation.Conversation.Status.ACTIVE,0,10).total());
        assertEquals(1,conversations.list(io.github.qianlixunbai.workspace.conversation.Conversation.Status.ARCHIVED,0,10).total());
        assertFalse(activeModels.status().ready());assertEquals(0,CHAT_CALLS.get());assertEquals(0,PROBE_CALLS.get());
        System.out.println("DESKTOP_CONVERSATION_SEARCH_UNCHANGED_HISTORY_NO_MODEL_PASS");
    }
    @org.springframework.beans.factory.annotation.Autowired io.github.qianlixunbai.workspace.model.ActiveModelManager activeModels;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext application;
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="knowledge.recovery.desktop.fixture",matches="prepare|reopen")
    void desktopReadsEncryptedRestoredKnowledgeWithoutModelOrTaskReplay() throws Exception {
        String phase=System.getProperty("knowledge.recovery.desktop.fixture");
        var history=application.getBean(io.github.qianlixunbai.workspace.knowledge.KnowledgeAnswerHistory.class);
        var tasks=application.getBean(io.github.qianlixunbai.workspace.task.TaskManager.class);
        io.github.qianlixunbai.workspace.knowledge.KnowledgeRecoveryDesktopFixture.verify(knowledgeStore,history,tasks);
        assertFalse(activeModels.status().ready());
        // Do not start the synthetic provider either. Its dedicated port is never contacted by this read-only workflow.
        Path evidence=io.github.qianlixunbai.workspace.security.WindowsNativeGate.profileRoot().resolve("evidence");
        Path ready=evidence.resolve("desktop-ready-"+phase+".json"),done=evidence.resolve("desktop-done-"+phase);
        assertFalse(Files.exists(ready));assertFalse(Files.exists(done));token();
        Path writing=evidence.resolve("desktop-ready-"+phase+".pending");
        io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.createFile(writing);
        Files.writeString(writing,json.writeValueAsString(Map.of("port",port,"tokenFile",TOKEN.toString(),"syntheticRecovery",true,"phase",phase,"pid",ProcessHandle.current().pid())));
        Files.move(writing,ready,StandardCopyOption.ATOMIC_MOVE);
        long deadline=System.nanoTime()+Duration.ofSeconds(180).toNanos();
        while(!Files.exists(done)&&System.nanoTime()<deadline)Thread.sleep(100);
        assertTrue(Files.exists(done));assertEquals("PASS",Files.readString(done));
        assertEquals(0,CHAT_CALLS.get());assertEquals(0,PROBE_CALLS.get());assertFalse(activeModels.status().ready());
        io.github.qianlixunbai.workspace.knowledge.KnowledgeRecoveryDesktopFixture.verify(knowledgeStore,history,tasks);
        System.out.println("DESKTOP_ENCRYPTED_HISTORY_OLD_SOURCE_MISSING_SOURCE_NO_TASK_REPLAY_PASS_"+phase);
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "web.desktop.fixture", matches = "true")
    void desktopWebKnowledgeWorkflowWithRealFetch() throws Exception {
        // Explicit opt-in only: the fake Web bean is absent, so normal DNS/TLS/SSRF controls execute.
        assertFalse(Boolean.getBoolean("memory.desktop.fixture"));
        var owners = application.getBeansOfType(io.github.qianlixunbai.workspace.web.WebFetchService.class);
        assertEquals(java.util.Set.of("webFetchService"), owners.keySet());
        startMock(); // Only private health metadata; this workflow must never generate model output.
        Path evidence = io.github.qianlixunbai.workspace.security.WindowsNativeGate.profileRoot().resolve("evidence");
        Path ready = evidence.resolve("desktop-ready.json"), done = evidence.resolve("desktop-done");
        assertFalse(Files.exists(ready)); assertFalse(Files.exists(done)); token();
        Path writing = evidence.resolve("desktop-ready-writing.json");
        io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.createFile(writing);
        Files.writeString(writing, json.writeValueAsString(Map.of("port", port, "tokenFile", TOKEN.toString(),
                "syntheticProvider", true, "realWeb", true)));
        Files.move(writing, ready, StandardCopyOption.ATOMIC_MOVE);
        long deadline = System.nanoTime() + Duration.ofSeconds(220).toNanos();
        while (!Files.exists(done) && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(Files.exists(done), "Desktop fixture timed out");
        assertEquals("PASS", Files.readString(done));
        assertEquals(0, CHAT_CALLS.get()); assertEquals(0, PROBE_CALLS.get());
        assertEquals(2, knowledgeStore.list("ACTIVE", 0).total());
        assertEquals("READY", knowledgeIndex.status().state());
        assertFalse(knowledgeIndex.search("documentation", 10).hits().isEmpty());
        System.out.println("DESKTOP_REAL_WEB_HTTP_SQLITE_VERIFIED_MODEL_CALLS_ZERO");
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "memory.desktop.fixture", matches = "true")
    void desktopMemoryWorkflowWithSyntheticProvider() throws Exception {
        // The native owner creates a NEW Profile and holds its pin. No normal service,
        // model or user credential is used. UI drives the real HTTP/Store contracts.
        startMock();
        Path evidence = io.github.qianlixunbai.workspace.security.WindowsNativeGate.profileRoot().resolve("evidence");
        Path ready = evidence.resolve("desktop-ready.json"), done = evidence.resolve("desktop-done");
        assertFalse(Files.exists(ready)); assertFalse(Files.exists(done));
        token(); // Ensure the normal native credential has been created before publication.
        Path writing = evidence.resolve("desktop-ready-writing.json");
        io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.createFile(writing);
        Files.writeString(writing, JsonMapper.builder().build().writeValueAsString(
                Map.of("port", port, "tokenFile", TOKEN.toString(), "syntheticProvider", true)));
        Files.move(writing, ready, StandardCopyOption.ATOMIC_MOVE);
        long deadline = System.nanoTime() + Duration.ofSeconds(220).toNanos();
        while (!Files.exists(done) && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(Files.exists(done), "Desktop fixture timed out");
        assertEquals("PASS", Files.readString(done));
        assertEquals(5, DESKTOP_CHATS.size()); // source turn, candidate, retained twice, cleared turn
        assertEquals(1, DESKTOP_CHATS.stream().filter(chat -> chat.toString().contains("Propose at most one concise durable memory")).count());
        assertEquals(io.github.qianlixunbai.workspace.provider.ResponseFormat.MEMORY_CANDIDATE.schema(), DESKTOP_CHATS.get(1).path("format"));
        for (int index : new int[]{0, 2, 3, 4}) assertFalse(DESKTOP_CHATS.get(index).has("format"));
        var explicit = JsonMapper.builder().build().readTree(DESKTOP_CHATS.get(2).path("messages").get(1).path("content").asString());
        assertEquals(1, explicit.path("explicitMemory").size());
        assertEquals("For project Amber, I prefer short bullet points.", explicit.path("explicitMemory").get(0).path("content").asString());
        assertEquals(DESKTOP_CHATS.get(2).path("messages").get(1), DESKTOP_CHATS.get(3).path("messages").get(1));
        for (var chat : List.of(DESKTOP_CHATS.get(0), DESKTOP_CHATS.get(1), DESKTOP_CHATS.get(4)))
            for (var message : chat.path("messages")) assertFalse(message.path("content").asString().startsWith("{\"explicitMemory\":"));
        // Clearing explicit context does not erase prior source text from conversation history.
        assertEquals("For project Amber, I prefer short bullet points.", DESKTOP_CHATS.get(4).path("messages").get(1).path("content").asString());
        Path trace = evidence.resolve("desktop-provider-requests.json");
        io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.createFile(trace);
        Files.writeString(trace, JsonMapper.builder().build().writeValueAsString(DESKTOP_CHATS));
        System.out.println("DESKTOP_REAL_HTTP_SQLITE_SYNTHETIC_PROVIDER_VERIFIED");
    }
    @org.springframework.beans.factory.annotation.Autowired io.github.qianlixunbai.workspace.knowledge.KnowledgeStore knowledgeStore;
    @org.springframework.beans.factory.annotation.Autowired io.github.qianlixunbai.workspace.knowledge.KnowledgeIngestion knowledgeIngestion;
    @org.springframework.beans.factory.annotation.Autowired io.github.qianlixunbai.workspace.knowledge.KnowledgeLexicalIndex knowledgeIndex;
    @org.springframework.beans.factory.annotation.Autowired io.github.qianlixunbai.workspace.task.TaskManager taskManager;
    @org.springframework.beans.factory.annotation.Autowired io.github.qianlixunbai.workspace.config.RuntimeProperties settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    private String memoryAskBody(String question, List<JsonNode> items) {
        return json.writeValueAsString(Map.of("question", question, "profile", "chat.balanced", "memories",
                items.stream().map(item -> Map.of("id", item.path("id").asString(), "revision", item.path("revision").asLong())).toList()));
    }

    @Test
    @org.springframework.test.annotation.DirtiesContext(methodMode = org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void nativeCandidateUsesFixedSchemaWithoutFallbackOrMemoryWrites() throws Exception {
        startMock();
        String endpoint = "/api/v1/ask/tasks";
        String body = "{\"question\":\"Synthetic candidate extraction\",\"outputContract\":\"memory-candidate-v1\"}";
        BATCH_OUTPUT.set("{\"candidate\":null}");
        var result = submitAndPoll(endpoint, body);
        assertEquals("SUCCEEDED", result.path("status").asString());
        assertEquals("memory-candidate-v1", result.path("promptVersion").asString());
        var schema = io.github.qianlixunbai.workspace.provider.ResponseFormat.MEMORY_CANDIDATE.schema();
        assertEquals(schema, LAST_CHAT.get().path("format"));
        assertEquals(AskPrompt.SYSTEM, LAST_CHAT.get().path("messages").get(0).path("content").asString());
        assertFalse(LAST_CHAT.get().has("tools"));
        assertTrue(LAST_CHAT.get().toString().getBytes(StandardCharsets.UTF_8).length + 2048 <= 8192);
        assertEquals(0, tree(send("GET", "/api/v1/memory/items", null, true)).path("total").asInt());
        var ordinary = submitAndPoll(endpoint, "{\"question\":\"ordinary\"}");
        assertEquals("ask-v1", ordinary.path("promptVersion").asString());
        assertFalse(LAST_CHAT.get().has("format"));

        int calls = CHAT_CALLS.get();
        for (String invalid : List.of(body.replace("memory-candidate-v1", "arbitrary-schema"),
                "{\"question\":\"q\",\"format\":{\"type\":\"object\"}}",
                "{\"question\":\"q\",\"outputContract\":{\"type\":\"object\"}}"))
            assertEquals(400, send("POST", endpoint, invalid, true).statusCode());
        var browserClient = pairBrowser("chrome-extension://" + "n".repeat(32));
        assertEquals(403, browser("POST", endpoint, body, browserClient.credential(), browserClient.origin()).statusCode());
        assertEquals(401, send("POST", endpoint, body, false).statusCode());
        assertEquals(calls, CHAT_CALLS.get());

        var oversized = submitAndPoll(endpoint, json.writeValueAsString(Map.of("question", "界".repeat(1800), "outputContract", "memory-candidate-v1")));
        assertEquals("FAILED", oversized.path("status").asString());
        assertEquals("INVALID_REQUEST", oversized.path("error").path("code").asString());
        assertEquals(calls, CHAT_CALLS.get()); // No inference after schema pushes the wire envelope over budget.
        MODE.set(3);
        var rejected = submitAndPoll(endpoint, body);
        assertEquals("FAILED", rejected.path("status").asString());
        assertEquals("PROVIDER_RESPONSE_INVALID", rejected.path("error").path("code").asString());
        assertEquals(calls + 1, CHAT_CALLS.get()); // No automatic retry without the schema.
        // A malformed provider response intentionally quarantines this model generation;
        // recreate the Spring fixture afterward, never force it ready to keep testing.
        assertEquals(0, tree(send("GET", "/api/v1/memory/items", null, true)).path("total").asInt());
    }

    @Test void nativeMemoryCrudContractAndPrivateErrors() throws Exception {
        String title = "memory-title-private-marker", content = "memory-content-private-marker 中文项目";
        privateValues.add(title); privateValues.add(content); privateValues.add("memory-query-private-marker");
        String endpoint = "/api/v1/memory/items";
        String body = json.writeValueAsString(Map.of("type", "PROJECT_NOTE", "title", title, "content", content));
        var accepted = send("POST", endpoint, body, true);
        assertEquals(201, accepted.statusCode());
        JsonNode item = tree(accepted); String path = endpoint + "/" + item.path("id").asString();
        assertEquals(path, accepted.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", accepted.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(1, item.path("revision").asLong());
        assertEquals("MANUAL", item.path("source").asString());
        assertEquals(content, tree(send("GET", path, null, true)).path("content").asString());
        String recall = json.writeValueAsString(Map.of("query", "中文项目计划 PRIVATE", "type", "PROJECT_NOTE"));
        var recalled = send("POST", "/api/v1/memory/recall", recall, true);
        assertEquals(200, recalled.statusCode());
        assertEquals("no-store", recalled.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(item.path("id").asString(), tree(recalled).path("hits").get(0).path("item").path("id").asString());
        assertEquals(1, tree(recalled).path("total").asInt());
        assertEquals(400, send("POST", "/api/v1/memory/recall", "{\"query\":\"\"}", true).statusCode());
        assertEquals(1, tree(send("GET", endpoint + "?query=" + URLEncoder.encode("中文项", StandardCharsets.UTF_8), null, true)).path("total").asLong());
        assertEquals(20, tree(send("GET", endpoint, null, true)).path("limit").asInt());
        assertEquals(0, tree(send("GET", endpoint + "?query=memory-query-private-marker", null, true)).path("total").asLong());
        String update = json.writeValueAsString(Map.of("expectedRevision",1,"type","PREFERENCE","title",title,"content",content));
        assertEquals(2, tree(send("PUT", path, update, true)).path("revision").asLong());
        for (String[] command : List.of(new String[]{"PUT",path,update},new String[]{"POST",path+"/archive","{\"expectedRevision\":1}"},
                new String[]{"DELETE",path,"{\"expectedRevision\":1}"})) {
            var response = send(command[0],command[1],command[2],true);
            assertEquals(409,response.statusCode()); assertEquals("MEMORY_REVISION_CONFLICT",tree(response).path("code").asString());
            assertFalse(response.body().contains(title)); assertFalse(response.body().contains(content));
            assertFalse(response.body().contains("memory.db"));
        }
        assertEquals(3,tree(send("POST",path+"/archive","{\"expectedRevision\":2}",true)).path("revision").asLong());
        assertEquals(0, tree(send("POST", "/api/v1/memory/recall", recall, true)).path("total").asInt());
        assertEquals(0,tree(send("GET",endpoint,null,true)).path("total").asLong());
        assertEquals(1,tree(send("GET",endpoint+"?status=ARCHIVED&type=PREFERENCE",null,true)).path("total").asLong());
        assertEquals(204,send("POST","/api/v1/memory/index/rebuild",null,true).statusCode());
        assertEquals(4,tree(send("POST",path+"/restore","{\"expectedRevision\":3}",true)).path("revision").asLong());
        assertEquals(204,send("DELETE",path,"{\"expectedRevision\":4}",true).statusCode());
        assertEquals(404,send("GET",path,null,true).statusCode());
        for (String bad : List.of("{}", "null", "{broken", body.replace("PROJECT_NOTE","FINANCE"),body.replace("}",",\"source\":\"AUTO\"}"),
                body.replace("}",",\"history\":[]}"))) {
            var response=send("POST",endpoint,bad,true);
            assertEquals(400,response.statusCode()); assertFalse(response.body().contains(title));
        }
        assertEquals(400,send("PUT",path,json.writeValueAsString(Map.of("type","PREFERENCE","title",title,"content",content)),true).statusCode());
        assertEquals(400,send("GET",endpoint+"?limit=101",null,true).statusCode());
        assertEquals(400,send("GET",endpoint+"?status=DELETED",null,true).statusCode());
        assertEquals(0,CHAT_CALLS.get()); // Memory is independent of AI execution.
    }

    @Test void memoryAuthorizationAndEveryMutationBodyAreBounded() throws Exception {
        var client = pairBrowser("chrome-extension://" + "c".repeat(32));
        String root="/api/v1/memory", collection=root+"/items", item=collection+"/00000000-0000-0000-0000-000000000000";
        List<String[]> routes=List.of(new String[]{"GET",collection},new String[]{"GET",item},new String[]{"POST",collection},
                new String[]{"PUT",item},new String[]{"PATCH",item},new String[]{"POST",item+"/archive"},
                new String[]{"POST",item+"/restore"},new String[]{"DELETE",item},new String[]{"POST",root+"/index/rebuild"},
                new String[]{"POST",root+"/recall"});
        for (String[] route : routes) {
            String body=route[0].equals("GET") ? null : "{}";
            assertEquals(401,send(route[0],route[1],body,false).statusCode());
            assertEquals(403,browser(route[0],route[1],body,client.credential(),client.origin()).statusCode());
            assertTrue(List.of(401,403).contains(browser(route[0],route[1],body,client.credential(),null).statusCode()));
            var web=http.send(HttpRequest.newBuilder(uri(route[1])).header("Origin","https://untrusted.example")
                    .header("Authorization","Bearer "+token()).method(route[0],HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,web.statusCode());
            var preflight=http.send(browserRequest(route[1],client.origin()).header("Access-Control-Request-Method",route[0])
                    .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,preflight.statusCode());
            assertTrue(preflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            if (!route[0].equals("GET")) {
                // Unknown-length streaming body: check actual bytes rather than trust Content-Length.
                var oversized=HttpRequest.newBuilder(uri(route[1])).header("Authorization","Bearer "+token())
                        .header("Content-Type","application/json").method(route[0],HttpRequest.BodyPublishers.ofInputStream(
                                () -> new java.io.ByteArrayInputStream("x".repeat(32769).getBytes(StandardCharsets.UTF_8)))).build();
                assertEquals(413,http.send(oversized,HttpResponse.BodyHandlers.ofString()).statusCode());
            }
        }
        assertEquals(0,CHAT_CALLS.get());
    }

    @Test void runtimeHealthRemainsIndependentOfOfflineProvider() throws Exception {
        // The fixture remains unbound: this exercises connection refusal and offline startup.
        assertEquals(200, send("GET", "/actuator/health/readiness", null, false).statusCode());
        assertEquals("UP", tree(send("GET", "/actuator/health", null, false)).path("status").asString());
        assertEquals(401, send("POST", "/api/v1/translate/tasks", valid(), false).statusCode());
        assertEquals(401, send("GET", "/api/v1/providers/readiness", null, false).statusCode());
        assertEquals(401, http.send(HttpRequest.newBuilder(uri("/api/v1/translate/tasks"))
                .header("Authorization", "Bearer " + token()).header("Origin", "https://untrusted.example")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(valid())).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        JsonNode offline = tree(send("GET", "/api/v1/providers/readiness", null, true));
        assertFalse(offline.path("available").asBoolean());
        var rejected = send("POST", "/api/v1/translate/tasks", valid(), true);
        assertEquals(503, rejected.statusCode());
        assertEquals("PROVIDER_UNAVAILABLE", tree(rejected).path("code").asString());
        assertEquals(200, send("GET", "/actuator/health/readiness", null, false).statusCode());

    }
    private enum ProviderFailure {
        UNAVAILABLE(1, "PROVIDER_UNAVAILABLE"), MODEL_MISSING(2, "MODEL_UNAVAILABLE"),
        MALFORMED_RESPONSE(3, "PROVIDER_RESPONSE_INVALID"), OUTPUT_BUDGET(4, "PROVIDER_RESPONSE_INVALID"),
        UNEXPECTED_WORK_FAILURE(-1, "INTERNAL_ERROR");
        final int mode;
        final String code;
        ProviderFailure(int mode, String code) { this.mode = mode; this.code = code; }
    }
    private void assertPublicProfile(JsonNode task, String profile) {
        JsonNode identity = task.path("profile");
        assertEquals(profile, identity.path("id").asString());
        assertTrue(identity.path("version").asString().matches("am1-[a-f0-9-]{36}"));
        assertEquals("LOCAL", identity.path("locality").asString());
        assertFalse(identity.has("model")); assertFalse(identity.has("provider"));
        assertFalse(task.toString().contains("qwen3.5"));
    }
    private void assertPrompt(String system, String input) {
        JsonNode messages = LAST_CHAT.get().path("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).path("role").asString());
        assertEquals(system, messages.get(0).path("content").asString());
        assertEquals("user", messages.get(1).path("role").asString());
        assertEquals(input, messages.get(1).path("content").asString());
        assertFalse(system.contains("input-private-marker"));
        privateValues.add(system); privateValues.add(input);
    }
    private record BrowserClient(String origin, String credential, String exchange) {}
    private record BrowserPair(BrowserClient a, BrowserClient b) {}
    private BrowserPair pairBrowsers() throws Exception {
        return new BrowserPair(pairBrowser("chrome-extension://" + "a".repeat(32)),
                pairBrowser("chrome-extension://" + "b".repeat(32)));
    }
    private BrowserClient pairBrowser(String origin) throws Exception {
        String exchange = exchangeBody(createPairing(origin));
        var response = browser("POST", "/api/v1/security/pairings/exchange", exchange, null, origin);
        assertEquals(200, response.statusCode());
        return new BrowserClient(origin, tree(response).path("credential").asString(), exchange);
    }
    @Test void browserSecurityContract(CapturedOutput logs) throws Exception {
        startMock();
        String originA = "chrome-extension://" + "a".repeat(32), originB = "chrome-extension://" + "b".repeat(32);
        String unknown = "chrome-extension://" + "c".repeat(32);
        assertEquals(400, send("POST", "/api/v1/security/pairings", json.writeValueAsString(java.util.Map.of(
                "origin", originA, "displayName", "Test", "userApproved", false)), true).statusCode());
        JsonNode sessionA = createPairing(originA), sessionB = createPairing(originB);
        String exchangeA = exchangeBody(sessionA), exchangeB = exchangeBody(sessionB);
        for (String origin : new String[]{"https://example.com", unknown, originB})
            assertEquals(401, browser("POST", "/api/v1/security/pairings/exchange", exchangeA, null, origin).statusCode());
        var preflight = http.send(browserRequest("/api/v1/security/pairings/exchange", originA)
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflight.statusCode());
        assertEquals(originA, preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        var responseA = browser("POST", "/api/v1/security/pairings/exchange", exchangeA, null, originA);
        assertEquals(200, responseA.statusCode());
        assertEquals("no-store", responseA.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode a = tree(responseA), b = tree(browser("POST", "/api/v1/security/pairings/exchange", exchangeB, null, originB));
        String credentialA = a.path("credential").asString(), credentialB = b.path("credential").asString();
        assertTrue(credentialA.startsWith("br1."));
        assertNotEquals(a.path("client").path("clientId").asString(), b.path("client").path("clientId").asString());
        assertEquals(401, browser("POST", "/api/v1/security/pairings/exchange", exchangeA, null, originA).statusCode());
        for (String origin : new String[]{originB, unknown, "https://example.com", "http://localhost:8765", "null", "*"}) {
            var denied = browser("POST", "/api/v1/translate/tasks", valid(), credentialA, origin);
            assertEquals(401, denied.statusCode()); assertTrue(denied.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        }
        for (String credential : new String[]{"bad", "br1.malformed", token(), "br1." + a.path("client").path("clientId").asString() + "." + "z".repeat(43)})
            assertEquals(401, browser("POST", "/api/v1/translate/tasks", valid(), credential, originA).statusCode());
        assertEquals(401, browser("POST", "/api/v1/translate/tasks", valid(), null, originA).statusCode());
        assertEquals(401, browser("POST", "/api/v1/translate/tasks", valid(), credentialA, null).statusCode());
        for (String site : new String[]{"cross-site", "same-site", "same-origin", "missing"}) {
            var request = HttpRequest.newBuilder(uri("/api/v1/translate/tasks"))
                    .header("Origin", originA).header("Authorization", "Bearer " + credentialA).header("Content-Type", "application/json");
            if (!site.equals("missing")) request.header("Sec-Fetch-Site", site);
            assertEquals(401, http.send(request.POST(HttpRequest.BodyPublishers.ofString(valid())).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        for (String endpoint : new String[]{"/api/v1/ask/tasks", "/api/v1/summarize/tasks", "/api/v1/security/pairings", "/api/v1/security/clients", "/api/v1/providers/readiness"})
            assertEquals(403, browser("POST", endpoint, "{}", credentialA, originA).statusCode());
        String metadata = send("GET", "/api/v1/security/clients", null, true).body();
        assertFalse(metadata.contains(credentialA)); assertFalse(metadata.contains("verifier")); assertFalse(metadata.contains(token()));
        var accepted = browser("POST", "/api/v1/translate/tasks", valid(), credentialA, originA);
        assertEquals(202, accepted.statusCode());
        assertEquals(originA, accepted.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        String id = tree(accepted).path("taskId").asString();
        JsonNode result = pollBrowser(id, credentialA, originA);
        assertEquals("SUCCEEDED", result.path("status").asString());
        var missing = browser("GET", "/api/v1/tasks/00000000-0000-0000-0000-000000000000", null, credentialB, originB);
        for (String method : new String[]{"GET", "DELETE"}) {
            var denied = browser(method, "/api/v1/tasks/" + id, null, credentialB, originB);
            assertEquals(404, denied.statusCode()); assertEquals(missing.body(), denied.body());
            assertEquals(404, send(method, "/api/v1/tasks/" + id, null, true).statusCode());
        }
        String nativeId = submitAndPoll(valid()).path("taskId").asString();
        assertEquals(200, send("GET", "/api/v1/tasks/" + nativeId, null, true).statusCode());
        for (String method : new String[]{"GET", "DELETE"})
            assertEquals(404, browser(method, "/api/v1/tasks/" + nativeId, null, credentialA, originA).statusCode());
        assertEquals(200, browser("DELETE", "/api/v1/tasks/" + id, null, credentialA, originA).statusCode());
        String clientId = a.path("client").path("clientId").asString();
        assertEquals(403, browser("DELETE", "/api/v1/security/clients/" + clientId, null, credentialB, originB).statusCode());
        assertEquals(204, send("DELETE", "/api/v1/security/clients/" + clientId, null, true).statusCode());
        assertEquals(401, browser("GET", "/api/v1/tasks/" + id, null, credentialA, originA).statusCode());
        assertEquals(401, browser("GET", "/api/v1/capabilities/translate/readiness", null, credentialA, originA).statusCode());
        assertEquals(401, browser("GET", "/api/v1/capabilities/translate/readiness", null, credentialA, null).statusCode());
        assertEquals(401, browser("GET", "/api/v1/tasks/" + id, null, credentialA, null).statusCode());
        assertEquals(204, send("DELETE", "/api/v1/security/clients/" + b.path("client").path("clientId").asString(), null, true).statusCode());
        for (String secret : new String[]{credentialA, credentialB, sessionA.path("pairingSecret").asString(), originA, originB, token()})
            assertFalse(logs.getAll().contains(secret));
    }
    private void chromeOriginlessGetCompatibility(String credential, String origin, String otherCredential,
                                                   String ownedId, String nativeId, String exchange) throws Exception {
        String readiness = "/api/v1/capabilities/translate/readiness", owned = "/api/v1/tasks/" + ownedId;
        for (String path : new String[]{readiness, owned}) {
            var accepted = browser("GET", path, null, credential, null);
            assertEquals(200, accepted.statusCode());
            assertTrue(accepted.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            assertEquals("no-store", accepted.headers().firstValue("Cache-Control").orElseThrow());
        }
        assertTrue(tree(browser("GET", readiness, null, credential, null)).path("available").asBoolean());
        assertEquals("SUCCEEDED", tree(browser("GET", owned, null, credential, null)).path("status").asString());
        var missing = browser("GET", "/api/v1/tasks/00000000-0000-0000-0000-000000000000", null, credential, null);
        for (var denied : List.of(browser("GET", owned, null, otherCredential, null),
                browser("GET", "/api/v1/tasks/" + nativeId, null, credential, null), send("GET", owned, null, true))) {
            assertEquals(404, denied.statusCode()); assertEquals(missing.body(), denied.body());
        }
        for (String invalid : new String[]{null, "bad", "br1.malformed", "br1." + ownedId + "." + "z".repeat(43),
                credential.substring(0, 41) + "z".repeat(43)})
            assertEquals(401, browser("GET", readiness, null, invalid, null).statusCode());
        String[] names = {"Sec-Fetch-Site", "Sec-Fetch-Mode", "Sec-Fetch-Dest"};
        String[][] invalidMetadata = {{null, "cross-site", "same-site", "same-origin"},
                {null, "no-cors", "navigate"}, {null, "document", "script"}};
        for (int index=0; index<names.length; index++) for (String value : invalidMetadata[index]) {
            var request = browserRequest(readiness, null).header("Authorization", "Bearer " + credential);
            if (value == null) {
                // Build without the selected header; empty and absent must both fail closed.
                request = HttpRequest.newBuilder(uri(readiness)).header("Authorization", "Bearer " + credential);
                for (int i=0; i<names.length; i++) if (i != index)
                    request.header(names[i], new String[]{"none", "cors", "empty"}[i]);
            } else request.setHeader(names[index], value);
            assertEquals(401, http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode(),
                    names[index] + "=" + value);
        }
        for (String path : new String[]{"/api/v1/ask/tasks", "/api/v1/summarize/tasks", "/api/v1/security/clients",
                "/api/v1/security/pairings", "/api/v1/security/pairings/exchange", "/api/v1/providers/readiness",
                "/api/v1/future", "/api/v1/tasks/" + "-".repeat(36), readiness + "/", owned + "/extra"}) {
            var denied = browser("GET", path, null, credential, null);
            assertEquals(403, denied.statusCode()); assertEquals("POLICY_DENIED", tree(denied).path("code").asString());
            assertTrue(denied.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        }
        for (String path : new String[]{"/api/v1/translate/tasks", "/api/v1/ask/tasks", "/api/v1/summarize/tasks",
                "/api/v1/security/pairings", "/api/v1/security/pairings/exchange"})
            assertEquals(401, browser("POST", path, valid(), credential, null).statusCode());
        for (String path : new String[]{owned, "/api/v1/security/clients/" + ownedId})
            assertEquals(401, browser("DELETE", path, null, credential, null).statusCode());
        assertEquals(401, browser("OPTIONS", readiness, null, credential, null).statusCode());
        assertEquals(401, browser("HEAD", readiness, null, credential, null).statusCode());
        assertEquals(401, browser("POST", "/api/v1/security/pairings/exchange", exchange, null, null).statusCode());
        assertEquals(401, browser("POST", "/api/v1/security/pairings/exchange", exchange, credential, origin).statusCode());
        // Origin-present authentication must remain bound even on the new GET allowlist.
        for (String wrong : new String[]{"chrome-extension://" + "b".repeat(32), "chrome-extension://" + "c".repeat(32),
                "https://example.com", ""})
            assertEquals(401, browser("GET", readiness, null, credential, wrong).statusCode());
        assertEquals(401, browser("GET", readiness, null, token(), origin).statusCode());
    }
    private void batchContract(String credential, String origin, String otherCredential, String otherOrigin, CapturedOutput logs) throws Exception {
        String endpoint = "/api/v1/translate/tasks", readiness = "/api/v1/capabilities/translate/readiness";
        assertEquals(401, send("GET", readiness, null, false).statusCode());
        assertEquals(401, browser("GET", readiness, null, "bad", origin).statusCode());
        for (String deniedOrigin : new String[]{otherOrigin, "https://example.com", "chrome-extension://" + "c".repeat(32)})
            assertEquals(401, browser("GET", readiness, null, credential, deniedOrigin).statusCode());
        var preflight = http.send(browserRequest(readiness, origin).header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization").method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflight.statusCode());
        assertEquals(origin, preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(401, http.send(browserRequest(readiness, origin).header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        for (int mode : new int[]{0, 1, 2}) {
            MODE.set(mode);
            var ready = tree(browser("GET", readiness, null, credential, origin));
            assertEquals(mode == 0, ready.path("available").asBoolean());
            assertEquals(mode != 0, ready.has("error"));
            if (mode != 0) assertEquals("PROVIDER_UNAVAILABLE", ready.path("error").path("code").asString());
            for (String forbidden : List.of("model", "provider", "profile")) assertFalse(ready.has(forbidden));
            assertFalse(ready.toString().contains("qwen3.5"));
        }
        MODE.set(0);
        String two = batch(List.of(Map.of("id", 1, "text", "batch-input-private-marker"), Map.of("id", 2, "text", "Settings")));
        String all = "[{\"id\":2,\"translation\":\"settings-output-private-marker\"},{\"id\":1,\"translation\":\"batch-output-private-marker\"}]";
        try {
            BATCH_OUTPUT.set(all);
            int before = CHAT_CALLS.get();
            var accepted = browser("POST", endpoint, two, credential, origin);
            assertEquals(202, accepted.statusCode());
            String id = tree(accepted).path("taskId").asString();
            assertEquals("/api/v1/tasks/" + id, accepted.headers().firstValue("Location").orElseThrow());
            var result = pollBrowser(id, credential, origin);
            assertEquals("SUCCEEDED", result.path("status").asString());
            assertEquals(before + 1, CHAT_CALLS.get(), "One batch must make exactly one chat inference");
            assertTrue(result.path("result").isObject());
            assertEquals(2, result.path("result").path("items").size());
            assertEquals(1, result.path("result").path("items").get(0).path("id").asInt());
            assertEquals("translate-batch-v1", result.path("promptVersion").asString());
            assertEquals("translate.fast", result.path("profile").path("id").asString());
            assertPublicProfile(result, "translate.fast");
            assertEquals("LOCAL", result.path("profile").path("locality").asString());
            assertFalse(result.toString().contains("qwen3.5"));
            JsonNode messages = LAST_CHAT.get().path("messages");
            String prompt = messages.get(0).path("content").asString();
            assertFalse(prompt.contains("batch-input-private-marker"));
            assertTrue(prompt.contains("untrusted"));
            assertEquals(2, json.readTree(messages.get(1).path("content").asString()).size());
            for (String method : new String[]{"GET", "DELETE"}) {
                var denied = browser(method, "/api/v1/tasks/" + id, null, otherCredential, otherOrigin);
                var missing = browser(method, "/api/v1/tasks/00000000-0000-0000-0000-000000000000", null, otherCredential, otherOrigin);
                assertEquals(404, denied.statusCode()); assertEquals(missing.body(), denied.body());
                assertEquals(404, send(method, "/api/v1/tasks/" + id, null, true).statusCode());
            }
            assertEquals("SUCCEEDED", tree(browser("DELETE", "/api/v1/tasks/" + id, null, credential, origin)).path("status").asString());

            var many = java.util.stream.IntStream.range(0, 32).mapToObj(n -> Map.of("id", n, "text", "Label " + n)).toList();
            BATCH_OUTPUT.set(json.writeValueAsString(java.util.stream.IntStream.range(0, 32)
                    .mapToObj(n -> Map.of("id", n, "translation", "Label result " + n)).toList()));
            assertEquals(32, submitAndPoll(batch(many)).path("result").path("items").size());
            BATCH_OUTPUT.set("[{\"id\":1,\"translation\":\"bounded\"}]");
            assertEquals("SUCCEEDED", submitAndPoll(batch(List.of(Map.of("id", 1, "text", "x".repeat(2800))))).path("status").asString());
            assertEquals("SUCCEEDED", submitAndPoll(batch(List.of(Map.of("id", 1, "text", "中".repeat(1365))))).path("status").asString());
            // Quotes obey the serialized context budget as well as the raw text budget.
            assertEquals("SUCCEEDED", submitAndPoll(batch(List.of(Map.of("id", 1, "text", "\"".repeat(2800))))).path("status").asString());
            int beforeInvalid = CHAT_CALLS.get();
            for (BatchInputCase row : List.of(
                    new BatchInputCase("missing input", "{\"targetLanguage\":\"zh-CN\"}"),
                    new BatchInputCase("text and items", two.replace("{\"items\"", "{\"text\":\"x\",\"items\"")),
                    new BatchInputCase("empty items", batch(List.of())),
                    new BatchInputCase("duplicate request id", batch(List.of(Map.of("id", 1, "text", "x"), Map.of("id", 1, "text", "y")))),
                    new BatchInputCase("negative id", batch(List.of(Map.of("id", -1, "text", "x")))),
                    new BatchInputCase("out of range id", batch(List.of(Map.of("id", 2147483648L, "text", "x")))),
                    new BatchInputCase("string id", batch(List.of(Map.of("id", "1", "text", "x")))),
                    new BatchInputCase("floating id", batch(List.of(Map.of("id", 1.0, "text", "x")))),
                    new BatchInputCase("boolean id", batch(List.of(Map.of("id", true, "text", "x")))),
                    new BatchInputCase("missing id", batch(List.of(Map.of("text", "x")))),
                    new BatchInputCase("null id", "{\"items\":[{\"id\":null,\"text\":\"x\"}],\"targetLanguage\":\"zh-CN\"}"),
                    new BatchInputCase("null item text", "{\"items\":[{\"id\":1,\"text\":null}],\"targetLanguage\":\"zh-CN\"}"),
                    new BatchInputCase("null items", "{\"items\":null,\"targetLanguage\":\"zh-CN\"}"),
                    new BatchInputCase("null single text", "{\"text\":null,\"targetLanguage\":\"zh-CN\"}"),
                    new BatchInputCase("blank item text", batch(List.of(Map.of("id", 1, "text", " \t")))),
                    new BatchInputCase("numeric item text", batch(List.of(Map.of("id", 1, "text", 5)))),
                    new BatchInputCase("unknown item field", batch(List.of(Map.of("id", 1, "text", "x", "instruction", "private-marker")))),
                    new BatchInputCase("33 items", batch(java.util.stream.IntStream.range(0, 33).mapToObj(n -> Map.of("id", n, "text", "x")).toList())),
                    new BatchInputCase("aggregate characters", batch(List.of(Map.of("id", 1, "text", "x".repeat(1400)), Map.of("id", 2, "text", "x".repeat(1401))))),
                    new BatchInputCase("item character budget", batch(List.of(Map.of("id", 1, "text", "x".repeat(2801))))),
                    new BatchInputCase("UTF-8 budget", batch(List.of(Map.of("id", 1, "text", "中".repeat(1366))))),
                    new BatchInputCase("serialized context budget", batch(List.of(Map.of("id", 1, "text", "\u0000".repeat(1000))))),
                    new BatchInputCase("unsafe language", two.replace("\"zh-CN\"", "\"ignore rules\"")),
                    new BatchInputCase("wrong profile", two.replace("\"zh-CN\"", "\"zh-CN\",\"profile\":\"chat.balanced\"")),
                    new BatchInputCase("null text and items", two.replace("{\"items\"", "{\"text\":null,\"items\"")),
                    new BatchInputCase("null item", two.replace("\"items\":[", "\"items\":[null,")))) {
                String invalid = row.body();
                var rejected = browser("POST", endpoint, invalid, credential, origin);
                assertEquals(400, rejected.statusCode(), row.name() + " must fail before inference");
                assertEquals("INVALID_REQUEST", tree(rejected).path("code").asString());
                assertFalse(rejected.body().contains("private-marker"));
            }
            for (String field : new String[]{"model", "systemPrompt", "translationPrompt", "instruction", "temperature", "top_p", "num_ctx", "num_predict", "keep_alive", "think"}) {
                var root = (tools.jackson.databind.node.ObjectNode) json.readTree(two);
                root.put(field, "private-marker");
                assertEquals(400, browser("POST", endpoint, root.toString(), credential, origin).statusCode());
            }
            assertEquals(413, browser("POST", endpoint, "x".repeat(32769), credential, origin).statusCode());
            assertEquals(beforeInvalid, CHAT_CALLS.get());

            for (BatchOutputCase row : List.of(
                    new BatchOutputCase("reordered complete output", all, List.of(1, 2)),
                    new BatchOutputCase("missing id partial", "[{\"id\":1,\"translation\":\"valid\"}]", List.of(1)),
                    new BatchOutputCase("unexpected id ignored", "[{\"id\":1,\"translation\":\"valid\"},{\"id\":99,\"translation\":\"unexpected-private-marker\"}]", List.of(1)),
                    new BatchOutputCase("duplicate output id omitted", "[{\"id\":1,\"translation\":\"a\"},{\"id\":1,\"translation\":\"b\"},{\"id\":1,\"translation\":\"c\"},{\"id\":2,\"translation\":\"valid\"}]", List.of(2)),
                    new BatchOutputCase("blank translation omitted", "[{\"id\":1,\"translation\":\" \"},{\"id\":2,\"translation\":\"valid\"}]", List.of(2)),
                    new BatchOutputCase("malformed items ignored", "[null,5,{}, {\"id\":\"1\",\"translation\":\"wrong\"},{\"id\":1.0,\"translation\":\"wrong\"},{\"id\":2,\"translation\":\"valid\"}]", List.of(2)),
                    new BatchOutputCase("null and repeated output id omitted", "[{\"id\":1,\"translation\":null},{\"id\":1,\"translation\":\"late-invalid\"}]", List.of()),
                    new BatchOutputCase("empty output partial", "[]", List.of()),
                    new BatchOutputCase("unknown output item field omitted", "[{\"id\":1,\"translation\":\"valid\",\"extra\":true}]", List.of()))) {
                BATCH_OUTPUT.set(row.output()); before = CHAT_CALLS.get();
                var mapped = pollBrowser(tree(browser("POST", endpoint, two, credential, origin)).path("taskId").asString(), credential, origin);
                assertEquals("SUCCEEDED", mapped.path("status").asString(), row.name());
                var actualIds = new java.util.ArrayList<Integer>();
                for (JsonNode item : mapped.path("result").path("items")) actualIds.add(item.path("id").asInt());
                assertEquals(row.ids(), actualIds, row.name());
                assertEquals(before + 1, CHAT_CALLS.get(), row.name());
                assertFalse(mapped.toString().contains("unexpected-private-marker"), row.name());
                assertFalse(mapped.toString().contains("late-invalid"), row.name());
            }
            for (RejectedBatchOutput row : List.of(
                    new RejectedBatchOutput("malformed JSON", "malformed-private-marker"),
                    new RejectedBatchOutput("object top level", "{}"),
                    new RejectedBatchOutput("null top level", "null"),
                    new RejectedBatchOutput("trailing JSON", "[] []"),
                    new RejectedBatchOutput("markdown fence", "```json\n[]\n```"),
                    new RejectedBatchOutput("duplicate JSON key", "[{\"id\":1,\"id\":2,\"translation\":\"duplicate-key-private-marker\"}]"),
                    new RejectedBatchOutput("output mapping budget", "x".repeat(8193)))) {
                BATCH_OUTPUT.set(row.output());
                var failed = submitAndPoll(two);
                assertEquals("FAILED", failed.path("status").asString(), row.name());
                assertEquals("PROVIDER_RESPONSE_INVALID", failed.path("error").path("code").asString(), row.name());
                assertTrue(failed.path("result").isNull(), row.name());
                assertFalse(failed.toString().contains("private-marker"), row.name());
            }
            BATCH_OUTPUT.set(all);
            MODE.set(5); slowEntered = new CountDownLatch(1); slowRelease = new CountDownLatch(1); slowExited = new CountDownLatch(1);
            // Revoke blocks future HTTP access, while an already accepted batch keeps its original lifecycle.
            slowEntered = new CountDownLatch(1); slowRelease = new CountDownLatch(1); slowExited = new CountDownLatch(1);
            var revokedTask = tree(browser("POST", endpoint, two, otherCredential, otherOrigin));
            assertTrue(slowEntered.await(2, TimeUnit.SECONDS));
            String otherClientId = otherCredential.split("\\.")[1];
            assertEquals(204, send("DELETE", "/api/v1/security/clients/" + otherClientId, null, true).statusCode());
            assertEquals(401, browser("GET", "/api/v1/tasks/" + revokedTask.path("taskId").asString(), null, otherCredential, otherOrigin).statusCode());
            slowRelease.countDown(); assertTrue(slowExited.await(2, TimeUnit.SECONDS));
            var revokedId = java.util.UUID.fromString(revokedTask.path("taskId").asString());
            long revokeDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (taskManager.get(revokedId, otherClientId).status() == io.github.qianlixunbai.workspace.task.TaskStatus.RUNNING
                    && System.nanoTime() < revokeDeadline) Thread.sleep(10);
            assertEquals(io.github.qianlixunbai.workspace.task.TaskStatus.SUCCEEDED, taskManager.get(revokedId, otherClientId).status());
            assertInstanceOf(io.github.qianlixunbai.workspace.task.TaskResult.TranslationBatch.class, taskManager.get(revokedId, otherClientId).result());
            for (String privateValue : new String[]{prompt, "batch-input-private-marker", "batch-output-private-marker", "settings-output-private-marker", "unexpected-private-marker", "duplicate-key-private-marker"})
                assertFalse(logs.getAll().contains(privateValue));
        } finally {
            if (slowRelease != null) slowRelease.countDown();
            MODE.set(0); BATCH_OUTPUT.set(null);
        }
    }
    private record BatchInputCase(String name, String body) {}
    private record BatchOutputCase(String name, String output, List<Integer> ids) {}
    private record RejectedBatchOutput(String name, String output) {}
    private String batch(List<?> items) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("items", items); body.put("targetLanguage", "zh-CN");
        return json.writeValueAsString(body);
    }
    private JsonNode pollBrowser(String id, String credential, String origin) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var result = tree(browser("GET", "/api/v1/tasks/" + id, null, credential, origin));
            String status = result.path("status").asString();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) return result;
            Thread.sleep(10);
        }
        throw new AssertionError("Browser task polling deadline expired");
    }
    private JsonNode createPairing(String origin) throws Exception {
        var response = send("POST", "/api/v1/security/pairings", json.writeValueAsString(java.util.Map.of("origin", origin,
                "displayName", "Synthetic client", "userApproved", true)), true);
        assertEquals(200, response.statusCode());
        JsonNode session = tree(response);
        privateValues.add(origin); privateValues.add(session.path("pairingSecret").asString());
        return session;
    }
    private String exchangeBody(JsonNode session) {
        return json.writeValueAsString(java.util.Map.of("pairingId", session.path("pairingId").asString(),
                "pairingSecret", session.path("pairingSecret").asString()));
    }
    private HttpRequest.Builder browserRequest(String path, String origin) {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5));
        if (origin != null) request.header("Origin", origin);
        return request.header("Sec-Fetch-Site", "none").header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Dest", "empty");
    }
    private HttpResponse<String> browser(String method, String path, String body, String credential, String origin) throws Exception {
        var request = browserRequest(path, origin);
        if (credential != null) request.header("Authorization", "Bearer " + credential);
        if (body != null) request.header("Content-Type", "application/json");
        var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (path.equals("/api/v1/security/pairings/exchange") && response.statusCode() == 200) {
            JsonNode exchanged = tree(response);
            clientIds.add(exchanged.path("client").path("clientId").asString());
            privateValues.add(exchanged.path("credential").asString());
        }
        return response;
    }
    private JsonNode submitAndPoll(String body) throws Exception {
        return submitAndPoll("/api/v1/translate/tasks", body);
    }
    private JsonNode submitAndPoll(String endpoint, String body) throws Exception {
        HttpResponse<String> accepted = send("POST", endpoint, body, true);
        assertEquals(202, accepted.statusCode());
        String id = tree(accepted).path("taskId").asString();
        assertTrue(accepted.headers().firstValue("Location").orElseThrow().endsWith(id));
        return pollNative(id);
    }
    private JsonNode pollNative(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode result = tree(send("GET", "/api/v1/tasks/" + id, null, true));
            if (!result.path("status").asString().equals("QUEUED") && !result.path("status").asString().equals("RUNNING")) return result;
            Thread.sleep(10);
        }
        throw new AssertionError("Task polling deadline expired");
    }
    private HttpResponse<String> send(String method, String path, String body, boolean auth) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5));
        if (auth) request.header("Authorization", "Bearer " + token());
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String token() throws Exception { return Files.readString(TOKEN).strip(); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private String valid() { return "{\"text\":\"input-private-marker\",\"targetLanguage\":\"zh-CN\",\"profile\":\"translate.fast\"}"; }

    @org.springframework.beans.factory.annotation.Autowired io.github.qianlixunbai.workspace.conversation.ConversationStore conversations;

    @Test void knowledgeNativeStreamingAndBrowserDenialBeforeReadingBody(CapturedOutput logs)throws Exception {
        var client=pairBrowser("chrome-extension://"+"k".repeat(32));String base="/api/v1/knowledge";
        for(String path:List.of(base+"/documents",base+"/imports",base+"/documents/"+java.util.UUID.randomUUID()+"/preview",base+"/backup",base+"/backup/validate",base+"/backup/restore")){
            String method=path.endsWith("imports")||path.endsWith("validate")||path.endsWith("restore")?"POST":"GET";
            assertEquals(403,browser(method,path,method.equals("POST")?"x".repeat(50000):null,client.credential(),client.origin()).statusCode());
            assertTrue(List.of(401,403).contains(browser(method,path,null,client.credential(),null).statusCode()));
            assertEquals(401,http.send(browserRequest(path,client.origin()).header("Access-Control-Request-Method",method).method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        String marker="knowledge-private-source-"+java.util.UUID.randomUUID(),requestId=java.util.UUID.randomUUID().toString();privateValues.add(marker);
        var request=HttpRequest.newBuilder(uri(base+"/imports")).header("Authorization","Bearer "+token()).header("Content-Type","application/octet-stream")
            .header("X-Knowledge-Request",requestId).header("X-Knowledge-Size",Integer.toString(marker.length()))
            .header("X-Knowledge-Filename",java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("fixture.txt".getBytes(StandardCharsets.UTF_8)))
            .POST(HttpRequest.BodyPublishers.ofString(marker)).build();
        var admitted=http.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,admitted.statusCode());String doc=tree(admitted).path("documentId").asString();
        try{
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);JsonNode job;
            do{job=tree(send("GET",base+"/imports/"+requestId,null,true));if(job.path("state").asString().equals("READY"))break;assertTrue(System.nanoTime()<until);Thread.sleep(5);}while(true);
            var preview=send("GET",base+"/documents/"+doc+"/preview?revision=1",null,true);assertEquals(marker,tree(preview).path("text").asString());assertFalse(preview.body().contains(MEMORY.toString()));
            assertEquals(413,send("POST","/api/v1/memory/items","x".repeat(32769),true).statusCode());
            assertFalse(logs.getAll().contains(marker));
        }finally{var current=tree(send("GET",base+"/documents/"+doc,null,true)).path("document").path("metadataVersion").asString();
            assertEquals(200,send("DELETE",base+"/documents/"+doc,json.writeValueAsString(Map.of("expectedMetadataVersion",current)),true).statusCode());}
    }
}
