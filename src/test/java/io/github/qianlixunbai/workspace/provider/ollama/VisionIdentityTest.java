package io.github.qianlixunbai.workspace.provider.ollama;

import io.github.qianlixunbai.workspace.task.Cancellation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisionIdentityTest {
    @Test void exactProjectorFitRequiresVerifiedCopyAndPinnedSourceBranch() throws Exception {
        Path root=Path.of("D:/fit-fixture"),dist=root.resolve("dist"),models=root.resolve("models"),windows=Path.of("C:/Windows");
        var serverPin=new java.util.concurrent.atomic.AtomicReference<>("74462c0d7caa1aeb547f086ef1662f2b23d1cb573f70d21027e72250679e9112");
        var instance=new OllamaVisionEnvironment.Instance(){
            public String id(){return "fixture";} public Path root(){return root;}
            public Path distribution(){return dist;} public Path models(){return models;}
            public java.net.URI endpoint(){return java.net.URI.create("http://127.0.0.1:11435");}
            public String serverHash(){return "74462c0d7caa1aeb547f086ef1662f2b23d1cb573f70d21027e72250679e9112";}
            public String pinsSha256(){return "b".repeat(64);} public OllamaVisionEnvironment.Pin driver(){return null;}
            public Map<String,OllamaVisionEnvironment.Pin> pins(){return Map.of("ollama.exe",new OllamaVisionEnvironment.Pin(27853192L,serverPin.get()));}
        };
        var parent=Map.of("OLLAMA_HOST","127.0.0.1:11435","OLLAMA_MODELS",models.toString(),"OLLAMA_LLM_LIBRARY","cuda_v13",
            "PATH",dist+";"+windows.resolve("System32")+";"+windows);
        var server=new OllamaVisionEnvironment.ProcessState(dist.resolve("ollama.exe"),List.of("serve"),dist,parent,1);
        Map<String,String> env=new HashMap<>(parent);
        env.put("PATH",dist.resolve("lib/ollama")+";"+dist.resolve("lib/ollama/cuda_v13")+";"+parent.get("PATH"));
        env.put("GGML_BACKEND_PATH",dist.resolve("lib/ollama/cuda_v13/ggml-cuda.dll").toString());
        env.put("CUDA_VISIBLE_DEVICES","0");env.put("LLAMA_MEDIA_MARKER","<__ollama_media_"+"a".repeat(32)+"__>");
        env.put("LLAMA_ARG_FIT_TARGET","1669");
        List<String> args=List.of("--model",models.resolve("blobs/sha256-"+OllamaVisionAdmission.LANGUAGE).toString(),
            "--mmproj",models.resolve("blobs/sha256-"+OllamaVisionAdmission.PROJECTOR).toString(),"--port","12345","--host","127.0.0.1",
            "--offline","--no-webui","-c","8192","-np","1","--log-verbosity","4","--no-log-prefix","--no-log-timestamps");
        Path image=dist.resolve("lib/ollama/llama-server.exe");
        var runner=new OllamaVisionEnvironment.ProcessState(image,args,dist,env,42);
        // Synthetic trusted-side hash result at the pure policy seam; this does not hash a real model or admit NativeGate.
        String verified=OllamaVisionAdmission.PROJECTOR;
        OllamaVisionEnvironment.validateRole(instance,runner,false,server,42,windows,verified);
        for(String missingOrWrong:Arrays.asList(null,"b".repeat(64)))
            assertThrows(IOException.class,()->OllamaVisionEnvironment.validateRole(instance,runner,false,server,42,windows,missingOrWrong));
        for(String wrong:List.of("1668","1670","01669")){
            var bad=new HashMap<>(env);bad.put("LLAMA_ARG_FIT_TARGET",wrong);
            assertThrows(IOException.class,()->OllamaVisionEnvironment.validateRole(instance,
                new OllamaVisionEnvironment.ProcessState(image,args,dist,bad,42),false,server,42,windows,verified));
        }
        var disabled=new ArrayList<>(args);disabled.add("--no-mmproj-offload");
        assertThrows(IOException.class,()->OllamaVisionEnvironment.validateRole(instance,
            new OllamaVisionEnvironment.ProcessState(image,disabled,dist,env,42),false,server,42,windows,verified));
        var inherited=new HashMap<>(parent);inherited.put("LLAMA_ARG_FIT_TARGET","1669");
        assertThrows(IOException.class,()->OllamaVisionEnvironment.validateRole(instance,runner,false,
            new OllamaVisionEnvironment.ProcessState(server.image(),server.arguments(),dist,inherited,1),42,windows,verified));
        var helper=List.of("--port","12345","--host","127.0.0.1","--offline","--no-webui","--verbose");
        assertThrows(IOException.class,()->OllamaVisionEnvironment.validateRole(instance,
            new OllamaVisionEnvironment.ProcessState(image,helper,dist,env,42),false,server,42,windows,verified));
        var wrongProjector=new ArrayList<>(args);wrongProjector.set(3,models.resolve("blobs/sha256-"+"b".repeat(64)).toString());
        assertThrows(IOException.class,()->OllamaVisionEnvironment.validateRole(instance,
            new OllamaVisionEnvironment.ProcessState(image,wrongProjector,dist,env,42),false,server,42,windows,verified));
        serverPin.set("b".repeat(64));
        assertThrows(IOException.class,()->OllamaVisionEnvironment.validateRole(instance,runner,false,server,42,windows,verified));
    }
    @Test void roleEnvironmentAndIdentityRemainBoundToTheAdmittedSource() throws Exception {
        Path root=Path.of("D:/role-fixture"),dist=root.resolve("dist"),models=root.resolve("models"),windows=Path.of("C:/Windows");
        var instance=new OllamaVisionEnvironment.Instance(){
            public String id(){return "fixture";} public Path root(){return root;} public Path distribution(){return dist;}
            public Path models(){return models;} public java.net.URI endpoint(){return java.net.URI.create("http://127.0.0.1:11435");}
            public String serverHash(){return "a".repeat(64);} public String pinsSha256(){return "b".repeat(64);}
            public OllamaVisionEnvironment.Pin driver(){return null;} public Map<String,OllamaVisionEnvironment.Pin> pins(){return Map.of();}
        };
        Map<String,String> parent=new HashMap<>(Map.of("OLLAMA_HOST","127.0.0.1:11435","OLLAMA_MODELS",models.toString(),
            "OLLAMA_LLM_LIBRARY","cuda_v13","PATH",dist+";"+windows.resolve("System32")+";"+windows,"SYSTEMROOT",windows.toString()));
        var server=new OllamaVisionEnvironment.ProcessState(dist.resolve("ollama.exe"),List.of("serve"),dist,parent,1);
        OllamaVisionEnvironment.validateRole(instance,server,true,null,42,windows);
        Map<String,String> child=new HashMap<>(parent);
        child.put("PATH",dist.resolve("lib/ollama")+";"+dist.resolve("lib/ollama/cuda_v13")+";"+parent.get("PATH"));
        child.put("GGML_BACKEND_PATH",dist.resolve("lib/ollama/cuda_v13/ggml-cuda.dll").toString());
        List<String> helper=List.of("--port","12345","--host","127.0.0.1","--offline","--no-webui","--verbose");
        var helperState=new OllamaVisionEnvironment.ProcessState(dist.resolve("lib/ollama/llama-server.exe"),helper,dist,child,42);
        OllamaVisionEnvironment.validateRole(instance,helperState,false,server,42,windows);
        List<String> runner=List.of("--model",models.resolve("blobs/sha256-"+OllamaVisionAdmission.LANGUAGE).toString(),
            "--mmproj",models.resolve("blobs/sha256-"+OllamaVisionAdmission.PROJECTOR).toString(),"--port","12345","--host","127.0.0.1",
            "--offline","--no-webui","-c","8192","-np","1","--log-verbosity","4","--no-log-prefix","--no-log-timestamps");
        child.put("LLAMA_MEDIA_MARKER","<__ollama_media_"+"a".repeat(32)+"__>");
        child.put("CUDA_VISIBLE_DEVICES","GPU-12345678-1234-1234-1234-123456789abc"); // Synthetic ID, never a runtime expectation.
        var runnerState=new OllamaVisionEnvironment.ProcessState(helperState.image(),runner,dist,child,42);
        OllamaVisionEnvironment.validateRole(instance,runnerState,false,server,42,windows);
        for(var invalid:List.of(Map.of("LLAMA_UNKNOWN","1"),Map.of("CUDA_VISIBLE_DEVICES","0,0"),
                Map.of("SYSTEMROOT","D:/override"),Map.of("LLAMA_MEDIA_MARKER","invalid"),Map.of("cuda_visible_devices","0"))){
            var bad=new HashMap<>(child);bad.putAll(invalid);
            assertThrows(IOException.class,()->OllamaVisionEnvironment.validateRole(instance,
                new OllamaVisionEnvironment.ProcessState(runnerState.image(),runner,dist,bad,42),false,server,42,windows));
        }
        var injectedParent=new HashMap<>(parent);injectedParent.put("CUDA_VISIBLE_DEVICES","0");
        assertThrows(IOException.class,()->OllamaVisionEnvironment.validateRole(instance,
            new OllamaVisionEnvironment.ProcessState(server.image(),server.arguments(),dist,injectedParent,1),true,null,42,windows));
        assertThrows(IOException.class,()->OllamaVisionEnvironment.validateRole(instance,
            new OllamaVisionEnvironment.ProcessState(helperState.image(),helper,dist,child,42),false,server,42,windows));
        child.put("LLAMA_ARG_FIT_TARGET","1234"); // No trusted tensor-memory expectation: even well-formed fit stays closed.
        assertEquals("ISOLATED_FIT_EVIDENCE_PENDING",assertThrows(IOException.class,()->OllamaVisionEnvironment.validateRole(instance,
            new OllamaVisionEnvironment.ProcessState(runnerState.image(),runner,dist,child,42),false,server,42,windows)).getMessage());
        var role=new OllamaVisionEnvironment.ProcessState(Path.of("fixture.exe"),List.of("serve"),Path.of("fixture"),Map.of("BACKEND","cuda_v13"),1);
        var original=new OllamaVisionEnvironment.Snapshot("service/1/runner/1",List.of("serve"),List.of("--model","fixture"),Map.of(1L,Path.of("runner.dll")),
            Map.of(1L,role),Map.of(1L,Map.of(1L,Path.of("server.dll"))));
        var changes=List.of(
            new OllamaVisionEnvironment.Snapshot("service/1/runner/2",original.serviceArguments(),original.runnerArguments(),original.modules(),original.roles(),original.roleModules()),
            new OllamaVisionEnvironment.Snapshot(original.generation(),original.serviceArguments(),List.of("--model","changed"),original.modules(),original.roles(),original.roleModules()),
            new OllamaVisionEnvironment.Snapshot(original.generation(),original.serviceArguments(),original.runnerArguments(),Map.of(1L,Path.of("runner.dll"),2L,Path.of("unknown.dll")),original.roles(),original.roleModules()),
            new OllamaVisionEnvironment.Snapshot(original.generation(),original.serviceArguments(),original.runnerArguments(),Map.of(2L,Path.of("runner.dll")),original.roles(),original.roleModules()),
            new OllamaVisionEnvironment.Snapshot(original.generation(),original.serviceArguments(),original.runnerArguments(),original.modules(),
                Map.of(1L,new OllamaVisionEnvironment.ProcessState(role.image(),role.arguments(),role.cwd(),Map.of("BACKEND","cuda_v12"),1)),original.roleModules()),
            new OllamaVisionEnvironment.Snapshot(original.generation(),original.serviceArguments(),original.runnerArguments(),original.modules(),original.roles(),
                Map.of(1L,Map.of(1L,Path.of("changed-server.dll")))));
        for(var after:changes) {
            var reader=new OllamaVisionEnvironment.IdentityReader() {
                boolean hashed;
                public OllamaVisionEnvironment.Snapshot snapshot(){return hashed?after:original;}
                public String hashModules(OllamaVisionEnvironment.Snapshot snapshot,Cancellation cancellation,long deadline){hashed=true;return "a".repeat(64);}
            };
            assertThrows(IOException.class,()->OllamaVisionEnvironment.sample(reader,new Cancellation(),System.nanoTime()+1_000_000_000L));
        }
        var stable=new OllamaVisionEnvironment.IdentityReader() {
            public OllamaVisionEnvironment.Snapshot snapshot(){return original;}
            public String hashModules(OllamaVisionEnvironment.Snapshot snapshot,Cancellation cancellation,long deadline){return "a".repeat(64);}
        };
        assertEquals(original,OllamaVisionEnvironment.sample(stable,new Cancellation(),System.nanoTime()+1_000_000_000L).snapshot());
        assertThrows(IOException.class,()->OllamaVisionEnvironment.sample(stable,new Cancellation(),System.nanoTime()-1));
    }
}
