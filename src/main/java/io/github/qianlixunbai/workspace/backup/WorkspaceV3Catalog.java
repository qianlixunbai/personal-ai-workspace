package io.github.qianlixunbai.workspace.backup;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.*;
import static io.github.qianlixunbai.workspace.backup.UnifiedPostgresqlBackup.*;

/** Frozen PG17 Workspace V1/V2 structure, independent of any archive's manifest.
 * The resource is generated only from reviewed build migrations in a new synthetic database.
 * ACL/owner/OIDs are deliberately excluded; definitions, dependencies and object identities are not.
 */
final class WorkspaceV3Catalog {
    static final String SCOPE="WORKSPACE_KNOWLEDGE_ONLY";
    static final List<String> EXCLUDED=List.of("workspace.knowledge_chunks","workspace.knowledge_deletes",
        "workspace.knowledge_index_metadata","workspace.knowledge_sqlite_import","workspace.sqlite_import");
    static final List<String> TABLES=List.of("conversation_memory_selections","conversation_messages","conversation_turns",
        "conversations","flyway_schema_history","knowledge_chunks","knowledge_control","knowledge_deletes",
        "knowledge_documents","knowledge_index_metadata","knowledge_jobs","knowledge_revisions","knowledge_sqlite_import",
        "memory_items","sqlite_import","storage_metadata","write_lock").stream().map(t->"workspace."+t).toList();
    static final Set<String> DATA=new TreeSet<>(TABLES.stream().filter(t->!EXCLUDED.contains(t)).toList());
    // No EXPLAIN or execution of catalog expressions: even validation must not invoke an unknown function.
    static final List<String> QUERIES=List.of(
        "SELECT c.relname,c.relkind,c.relpersistence,c.relrowsecurity,c.relforcerowsecurity,c.relreplident,c.reloptions,am.amname,t.spcname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_am am ON am.oid=c.relam LEFT JOIN pg_tablespace t ON t.oid=c.reltablespace WHERE n.nspname='workspace' ORDER BY 1",
        "SELECT c.relname,a.attnum,a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attisdropped,a.attidentity,a.attgenerated,co.collname,cn.nspname,pg_get_expr(d.adbin,d.adrelid,false) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum LEFT JOIN pg_collation co ON co.oid=a.attcollation LEFT JOIN pg_namespace cn ON cn.oid=co.collnamespace WHERE n.nspname='workspace' AND c.relkind='r' ORDER BY 1,2",
        "SELECT c.relname,x.conname,x.contype,x.convalidated,x.condeferrable,x.condeferred,pg_get_constraintdef(x.oid,false) FROM pg_constraint x JOIN pg_class c ON c.oid=x.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='workspace' ORDER BY 1,2",
        "SELECT c.relname,pg_get_indexdef(i.indexrelid,0,false),i.indisvalid,i.indisready,i.indislive,i.indisreplident,i.indisclustered FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='workspace' ORDER BY 1",
        "SELECT c.relname,CASE WHEN t.tgisinternal THEN '<internal>' ELSE t.tgname END,t.tgisinternal,t.tgenabled,CASE WHEN NOT t.tgisinternal THEN pg_get_triggerdef(t.oid,false) END,pn.nspname,p.proname,t.tgtype,x.conname,t.tgdeferrable,t.tginitdeferred,t.tgattr::text,encode(t.tgargs,'hex') FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_proc p ON p.oid=t.tgfoid JOIN pg_namespace pn ON pn.oid=p.pronamespace LEFT JOIN pg_constraint x ON x.oid=t.tgconstraint WHERE n.nspname='workspace' ORDER BY 1,2,7,9",
        "SELECT p.proname,pg_get_function_identity_arguments(p.oid),p.prokind,p.prosecdef,p.proleakproof,p.proconfig,l.lanname,pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang WHERE n.nspname='workspace' ORDER BY 1,2",
        "SELECT t.typname,t.typtype,t.typcategory,t.typnotnull,pg_get_expr(t.typdefaultbin,0) FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='workspace' ORDER BY 1",
        "SELECT c.relname,r.rulename,pg_get_ruledef(r.oid,false) FROM pg_rewrite r JOIN pg_class c ON c.oid=r.ev_class JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='workspace' ORDER BY 1,2",
        "SELECT c.relname,p.polname,p.polcmd,p.polpermissive,pg_get_expr(p.polqual,p.polrelid),pg_get_expr(p.polwithcheck,p.polrelid) FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='workspace' ORDER BY 1,2",
        // Follow internally/automatically owned objects (defaults, constraints, triggers, array types).
        // Compare every dependency, including external public/extension/function/type dependencies.
        "WITH RECURSIVE owned(classid,objid) AS (SELECT 'pg_namespace'::regclass,oid FROM pg_namespace WHERE nspname='workspace' UNION SELECT d.classid,d.objid FROM pg_depend d JOIN owned o ON d.refclassid=o.classid AND d.refobjid=o.objid WHERE d.deptype IN ('a','i') OR o.classid='pg_namespace'::regclass) SELECT a.type,a.schema,a.identity,d.objsubid,d.deptype,b.type,b.schema,b.identity,d.refobjsubid FROM owned o JOIN pg_depend d ON d.classid=o.classid AND d.objid=o.objid CROSS JOIN LATERAL pg_identify_object(d.classid,d.objid,d.objsubid) a CROSS JOIN LATERAL pg_identify_object(d.refclassid,d.refobjid,d.refobjsubid) b WHERE a.schema IS DISTINCT FROM 'pg_toast' AND NOT (d.classid='pg_trigger'::regclass AND EXISTS(SELECT FROM pg_trigger t WHERE t.oid=d.objid AND t.tgisinternal)) ORDER BY 1,2,3,4,5,6,7,8,9",
        "SELECT c.relname,pn.nspname,p.relname FROM pg_inherits i JOIN pg_class c ON c.oid=i.inhrelid JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_class p ON p.oid=i.inhparent JOIN pg_namespace pn ON pn.oid=p.relnamespace WHERE n.nspname='workspace' ORDER BY 1,2,3",
        "SELECT e.stxname,pg_get_statisticsobjdef(e.oid) FROM pg_statistic_ext e JOIN pg_namespace n ON n.oid=e.stxnamespace WHERE n.nspname='workspace' ORDER BY 1",
        "SELECT p.pubname,c.relname,pg_get_expr(r.prqual,r.prrelid),r.prattrs::text FROM pg_publication_rel r JOIN pg_publication p ON p.oid=r.prpubid JOIN pg_class c ON c.oid=r.prrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='workspace' ORDER BY 1,2"
    );
    static List<List<List<String>>> catalog(Connection db) throws Exception {
        session(db);var queries=new ArrayList<List<List<String>>>();
        for(String query:QUERIES){var rows=new ArrayList<List<String>>();
            try(var s=db.createStatement();var r=s.executeQuery(query)){while(r.next()){
                require(rows.size()<4096);var cells=new ArrayList<String>();
                for(int i=1;i<=r.getMetaData().getColumnCount();i++)cells.add(r.getString(i));rows.add(cells);
            }}queries.add(rows);
        }return queries;
    }
    static String digest(List<List<List<String>>> catalog) {
        var hash=sha256();
        for(var query:catalog){for(var row:query){for(var cell:row){
            byte[] bytes=cell==null?null:cell.getBytes(StandardCharsets.UTF_8);
            hash.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)hash.update(bytes);
        }hash.update((byte)1);}hash.update((byte)2);}return HexFormat.of().formatHex(hash.digest());
    }
    static tools.jackson.databind.JsonNode expected() throws Exception {
        try(var input=WorkspaceV3Catalog.class.getResourceAsStream("/backup/workspace-v3-pg17.json")){
            require(input!=null);return JSON.readTree(input);
        }
    }
    static String verify(Connection db) throws Exception {
        var actual=catalog(db);var expected=expected();
        require(JSON.valueToTree(actual).equals(expected.get("catalog")));
        return digest(actual);
    }
    static void emptyExcluded(Connection db,boolean rebuilt) throws Exception {
        for(String table:EXCLUDED)if(!rebuilt||!Set.of("workspace.knowledge_chunks","workspace.knowledge_index_metadata").contains(table))
            require(scalar(db,"SELECT count(*) FROM "+table)==0);
    }
    static List<String> toc(String text) throws Exception {
        var entries=new ArrayList<String>();
        for(String line:text.split("\\R")){
            if(line.isBlank()||line.startsWith(";"))continue;
            require(line.matches("[0-9]+; [0-9]+ [0-9]+ .+ [a-z][a-z0-9_]{0,62}"));
            entries.add(line.replaceFirst("^[0-9]+; [0-9]+ [0-9]+ ","").replaceFirst(" [a-z][a-z0-9_]{0,62}$",""));
        }
        require(entries.size()<256&&new HashSet<>(entries).size()==entries.size());Collections.sort(entries);return entries;
    }
    static void verifyToc(String text) throws Exception {require(JSON.valueToTree(toc(text)).equals(expected().get("toc")));}
}
