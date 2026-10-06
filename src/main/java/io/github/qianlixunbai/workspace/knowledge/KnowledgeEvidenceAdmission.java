package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import io.github.qianlixunbai.workspace.task.TaskResult;
import org.springframework.stereotype.Component;
import java.util.*;

/** Search and authoritative capture share one Store monitor. No task/provider work occurs here. */
@Component
public final class KnowledgeEvidenceAdmission {
    private final KnowledgeStore store;
    private final KnowledgeLexicalIndex index;
    public KnowledgeEvidenceAdmission(KnowledgeStore store, KnowledgeLexicalIndex index) { this.store=store; this.index=index; }
    public record Evidence(String label, TaskResult.KnowledgeCitation citation, String text, String representationDigest) {
        @Override public String toString() { return "Evidence[redacted]"; }
    }
    public record Snapshot(List<Evidence> items) {
        public Snapshot { items=List.copyOf(items); }
        @Override public String toString() { return "EvidenceSnapshot[count="+items.size()+"]"; }
    }
    public Snapshot capture(String query) {
        if (!ClientIdentity.current().equals(ClientIdentity.NATIVE)) throw new WorkspaceException(ErrorCode.POLICY_DENIED,"CAPABILITY");
        synchronized(store) {
            var hits=index.search(query,10).hits();
            if(hits.isEmpty())throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"KNOWLEDGE_NO_EVIDENCE");
            try {
                var refs=store.searchCorpus();
                Map<String,KnowledgeParser.Representation> sources=new HashMap<>();
                Map<String,List<LexicalChunker.Chunk>> chunks=new HashMap<>();
                List<Evidence> evidence=new ArrayList<>();
                Set<TaskResult.KnowledgeCitation> identities=new HashSet<>();
                for(var hit:hits) {
                    var ref=refs.stream().filter(r->r.documentId().equals(hit.documentId())&&r.sourceRevision().equals(hit.sourceRevision()))
                            .findFirst().orElseThrow(KnowledgeEvidenceAdmission::mismatch);
                    var source=sources.computeIfAbsent(ref.documentId(),id->store.searchSource(ref));
                    if(!source.digest().equals(ref.digest())||!hit.sourceType().equals(ref.sourceType())
                            ||!hit.title().equals(LexicalSnippet.bounded(ref.title(),160)))throw mismatch();
                    var boundaries=chunks.computeIfAbsent(ref.documentId(),id->{List<LexicalChunker.Chunk> list=new ArrayList<>();LexicalChunker.chunks(source,list::add);return list;});
                    var chunk=boundaries.stream().filter(c->c.startOffset()==hit.startOffset()&&c.endOffset()==hit.endOffset()
                            &&c.startLine()==hit.startLine()&&c.endLine()==hit.endLine()
                            &&Objects.equals(LexicalSnippet.bounded(c.heading(),96),hit.heading())).findFirst().orElseThrow(KnowledgeEvidenceAdmission::mismatch);
                    var locator=source.locators().stream().filter(l->l.startOffset()<=chunk.startOffset()&&l.endOffset()>=chunk.endOffset()
                            &&l.startLine()<=chunk.startLine()&&l.endLine()>=chunk.endLine()&&Objects.equals(l.heading(),chunk.heading()))
                            .findFirst().orElseThrow(KnowledgeEvidenceAdmission::mismatch);
                    var citation=new TaskResult.KnowledgeCitation(ref.documentId(),ref.title(),ref.sourceRevision(),ref.sourceType(),
                            chunk.startOffset(),chunk.endOffset(),chunk.startLine(),chunk.endLine(),chunk.heading(),
                            new TaskResult.KnowledgeLocator(locator.type(),locator.startLine(),locator.endLine(),locator.startOffset(),locator.endOffset(),locator.section()));
                    if(!identities.add(citation))throw mismatch();
                    evidence.add(new Evidence("S"+(evidence.size()+1),citation,source.text().substring(chunk.startOffset(),chunk.endOffset()),source.digest()));
                }
                return new Snapshot(evidence);
            } catch(WorkspaceException e) {
                index.rebuild();
                throw mismatch();
            }
        }
    }
    private static WorkspaceException mismatch() { return new WorkspaceException(ErrorCode.KNOWLEDGE_INDEX_NOT_READY,"KNOWLEDGE_EVIDENCE"); }
}
