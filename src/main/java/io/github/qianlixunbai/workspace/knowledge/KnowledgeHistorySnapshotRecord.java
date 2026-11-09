package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.model.ModelProfile;
import io.github.qianlixunbai.workspace.task.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Logical, bounded terminal records. No SQLite pages, executable SQL, credentials or running tasks. */
final class KnowledgeHistorySnapshotRecord {
    static final int MAX_RECORD_BYTES=131072;
    static final JsonMapper JSON=JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10).maxStringLength(16384)
                    .maxNameLength(64).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
    record Reference(String availability,String sourceDigest,String representationDigest,String excerptDigest) {}
    record Saved(String taskId,String question,String query,ModelProfile.PublicProfile profile,String promptVersion,
                 String createdAt,String finishedAt,TaskStatus status,TaskResult.KnowledgeAnswer result,
                 ErrorCode errorCode,String errorPhase,List<Reference> references,KnowledgeScope scope) {
        @Override public String toString(){return "KnowledgeSnapshotRecord[redacted]";}
        KnowledgeAnswerHistory.Entry entry(){
            return new KnowledgeAnswerHistory.Entry(question,query,new TaskView(UUID.fromString(taskId),"knowledge-answer",status,
                    profile,promptVersion,Instant.parse(createdAt),Instant.parse(finishedAt),result,
                    errorCode==null?null:ApiError.of(errorCode,errorPhase)),scope);
        }
    }
    static WorkspaceException invalid(){return new WorkspaceException(ErrorCode.KNOWLEDGE_BACKUP_INVALID,"KNOWLEDGE_SNAPSHOT");}
    static void require(boolean value){if(!value)throw invalid();}
    static <T> T decode(byte[] bytes,Class<T> type) {
        try {
            require(bytes.length<=MAX_RECORD_BYTES);
            var tree=JSON.readTree(bytes);T result=JSON.treeToValue(tree,type);
            // Reject implicit scalar coercion and absent/null fields, rather than silently normalizing an archive.
            require(tree.equals(JSON.readTree(JSON.writeValueAsBytes(result))));return result;
        }catch(WorkspaceException e){throw e;}catch(Exception e){throw invalid();}
    }
    static byte[] encode(Saved saved){
        validate(saved.entry());byte[] bytes=JSON.writeValueAsBytes(saved);
        require(bytes.length<=MAX_RECORD_BYTES);return bytes;
    }
    static Saved decodeSaved(byte[] bytes,int version) {
        if(version==2)return decode(bytes,Saved.class);
        require(version==1&&bytes.length<=MAX_RECORD_BYTES);
        try {
            var tree=JSON.readTree(bytes);require(tree.isObject()&&!tree.has("scope"));
            ((tools.jackson.databind.node.ObjectNode)tree).putNull("scope");
            return decode(JSON.writeValueAsBytes(tree),Saved.class);
        }catch(WorkspaceException e){throw e;}catch(Exception e){throw invalid();}
    }
    static Saved capture(KnowledgeAnswerHistory.Entry entry,KnowledgeStore source) throws Exception {
        validate(entry);var t=entry.task();var result=(TaskResult.KnowledgeAnswer)t.result();
        var references=new ArrayList<Reference>();
        if(result!=null)for(var c:result.citations())references.add(reference(source,c));
        return new Saved(t.taskId().toString(),entry.question(),entry.query(),t.profile(),t.promptVersion(),t.createdAt().toString(),
                t.finishedAt().toString(),t.status(),result,t.error()==null?null:t.error().code(),t.error()==null?null:t.error().phase(),List.copyOf(references),entry.scope());
    }
    static KnowledgeAnswerHistory.Entry verify(Saved saved,KnowledgeStore source) throws Exception {
        require(saved!=null&&saved.taskId()!=null&&UUID.fromString(saved.taskId()).toString().equals(saved.taskId()));
        var entry=saved.entry();validate(entry);
        require(saved.references()!=null&&capture(entry,source).equals(saved));return entry;
    }
    static void validate(KnowledgeAnswerHistory.Entry entry) {
        try {
            require(entry!=null);text(entry.question(),3000);text(entry.query(),256);
            var t=entry.task();require(t!=null&&t.taskId()!=null&&"knowledge-answer".equals(t.capability())
                    &&"knowledge-answer-v2".equals(t.promptVersion())&&t.createdAt()!=null&&t.finishedAt()!=null
                    &&!t.createdAt().isBefore(Instant.EPOCH)&&!t.finishedAt().isBefore(t.createdAt()));
            var p=t.profile();require(p!=null&&"chat.balanced".equals(p.id())&&p.locality()==ModelProfile.Locality.LOCAL);text(p.version(),256);
            if(t.status()==TaskStatus.SUCCEEDED) {
                require(t.error()==null&&t.result() instanceof TaskResult.KnowledgeAnswer);
                var a=(TaskResult.KnowledgeAnswer)t.result();text(a.answer(),2048);
                require(a.citations()!=null&&!a.citations().isEmpty()&&a.citations().size()<=10
                        &&JSON.writeValueAsBytes(a).length<=60000);
                Set<String> ids=new HashSet<>();
                for(var c:a.citations()){
                    require(c!=null);KnowledgeStore.id(c.documentId());KnowledgeParser.filename(c.title());
                    require(entry.scope()==null||entry.scope().matches(c.documentId(),c.sourceRevision()));
                    require(KnowledgeStore.version(c.sourceRevision())<=10&&Set.of("TXT","MARKDOWN").contains(c.sourceType()));
                    require(c.startOffset()>=0&&c.endOffset()>c.startOffset()&&c.endOffset()<=1000000
                            &&c.endOffset()-c.startOffset()<=4096&&c.startLine()>=1&&c.endLine()>=c.startLine()&&c.endLine()<=100000);
                    require(ids.add(c.documentId()+"/"+c.sourceRevision()+"/"+c.startOffset()));
                    if(c.heading()!=null){unicode(c.heading());require(c.heading().codePointCount(0,c.heading().length())<=160);}
                    var l=c.locator();require(l!=null&&l.type().equals(c.sourceType().equals("TXT")?"TXT_LINES":"MARKDOWN_SECTION_LINES")
                            &&l.startOffset()>=0&&l.startOffset()<=c.startOffset()&&l.endOffset()>=c.endOffset()&&l.endOffset()<=1000000
                            &&l.startLine()>=1&&l.startLine()<=c.startLine()&&l.endLine()>=c.endLine()&&l.endLine()<=100000);
                    require(!c.sourceType().equals("TXT")||(c.heading()==null&&l.section()==null));
                    if(l.section()!=null)require(l.section().matches("line-[1-9][0-9]{0,5}")&&Integer.parseInt(l.section().substring(5))<=l.startLine());
                }
            }else{
                require(t.result()==null&&t.error()!=null);var e=t.error();
                boolean allowed=switch(t.status()){
                    case CANCELLED->e.code()==ErrorCode.TASK_CANCELLED;
                    case TIMED_OUT->e.code()==ErrorCode.TASK_TIMEOUT;
                    case FAILED->Set.of(ErrorCode.PROVIDER_UNAVAILABLE,ErrorCode.MODEL_UNAVAILABLE,ErrorCode.MODEL_STATE_UNAVAILABLE,
                            ErrorCode.MODEL_IDENTITY_CHANGED,ErrorCode.MODEL_EXECUTION_UNCERTAIN,ErrorCode.PROVIDER_RESPONSE_INVALID,
                            ErrorCode.POLICY_DENIED,ErrorCode.INVALID_REQUEST,ErrorCode.INTERNAL_ERROR,ErrorCode.QUEUE_FULL,
                            ErrorCode.KNOWLEDGE_INTERRUPTED,ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE,ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED).contains(e.code());
                    default->false;
                };
                require(allowed&&e.phase()!=null&&e.phase().matches("[A-Z][A-Z0-9_]{0,63}")&&e.equals(ApiError.of(e.code(),e.phase())));
            }
        }catch(WorkspaceException e){throw invalid();}catch(Exception e){throw invalid();}
    }
    private static void text(String text,int max){require(text!=null&&!text.isBlank()&&text.length()<=max);unicode(text);}
    private static void unicode(String text){
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)){require(i+1<text.length()&&Character.isLowSurrogate(text.charAt(++i)));}
            else require(!Character.isLowSurrogate(c));
        }
    }
    private static Reference reference(KnowledgeStore source,TaskResult.KnowledgeCitation c) throws Exception {
        // Only an absent entire document may be unavailable; a missing revision of an existing document is damage.
        if(source.scalar("SELECT count(*) FROM documents WHERE id=?",c.documentId())==0)return new Reference("UNAVAILABLE_AT_SNAPSHOT",null,null,null);
        try(var s=source.statement("SELECT * FROM revisions WHERE doc_id=? AND revision=?",c.documentId(),KnowledgeStore.version(c.sourceRevision()));var r=s.executeQuery()){
            require(r.next()&&r.getString("type").equals(c.sourceType()));
            String text=r.getString("text");require(c.endOffset()<=text.length()
                    &&(c.startOffset()==0||!Character.isLowSurrogate(text.charAt(c.startOffset())))
                    &&(c.endOffset()==text.length()||!Character.isLowSurrogate(text.charAt(c.endOffset()))));
            int first=1,last;for(int i=0;i<c.startOffset();i++)if(text.charAt(i)=='\n')first++;
            last=first;for(int i=c.startOffset();i<c.endOffset();i++)if(text.charAt(i)=='\n')last++;
            if(text.charAt(c.endOffset()-1)=='\n')last--;
            require(first==c.startLine()&&last==c.endLine());
            var parsed=KnowledgeParser.represent(text,c.sourceType(),()->false);var l=c.locator();
            require(parsed.digest().equals(r.getString("representation_digest"))&&parsed.locators().stream().anyMatch(x->
                    x.type().equals(l.type())&&x.startOffset()==l.startOffset()&&x.endOffset()==l.endOffset()
                    &&x.startLine()==l.startLine()&&x.endLine()==l.endLine()&&Objects.equals(x.section(),l.section())&&Objects.equals(x.heading(),c.heading())));
            return new Reference("AVAILABLE",r.getString("digest"),parsed.digest(),sha(text.substring(c.startOffset(),c.endOffset()).getBytes(StandardCharsets.UTF_8)));
        }
    }
    static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw invalid();}}
}
