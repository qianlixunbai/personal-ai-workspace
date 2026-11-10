package io.github.qianlixunbai.workspace.persistence;

import java.sql.*;
import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;

/** Durable identity plus a once-only receipt committed with both domains. No DDL or grants.
 * Physical cluster clones can share system identifiers: this is not remote attestation.
 * Missing identity privileges or target extension fail closed, never fall back to names. */
public final class MaintenanceTarget {
    private MaintenanceTarget() {}
    public record Binding(String systemIdentifier, long databaseOid, long roleOid, long ownerOid,
                          long schemaOid, long receiptOid, String operation) {
        public Binding {
            if (systemIdentifier == null || !systemIdentifier.matches("[1-9][0-9]{0,19}")
                    || databaseOid <= 0 || roleOid <= 0 || ownerOid <= 0 || schemaOid <= 0 || receiptOid <= 0
                    || operation == null || !operation.matches("[0-9a-f]{32}")) throw new IllegalArgumentException();
        }
        @Override public String toString() { return "MaintenanceTarget[private]"; }
    }

    static Binding identify(Connection db, String operation) throws SQLException {
        try (var s = db.createStatement(); var r = s.executeQuery("""
                SELECT c.system_identifier::text,d.oid,r.oid,d.datdba,n.oid,t.oid,
                  t.relkind='r' AND NOT t.relrowsecurity AND NOT t.relforcerowsecurity
                  AND t.relpersistence='p' AND t.relowner<>r.oid AND n.nspowner<>r.oid
                  AND NOT pg_has_role(r.oid,t.relowner,'MEMBER')
                  AND NOT pg_has_role(r.oid,n.nspowner,'MEMBER')
                  AND NOT pg_has_role(r.oid,d.datdba,'MEMBER')
                  AND NOT has_any_column_privilege(current_user,t.oid,'UPDATE')
                  AND NOT has_table_privilege(current_user,t.oid,'DELETE')
                  AND NOT has_table_privilege(current_user,t.oid,'TRUNCATE')
                  AND has_table_privilege(current_user,t.oid,'SELECT')
                  AND has_table_privilege(current_user,t.oid,'INSERT')
                FROM pg_catalog.pg_control_system() c,pg_catalog.pg_database d,pg_catalog.pg_roles r,
                  pg_catalog.pg_namespace n,pg_catalog.pg_class t
                WHERE d.datname=current_database() AND r.rolname=current_user
                  AND n.nspname='workspace' AND t.relnamespace=n.oid AND t.relname='maintenance_import'
                """)) {
            if (!r.next() || !r.getBoolean(7)) throw new SQLException();
            var binding = new Binding(r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4),
                    r.getLong(5),r.getLong(6),operation);
            if (r.next()) throw new SQLException();
            return binding;
        }
    }

    static void insert(Connection db, Binding binding) throws SQLException {
        if (!binding.equals(identify(db,binding.operation))) throw new SQLException();
        try (var s=db.createStatement(); var r=s.executeQuery("SELECT count(*) FROM workspace.maintenance_import")) {
            if (!r.next() || r.getLong(1)!=0) throw new SQLException();
        }
        try (var s=db.prepareStatement("INSERT INTO workspace.maintenance_import(singleton,operation) VALUES(1,?)")) {
            s.setString(1,binding.operation); if(s.executeUpdate()!=1) throw new SQLException();
        }
    }

    static boolean matches(Connection db, Binding binding) throws SQLException {
        if (!binding.equals(identify(db,binding.operation))) return false;
        try (var s=db.createStatement();var r=s.executeQuery("SELECT singleton,operation FROM workspace.maintenance_import")) {
            return r.next() && r.getInt(1)==1 && binding.operation.equals(r.getString(2)) && !r.next();
        }
    }

    /** Retain table-level write exclusion while local configuration is prepared/published.
     * New connections perform the complete content check; this never imports or alters data. */
    static Connection holdVerified(DataSource target, SqliteCombinedMigration.Expected expected) throws IOException {
        return hold(target,expected,true);
    }
    /** Inspection after a writable Runtime session must preserve later business data. */
    static Connection holdCurrentBinding(DataSource target,SqliteCombinedMigration.Expected expected)throws IOException {
        return hold(target,expected,false);
    }
    private static Connection hold(DataSource target,SqliteCombinedMigration.Expected expected,boolean importedContent)throws IOException {
        Connection db=null;
        try {
            if(expected==null || expected.binding()==null) throw new SQLException();
            db=target.getConnection(); if(!db.getAutoCommit()) throw new SQLException();
            PostgresqlWorkspace.verify(db); db.setAutoCommit(false);
            try(var s=db.createStatement()) {
                s.execute("SET LOCAL lock_timeout='3s'"); s.execute("SET LOCAL statement_timeout='30s'");
                // Table locks include non-cooperating SQL writers; advisory locks alone do not.
                for(String table:List.of("write_lock","knowledge_control","maintenance_import","sqlite_import",
                        "knowledge_sqlite_import","memory_items","conversations","conversation_turns",
                        "conversation_messages","conversation_memory_selections","knowledge_documents",
                        "knowledge_revisions","knowledge_jobs","knowledge_deletes","knowledge_chunks","knowledge_index_metadata"))
                    s.execute("LOCK TABLE workspace."+table+(List.of("maintenance_import","sqlite_import","knowledge_sqlite_import").contains(table)
                            ? " IN ACCESS SHARE MODE" : " IN SHARE MODE"));
            }
            if(importedContent)verifyHeld(db,expected);
            else if(!matches(db,expected.binding()))throw new SQLException();
            return db;
        }catch(Exception failure) {
            if(db!=null)try{db.close();}catch(SQLException ignored){}
            throw new IOException("Bound target verification unavailable; no configuration write");
        }
    }

    static void verifyHeld(Connection db,SqliteCombinedMigration.Expected expected)throws Exception {
        if(db.isClosed()||db.getAutoCommit()||!matches(db,expected.binding())
                ||!SqliteWorkspaceMigration.matchesCommitted(db,expected.workspace())
                ||!io.github.qianlixunbai.workspace.knowledge.SqliteKnowledgeMigration.matchesCommitted(db,expected.knowledge()))throw new SQLException();
    }
}
