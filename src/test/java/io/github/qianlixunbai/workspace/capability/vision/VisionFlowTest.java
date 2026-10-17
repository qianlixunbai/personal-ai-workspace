package io.github.qianlixunbai.workspace.capability.vision;

import com.sun.net.httpserver.*;
import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.api.VisionController;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import io.github.qianlixunbai.workspace.model.*;
import io.github.qianlixunbai.workspace.policy.*;
import io.github.qianlixunbai.workspace.provider.ollama.OllamaProvider;
import io.github.qianlixunbai.workspace.task.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisionFlowTest {
    private static final String CHILD="96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7";
    private static final String GGML="3c3adf637187c95955232acd3ed05b9ea45aeb2a5f953e631c38174a46b5b56a";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @Test void singleActiveProofBoundAdmissionTaskAndUncertainCancellation() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService threads=Executors.newCachedThreadPool();server.setExecutor(threads);
        AtomicBoolean policyPresent=new AtomicBoolean(true),block=new AtomicBoolean(),wrongProjector=new AtomicBoolean();
        AtomicInteger images=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicBoolean liveDrift=new AtomicBoolean();AtomicInteger liveChecks=new AtomicInteger();
        Map<String,Object> details=Map.of("parent_model","","format","gguf","family","qwen35","families",List.of("qwen35","clip"),"parameter_size","4.7B","quantization_level","Q4_K_M","runner","llamacpp");
        Map<String,Object> tag=Map.of("name","qwen3.5:4b","model","qwen3.5:4b","size",3397830016L,"digest",CHILD,"details",details);
        server.createContext("/api/version",e->respond(e,Map.of("version","0.40.1")));
        server.createContext("/api/tags",e->respond(e,Map.of("models",List.of(tag))));
        server.createContext("/api/show",e->{e.getRequestBody().readAllBytes();respond(e,Map.of(
            "details",details,"requires","0.17.1","capabilities",List.of("completion","vision"),
            "model_info",Map.of("general.architecture","qwen35","qwen35.context_length",262144),
            "projector_info",Map.of("general.architecture",wrongProjector.get()?"unknown":"clip"),
            "manifests",List.of(Map.of("runner","ggml","format","gguf","digest","sha256:"+GGML),Map.of("runner","llamacpp","format","gguf","digest","sha256:"+CHILD,"selected",true)),
            "modelfile","FROM /fixture/blobs/sha256-language\nFROM /fixture/blobs/sha256-projector\n"));});
        server.createContext("/api/chat",e->{
            var request=JSON.readTree(e.getRequestBody().readAllBytes());String answer="OK";
            assertEquals("sha256-"+CHILD+":local",request.path("model").asString());assertEquals("llamacpp",request.path("runner").asString());
            var message=request.path("messages").path(1);
            if(message.has("images")) {
                images.incrementAndGet();assertEquals(8192,request.path("options").path("num_ctx").asInt());assertEquals(1024,request.path("options").path("num_predict").asInt());
                byte[] encoded=Base64.getDecoder().decode(message.path("images").path(0).asString());
                var reader=ImageIO.getImageReadersByFormatName("png").next();
                try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
                    reader.setInput(input);var image=reader.read(0);answer=(image.getRGB(512,512)&0xffffff)==0xff0000?"red":"green";image.flush();
                } finally {reader.dispose();}
                if(block.get()) {entered.countDown();try {release.await();}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}}
            }
            respond(e,Map.of("model",request.path("model").asString(),"created_at","2026-10-12T00:00:00Z","done",true,"done_reason","stop","message",Map.of("role","assistant","content",answer)));
        });server.start();
        var base=TestSettings.settings(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
        var settings=new RuntimeProperties(base.security(),new RuntimeProperties.Ollama(base.ollama().baseUrl(),Duration.ofSeconds(1),Duration.ofSeconds(5),Duration.ofSeconds(2),1024*1024),
            TestSettings.tasks(Duration.ofSeconds(30),Duration.ofSeconds(150),Duration.ofMinutes(2)),base.translate(),base.summarize(),base.ask());
        ModelStateStore store=mock(ModelStateStore.class);when(store.selection()).thenReturn(new ModelStateStore.Selection(1,"ollama","qwen3.5:4b",CHILD));
        String a="a".repeat(64),b="b".repeat(64),c="c".repeat(64);
        OllamaProvider.VisionTrust trust=new OllamaProvider.VisionTrust() {
            public void verifyIdentity(int port,tools.jackson.databind.JsonNode show,Cancellation cancel){assertEquals(server.getAddress().getPort(),port);cancel.check();}
            public OllamaProvider.VisionContext currentContext(String identity){return policyPresent.get()?new OllamaProvider.VisionContext(new VisionTokenBudget.Evidence(identity,a,b,c,VisionTokenBudget.POLICY,4096,256,1024,512),a,b,c):null;}
            public OllamaProvider.VisionContext liveContext(String identity,Cancellation cancel) {
                cancel.check();liveChecks.incrementAndGet();var cached=currentContext(identity);
                return liveDrift.get()?new OllamaProvider.VisionContext(cached.evidence(),a,b,c,"replacement-generation"):cached;
            }
        };
        var tasks=new TaskManager(settings);
        try(var provider=new ProviderHandle(new OllamaProvider(settings,new ProviderPolicy(),trust));var models=new ActiveModelManager(new ProfileResolver(settings),provider.value,settings,store)) {
            try(var text=models.reserve("translate.fast","translate-v1")) {assertEquals("llamacpp",text.model().runner());}
            assertTrue(models.visionReadiness().providerDeclaredVision());assertTrue(models.visionReadiness().runtimeValidatedText());assertFalse(models.visionReadiness().workspaceVisionExecutionValidated());
            assertThrows(WorkspaceException.class,()->models.reserveVision(1));assertEquals(0,images.get());
            var slots=new VisionSlot();var controller=new VisionController(models,provider.value,tasks,slots);
            var intent=models.prepareVisionValidation(UUID.randomUUID());
            var validation=new MockHttpServletRequest("POST","/api/v1/vision/validation");validation.setContentType("application/json");validation.setContent(JSON.writeValueAsBytes(intent));
            assertTrue(controller.validate(validation).available());assertEquals(2,images.get());
            assertThrows(WorkspaceException.class,()->models.validateVision(intent));assertEquals(2,images.get());
            byte[] png=synthetic();UUID id=UUID.randomUUID();TaskView accepted=controller.submit(request(id,png)).getBody();assertNotNull(accepted);
            await(()->tasks.get(id).status()==TaskStatus.SUCCEEDED&&models.status().reserved()+models.status().executing()==0);
            assertEquals("red",tasks.get(id).result());int prior=images.get();
            assertEquals(ErrorCode.DUPLICATE_TASK_ID,assertThrows(WorkspaceException.class,()->controller.submit(request(id,png))).error().code());assertEquals(prior,images.get());
            assertTrue(liveChecks.get()>=3); // Paired proof and legitimate user execution reached independent sampling.
            liveDrift.set(true);UUID drifted=UUID.randomUUID();controller.submit(request(drifted,png));
            await(()->tasks.get(drifted).status()==TaskStatus.FAILED&&models.status().executing()+models.status().draining()==0);
            assertEquals(prior,images.get());assertFalse(models.visionReadiness().available());assertFalse(models.status().uncertain());
            // The rejected pre-send guard was cleaned up, rather than reported as remote uncertainty.
            verify(store,atLeastOnce()).clearGuard();
            liveDrift.set(false);models.validateVision(models.prepareVisionValidation(UUID.randomUUID()));prior=images.get();
            policyPresent.set(false);assertFalse(models.visionReadiness().available());assertTrue(models.visionReadiness().runtimeValidatedText());
            assertThrows(WorkspaceException.class,()->controller.submit(request(UUID.randomUUID(),png)));assertEquals(prior,images.get());
            policyPresent.set(true);assertFalse(models.visionReadiness().available());models.validateVision(models.prepareVisionValidation(UUID.randomUUID()));
            wrongProjector.set(true);try(var lease=models.reserveVision(1)) {assertThrows(WorkspaceException.class,()->provider.value.executeVision(
                new io.github.qianlixunbai.workspace.provider.Provider.ProviderExecution(lease.profile(),PrivacyMode.LOCAL_ONLY,"Describe","Describe",List.of(new io.github.qianlixunbai.workspace.provider.Provider.ChatMessage("user","Describe")),lease),VisionImage.normalize(png,"image/png").png(),new Cancellation()));}
            assertFalse(models.visionReadiness().available());wrongProjector.set(false);
            try(var text=models.reserve("translate.fast","translate-v1")) {} // Independent text revalidation restores text, never Vision.
            models.validateVision(models.prepareVisionValidation(UUID.randomUUID()));
            block.set(true);UUID pending=UUID.randomUUID();controller.submit(request(pending,png));assertTrue(entered.await(5,TimeUnit.SECONDS));tasks.cancel(pending);
            await(()->models.status().uncertain());assertEquals(TaskStatus.CANCELLED,tasks.get(pending).status());
            assertEquals(ErrorCode.MODEL_EXECUTION_UNCERTAIN,assertThrows(WorkspaceException.class,()->controller.submit(request(UUID.randomUUID(),png))).error().code());
            assertThrows(WorkspaceException.class,()->models.reserve("translate.fast","translate-v1"));release.countDown();
        } finally {release.countDown();tasks.close();server.stop(0);threads.shutdownNow();}
    }
    private record ProviderHandle(OllamaProvider value) implements AutoCloseable {public void close(){value.close();}}
    private static MockHttpServletRequest request(UUID id,byte[] image) {
        var r=new MockHttpServletRequest("POST","/api/v1/vision/tasks");r.setContentType("application/json");r.setContent(JSON.writeValueAsBytes(Map.of("taskId",id.toString(),"question","Describe","expectedSelectionRevision",1,"image",Map.of("mediaType","image/png","dataBase64",Base64.getEncoder().encodeToString(image)))));return r;
    }
    private static byte[] synthetic() throws Exception {
        BufferedImage image=new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB);for(int y=0;y<16;y++)for(int x=0;x<16;x++)image.setRGB(x,y,0xff0000);
        var bytes=new ByteArrayOutputStream();var writer=ImageIO.getImageWritersByFormatName("png").next();
        try(var output=new MemoryCacheImageOutputStream(bytes)){writer.setOutput(output);writer.write(null,new IIOImage(image,null,null),writer.getDefaultWriteParam());output.flush();}finally{writer.dispose();image.flush();}return bytes.toByteArray();
    }
    private static void respond(HttpExchange e,Object body) throws IOException {try(e){byte[] bytes=JSON.writeValueAsBytes(body);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}}
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()){if(System.nanoTime()>deadline)fail("Fixture worker did not exit");Thread.sleep(10);}}
}
