package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.json.JsonMapper;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeLimits.*;

/** Pure textual interpretation: LF line endings; only initial UTF-8 BOM is removed. */
public final class KnowledgeParser {
    public static final String PARSER_VERSION="text-1",NORMALIZATION_VERSION="lf-1";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public record Locator(String type,int startLine,int endLine,int startOffset,int endOffset,String section,String heading) {}
    public record Representation(String text,List<Locator> locators,String locatorJson,String digest,int lineCount) {
        @Override public String toString(){return "Representation[lineCount="+lineCount+"]";}
    }
    static WorkspaceException error(ErrorCode code){return new WorkspaceException(code,"KNOWLEDGE");}
    public static String type(String filename) {
        String f=filename.toLowerCase(Locale.ROOT);
        if(f.endsWith(".txt"))return "TXT";
        if(f.endsWith(".md")||f.endsWith(".markdown"))return "MARKDOWN";
        throw error(ErrorCode.KNOWLEDGE_UNSUPPORTED_TYPE);
    }
    public static String filename(String text) {
        if(text==null || text.isBlank() || text.codePointCount(0,text.length())>160 || text.contains("/")||text.contains("\\")
                ||text.contains(":"))throw error(ErrorCode.KNOWLEDGE_INVALID_SOURCE);
        for(int i=0;i<text.length();) { int cp=text.codePointAt(i); if(Character.isISOControl(cp)||cp>=0xD800&&cp<=0xDFFF)
                throw error(ErrorCode.KNOWLEDGE_INVALID_SOURCE);i+=Character.charCount(cp); }
        type(text);return text;
    }
    public static Representation parse(Path file,String type,BooleanSupplier cancelled) {
        StringBuilder normalized=new StringBuilder();
        try(var reader=new InputStreamReader(Files.newInputStream(file),StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            char[] chunk=new char[4096];int n;boolean first=true,cr=false;
            while((n=reader.read(chunk))!=-1) {
                if(cancelled.getAsBoolean())throw error(ErrorCode.KNOWLEDGE_CANCELLED);
                for(int i=0;i<n;i++) {char c=chunk[i];if(first){first=false;if(c=='\uFEFF')continue;}
                    if(c==0||Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t')throw error(ErrorCode.KNOWLEDGE_INVALID_SOURCE);
                    if(c=='\n'&&cr){cr=false;continue;}cr=c=='\r';normalized.append(cr?'\n':c);
                    if(normalized.length()>CODE_POINTS*2)throw error(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
                }
            }
        } catch(CharacterCodingException e){throw error(ErrorCode.KNOWLEDGE_INVALID_UTF8);}
        catch(IOException e){throw error(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE);}
        return represent(normalized.toString(),type,cancelled);
    }
    public static Representation represent(String text,String type,BooleanSupplier cancelled) {
        if(!Set.of("TXT","MARKDOWN").contains(type)||text.isBlank()||text.indexOf('\r')>=0||text.indexOf('\0')>=0)
            throw error(ErrorCode.KNOWLEDGE_INVALID_SOURCE);
        if(text.codePointCount(0,text.length())>CODE_POINTS||text.getBytes(StandardCharsets.UTF_8).length>TEXT_BYTES)
            throw error(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
        List<Locator> locators=new ArrayList<>();int line=1,start=0,blockStart=0,blockLine=1;String section=null,heading=null;
        boolean fenced=false;char fence=0;int fenceSize=0;
        while(start<text.length()) {
            if(cancelled.getAsBoolean())throw error(ErrorCode.KNOWLEDGE_CANCELLED);
            if(line>LINES)throw error(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
            int end=text.indexOf('\n',start);if(end<0)end=text.length();
            String value=text.substring(start,end);String trimmed=value.stripLeading();
            boolean boundary=false;
            if(type.equals("MARKDOWN")) {
                int spaces=value.length()-trimmed.length();
                int run=0;char ch=trimmed.isEmpty()?' ':trimmed.charAt(0);
                if(spaces<=3&&(ch=='`'||ch=='~')){while(run<trimmed.length()&&trimmed.charAt(run)==ch)run++;
                    if(run>=3){if(!fenced){fenced=true;fence=ch;fenceSize=run;}else if(ch==fence&&run>=fenceSize&&trimmed.substring(run).isBlank())fenced=false;}}
                if(!fenced&&spaces<=3&&trimmed.startsWith("#")) {
                    int level=0;while(level<trimmed.length()&&trimmed.charAt(level)=='#')level++;
                    if(level<=6&&(level==trimmed.length()||trimmed.charAt(level)==' '||trimmed.charAt(level)=='\t')) {
                        boundary=true;
                        if(blockStart<start)locators.add(new Locator("MARKDOWN_SECTION_LINES",blockLine,line-1,blockStart,start,section,heading));
                        heading=trimmed.substring(level).strip();if(heading.codePointCount(0,heading.length())>160)
                            heading=heading.substring(0,heading.offsetByCodePoints(0,160));
                        section="line-"+line;blockStart=start;blockLine=line;
                    }
                }
            }
            int next=end<text.length()?end+1:end;
            if(line-blockLine>=15||next==text.length()) {
                locators.add(new Locator(type.equals("TXT")?"TXT_LINES":"MARKDOWN_SECTION_LINES",blockLine,line,blockStart,next,section,heading));
                blockStart=next;blockLine=line+1;
            }
            if(locators.size()>BLOCKS)throw error(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
            start=next;line++;
        }
        int count=line-1; if(count<1||count>LINES)throw error(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
        String encoded=JSON.writeValueAsString(locators);
        if(encoded.getBytes(StandardCharsets.UTF_8).length>LOCATOR_BYTES)throw error(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
        return new Representation(text,List.copyOf(locators),encoded,representationDigest(text,encoded),count);
    }
    public static String representationDigest(String text,String locators) {
        try {var hash=MessageDigest.getInstance("SHA-256");for(String value:List.of(PARSER_VERSION,NORMALIZATION_VERSION,text,locators)) {
            byte[] bytes=value.getBytes(StandardCharsets.UTF_8);hash.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.length).array());hash.update(bytes); }
            return HexFormat.of().formatHex(hash.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException();}
    }
    public static String sourceDigest(Path file) throws IOException {
        try {var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)){byte[] buffer=new byte[65536];int n;long total=0;
            while((n=input.read(buffer))!=-1){if((total+=n)>SOURCE_BYTES)throw error(ErrorCode.KNOWLEDGE_SOURCE_TOO_LARGE);digest.update(buffer,0,n);}}
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException();}
    }
}
