package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import java.sql.*;
import java.util.*;

/** Exercises the production metadata query against synthetic in-memory SQLite only. */
public final class KnowledgeDocumentListingScenarios {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void add(Connection db,int number,String title,String status,String state)throws SQLException {
        String id=new UUID(0,number).toString(),job=new UUID(1,number).toString();
        try(var s=db.prepareStatement("INSERT INTO documents VALUES(?,?,?,1,NULL,'2026-11-12T00:00:00Z','2026-11-12T00:00:00Z',?)")) {
            s.setString(1,id);s.setString(2,title);s.setString(3,status);s.setString(4,job);s.execute();
        }
        try(var s=db.prepareStatement("INSERT INTO jobs VALUES(?,?,?,NULL,1,?,'TXT',1,1,'2026-11-12T00:00:00Z')")) {
            s.setString(1,job);s.setString(2,id);s.setString(3,state);s.setString(4,title);s.execute();
        }
    }
    private static KnowledgeStore.Page list(Connection db,String status,int page,String query)throws SQLException {
        return KnowledgeDocumentListing.read(db,false,status,page,query);
    }
    public static void main(String[] args)throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try(var s=db.createStatement()){s.execute("PRAGMA foreign_keys=ON");for(String ddl:KnowledgeStore.SCHEMA_DDL)s.execute(ddl);}
            for(int i=1;i<=20;i++)add(db,i,"Other "+i+".txt","ACTIVE","PENDING");
            String[] states={"PENDING","PARSING","FAILED","CANCELLED","INTERRUPTED"};
            for(int i=21;i<=43;i++)add(db,i,"Budget 预算 "+i+".txt","ACTIVE",states[(i-21)%states.length]);
            add(db,44,"CAFÉ😀%_[draft].txt","ARCHIVED","FAILED");
            add(db,45,"Budget 预算 archive.txt","ARCHIVED","CANCELLED");
            var first=list(db,"ACTIVE",0,"budget 预算");var second=list(db,"ACTIVE",1,"BUDGET 预算");
            require(first.total()==23&&first.items().size()==20&&second.total()==23&&second.items().size()==3,"Filter must precede pagination across the complete status set");
            require(first.items().getFirst().documentId().equals(new UUID(0,21).toString())&&second.items().getFirst().documentId().equals(new UUID(0,41).toString()),"Stable metadata ordering lost");
            require(new HashSet<>(first.items().stream().map(KnowledgeDocument::processingState).toList()).equals(Set.of(states)),"Non-ready documents disappeared");
            require(list(db,"ACTIVE",0,"").total()==43&&list(db,"ACTIVE",2,"").items().size()==3,"Unfiltered list compatibility lost");
            require(list(db,"ARCHIVED",0,"budget").total()==1&&list(db,"ACTIVE",0,"archive").total()==0,"Status scope leaked");
            require(list(db,"ARCHIVED",0,"café😀%_[").total()==1&&list(db,"ARCHIVED",0,"%_").total()==1&&list(db,"ARCHIVED",0,"%").total()==1,"Unicode or literal punctuation lookup failed");
            require(list(db,"ACTIVE",0,"' OR 1=1 --").total()==0&&list(db,"ACTIVE",2,"budget").items().isEmpty(),"Unmatched query or empty page changed meaning");
            for(String invalid:Arrays.asList(null,"x".repeat(129),"😀".repeat(129),"line\nfeed","\u0085","\uD800")) {
                try{list(db,"ACTIVE",0,invalid);throw new AssertionError("Invalid query accepted");}
                catch(WorkspaceException e){require(e.error().code()==ErrorCode.KNOWLEDGE_SEARCH_INVALID,"Wrong query failure");}
            }
            require(list(db,"ACTIVE",0,"😀".repeat(128)).total()==0,"Unicode limit counts UTF-16 units instead of code points");
            try{list(db,"ALL",0,"");throw new AssertionError("Broadened status accepted");}
            catch(WorkspaceException e){require(e.error().code()==ErrorCode.INVALID_REQUEST,"Wrong status failure");}
        }
        System.out.println("PASS: metadata pagination/status, Unicode/literal names, and bounded invalid-input scenarios (3 groups)");
    }
}
