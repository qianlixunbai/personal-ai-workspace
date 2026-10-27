package io.github.qianlixunbai.workspace.provider.ollama;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.capability.vision.VisionTokenBudget;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.task.Cancellation;
import tools.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Development-only trusted service/storage check; never opens a provider-supplied path. */
final class OllamaVisionEnvironment {
    private static final Path ROOT=Path.of("D:\\Ollama\\moxing");
    private static final String SERVER_HASH="74462c0d7caa1aeb547f086ef1662f2b23d1cb573f70d21027e72250679e9112";
    // Reviewed shared 0.40.6, valid Ollama Inc. Authenticode signature. This does not
    // repin the isolated 0.40.1 distribution or add any image token/payload policy.
    private static final Set<String> SHARED_SERVER_HASHES=Set.of(SERVER_HASH,
            "71d2002d3ae572d5fa8c1d368ce9d1b9a39c7187cf169e8b330c55410bf6c58e");
    // cf2a313a298066d572c36812e5ad30a21c0db13b, external PROJECTOR: all 298 tensors, not file size.
    private static final long PROJECTOR_TENSOR_BYTES=675551232L;
    private static final Map<String,Long> BLOBS=Map.of(
            OllamaVisionAdmission.CHILD,940L,OllamaVisionAdmission.CONFIG,184L,
            OllamaVisionAdmission.LANGUAGE,2722261504L,OllamaVisionAdmission.PROJECTOR,675568512L,
            "7339fa418c9ad3e8e12e74ad0fd26a9cc4be8703f9c110728a992b193be85cb2",11355L,
            "9371364b27a52acac9d87f88bd93c9db1174d8d6ec57f6888925cdc1788871ff",65L);
    // Source-reviewed policy data only. No current native payload/preprocessing/template proof was
    // supplied by the approval pack, so production has no positive entry. Never infer one from show.
    private static final Map<String,VisionTokenBudget.Evidence> REVIEWED_TOKEN_POLICIES=Map.of();
    private OllamaVisionEnvironment() {}
    record Pin(long bytes, String sha256) {}
    interface Instance {
        String id(); Path root(); Path distribution(); Path models(); java.net.URI endpoint();
        String serverHash(); String pinsSha256(); Pin driver(); Map<String,Pin> pins();
    }
    private static Instance boundInstance;
    private static Path boundProfile;
    private static final List<HeldFile> boundDirectories=new ArrayList<>();
    static synchronized Instance developmentInstance() {
        try {
            Class<?> source = WindowsNativeGate.isolatedDevelopmentClass();
            if(source==null)return null;
            Instance instance=(Instance)source.getMethod("contract").invoke(null);
            if(instance.getClass()!=source)throw new IOException();
            if(boundInstance==null){
                Path profile=WindowsNativeGate.profileRoot();List<HeldFile> opened=new ArrayList<>();
                try {
                    for(Path dir:List.of(profile,instance.root(),instance.distribution(),instance.models()))opened.add(new HeldFile(dir,true));
                    boundDirectories.addAll(opened);boundProfile=profile;boundInstance=instance;opened.clear();
                }finally{for(var dir:opened)dir.close();}
            }
            if(instance!=boundInstance || !boundProfile.equals(WindowsNativeGate.profileRoot()))throw new IOException();
            for(var dir:boundDirectories)dir.check();
            return boundInstance;
        } catch (Exception | LinkageError denied) {
            throw new WorkspaceException(ErrorCode.MODEL_UNAVAILABLE,"ISOLATED_BUILD_IDENTITY");
        }
    }
    abstract static class ServiceSession implements AutoCloseable {
        abstract java.net.URI endpoint();
        abstract String generation();
        abstract void verifyConnected(java.net.Socket socket, Cancellation cancellation, long deadline) throws IOException;
        abstract void verifyMetadata(JsonNode show, Cancellation cancellation);
        abstract OllamaProvider.VisionContext liveContext(String identity, Cancellation cancellation);
        public abstract void close();
    }
    static ServiceSession openSession(Instance instance, Cancellation cancellation, long deadline) throws Exception {
        WindowsNativeGate.admit();
        return new NativeSession(instance,cancellation,deadline);
    }
    record ProcessState(Path image, List<String> arguments, Path cwd, Map<String,String> environment, long parent) {
        ProcessState {arguments=List.copyOf(arguments);environment=Map.copyOf(environment);}
    }
    private static final class NativeSession extends ServiceSession {
        final Instance instance;
        final Path profile;
        final HeldProcess server;
        final ProcessState state;
        final List<HeldFile> files=new ArrayList<>();
        final List<HeldFile> directories=new ArrayList<>();
        final long deadline;
        boolean closed;
        private String verifiedProjectorSha;
        NativeSession(Instance instance,Cancellation cancellation,long deadline) throws Exception {
            this.instance=instance;this.deadline=deadline;profile=WindowsNativeGate.profileRoot();
            server=new HeldProcess(isolatedListener(instance.endpoint().getPort()));
            try {
                long until=Math.min(deadline,System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos());
                for(Path dir:List.of(instance.root(),instance.distribution(),instance.models())) directories.add(new HeldFile(dir,true));
                state=state(server.handle);
                validateRole(instance,state,true);
                HeldFile image=new HeldFile(state.image(),false);files.add(image);
                Pin pin=instance.pins().get("ollama.exe");image.hash(pin.bytes(),pin.sha256(),cancellation,until);
                checkGeneration(cancellation,until);
            } catch(Exception | LinkageError rejected) {close();throw rejected;}
        }
        java.net.URI endpoint(){return instance.endpoint();}
        String generation(){return server.generation();}
        void checkGeneration(Cancellation cancellation,long until) throws Exception {
            check(cancellation,Math.min(until,deadline));
            if(closed || !profile.equals(WindowsNativeGate.profileRoot()))throw new IOException();
            if(instance!=developmentInstance())throw new IOException();
            server.check();
            if(isolatedListener(endpoint().getPort()).pid()!=server.process.pid() || !state.equals(state(server.handle)))throw new IOException();
            for(var dir:directories)dir.check();
            for(var file:files)file.check();
            check(cancellation,Math.min(until,deadline));
        }
        void verifyConnected(java.net.Socket socket,Cancellation cancellation,long until) throws IOException {
            try {
                checkGeneration(cancellation,until);
                if(!socket.isConnected() || socket.isClosed() || !socket.getInetAddress().getHostAddress().equals("127.0.0.1")
                        || !socket.getLocalAddress().getHostAddress().equals("127.0.0.1") || socket.getPort()!=endpoint().getPort())throw new IOException();
                List<TcpRow> rows=tcpRows(2);
                long client=ProcessHandle.current().pid(),service=server.process.pid();
                List<TcpRow> outbound=rows.stream().filter(r->r.state()==5 && r.localAddress()==0x0100007f && r.remoteAddress()==0x0100007f
                    && r.localPort()==socket.getLocalPort() && r.remotePort()==socket.getPort()).toList();
                List<TcpRow> inbound=rows.stream().filter(r->r.state()==5 && r.localAddress()==0x0100007f && r.remoteAddress()==0x0100007f
                    && r.localPort()==socket.getPort() && r.remotePort()==socket.getLocalPort()).toList();
                if(outbound.size()!=1 || inbound.size()!=1 || outbound.getFirst().pid()!=client || inbound.getFirst().pid()!=service)throw new IOException();
                checkGeneration(cancellation,until);
            } catch(WorkspaceException cancelled){throw cancelled;}
            catch(Exception | LinkageError denied){throw new IOException("Isolated socket identity STOP");}
        }
        void verifyMetadata(JsonNode show,Cancellation cancellation) {
            try {
                checkGeneration(cancellation,deadline);
                // Storage admission has its own cancellation/stability checks; module budgets do not apply to weights.
                for(Path dir:List.of(instance.models(),instance.models().resolve("blobs"),instance.models().resolve("manifests-v2/ollama.com/library/qwen3.5")))
                    io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.verify(dir,true);
                storage(instance.models().resolve("manifests-v2/ollama.com/library/qwen3.5/4b"),464,OllamaVisionAdmission.LIST,cancellation);
                String projectorSha=null;
                for(var blob:BLOBS.entrySet()){
                    String observed=storage(instance.models().resolve("blobs/sha256-"+blob.getKey()),blob.getValue(),blob.getKey(),cancellation);
                    if(blob.getKey().equals(OllamaVisionAdmission.PROJECTOR))projectorSha=observed;
                }
                List<String> commands=show.path("modelfile").asString("").lines().filter(s->!s.isBlank()&&!s.startsWith("#")).toList();
                List<String> from=commands.stream().filter(s->s.startsWith("FROM ")).map(s->{String v=s.substring(5);return v.startsWith("\"")&&v.endsWith("\"")?v.substring(1,v.length()-1):v;}).toList();
                if(!from.equals(List.of(modelBlob(instance,OllamaVisionAdmission.LANGUAGE).toString(),modelBlob(instance,OllamaVisionAdmission.PROJECTOR).toString()))
                        || commands.stream().anyMatch(s->s.matches("(?i)^(ADAPTER|DRAFT|REQUIRES|RUNNER|PROJECTOR)\\b.*")))throw new IOException();
                checkGeneration(cancellation,deadline);
                verifiedProjectorSha=projectorSha; // Successful full hash; files retain the no-write/delete handle for this session.
                sampleIsolated(this,cancellation); // Also audits server/helpers when no runner is loaded.
            } catch(WorkspaceException cancelled){throw cancelled;}
            catch(Exception | LinkageError denied){throw new WorkspaceException(ErrorCode.MODEL_UNAVAILABLE,"ISOLATED_STORAGE");}
        }
        private String storage(Path path,long size,String sha,Cancellation cancellation)throws Exception {
            io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.verify(path,false);
            var held=new HeldFile(path,false);files.add(held);return held.hash(size,sha,cancellation,deadline);
        }
        OllamaProvider.VisionContext liveContext(String identity,Cancellation cancellation) {
            try {
                Sample sample=sampleIsolated(this,cancellation);
                if(sample==null)return null;
                List<String> args=new ArrayList<>();List<String> values=sample.snapshot().runnerArguments();
                for(int i=0;i<values.size();i++) {
                    if(values.get(i).equals("--port")){i++;continue;}
                    args.add(values.get(i));
                }
                String payload=sample.payload();
                String preprocessing=hash(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(List.of(VisionTokenBudget.POLICY,payload,args)));
                String template=hash(("renderer=qwen3.5|"+instance.serverHash()+"|"+OllamaVisionAdmission.CONFIG).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var evidence=REVIEWED_TOKEN_POLICIES.get(payload+"/"+preprocessing+"/"+template);
                if(evidence==null || !identity.equals(evidence.identity()))return null;
                return new OllamaProvider.VisionContext(evidence,payload,preprocessing,template,sample.snapshot().generation());
            } catch(Exception | LinkageError missing){return null;}
        }
        public void close(){closed=true;for(var file:files)file.close();for(var dir:directories)dir.close();server.close();}
    }
    private static Path modelBlob(Instance instance,String sha){return instance.models().resolve("blobs/sha256-"+sha);}
    private record TcpRow(int state,int localAddress,int localPort,int remoteAddress,int remotePort,long pid){}
    private static List<TcpRow> tcpRows(int family)throws IOException {
        Tcp api=com.sun.jna.Native.load("iphlpapi",Tcp.class);var size=new com.sun.jna.ptr.IntByReference();
        if(api.GetExtendedTcpTable(null,size,false,family,5,0)!=122 || size.getValue()<4 || size.getValue()>1024*1024)throw new IOException();
        try(var buffer=new com.sun.jna.Memory(size.getValue())){
            if(api.GetExtendedTcpTable(buffer,size,false,family,5,0)!=0)throw new IOException();
            int stride=family==2?24:56,count=buffer.getInt(0);
            if(count<0 || (long)count*stride+4>size.getValue())throw new IOException();
            List<TcpRow> result=new ArrayList<>();
            for(int i=0;i<count;i++){
                long at=4L+(long)stride*i;
                if(family==2)result.add(new TcpRow(buffer.getInt(at),buffer.getInt(at+4),port(buffer,at+8),buffer.getInt(at+12),port(buffer,at+16),Integer.toUnsignedLong(buffer.getInt(at+20))));
                else result.add(new TcpRow(buffer.getInt(at+48),0,port(buffer,at+20),0,port(buffer,at+44),Integer.toUnsignedLong(buffer.getInt(at+52))));
            }
            return List.copyOf(result);
        }
    }
    private static int port(com.sun.jna.Pointer p,long at){return Short.toUnsignedInt(Short.reverseBytes(p.getShort(at)));}
    private static ProcessHandle isolatedListener(int port)throws IOException {
        for(var row:tcpRows(23))if(row.state()==2 && row.localPort()==port)throw new IOException();
        List<TcpRow> rows=tcpRows(2).stream().filter(r->r.state()==2 && r.localPort()==port).toList();
        if(rows.size()!=1 || rows.getFirst().localAddress()!=0x0100007f)throw new IOException();
        return ProcessHandle.of(rows.getFirst().pid()).orElseThrow(IOException::new);
    }
    private static ProcessState state(com.sun.jna.platform.win32.WinNT.HANDLE handle)throws Exception {
        var kernel=com.sun.jna.platform.win32.Kernel32.INSTANCE;
        var process=com.sun.jna.Native.load("kernel32",NativeProcess.class);
        char[] image=new char[32768];var imageLength=new com.sun.jna.ptr.IntByReference(image.length);
        if(!process.QueryFullProcessImageNameW(handle,0,image,imageLength) || imageLength.getValue()<1 || imageLength.getValue()>=image.length)throw new IOException();
        try(var basic=new com.sun.jna.Memory(48);var pointer=new com.sun.jna.Memory(8);var header=new com.sun.jna.Memory(1024)) {
            var returned=new com.sun.jna.ptr.IntByReference();var query=com.sun.jna.Native.load("ntdll",NativeCommand.class);
            if(query.NtQueryInformationProcess(handle,0,basic,48,returned)!=0 || returned.getValue()!=48 || basic.getPointer(8)==null)throw new IOException();
            readRemote(kernel,handle,basic.getPointer(8).share(32),pointer,8);
            var parameters=pointer.getPointer(0);if(parameters==null)throw new IOException();
            // Win64 RTL_USER_PROCESS_PARAMETERS: CurrentDirectory 0x38, Environment 0x80,
            // EnvironmentSize 0x3f0. Reject unknown/truncated layouts rather than guessing.
            readRemote(kernel,handle,parameters,header,1024);
            long declared=Integer.toUnsignedLong(header.getInt(4)),maximum=Integer.toUnsignedLong(header.getInt(0));
            if(declared<1024 || maximum<declared || maximum>65536)throw new IOException();
            Path cwd=Path.of(remoteUnicode(handle,header.share(56)));
            long bytes=header.getLong(1008);var environment=header.getPointer(128);
            if(bytes<4 || bytes>65536 || bytes%2!=0 || environment==null)throw new IOException();
            Map<String,String> env=new TreeMap<>();
            try(var buffer=new com.sun.jna.Memory(bytes)) {
                readRemote(kernel,handle,environment,buffer,Math.toIntExact(bytes));
                String text=utf16(buffer.getByteArray(0,Math.toIntExact(bytes)));
                if(!text.endsWith("\0\0"))throw new IOException();
                String[] values=text.substring(0,text.length()-2).split("\0",-1);
                for(String value:values){
                    int split=value.indexOf('=',value.startsWith("=")?1:0);
                    if(split<1 || value.indexOf('\0')>=0 || env.put(value.substring(0,split).toUpperCase(Locale.ROOT),value.substring(split+1))!=null)throw new IOException();
                }
            }
            return new ProcessState(Path.of(new String(image,0,imageLength.getValue())),List.of(arguments(handle)),cwd,env,basic.getLong(40));
        }
    }
    private static String remoteUnicode(com.sun.jna.platform.win32.WinNT.HANDLE handle,com.sun.jna.Pointer descriptor)throws Exception {
        int length=Short.toUnsignedInt(descriptor.getShort(0)),maximum=Short.toUnsignedInt(descriptor.getShort(2));
        var pointer=descriptor.getPointer(8);
        if(length<2 || length>65534 || length%2!=0 || maximum<length || pointer==null)throw new IOException();
        try(var buffer=new com.sun.jna.Memory(length)) {
            readRemote(com.sun.jna.platform.win32.Kernel32.INSTANCE,handle,pointer,buffer,length);
            String value=utf16(buffer.getByteArray(0,length));if(value.indexOf(0)>=0)throw new IOException();return value;
        }
    }
    private static String utf16(byte[] bytes)throws Exception {
        return java.nio.charset.StandardCharsets.UTF_16LE.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    private static Path windowsRoot()throws IOException {
        NativeModules api=com.sun.jna.Native.load("kernel32",NativeModules.class);char[] name=new char[32768];
        int length=api.GetSystemWindowsDirectoryW(name,name.length);if(length<1 || length>=name.length)throw new IOException();
        return Path.of(new String(name,0,length));
    }
    private static void validateRole(Instance instance,ProcessState state,boolean server)throws Exception {
        validateRole(instance,state,server,null,0,windowsRoot());
    }
    // Same validator used by the held-process sampler and model-free role fixtures.
    static void validateRole(Instance instance,ProcessState state,boolean server,ProcessState source,long serverPid,Path windows)throws Exception {
        validateRole(instance,state,server,source,serverPid,windows,null);
    }
    static void validateRole(Instance instance,ProcessState state,boolean server,ProcessState source,long serverPid,Path windows,
                             String verifiedProjectorSha)throws Exception {
        Path expected=instance.distribution().resolve(server?"ollama.exe":"lib/ollama/llama-server.exe");
        if(!expected.equals(state.image()) || !instance.distribution().equals(state.cwd()))throw new IOException();
        var env=state.environment();
        boolean model=!server && state.arguments().contains("--model");
        Set<String> generated=server?Set.of():Set.of("GGML_BACKEND_PATH","CUDA_VISIBLE_DEVICES",
            "LLAMA_MEDIA_MARKER","LLAMA_ARG_FIT_TARGET");
        if(!server){
            if(source==null || state.parent()!=serverPid)throw new IOException();
            validateRole(instance,source,true,null,serverPid,windows);
            // Fixed upstream exec inherits the admitted server environment, changing only these keys/PATH.
            for(var entry:source.environment().entrySet())
                if(!entry.getKey().equals("PATH") && !Objects.equals(entry.getValue(),env.get(entry.getKey())))throw new IOException();
            for(String key:env.keySet())
                if(!source.environment().containsKey(key) && !generated.contains(key))throw new IOException();
        }
        if(!"127.0.0.1:11435".equals(env.get("OLLAMA_HOST")) || !instance.models().toString().equals(env.get("OLLAMA_MODELS"))
                || !"cuda_v13".equals(env.get("OLLAMA_LLM_LIBRARY")))throw new IOException();
        for(String key:env.keySet()){
            if(!key.equals(key.toUpperCase(Locale.ROOT)))throw new IOException();
            if(generated.contains(key))continue;
            if(key.startsWith("OLLAMA_") && !Set.of("OLLAMA_HOST","OLLAMA_MODELS","OLLAMA_LLM_LIBRARY").contains(key)
                    || key.startsWith("GGML_") && !(key.equals("GGML_BACKEND_PATH")&&!server)
                    || key.startsWith("LLAMA_") || key.startsWith("LD_") || key.startsWith("DYLD_")
                    || key.startsWith("CUDA_") || key.startsWith("HIP_") || key.startsWith("ROCR_")
                    || key.startsWith("NVIDIA_") || key.startsWith("VK_"))throw new IOException();
        }
        List<String> paths=new ArrayList<>();
        if(!server){paths.add(instance.distribution().resolve("lib/ollama").toString());paths.add(instance.distribution().resolve("lib/ollama/cuda_v13").toString());}
        paths.add(instance.distribution().toString());paths.add(windows.resolve("System32").toString());paths.add(windows.toString());
        if(!String.join(";",paths).equals(env.get("PATH")))throw new IOException();
        if(server){if(!state.arguments().equals(List.of("serve")) || env.containsKey("GGML_BACKEND_PATH"))throw new IOException();return;}
        if(!instance.distribution().resolve("lib/ollama/cuda_v13/ggml-cuda.dll").toString().equals(env.get("GGML_BACKEND_PATH")))throw new IOException();
        if(env.containsKey("CUDA_VISIBLE_DEVICES")){
            String value=env.get("CUDA_VISIBLE_DEVICES");Set<String> ids=new HashSet<>();
            String[] devices=value.split(",",-1);if(devices.length>64)throw new IOException();
            for(String id:devices){
                if(!(id.matches("0|[1-9][0-9]{0,9}") && Long.parseLong(id)<=Integer.MAX_VALUE
                        || id.matches("GPU-[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))
                        || !ids.add(id.toLowerCase(Locale.ROOT)))throw new IOException();
            }
            // IDs are generated by GetDevicesEnv of the pinned, override-free held parent.
            // This proves source association, not independent GPU enumeration or execution success.
        }
        String marker=env.get("LLAMA_MEDIA_MARKER");
        if(model){
            if(marker==null || !marker.matches("<__ollama_media_(?:[a-f0-9]{32}|[1-9][0-9]{0,18}_[0-9]{1,19})__>"))throw new IOException();
            if(!marker.matches("<__ollama_media_[a-f0-9]{32}__>")){
                String[] parts=marker.substring(16,marker.length()-3).split("_");
                if(Long.parseLong(parts[0])<=0 || Long.parseLong(parts[1])<0)throw new IOException();
            }
        }else if(marker!=null || env.containsKey("LLAMA_ARG_FIT_TARGET"))throw new IOException();
        List<String> args=state.arguments();Map<String,String> values=new HashMap<>();Set<String> flags=new HashSet<>();
        Set<String> allowedValues=Set.of("--model","--mmproj","--port","--host","-c","-np","--log-verbosity","--flash-attn","-b","-ub","-ngl","-t","--cache-type-k","--cache-type-v","--chat-template");
        Set<String> allowedFlags=Set.of("--no-webui","--offline","--verbose","--no-log-prefix","--no-log-timestamps","--no-jinja","--no-mmproj-offload");
        for(int i=0;i<args.size();i++){
            String name=args.get(i);if(name.length()>16384)throw new IOException();
            if(allowedFlags.contains(name)){if(!flags.add(name))throw new IOException();}
            else if(allowedValues.contains(name)){if(++i>=args.size() || values.put(name,args.get(i))!=null)throw new IOException();}
            else throw new IOException();
        }
        if(!flags.containsAll(Set.of("--offline","--no-webui")) || !"127.0.0.1".equals(values.get("--host"))
                || !values.getOrDefault("--port","").matches("[1-9][0-9]{0,4}") || Integer.parseInt(values.get("--port"))>65535)throw new IOException();
        if(!values.containsKey("--model")) {
            if(!values.keySet().equals(Set.of("--port","--host")) || !flags.equals(Set.of("--offline","--no-webui","--verbose")))throw new IOException();
            return;
        }
        if(!modelBlob(instance,OllamaVisionAdmission.LANGUAGE).toString().equals(values.get("--model"))
                || !modelBlob(instance,OllamaVisionAdmission.PROJECTOR).toString().equals(values.get("--mmproj")))throw new IOException();
        for(String key:List.of("-c","-np","-b","-ub","-ngl","-t"))if(values.containsKey(key)
                && (!values.get(key).matches("[1-9][0-9]{0,5}") || Integer.parseInt(values.get(key))>262144))throw new IOException();
        if(!values.containsKey("-c") || !values.containsKey("-np") || !"4".equals(values.get("--log-verbosity"))
                || !flags.containsAll(Set.of("--no-log-prefix","--no-log-timestamps")))throw new IOException();
        if(values.containsKey("--flash-attn") && !Set.of("on","off","auto").contains(values.get("--flash-attn")))throw new IOException();
        if(values.containsKey("--chat-template") && (!"chatml".equals(values.get("--chat-template")) || !flags.contains("--no-jinja")))throw new IOException();
        for(String key:List.of("--cache-type-k","--cache-type-v"))if(values.containsKey(key) && !Set.of("f16","q8_0","q4_0").contains(values.get(key)))throw new IOException();
        if(env.containsKey("LLAMA_ARG_FIT_TARGET")){
            long fit=(PROJECTOR_TENSOR_BYTES+(1L<<30)+(1L<<20)-1)/(1L<<20);
            Pin serverPin=instance.pins().get("ollama.exe");
            // Both argv and fit use mmprojOffloadDisabled in the pinned source. CUDA discovery creates
            // no fit base override; its persistent override producer is Metal-only. Parent overrides were rejected above.
            if(flags.contains("--no-mmproj-offload") || !env.containsKey("CUDA_VISIBLE_DEVICES")
                    || !OllamaVisionAdmission.PROJECTOR.equals(verifiedProjectorSha)
                    || !SERVER_HASH.equals(instance.serverHash()) || serverPin==null || !SERVER_HASH.equals(serverPin.sha256())
                    || !Long.toString(fit).equals(env.get("LLAMA_ARG_FIT_TARGET")))
                throw new IOException("ISOLATED_FIT_EVIDENCE_PENDING");
        }
    }
    private static final class HeldFile implements AutoCloseable {
        final Path path;final boolean directory;final com.sun.jna.platform.win32.WinNT.HANDLE handle;final String identity;
        HeldFile(Path path,boolean directory)throws Exception {
            this(path,directory,true);
        }
        final boolean singleLink;
        HeldFile(Path path,boolean directory,boolean singleLink)throws Exception {
            this.singleLink=singleLink;
            this.path=path;this.directory=directory;
            for(Path part=path;part!=null;part=part.getParent()){
                int attributes=com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(part.toString());
                if(attributes==-1 || (attributes&0x400)!=0 || !part.equals(part.toRealPath()))throw new IOException();
            }
            handle=com.sun.jna.platform.win32.Kernel32.INSTANCE.CreateFile(path.toString(),0x20081,1,null,3,0x02200000,null);
            if(com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE.equals(handle))throw new IOException();
            try{identity=fileIdentity();check();}catch(Exception | LinkageError failure){close();throw failure;}
        }
        String fileIdentity()throws IOException {
            var kernel=com.sun.jna.platform.win32.Kernel32.INSTANCE;
            try(var info=new com.sun.jna.Memory(24);var id=new com.sun.jna.Memory(24)){
                if(!kernel.GetFileInformationByHandleEx(handle,1,info,new com.sun.jna.platform.win32.WinDef.DWORD(24))
                        || !kernel.GetFileInformationByHandleEx(handle,18,id,new com.sun.jna.platform.win32.WinDef.DWORD(24))
                        || (info.getByte(21)!=0)!=directory || info.getByte(20)!=0 || !directory&&singleLink&&info.getInt(16)!=1)throw new IOException();
                return HexFormat.of().formatHex(id.getByteArray(0,24));
            }
        }
        void check()throws Exception {
            if(!identity.equals(fileIdentity()) || !path.equals(path.toRealPath()))throw new IOException();
            // A second no-write/delete handle binds the current name to the pinned object.
            var kernel=com.sun.jna.platform.win32.Kernel32.INSTANCE;
            var current=kernel.CreateFile(path.toString(),0x80,1,null,3,0x02200000,null);
            if(com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE.equals(current))throw new IOException();
            try(var id=new com.sun.jna.Memory(24)){
                if(!kernel.GetFileInformationByHandleEx(current,18,id,new com.sun.jna.platform.win32.WinDef.DWORD(24))
                        || !identity.equals(HexFormat.of().formatHex(id.getByteArray(0,24))))throw new IOException();
            }finally{kernel.CloseHandle(current);}
        }
        String hash(long size,String expected,Cancellation cancellation,long deadline)throws Exception {
            check();if(directory || Files.size(path)!=size)throw new IOException();
            var sha=MessageDigest.getInstance("SHA-256");long length=0;
            try(var file=java.nio.channels.FileChannel.open(path,StandardOpenOption.READ,com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)){
                var bytes=java.nio.ByteBuffer.allocate(65536);int n;
                while((n=file.read(bytes))!=-1){OllamaVisionEnvironment.check(cancellation,deadline);length+=n;if(length>size)throw new IOException();bytes.flip();sha.update(bytes);bytes.clear();}
            }
            String result=HexFormat.of().formatHex(sha.digest());check();OllamaVisionEnvironment.check(cancellation,deadline);
            if(length!=size || expected!=null&&!result.equals(expected))throw new IOException();return result;
        }
        public void close(){com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(handle);}
    }
    private static Sample sampleIsolated(NativeSession session,Cancellation cancellation)throws Exception {
        long deadline=Math.min(session.deadline,System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos());
        session.checkGeneration(cancellation,deadline);
        Map<Long,HeldProcess> held=new TreeMap<>();held.put(session.server.process.pid(),session.server);
        List<HeldFile> pinned=new ArrayList<>();
        try {
            var candidates=session.server.process.descendants().limit(65).toList();if(candidates.size()>64)throw new IOException();
            for(var process:candidates){check(cancellation,deadline);held.put(process.pid(),new HeldProcess(process));}
            var api=com.sun.jna.Native.load("kernel32",NativeModules.class);
            IdentityReader reader=new IdentityReader(){
                public Snapshot snapshot()throws Exception {
                    auditLayout(session.instance,cancellation,deadline);
                    Map<Long,ProcessState> states=new TreeMap<>();Map<Long,Map<Long,Path>> maps=new TreeMap<>();HeldProcess runner=null;
                    for(var entry:held.entrySet()){
                        check(cancellation,deadline);var process=entry.getValue();process.check();var state=state(process.handle);
                        states.put(entry.getKey(),state);
                        if(state.arguments().contains("--model")){if(runner!=null)throw new IOException();runner=process;}
                        maps.put(entry.getKey(),modules(api,process.handle));
                    }
                    verifyTree(session,held,states,cancellation,deadline);
                    for(var entry:states.entrySet()){
                        check(cancellation,deadline);
                        validateRole(session.instance,entry.getValue(),entry.getKey()==session.server.process.pid(),
                            session.state,session.server.process.pid(),windowsRoot(),session.verifiedProjectorSha);
                    }
                    return new Snapshot(session.generation()+(runner==null?"/unloaded":"/"+runner.generation()),session.state.arguments(),
                        runner==null?List.of():states.get(runner.process.pid()).arguments(),runner==null?Map.of():maps.get(runner.process.pid()),states,maps);
                }
                public String hashModules(Snapshot snapshot,Cancellation cancel,long until)throws Exception {
                    String payload=null;
                    for(var entry:snapshot.roles().entrySet()){
                        boolean runner=entry.getValue().arguments().contains("--model");
                        String digest=hashPinnedModules(session.instance,snapshot.roleModules().get(entry.getKey()),runner,cancel,until,pinned);
                        if(runner)payload=digest;
                    }
                    for(var file:pinned){check(cancel,until);file.check();}
                    return payload; // An unloaded service never obtains a runner payload or positive VisionContext.
                }
            };
            Sample sample=sample(reader,cancellation,deadline);
            for(var file:pinned){check(cancellation,deadline);file.check();}
            return sample.payload()==null?null:sample;
        } finally {for(var file:pinned)file.close();for(var process:held.values())if(process!=session.server)process.close();}
    }
    private static void verifyTree(NativeSession session,Map<Long,HeldProcess> held,Map<Long,ProcessState> states,
                                   Cancellation cancellation,long deadline)throws Exception {
        session.checkGeneration(cancellation,deadline);
        Set<Long> current=new TreeSet<>();current.add(session.server.process.pid());
        session.server.process.descendants().limit(65).forEach(p->current.add(p.pid()));
        if(!current.equals(held.keySet()))throw new IOException();
        for(var process:held.values()){
            check(cancellation,deadline);process.check();Set<Long> chain=new HashSet<>();HeldProcess child=process;
            while(child!=session.server){
                if(!chain.add(child.process.pid()))throw new IOException();
                ProcessState state=states.get(child.process.pid());HeldProcess parent=held.get(state.parent());
                if(parent==null || parent.started>child.started)throw new IOException();parent.check();child=parent;
            }
        }
    }
    private static String hashPinnedModules(Instance instance,Map<Long,Path> modules,boolean runner,
                                           Cancellation cancellation,long deadline,List<HeldFile> pinned)throws Exception {
        if(modules.isEmpty())throw new IOException();
        SortedMap<String,String> hashes=new TreeMap<>();boolean cuda=false,driver=false;
        for(Path path:modules.values()){
            check(cancellation,deadline);
            if(!path.startsWith(instance.distribution())){
                verifySystemModule(instance,path,cancellation,deadline,pinned);
                if(path.equals(windowsRoot().resolve("System32/nvcuda.dll")))driver=true;
                continue;
            }
            String relative=instance.distribution().relativize(path).toString().replace('\\','/');Pin pin=instance.pins().get(relative);
            if(pin==null || pin.bytes()>512L*1024*1024 || hashes.size()>=64 || relative.contains("/cuda_v12/") || relative.contains("/vulkan/"))throw new IOException();
            {
                var file=new HeldFile(path,false);pinned.add(file);
                String sha=file.hash(pin.bytes(),pin.sha256(),cancellation,deadline);
                // Keep the existing runner payload definition: Windows relative-module-path -> full SHA JSON.
                if(hashes.put(instance.distribution().relativize(path).toString(),sha)!=null)throw new IOException();
            }
            if(relative.equals("lib/ollama/cuda_v13/ggml-cuda.dll"))cuda=true;
        }
        if(hashes.isEmpty() || runner&&(!cuda || !driver))throw new IOException();
        if(runner)for(String required:List.of("lib/ollama/llama-server.exe","lib/ollama/libllama-server-impl.dll","lib/ollama/libllama.dll",
                "lib/ollama/libmtmd.dll","lib/ollama/ggml-base.dll","lib/ollama/ggml.dll","lib/ollama/libllama-common.dll"))
            if(!hashes.containsKey(required.replace('/',java.io.File.separatorChar)))throw new IOException();
        check(cancellation,deadline);return hash(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(hashes));
    }
    private static void auditLayout(Instance instance,Cancellation cancellation,long deadline)throws Exception {
        Set<String> actual=new TreeSet<>();
        try(var walk=Files.walk(instance.distribution())){
            List<Path> paths=walk.limit(257).toList();if(paths.size()>256)throw new IOException();
            for(Path path:paths){
                check(cancellation,deadline);
                var attr=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(attr.isSymbolicLink() || !path.equals(path.toRealPath()))throw new IOException();
                int flags=com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(path.toString());
                if(flags==-1 || (flags&0x400)!=0)throw new IOException();
                if(attr.isRegularFile()){
                    String relative=instance.distribution().relativize(path).toString().replace('\\','/');Pin pin=instance.pins().get(relative);
                    if(pin==null || pin.bytes()!=attr.size() || !actual.add(relative))throw new IOException();
                }else if(!attr.isDirectory())throw new IOException();
            }
        }
        if(!actual.equals(instance.pins().keySet()))throw new IOException();
    }
    private static final Set<String> OS_AUTHORITIES=Set.of("S-1-5-18","S-1-5-32-544",
        "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464");
    private static void verifySystemModule(Instance instance,Path path,Cancellation cancellation,long deadline,List<HeldFile> pins)throws Exception {
        Path windows=windowsRoot();
        if(!path.startsWith(windows.resolve("System32")) && !path.startsWith(windows.resolve("WinSxS")))throw new IOException();
        String basename=path.getFileName().toString();
        for(String installed:instance.pins().keySet())
            if(Path.of(installed).getFileName().toString().equalsIgnoreCase(basename))throw new IOException();
        var file=new HeldFile(path,false,false);pins.add(file);
        systemAuthority(file.handle);
        // The system tree may contain legitimate component-store hardlinks; install/model files cannot.
        for(Path parent=path.getParent();parent!=null && parent.startsWith(windows);parent=parent.getParent()){
            var dir=new HeldFile(parent,true,false);pins.add(dir);systemAuthority(dir.handle);
        }
        if(path.equals(windows.resolve("System32/nvcuda.dll")))file.hash(instance.driver().bytes(),instance.driver().sha256(),cancellation,deadline);
        else {long bytes=Files.size(path);if(bytes<1 || bytes>512L*1024*1024)throw new IOException();file.hash(bytes,null,cancellation,deadline);}
        check(cancellation,deadline);
        if(!trustedSignature(file,cancellation,deadline,pins))throw new IOException();
        file.check();check(cancellation,deadline);
    }
    private static void systemAuthority(com.sun.jna.platform.win32.WinNT.HANDLE file)throws Exception {
        var api=com.sun.jna.platform.win32.Advapi32.INSTANCE;
        var owner=new com.sun.jna.ptr.PointerByReference();var dacl=new com.sun.jna.ptr.PointerByReference();var descriptor=new com.sun.jna.ptr.PointerByReference();
        if(api.GetSecurityInfo(file,1,5,owner,null,dacl,null,descriptor)!=0)throw new IOException();
        try {
            if(owner.getValue()==null || dacl.getValue()==null || !OS_AUTHORITIES.contains(com.sun.jna.platform.win32.Advapi32Util.convertSidToStringSid(new com.sun.jna.platform.win32.WinNT.PSID(owner.getValue()))))throw new IOException();
            var acl=new com.sun.jna.platform.win32.WinNT.ACL(dacl.getValue());
            for(int i=0;i<Short.toUnsignedInt(acl.AceCount);i++){
                var entry=new com.sun.jna.ptr.PointerByReference();if(!api.GetAce(acl,i,entry))throw new IOException();var ace=entry.getValue();
                int type=Byte.toUnsignedInt(ace.getByte(0)),flags=Byte.toUnsignedInt(ace.getByte(1));
                if(type==1 || (flags&8)!=0)continue;
                if(type!=0 || Short.toUnsignedInt(ace.getShort(2))<16)throw new IOException();
                String sid=com.sun.jna.platform.win32.Advapi32Util.convertSidToStringSid(new com.sun.jna.platform.win32.WinNT.PSID(ace.share(8)));
                if((ace.getInt(4)&0x500d0156)!=0 && !OS_AUTHORITIES.contains(sid))throw new IOException();
            }
        }finally{if(descriptor.getValue()!=null)com.sun.jna.platform.win32.Kernel32.INSTANCE.LocalFree(descriptor.getValue());}
    }
    private static boolean trustedSignature(HeldFile file,Cancellation cancellation,long deadline,List<HeldFile> pins)throws Exception {
        var api=com.sun.jna.Native.load("wintrust",NativeTrust.class);
        try(var name=wide(file.path.toString());var info=new com.sun.jna.Memory(32)){
            info.clear();info.setInt(0,32);info.setPointer(8,name);info.setPointer(16,file.handle.getPointer());
            if(winTrust(api,1,info,!file.path.equals(windowsRoot().resolve("System32/nvcuda.dll"))))return true;
        }
        check(cancellation,deadline);
        var admin=new com.sun.jna.ptr.PointerByReference();
        if(!api.CryptCATAdminAcquireContext2(admin,null,new com.sun.jna.WString("SHA256"),null,0) || admin.getValue()==null)throw new IOException();
        com.sun.jna.Pointer catalog=null;
        try {
            var size=new com.sun.jna.ptr.IntByReference();
            if(!api.CryptCATAdminCalcHashFromFileHandle2(admin.getValue(),file.handle,size,null,0) || size.getValue()!=32)throw new IOException();
            try(var hash=new com.sun.jna.Memory(32)){
                if(!api.CryptCATAdminCalcHashFromFileHandle2(admin.getValue(),file.handle,size,hash,0) || size.getValue()!=32)throw new IOException();
                catalog=api.CryptCATAdminEnumCatalogFromHash(admin.getValue(),hash,32,0,null);
                if(catalog==null)return false; // No catalogue is positive evidence of nothing.
                try(var description=new com.sun.jna.Memory(524)){
                    description.clear();description.setInt(0,524);
                    if(!api.CryptCATCatalogInfoFromContext(catalog,description,0))throw new IOException();
                    char[] text=description.getCharArray(4,260);int end=0;while(end<text.length&&text[end]!=0)end++;
                    if(end==0 || end==text.length)throw new IOException();Path path=Path.of(new String(text,0,end));
                    if(!path.startsWith(windowsRoot().resolve("System32/CatRoot")) && !path.startsWith(windowsRoot().resolve("WinSxS/Catalogs")))throw new IOException();
                    var held=new HeldFile(path,false,false);pins.add(held);systemAuthority(held.handle);
                    try(var catName=wide(path.toString());var member=wide(file.path.toString());
                            var tag=wide(HexFormat.of().withUpperCase().formatHex(hash.getByteArray(0,32)));var info=new com.sun.jna.Memory(72)){
                        info.clear();info.setInt(0,72);info.setPointer(8,catName);info.setPointer(16,tag);info.setPointer(24,member);
                        info.setPointer(32,file.handle.getPointer());info.setPointer(40,hash);info.setInt(48,32);info.setPointer(64,admin.getValue());
                        check(cancellation,deadline);return winTrust(api,2,info,!file.path.equals(windowsRoot().resolve("System32/nvcuda.dll")));
                    }
                }
            }
        }finally{if(catalog!=null)api.CryptCATAdminReleaseCatalogContext(admin.getValue(),catalog,0);api.CryptCATAdminReleaseContext(admin.getValue(),0);}
    }
    private static com.sun.jna.Memory wide(String text){
        byte[] bytes=(text+"\0").getBytes(java.nio.charset.StandardCharsets.UTF_16LE);var memory=new com.sun.jna.Memory(bytes.length);memory.write(0,bytes,0,bytes.length);return memory;
    }
    private static boolean winTrust(NativeTrust api,int choice,com.sun.jna.Pointer subject,boolean system)throws Exception {
        var action=new com.sun.jna.platform.win32.Guid.GUID("{00AAC56B-CD44-11d0-8CC2-00C04FC295EE}");action.write();
        try(var data=new com.sun.jna.Memory(88)){
            data.clear();data.setInt(0,88);data.setInt(24,2);data.setInt(32,choice);data.setPointer(40,subject);
            data.setInt(48,1);data.setInt(72,0x1000|0x10|0x2000); // Cache-only, no UI/network, no MD2/MD4.
            try{
                if(api.WinVerifyTrust(com.sun.jna.Pointer.createConstant(-1),action.getPointer(),data)!=0)return false;
                if(!system)return true; // Only the independently full-SHA-pinned nvcuda anchor uses this branch.
                var provider=api.WTHelperProvDataFromStateData(data.getPointer(56));if(provider==null)throw new IOException();
                var signer=api.WTHelperGetProvSignerFromChain(provider,0,false,0);
                if(signer==null || signer.getInt(0)!=64 || signer.getInt(40)!=0)throw new IOException();
                int count=signer.getInt(12);var chain=signer.getPointer(16);
                if(count<1 || count>32 || chain==null || chain.getInt(0)!=88)throw new IOException();
                var root=chain.share(88L*(count-1));
                if(root.getInt(0)!=88 || root.getInt(20)==0 || root.getInt(40)!=0)throw new IOException();
                String leaf=certificateName(chain.getPointer(8)),authority=certificateName(root.getPointer(8));
                return Set.of("Microsoft Windows","Microsoft Windows Publisher","Microsoft Corporation").contains(leaf)
                    && Set.of("Microsoft Root Authority","Microsoft Root Certificate Authority","Microsoft Root Certificate Authority 2010",
                        "Microsoft Root Certificate Authority 2011","Microsoft Identity Verification Root Certificate Authority 2020").contains(authority);
            }
            finally{data.setInt(48,2);api.WinVerifyTrust(com.sun.jna.Pointer.createConstant(-1),action.getPointer(),data);}
        }
    }
    private static String certificateName(com.sun.jna.Pointer certificate)throws IOException {
        if(certificate==null)throw new IOException();
        var crypt=com.sun.jna.Native.load("crypt32",NativeCertificate.class);char[] name=new char[512];
        int length=crypt.CertGetNameStringW(certificate,4,0,null,name,name.length);
        if(length<2 || length>=name.length || name[length-1]!=0)throw new IOException();return new String(name,0,length-1);
    }
    private interface NativeCertificate extends com.sun.jna.win32.StdCallLibrary {
        int CertGetNameStringW(com.sun.jna.Pointer certificate,int type,int flags,com.sun.jna.Pointer parameter,char[] name,int length);
    }
    private interface NativeTrust extends com.sun.jna.win32.StdCallLibrary {
        int WinVerifyTrust(com.sun.jna.Pointer window,com.sun.jna.Pointer action,com.sun.jna.Pointer data);
        boolean CryptCATAdminAcquireContext2(com.sun.jna.ptr.PointerByReference context,com.sun.jna.Pointer subsystem,com.sun.jna.WString algorithm,com.sun.jna.Pointer policy,int flags);
        boolean CryptCATAdminCalcHashFromFileHandle2(com.sun.jna.Pointer context,com.sun.jna.platform.win32.WinNT.HANDLE file,com.sun.jna.ptr.IntByReference size,com.sun.jna.Pointer hash,int flags);
        com.sun.jna.Pointer CryptCATAdminEnumCatalogFromHash(com.sun.jna.Pointer context,com.sun.jna.Pointer hash,int size,int flags,com.sun.jna.ptr.PointerByReference previous);
        boolean CryptCATCatalogInfoFromContext(com.sun.jna.Pointer catalog,com.sun.jna.Pointer info,int flags);
        boolean CryptCATAdminReleaseCatalogContext(com.sun.jna.Pointer context,com.sun.jna.Pointer catalog,int flags);
        boolean CryptCATAdminReleaseContext(com.sun.jna.Pointer context,int flags);
        com.sun.jna.Pointer WTHelperProvDataFromStateData(com.sun.jna.Pointer state);
        com.sun.jna.Pointer WTHelperGetProvSignerFromChain(com.sun.jna.Pointer provider,int signer,boolean counterSigner,int counterSignerIndex);
    }
    static void verify(int port,JsonNode show,Cancellation cancellation) {
        try {
            WindowsNativeGate.admit();
            ProcessHandle before=listener(port);
            String command=before.info().command().orElseThrow();
            Path executable=Path.of(command);
            if(!executable.getFileName().toString().equalsIgnoreCase("ollama.exe")) throw new IOException();
            digest(executable,Files.size(executable),SHARED_SERVER_HASHES,cancellation);
            String[] args=arguments(before);
            if(!Arrays.asList(args).equals(List.of("serve"))) throw new IOException();
            // Full pinned manifests bind every allowed layer, including license/params, and disallow extras.
            digest(ROOT.resolve("manifests-v2/ollama.com/library/qwen3.5/4b"),464,OllamaVisionAdmission.LIST,cancellation);
            for(var blob:BLOBS.entrySet()) digest(ROOT.resolve("blobs/sha256-"+blob.getKey()),blob.getValue(),blob.getKey(),cancellation);
            String modelPath=ROOT.resolve("blobs/sha256-"+OllamaVisionAdmission.LANGUAGE).toString();
            String projectorPath=ROOT.resolve("blobs/sha256-"+OllamaVisionAdmission.PROJECTOR).toString();
            List<String> commands=show.path("modelfile").asString("").lines().filter(s->!s.isBlank()&&!s.startsWith("#")).toList();
            List<String> from=commands.stream().filter(s->s.startsWith("FROM ")).map(s->{ String value=s.substring(5); return value.startsWith("\"")&&value.endsWith("\"")?value.substring(1,value.length()-1):value; }).toList();
            if(!from.equals(List.of(modelPath,projectorPath)) || commands.stream().anyMatch(s->s.matches("(?i)^(ADAPTER|DRAFT|REQUIRES|RUNNER|PROJECTOR)\\b.*"))) throw new IOException();
            ProcessHandle after=listener(port);
            if(before.pid()!=after.pid() || !before.info().startInstant().equals(after.info().startInstant()) || !before.isAlive()) throw new IOException();
        } catch(WorkspaceException cancelled) { throw cancelled; }
        catch(Exception | LinkageError unsafe) { throw new WorkspaceException(ErrorCode.MODEL_UNAVAILABLE,"VISION_SERVICE_IDENTITY"); }
    }
    record Snapshot(String generation, List<String> serviceArguments, List<String> runnerArguments, Map<Long,Path> modules,
                    Map<Long,ProcessState> roles,Map<Long,Map<Long,Path>> roleModules) {
        Snapshot(String generation,List<String> serviceArguments,List<String> runnerArguments,Map<Long,Path> modules){
            this(generation,serviceArguments,runnerArguments,modules,Map.of(),Map.of());
        }
        Snapshot {
            serviceArguments=List.copyOf(serviceArguments);runnerArguments=List.copyOf(runnerArguments);modules=Map.copyOf(modules);roles=Map.copyOf(roles);
            Map<Long,Map<Long,Path>> copy=new TreeMap<>();roleModules.forEach((pid,map)->copy.put(pid,Map.copyOf(map)));roleModules=Map.copyOf(copy);
        }
    }
    interface IdentityReader {
        Snapshot snapshot() throws Exception;
        String hashModules(Snapshot snapshot, Cancellation cancellation, long deadline) throws Exception;
    }
    record Sample(Snapshot snapshot, String payload) {}
    // The same stable-sampling algorithm is exercised by injected, model-free readers.
    static Sample sample(IdentityReader reader,Cancellation cancellation,long deadline) throws Exception {
        check(cancellation,deadline);Snapshot before=reader.snapshot();check(cancellation,deadline);
        String payload=reader.hashModules(before,cancellation,deadline);
        Snapshot after=reader.snapshot();check(cancellation,deadline);
        if(!before.equals(after))throw new IOException();
        return new Sample(after,payload);
    }
    private static SortedMap<String,Path> sharedModules(Snapshot snapshot,long serverPid,long runnerPid,Path installation,Path windows)throws IOException {
        if(snapshot.roles().size()!=2 || snapshot.roleModules().size()!=2
                || !snapshot.roles().keySet().equals(Set.of(serverPid,runnerPid))
                || !snapshot.roles().keySet().equals(snapshot.roleModules().keySet()))throw new IOException();
        SortedMap<String,Path> paths=new TreeMap<>();
        for(var role:Map.of("server",serverPid,"runner",runnerPid).entrySet()) {
            for(Path path:snapshot.roleModules().get(role.getValue()).values()) {
                if(!path.startsWith(installation)) {
                    if(path.startsWith(windows.resolve("System32")) || path.startsWith(windows.resolve("WinSxS")))continue;
                    throw new IOException("SHARED_EXTERNAL_MODULE");
                }
                if(paths.size()>=64 || paths.put(role.getKey()+"/"+installation.relativize(path),path)!=null)throw new IOException();
            }
        }
        if(paths.isEmpty())throw new IOException();
        return paths;
    }
    private static SortedMap<String,String> sharedEnvironment(ProcessState state,boolean runner)throws IOException {
        SortedMap<String,String> values=new TreeMap<>();
        // Fixed native sources read these option/backend/loader namespaces. Unknown options
        // within them remain bound; unrelated account credentials are not policy material.
        var prefixes=List.of("OLLAMA_","LLAMA_","GGML_","MTMD_","CLIP_","GGUF_","CUDA_","HIP_","HSA_",
                "ROCR_","NVIDIA_","VK_","LD_","DYLD_","OMP_","KMP_","DISABLE_LAYER_");
        for(var entry:state.environment().entrySet()) {
            String key=entry.getKey(),value=entry.getValue();
            if(key.equals("LLAMA_MEDIA_MARKER")) {
                if(!runner || !value.matches("<__ollama_media_(?:[a-f0-9]{32}|[1-9][0-9]{0,18}_[0-9]{1,19})__>"))throw new IOException();
                // The exact value still participates in both stable snapshots. Only a
                // well-formed generated delimiter is normalized across runner generations.
                value="<generated-per-runner>";
            }
            if(prefixes.stream().anyMatch(key::startsWith)
                    || Set.of("PATH","SYSTEMROOT","WINDIR","__COMPAT_LAYER","DISABLE_VULKAN_OBS_CAPTURE").contains(key))
                values.put(key,value);
        }
        if(runner && !values.containsKey("LLAMA_MEDIA_MARKER"))throw new IOException();
        return values;
    }
    private static String sharedPreprocessing(Snapshot snapshot,String payload,long serverPid,long runnerPid)throws Exception {
        ProcessState server=snapshot.roles().get(serverPid),runner=snapshot.roles().get(runnerPid);
        if(server==null || runner==null || runner.parent()!=serverPid || !server.arguments().equals(snapshot.serviceArguments())
                || !runner.arguments().equals(snapshot.runnerArguments()))throw new IOException();
        List<String> args=new ArrayList<>(),values=snapshot.runnerArguments();
        for(int i=0;i<values.size();i++) {
            String value=values.get(i);if(value.length()>16384)throw new IOException();
            if(value.equals("--port")) {
                if(++i>=values.size() || !values.get(i).matches("[1-9][0-9]{0,4}") || Integer.parseInt(values.get(i))>65535)throw new IOException();
            } else args.add(value);
        }
        return hash(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(List.of(VisionTokenBudget.POLICY,payload,args,
                List.of(server.image().toString(),server.cwd().toString(),sharedEnvironment(server,false)),
                List.of(runner.image().toString(),runner.cwd().toString(),sharedEnvironment(runner,true)))));
    }
    static OllamaProvider.VisionContext currentContext(int port,String identity,Cancellation cancellation) {
        if(REVIEWED_TOKEN_POLICIES.isEmpty()) return null;
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
        List<HeldFile> pinned=new ArrayList<>();
        try {
            WindowsNativeGate.admit();
            ProcessHandle service=listener(port);
            try(var server=new HeldProcess(service)) {
                Path executable=server.executable();Path installation=executable.getParent();
                var serverImage=new HeldFile(executable,false);pinned.add(serverImage);
                String serverHash=serverImage.hash(Files.size(executable),null,cancellation,deadline);
                if(!SHARED_SERVER_HASHES.contains(serverHash))return null;
                List<ProcessHandle> candidates=service.descendants().limit(65).toList();
                if(candidates.size()>64) return null;
                HeldProcess selected=null;
                try {
                    for(ProcessHandle child:candidates) {
                        check(cancellation,deadline);
                        HeldProcess candidate=new HeldProcess(child);boolean keep=false;
                        try {
                            List<String> args=List.of(arguments(candidate.handle));int model=args.indexOf("--model");
                            if(model>=0 && model+1<args.size() && args.get(model+1).equals(ROOT.resolve("blobs/sha256-"+OllamaVisionAdmission.LANGUAGE).toString())) {
                                if(selected!=null) return null;selected=candidate;keep=true;
                            }
                        } finally {if(!keep)candidate.close();}
                    }
                    if(selected==null) return null;
                    HeldProcess runner=selected;
                    NativeModules api=com.sun.jna.Native.load("kernel32",NativeModules.class);
                    char[] windowsName=new char[32768];int n=api.GetSystemWindowsDirectoryW(windowsName,windowsName.length);
                    if(n<=0 || n>=windowsName.length)return null;
                    Path windows=Path.of(new String(windowsName,0,n));
                    IdentityReader reader=new IdentityReader() {
                        public Snapshot snapshot() throws Exception {
                            check(cancellation,deadline);server.check();runner.check();
                            ProcessState serve=state(server.handle),child=state(runner.handle);
                            if(!serve.arguments().equals(List.of("serve")) || child.parent()!=service.pid())throw new IOException();
                            Map<Long,Path> serverModules=modules(api,server.handle),runnerModules=modules(api,runner.handle);
                            // Bind both processes' full state and module maps to the same held handles.
                            server.check();runner.check();
                            if(listener(port).pid()!=service.pid() || !serve.equals(state(server.handle)) || !child.equals(state(runner.handle)))throw new IOException();
                            check(cancellation,deadline);
                            return new Snapshot(server.generation()+"/"+runner.generation(),serve.arguments(),child.arguments(),runnerModules,
                                    Map.of(service.pid(),serve,runner.process.pid(),child),Map.of(service.pid(),serverModules,runner.process.pid(),runnerModules));
                        }
                        public String hashModules(Snapshot snapshot,Cancellation cancel,long until) throws Exception {
                            SortedMap<String,String> hashes=new TreeMap<>();
                            for(var entry:sharedModules(snapshot,service.pid(),runner.process.pid(),installation,windows).entrySet()) {
                                Path path=entry.getValue();
                                check(cancel,until);
                                for(Path parent=path;parent!=null;parent=parent.getParent()) {
                                    int attributes=com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(parent.toString());
                                    if(attributes==-1 || (attributes&0x400)!=0)throw new IOException();
                                }
                                if(hashes.size()>=64 || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>512L*1024*1024)throw new IOException();
                                // Keep the same no-write/delete object pinned through the second
                                // snapshot and policy lookup, rather than releasing it after hashing.
                                var file=new HeldFile(path,false);pinned.add(file);
                                String sha=file.hash(Files.size(path),null,cancel,until);
                                hashes.put(entry.getKey(),sha);
                            }
                            if(hashes.isEmpty())throw new IOException();
                            return hash(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(hashes));
                        }
                    };
                    Sample sample=sample(reader,cancellation,deadline);
                    for(var file:pinned){check(cancellation,deadline);file.check();}
                    String payload=sample.payload();
                    String preprocessing=sharedPreprocessing(sample.snapshot(),payload,service.pid(),runner.process.pid());
                    String template=hash(("renderer=qwen3.5|"+serverHash+"|"+OllamaVisionAdmission.CONFIG).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    var evidence=REVIEWED_TOKEN_POLICIES.get(payload+"/"+preprocessing+"/"+template);
                    check(cancellation,deadline);
                    if(evidence==null || !identity.equals(evidence.identity()))return null;
                    return new OllamaProvider.VisionContext(evidence,payload,preprocessing,template,sample.snapshot().generation());
                } finally {if(selected!=null)selected.close();}
            }
        } catch(Exception | LinkageError missing) {return null;}
        finally {for(var file:pinned)file.close();}
    }
    private static void check(Cancellation cancellation,long deadline) throws IOException {
        cancellation.check();if(Thread.currentThread().isInterrupted() || System.nanoTime()>=deadline)throw new IOException();
    }
    private static Map<Long,Path> modules(NativeModules api,com.sun.jna.platform.win32.WinNT.HANDLE handle) throws IOException {
        var entries=new com.sun.jna.Pointer[1024];var needed=new com.sun.jna.ptr.IntByReference();
        if(!api.K32EnumProcessModules(handle,entries,entries.length*8,needed) || needed.getValue()<=0 || needed.getValue()>entries.length*8 || needed.getValue()%8!=0)throw new IOException();
        Map<Long,Path> result=new TreeMap<>();char[] name=new char[32768];
        for(int i=0;i<needed.getValue()/8;i++) {
            if(entries[i]==null)throw new IOException();int n=api.K32GetModuleFileNameExW(handle,entries[i],name,name.length);
            if(n<=0 || n>=name.length || result.put(com.sun.jna.Pointer.nativeValue(entries[i]),Path.of(new String(name,0,n)))!=null)throw new IOException();
        }
        return Map.copyOf(result);
    }
    private static final class HeldProcess implements AutoCloseable {
        final com.sun.jna.platform.win32.WinNT.HANDLE handle;
        final ProcessHandle process;
        final long started;
        final NativeProcess api=com.sun.jna.Native.load("kernel32",NativeProcess.class);
        HeldProcess(ProcessHandle process) throws Exception {
            this.process=process;
            if(com.sun.jna.Native.POINTER_SIZE!=8)throw new IOException();
            var expected=process.info().startInstant().orElseThrow();
            handle=com.sun.jna.platform.win32.Kernel32.INSTANCE.OpenProcess(0x100410,false,Math.toIntExact(process.pid()));
            if(handle==null)throw new IOException();
            try {
                started=creationTime();
                if(started/10000-11644473600000L!=expected.toEpochMilli())throw new IOException();
                check();
            } catch(Exception | LinkageError failure) {close();throw failure;}
        }
        private long creationTime() throws IOException {
            try(var times=new com.sun.jna.Memory(32)) {
                if(!api.GetProcessTimes(handle,times,times.share(8),times.share(16),times.share(24)))throw new IOException();
                return times.getLong(0);
            }
        }
        void check() throws IOException {
            if(api.GetProcessId(handle)!=Math.toIntExact(process.pid()) || api.WaitForSingleObject(handle,0)!=258 || creationTime()!=started || !process.isAlive())throw new IOException();
        }
        String generation() {return process.pid()+"/"+started;}
        Path executable() throws IOException {
            char[] path=new char[32768];var length=new com.sun.jna.ptr.IntByReference(path.length);
            if(!api.QueryFullProcessImageNameW(handle,0,path,length) || length.getValue()<=0 || length.getValue()>=path.length)throw new IOException();
            return Path.of(new String(path,0,length.getValue()));
        }
        public void close() {com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(handle);}
    }
    private static String hash(byte[] b) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    /** Windows JDK 21 supplies executable paths but not foreign-process arguments. Read only
     * documented Win64 PBI/PEB/RTL_USER_PROCESS_PARAMETERS fields, with a 64 KiB ceiling. */
    private static String[] arguments(ProcessHandle process) throws Exception {
        try(var held=new HeldProcess(process)) {
            String[] args=arguments(held.handle);held.check();return args;
        }
    }
    private static String[] arguments(com.sun.jna.platform.win32.WinNT.HANDLE handle) throws Exception {
        var kernel=com.sun.jna.platform.win32.Kernel32.INSTANCE;
        try(var basic=new com.sun.jna.Memory(48);var pointer=new com.sun.jna.Memory(8);var unicode=new com.sun.jna.Memory(16)) {
            NativeCommand query=com.sun.jna.Native.load("ntdll",NativeCommand.class);var returned=new com.sun.jna.ptr.IntByReference();
            if(query.NtQueryInformationProcess(handle,0,basic,48,returned)!=0 || returned.getValue()!=48)throw new IOException();
            com.sun.jna.Pointer peb=basic.getPointer(8);if(peb==null)throw new IOException();
            readRemote(kernel,handle,peb.share(32),pointer,8);com.sun.jna.Pointer parameters=pointer.getPointer(0);if(parameters==null)throw new IOException();
            readRemote(kernel,handle,parameters.share(112),unicode,16);int length=Short.toUnsignedInt(unicode.getShort(0));
            if(length==0 || length>65534 || length%2!=0 || Short.toUnsignedInt(unicode.getShort(2))<length)throw new IOException();
            com.sun.jna.Pointer text=unicode.getPointer(8);if(text==null)throw new IOException();
            try(var buffer=new com.sun.jna.Memory(length)) {
                readRemote(kernel,handle,text,buffer,length);
                String command=java.nio.charset.StandardCharsets.UTF_16LE.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(buffer.getByteArray(0,length))).toString();
                if(command.indexOf(0)>=0)throw new IOException();
                var count=new com.sun.jna.ptr.IntByReference();var shell=com.sun.jna.Native.load("shell32",NativeArguments.class);
                com.sun.jna.Pointer argv=shell.CommandLineToArgvW(new com.sun.jna.WString(command),count);if(argv==null)throw new IOException();
                try {
                    if(count.getValue()<1 || count.getValue()>129)throw new IOException();String[] args=new String[count.getValue()-1];
                    for(int i=1;i<count.getValue();i++) {var item=argv.getPointer(i*8L);if(item==null)throw new IOException();args[i-1]=item.getWideString(0);}
                    return args;
                } finally {kernel.LocalFree(argv);}
            }
        }
    }
    private static void readRemote(com.sun.jna.platform.win32.Kernel32 kernel,com.sun.jna.platform.win32.WinNT.HANDLE process,
                                   com.sun.jna.Pointer address,com.sun.jna.Memory output,int bytes) throws IOException {
        var nativeRead=com.sun.jna.Native.load("kernel32",NativeRemote.class);
        try(var read=new com.sun.jna.Memory(8)) {if(!nativeRead.ReadProcessMemory(process,address,output,bytes,read) || read.getLong(0)!=bytes)throw new IOException();}
    }
    private static void digest(Path path,long size,String expected,Cancellation cancellation) throws Exception {
        digest(path,size,Set.of(expected),cancellation);
    }
    private static void digest(Path path,long size,Set<String> expected,Cancellation cancellation) throws Exception {
        for(Path p=path;p!=null;p=p.getParent()) {
            int attributes=com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(p.toString());
            if(attributes==-1 || (attributes&0x400)!=0 || Files.isSymbolicLink(p) || !p.equals(p.toRealPath())) throw new IOException();
        }
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)!=size) throw new IOException();
        var hash=MessageDigest.getInstance("SHA-256");
        try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) { byte[] buffer=new byte[65536]; int count; while((count=stream.read(buffer))!=-1) { cancellation.check(); hash.update(buffer,0,count); } }
        if(!expected.contains(HexFormat.of().formatHex(hash.digest()))) throw new IOException();
    }
    private static ProcessHandle listener(int port) throws IOException {
        com.sun.jna.ptr.IntByReference size=new com.sun.jna.ptr.IntByReference();
        Tcp api=com.sun.jna.Native.load("iphlpapi",Tcp.class);
        if(api.GetExtendedTcpTable(null,size,false,2,5,0)!=122 || size.getValue()<4 || size.getValue()>1024*1024) throw new IOException();
        try(var buffer=new com.sun.jna.Memory(size.getValue())) {
            if(api.GetExtendedTcpTable(buffer,size,false,2,5,0)!=0) throw new IOException();
            int count=buffer.getInt(0); if(count<0 || (long)count*24+4>size.getValue()) throw new IOException();
            long pid=-1;
            for(int i=0;i<count;i++) { long at=4L+24L*i;
                int localPort=Short.toUnsignedInt(Short.reverseBytes(buffer.getShort(at+8)));
                if(buffer.getInt(at)==2 && localPort==port) {
                    if(buffer.getInt(at+4)!=0x0100007f || pid!=-1) throw new IOException();
                    pid=Integer.toUnsignedLong(buffer.getInt(at+20));
                }
            }
            return ProcessHandle.of(pid).orElseThrow(IOException::new);
        }
    }
    private interface Tcp extends com.sun.jna.win32.StdCallLibrary {
        int GetExtendedTcpTable(com.sun.jna.Pointer table,com.sun.jna.ptr.IntByReference size,boolean order,int family,int tableClass,int reserved);
    }
    private interface NativeProcess extends com.sun.jna.win32.StdCallLibrary {
        boolean GetProcessTimes(com.sun.jna.platform.win32.WinNT.HANDLE process,com.sun.jna.Pointer created,com.sun.jna.Pointer exited,com.sun.jna.Pointer kernel,com.sun.jna.Pointer user);
        int GetProcessId(com.sun.jna.platform.win32.WinNT.HANDLE process);
        int WaitForSingleObject(com.sun.jna.platform.win32.WinNT.HANDLE process,int milliseconds);
        boolean QueryFullProcessImageNameW(com.sun.jna.platform.win32.WinNT.HANDLE process,int flags,char[] path,com.sun.jna.ptr.IntByReference size);
    }
    private interface NativeModules extends com.sun.jna.win32.StdCallLibrary {
        int GetSystemWindowsDirectoryW(char[] path,int size);
        boolean K32EnumProcessModules(com.sun.jna.platform.win32.WinNT.HANDLE process,com.sun.jna.Pointer[] modules,int bytes,com.sun.jna.ptr.IntByReference needed);
        int K32GetModuleFileNameExW(com.sun.jna.platform.win32.WinNT.HANDLE process,com.sun.jna.Pointer module,char[] path,int size);
    }
    private interface NativeCommand extends com.sun.jna.win32.StdCallLibrary {
        int NtQueryInformationProcess(com.sun.jna.platform.win32.WinNT.HANDLE process,int type,com.sun.jna.Pointer output,int bytes,com.sun.jna.ptr.IntByReference returned);
    }
    private interface NativeArguments extends com.sun.jna.win32.StdCallLibrary {
        com.sun.jna.Pointer CommandLineToArgvW(com.sun.jna.WString command,com.sun.jna.ptr.IntByReference count);
    }
    /** Win64 SIZE_T and SIZE_T* are both eight bytes; no four-byte returned-count buffer. */
    private interface NativeRemote extends com.sun.jna.win32.StdCallLibrary {
        boolean ReadProcessMemory(com.sun.jna.platform.win32.WinNT.HANDLE process,com.sun.jna.Pointer address,com.sun.jna.Pointer output,long bytes,com.sun.jna.Pointer returned);
    }
}
