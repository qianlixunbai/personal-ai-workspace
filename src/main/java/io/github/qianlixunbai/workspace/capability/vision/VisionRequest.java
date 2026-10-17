package io.github.qianlixunbai.workspace.capability.vision;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.model.ModelStateStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;

/** Bounded UTF-8 parse; the image string is never materialized. */
public record VisionRequest(UUID taskId,String question,String mediaType,byte[] image,long expectedSelectionRevision) {
    public static final int MAX_BODY=6*1024*1024;
    private static final JsonFactory JSON=JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxNameLength(64).maxNumberLength(20).maxStringLength(4096).build()).build();
    @Override public String toString() {return "VisionRequest[redacted]";}
    public static VisionRequest read(InputStream input) throws IOException {
        AtomicBoolean timedOut=new AtomicBoolean();
        Thread timer=Thread.ofVirtual().start(()->{try {Thread.sleep(15_000);timedOut.set(true);input.close();} catch(InterruptedException done) {Thread.currentThread().interrupt();} catch(IOException ignored) {}});
        byte[] body=new byte[MAX_BODY+1]; int length=0;
        try {
            int n; while(length<body.length && (n=input.read(body,length,body.length-length))!=-1) {length+=n;if(timedOut.get()) timeout();}
            if(timedOut.get()) timeout(); if(length>MAX_BODY) throw new WorkspaceException(ErrorCode.VISION_TOO_LARGE,"VISION_UPLOAD");
        } catch(IOException error) {if(timedOut.get()) timeout();throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"VISION_UPLOAD");}
        finally {timer.interrupt();}
        try(var parser=JSON.createParser(body,0,length)) {
            require(parser.nextToken()==JsonToken.START_OBJECT); UUID id=null;String question=null,media=null;byte[] image=null;Long revision=null;
            Set<String> names=new HashSet<>();
            while(parser.nextToken()!=JsonToken.END_OBJECT) {
                require(parser.currentToken()==JsonToken.PROPERTY_NAME);String name=parser.currentName();require(names.add(name));parser.nextToken();
                switch(name) {
                    case "taskId" -> {require(parser.currentToken()==JsonToken.VALUE_STRING);String value=parser.getString();require(value.length()==36);id=UUID.fromString(value);require(id.toString().equals(value)&&!id.equals(new UUID(0,0)));}
                    case "question" -> {require(parser.currentToken()==JsonToken.VALUE_STRING);question=parser.getString();require(!question.isBlank()&&question.length()<=1000&&question.getBytes(StandardCharsets.UTF_8).length<=4096);}
                    case "expectedSelectionRevision" -> {require(parser.currentToken()==JsonToken.VALUE_NUMBER_INT);revision=parser.getLongValue();require(revision>=0&&revision<=ModelStateStore.MAX_REVISION);}
                    case "image" -> {
                        require(parser.currentToken()==JsonToken.START_OBJECT);Set<String> fields=new HashSet<>();
                        while(parser.nextToken()!=JsonToken.END_OBJECT) {
                            require(parser.currentToken()==JsonToken.PROPERTY_NAME);String field=parser.currentName();require(fields.add(field));require(parser.nextToken()==JsonToken.VALUE_STRING);
                            if(field.equals("mediaType")) {media=parser.getString();require(Set.of("image/png","image/jpeg").contains(media));}
                            else if(field.equals("dataBase64")) {
                                int start=Math.toIntExact(parser.currentTokenLocation().getByteOffset())+1;
                                var decoded=new FixedOutput();parser.readBinaryValue(decoded);image=decoded.bytes();
                                canonical(body,start,image);
                            } else invalid();
                        }
                        require(fields.equals(Set.of("mediaType","dataBase64")));
                    }
                    default -> invalid();
                }
            }
            require(names.equals(Set.of("taskId","question","image","expectedSelectionRevision"))&&parser.nextToken()==null);
            return new VisionRequest(id,question,media,image,revision);
        } catch(WorkspaceException error) {throw error;} catch(RuntimeException error) {throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"VISION_JSON");}
    }
    private static void canonical(byte[] body,int start,byte[] decoded) {
        // Compare canonical encoding without allocating a second Base64 string, rejecting escapes/whitespace/pad bits.
        final String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int encoded=4*((decoded.length+2)/3); require(encoded>0 && start>=1 && (long)start+encoded<body.length && body[start+encoded]=='"');
        for(int i=0,at=start;i<decoded.length;i+=3) {
            int a=decoded[i]&255,b=i+1<decoded.length?decoded[i+1]&255:0,c=i+2<decoded.length?decoded[i+2]&255:0;
            require(body[at++]==alphabet.charAt(a>>>2));require(body[at++]==alphabet.charAt(((a&3)<<4)|(b>>>4)));
            require(body[at++]==(i+1<decoded.length?alphabet.charAt(((b&15)<<2)|(c>>>6)):'='));
            require(body[at++]==(i+2<decoded.length?alphabet.charAt(c&63):'='));
        }
    }
    private static void timeout() {throw new WorkspaceException(ErrorCode.TASK_TIMEOUT,"VISION_UPLOAD");}
    private static void require(boolean b) {if(!b) invalid();}
    private static void invalid() {throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"VISION_JSON");}
    private static final class FixedOutput extends OutputStream {
        private final byte[] output=new byte[VisionImage.MAX_BYTES];private int count;
        @Override public void write(int b) {if(count==output.length) large();output[count++]=(byte)b;}
        @Override public void write(byte[] bytes,int offset,int length) {if(length>output.length-count) large();System.arraycopy(bytes,offset,output,count,length);count+=length;}
        byte[] bytes() {return Arrays.copyOf(output,count);}
        private void large() {throw new WorkspaceException(ErrorCode.VISION_TOO_LARGE,"VISION_IMAGE");}
    }
}
