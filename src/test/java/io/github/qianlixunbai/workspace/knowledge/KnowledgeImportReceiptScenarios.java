package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Deterministic queue handoff with synthetic in-memory SQL; no Store/Profile construction. */
public final class KnowledgeImportReceiptScenarios {
    private static final String DOC=new UUID(0,1).toString(),REQUEST=new UUID(0,2).toString(),NEXT=new UUID(0,3).toString();
    private static void exec(Connection db,String sql,Object... values)throws SQLException {
        try(var s=db.prepareStatement(sql)){for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);s.execute();}
    }
    private static Connection database()throws SQLException {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(var s=db.createStatement()){s.execute("PRAGMA foreign_keys=ON");for(String ddl:KnowledgeStore.SCHEMA_DDL)s.execute(ddl);}
        exec(db,"INSERT INTO documents VALUES(?,'Synthetic.txt','ACTIVE',1,NULL,'2026-11-12T00:00:00Z','2026-11-12T00:00:00Z',NULL)",DOC);
        exec(db,"INSERT INTO jobs VALUES(?,?,'PENDING',NULL,1,'Synthetic.txt','TXT',1,1,'2026-11-12T00:00:00Z')",REQUEST,DOC);
        exec(db,"UPDATE documents SET last_job=? WHERE id=?",REQUEST,DOC);
        return db;
    }
    private static KnowledgeStore.Job receipt(Connection db) {
        try(var s=db.prepareStatement("SELECT * FROM jobs WHERE id=?")) {
            s.setString(1,REQUEST);
            try(var r=s.executeQuery()) {
                if(!r.next())throw new WorkspaceException(ErrorCode.KNOWLEDGE_NOT_FOUND,"KNOWLEDGE");
                return new KnowledgeStore.Job(REQUEST,r.getString("doc_id"),r.getString("state"),r.getString("error"),
                        r.getString("state").equals("READY")?r.getString("revision"):null);
            }
        }catch(SQLException e){throw new AssertionError(e);}
    }
    private static void finishAndRetry(Connection db) {
        try {
            exec(db,"UPDATE jobs SET state='FAILED',error='KNOWLEDGE_INGESTION_FAILED' WHERE id=?",REQUEST);
            // Worker cleanup has drained; a retry replaces last_job, then another
            // admission legitimately reclaims the finished request before its caller resumes.
            exec(db,"INSERT INTO jobs VALUES(?,?,'PENDING',NULL,1,'Retry.txt','TXT',1,1,'2026-11-12T00:00:01Z')",NEXT,DOC);
            exec(db,"UPDATE documents SET last_job=? WHERE id=?",NEXT,DOC);
            KnowledgeStore.pruneFinishedJobs(db,false,Set.of(NEXT));
        }catch(SQLException e){throw new AssertionError(e);}
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void missing(Supplier<KnowledgeStore.Job> read) {
        try{read.get();throw new AssertionError("Expected retired request to be absent");}
        catch(WorkspaceException e){require(e.error().code()==ErrorCode.KNOWLEDGE_NOT_FOUND,"Wrong missing-job outcome");}
    }
    private static void immediateCompletionAndRetry()throws Exception {
        try(var db=database()) {
            Executor immediate=Runnable::run;
            // Original order, with the worker and competing admissions completing first.
            immediate.execute(()->finishAndRetry(db));missing(()->receipt(db));
        }
        try(var db=database()) {
            var accepted=KnowledgeIngestion.enqueueWithReceipt(()->receipt(db),Runnable::run,()->finishAndRetry(db));
            missing(()->receipt(db)); // The fix did not extend job retention or suppress pruning.
            require(accepted.requestId().equals(REQUEST)&&accepted.documentId().equals(DOC),"Admission identity lost");
            require(accepted.state().equals("PENDING")&&accepted.errorCode()==null&&accepted.sourceRevision()==null,
                    "Response must be the truthful pre-submission snapshot, not invented completion");
        }
    }
    private static void rejectionIsNotAcceptance()throws Exception {
        try(var db=database()) {
            var rejection=new RejectedExecutionException("Synthetic full queue");
            try {
                KnowledgeIngestion.enqueueWithReceipt(()->receipt(db),work->{throw rejection;},()->{throw new AssertionError("Rejected work ran");});
                throw new AssertionError("Rejected submission returned acceptance");
            }catch(RejectedExecutionException e){require(e==rejection,"Queue failure was replaced");}
            require(receipt(db).state().equals("PENDING"),"Queue helper must leave failure cleanup to receive()");
        }
    }
    public static void main(String[] args)throws Exception {
        immediateCompletionAndRetry();rejectionIsNotAcceptance();
        System.out.println("PASS: legacy receipt loss reproduced; 2 synthetic queue/SQLite scenarios passed");
    }
}
