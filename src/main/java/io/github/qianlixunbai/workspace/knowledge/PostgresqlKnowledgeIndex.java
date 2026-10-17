package io.github.qianlixunbai.workspace.knowledge;

import java.sql.*;
import java.util.*;

/** Bounded, streaming scan over application-owned tokens. No PostgreSQL language tokenizer. */
final class PostgresqlKnowledgeIndex {
    private PostgresqlKnowledgeIndex() {}
    record Row(String document,long revision,int ordinal,int start,int end,int firstLine,int lastLine,String heading,double score){}
    static int build(Connection db,List<KnowledgeStore.SearchRef> refs,java.util.function.Function<KnowledgeStore.SearchRef,KnowledgeParser.Representation> sourceReader,int maxChunks,long maxBytes)throws SQLException{
        try(var s=db.createStatement()){s.execute("DELETE FROM workspace.knowledge_chunks");s.execute("DELETE FROM workspace.knowledge_index_metadata");}
        int[] count={0};long[] size={0};
        try(var insert=db.prepareStatement("INSERT INTO workspace.knowledge_chunks VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")){
            for(var ref:refs){var source=sourceReader.apply(ref);
                LexicalChunker.chunks(source,chunk->{
                    if(++count[0]>maxChunks)throw KnowledgeParser.error(io.github.qianlixunbai.workspace.common.ErrorCode.KNOWLEDGE_INDEX_LIMIT_EXCEEDED);
                    String title=LexicalAnalyzer.stream(ref.title()),heading=LexicalAnalyzer.stream(chunk.heading()==null?"":chunk.heading()),body=LexicalAnalyzer.stream(source.text().substring(chunk.startOffset(),chunk.endOffset()));
                    size[0]+=title.length()+heading.length()+body.length()+128L;
                    if(size[0]>maxBytes)throw KnowledgeParser.error(io.github.qianlixunbai.workspace.common.ErrorCode.KNOWLEDGE_INDEX_LIMIT_EXCEEDED);
                    try{Object[] values={count[0],ref.documentId(),KnowledgeStore.version(ref.sourceRevision()),chunk.ordinal(),chunk.startOffset(),chunk.endOffset(),chunk.startLine(),chunk.endLine(),chunk.heading(),title,heading,body};
                        for(int i=0;i<values.length;i++)insert.setObject(i+1,values[i]);insert.executeUpdate();
                    }catch(SQLException e){throw KnowledgeParser.error(io.github.qianlixunbai.workspace.common.ErrorCode.KNOWLEDGE_INDEX_REBUILD_FAILED);}
                });
            }
        }
        Map<String,String> meta=new TreeMap<>(Map.of("schema","1","chunker",LexicalChunker.VERSION,"analyzer",LexicalAnalyzer.VERSION,"ranking",KnowledgeLexicalIndex.RANKING_VERSION,"engine","postgresql-token-bm25-1",
                "fingerprint",KnowledgeLexicalIndex.fingerprint(refs,"postgresql-token-bm25-1"),"documents",Integer.toString(refs.size()),"chunks",Integer.toString(count[0])));
        try(var insert=db.prepareStatement("INSERT INTO workspace.knowledge_index_metadata VALUES(?,?)")){for(var entry:meta.entrySet()){insert.setString(1,entry.getKey());insert.setString(2,entry.getValue());insert.executeUpdate();}}
        return count[0];
    }
    static Map<String,String> metadata(Connection db)throws SQLException{
        Map<String,String> meta=new TreeMap<>();try(var s=db.createStatement();var r=s.executeQuery("SELECT k,v FROM workspace.knowledge_index_metadata")){
            while(r.next())meta.put(r.getString(1),r.getString(2));}return meta;
    }
    static List<Row> search(Connection db,String fingerprint,Map<String,String> versions,List<String> query,int limit,int maxChunks,long maxBytes)throws SQLException{
        db.setReadOnly(true);db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);db.setAutoCommit(false);
        try{
            var meta=metadata(db);
            if(meta.size()!=8||!meta.entrySet().containsAll(versions.entrySet())||!fingerprint.equals(meta.get("fingerprint")))throw new SQLException();
            long[] documents={0},length={0},size={0};long[] frequencies=new long[query.size()];
            scan(db,r->{
                documents[0]++;String[][] fields=fields(r);int[] counts=counts(fields,query);
                for(String[] field:fields)length[0]+=field.length;
                size[0]+=r.getString("title_tokens").length()+r.getString("heading_tokens").length()+r.getString("body_tokens").length()+128L;
                if(documents[0]>maxChunks||size[0]>maxBytes)throw new SQLException();
                for(int i=0;i<counts.length;i++)if(counts[i]>0)frequencies[i]++;
            });
            if(documents[0]!=Long.parseLong(meta.get("chunks")))throw new SQLException();
            Comparator<Row> order=Comparator.comparingDouble(Row::score).thenComparing(Row::document).thenComparingLong(Row::revision).thenComparingInt(Row::ordinal);
            PriorityQueue<Row> best=new PriorityQueue<>(order.reversed());
            if(documents[0]>0){double average=(double)length[0]/documents[0];
                scan(db,r->{
                    String[][] fields=fields(r);int[] frequency=counts(fields,query);for(int n:frequency)if(n==0)return;
                    int dl=0;for(String[] field:fields)dl+=field.length;
                    double score=0;
                    for(int i=0;i<frequency.length;i++){
                        double idf=Math.max(1e-6,Math.log((documents[0]-frequencies[i]+0.5)/(frequencies[i]+0.5)));
                        score-=idf*(frequency[i]*2.2)/(frequency[i]+1.2*(0.25+0.75*dl/average));
                    }
                    var row=new Row(r.getString("document"),r.getLong("revision"),r.getInt("ordinal"),r.getInt("start_offset"),r.getInt("end_offset"),r.getInt("first_line"),r.getInt("last_line"),r.getString("heading"),score);
                    best.add(row);if(best.size()>limit)best.poll();
                });
            }
            db.commit();List<Row> result=new ArrayList<>(best);result.sort(order);return result;
        }catch(SQLException|RuntimeException e){db.rollback();throw e;}
    }
    private static String[][] fields(ResultSet r)throws SQLException{
        return new String[][]{tokens(r.getString("title_tokens")),tokens(r.getString("heading_tokens")),tokens(r.getString("body_tokens"))};
    }
    private static String[] tokens(String value){return value.isEmpty()?new String[0]:value.split(" ");}
    private static int[] counts(String[][] fields,List<String> query){
        int[] counts=new int[query.size()];int[] weights={4,2,1};
        for(int f=0;f<fields.length;f++)for(String token:fields[f]){int i=query.indexOf(token);if(i>=0)counts[i]+=weights[f];}return counts;
    }
    @FunctionalInterface private interface Reader{void accept(ResultSet r)throws SQLException;}
    private static void scan(Connection db,Reader reader)throws SQLException{
        try(var s=db.createStatement()){s.setFetchSize(128);s.setQueryTimeout(30);try(var r=s.executeQuery("SELECT * FROM workspace.knowledge_chunks ORDER BY id")){while(r.next())reader.accept(r);}}
    }
}
