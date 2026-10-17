package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.persistence.PostgresqlWorkspace;
import java.sql.*;

/** Fixed Knowledge namespace, independently serialized from Memory/Conversation. */
final class KnowledgePostgresql {
    private KnowledgePostgresql() {}
    static String sql(String sql) {
        return sql.replaceAll("\\b(documents|revisions|jobs|deletes)\\b", "knowledge_$1")
                .replace("COLLATE BINARY", "COLLATE \"C\"");
    }
    static void verify(Connection db) throws SQLException {
        PostgresqlWorkspace.verify(db);
        try(var s=db.createStatement();var r=s.executeQuery("SELECT format FROM workspace.knowledge_control WHERE singleton=1")) {
            if(!r.next()||r.getInt(1)!=1||r.next())throw new SQLException();
        }
    }
    static void begin(Connection db,boolean write)throws SQLException {
        try(var s=db.createStatement()) {
            s.execute(write?"BEGIN":"BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY");
            s.execute("SET LOCAL lock_timeout='3s'");s.execute("SET LOCAL statement_timeout='30s'");
            if(write) {
                s.execute("SELECT pg_advisory_xact_lock(170017,3)");
                try(var r=s.executeQuery("SELECT singleton FROM workspace.knowledge_control WHERE singleton=1 FOR UPDATE")) {
                    if(!r.next())throw new SQLException();
                }
            }
        }catch(SQLException e){try(var s=db.createStatement()){s.execute("ROLLBACK");}catch(SQLException ignored){db.close();}throw e;}
    }
}
