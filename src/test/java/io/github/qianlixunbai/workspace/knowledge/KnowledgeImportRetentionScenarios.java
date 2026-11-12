package io.github.qianlixunbai.workspace.knowledge;

import java.sql.*;
import java.util.*;

/** Standalone SQL-only regression: synthetic :memory: DB, no Profile, server or JUnit dependencies. */
public final class KnowledgeImportRetentionScenarios {
    private static final String LEGACY_PRUNE="DELETE FROM jobs WHERE state NOT IN ('PENDING','PARSING') AND id NOT IN (SELECT last_job FROM documents WHERE last_job IS NOT NULL)";
    private static Connection database()throws Exception {
        var db=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(var s=db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            for(String ddl:KnowledgeStore.SCHEMA_DDL)s.execute(ddl);
        }
        return db;
    }
    private static String id(int n){return new UUID(0,n).toString();}
    private static void exec(Connection db,String sql,Object... args)throws Exception {
        try(var s=db.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.execute();}
    }
    private static void document(Connection db,int doc)throws Exception {
        exec(db,"INSERT INTO documents VALUES(?,'Synthetic.txt','ACTIVE',1,NULL,'2026-11-12T00:00:00Z','2026-11-12T00:00:00Z',NULL)",id(doc));
    }
    private static void job(Connection db,int request,int doc,String state,boolean latest)throws Exception {
        exec(db,"INSERT INTO jobs VALUES(?,?,?,NULL,1,'Synthetic.txt','TXT',1,1,'2026-11-12T00:00:00Z')",id(request),id(doc),state);
        if(latest)exec(db,"UPDATE documents SET last_job=? WHERE id=?",id(request),id(doc));
    }
    private static boolean exists(Connection db,int request)throws Exception {
        try(var s=db.prepareStatement("SELECT 1 FROM jobs WHERE id=?")){s.setString(1,id(request));try(var r=s.executeQuery()){return r.next();}}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void cancellationRetryAndLaterAdmissionRetainRecoveryIdentity()throws Exception {
        // Interleave: A is cancelled while its upload is still active; retry B
        // replaces last_job; a later admission runs the obsolete-job collection.
        try(var db=database()) {
            document(db,1);job(db,101,1,"CANCELLED",true);job(db,102,1,"PENDING",true);
            exec(db,LEGACY_PRUNE);
            require(!exists(db,101),"Legacy SQL must reproduce lost cleanup identity");
        }
        try(var db=database()) {
            document(db,1);job(db,101,1,"CANCELLED",true);job(db,102,1,"PENDING",true);
            Set<String> active=new HashSet<>(Set.of(id(101),id(102)));
            KnowledgeStore.pruneFinishedJobs(db,false,active);
            require(exists(db,101),"Cancelled upload must remain addressable by cleanup");
            require(exists(db,102),"Retry must retain its own identity");
            active.remove(id(101)); // The production owner releases only after cleanup succeeds.
            KnowledgeStore.pruneFinishedJobs(db,false,active);
            require(!exists(db,101),"Drained obsolete job must be reclaimable");
            require(exists(db,102),"Reclaiming old job must not delete retry");
        }
    }
    private static void failedCleanupAndExistingStateBoundariesRemainRecoverable()throws Exception {
        try(var db=database()) {
            document(db,1);document(db,2);
            job(db,201,1,"FAILED",false);job(db,202,1,"CANCELLED",true);
            job(db,203,2,"READY",false);job(db,204,2,"PARSING",false);job(db,205,2,"PENDING",true);
            Set<String> active=Set.of(id(201)); // Failed cleanup has not released ownership.
            exec(db,"BEGIN IMMEDIATE");
            KnowledgeStore.pruneFinishedJobs(db,false,active);
            require(exists(db,201),"Failed cleanup must preserve durable retry/recovery identity");
            require(exists(db,202),"Latest terminal outcome must stay readable");
            require(!exists(db,203),"Drained obsolete READY job must be reclaimed");
            require(exists(db,204)&&exists(db,205),"Pending/parsing jobs must survive");
            exec(db,"ROLLBACK");
            require(exists(db,203),"Failed admission transaction must restore pruned jobs");
        }
    }
    public static void main(String[] args)throws Exception {
        cancellationRetryAndLaterAdmissionRetainRecoveryIdentity();
        failedCleanupAndExistingStateBoundariesRemainRecoverable();
        System.out.println("PASS: legacy loss reproduced; 2 synthetic SQLite retention scenarios passed");
    }
}
