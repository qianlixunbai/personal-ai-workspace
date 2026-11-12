package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import java.sql.*;
import java.util.*;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeLimits.*;

/** Bounded metadata lookup, independent of source availability and the lexical index. */
final class KnowledgeDocumentListing {
    private KnowledgeDocumentListing() {}

    static KnowledgeStore.Page read(Connection db,boolean postgresql,String status,int page,String query)throws SQLException {
        if(!Set.of("ACTIVE","ARCHIVED").contains(status)||page<0||page>=DOCUMENTS/PAGE_SIZE)
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"KNOWLEDGE");
        validate(query);
        String needle=query.toLowerCase(Locale.ROOT);
        List<KnowledgeDocument> items=new ArrayList<>();
        int seen=0,total=0;
        // One statement supplies titles, ordering and processing state for both
        // backends. Filter before pagination; never load revision text or files.
        String sql="SELECT d.*,j.state AS job_state FROM documents d LEFT JOIN jobs j ON j.id=d.last_job WHERE d.status=? ORDER BY d.updated DESC,d.id LIMIT ?";
        try(var s=db.prepareStatement(postgresql?KnowledgePostgresql.sql(sql):sql)) {
            s.setString(1,status);s.setInt(2,DOCUMENTS+1);
            try(var r=s.executeQuery()) {
                while(r.next()) {
                    if(++seen>DOCUMENTS)throw new WorkspaceException(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED,"KNOWLEDGE");
                    if(!r.getString("title").toLowerCase(Locale.ROOT).contains(needle))continue;
                    int index=total++;
                    if(index<page*PAGE_SIZE||items.size()>=PAGE_SIZE)continue;
                    String current=r.getString("current_revision"),request=r.getString("last_job"),state=r.getString("job_state");
                    if(request==null)state=current==null?"INTERRUPTED":"READY";
                    else if(state==null)throw new SQLException("Missing document job");
                    items.add(new KnowledgeDocument(r.getString("id"),r.getString("title"),r.getString("status"),r.getString("version"),
                            current,r.getString("created"),r.getString("updated"),state,request));
                }
            }
        }
        return new KnowledgeStore.Page(List.copyOf(items),total,page,PAGE_SIZE);
    }

    private static void validate(String query) {
        if(query==null||query.codePointCount(0,query.length())>128)
            throw new WorkspaceException(ErrorCode.KNOWLEDGE_SEARCH_INVALID,"KNOWLEDGE");
        for(int i=0;i<query.length();) {
            int cp=query.codePointAt(i);
            if(Character.isISOControl(cp)||cp>=0xD800&&cp<=0xDFFF)
                throw new WorkspaceException(ErrorCode.KNOWLEDGE_SEARCH_INVALID,"KNOWLEDGE");
            i+=Character.charCount(cp);
        }
    }
}
