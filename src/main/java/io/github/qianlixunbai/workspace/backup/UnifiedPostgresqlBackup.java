package io.github.qianlixunbai.workspace.backup;

import io.github.qianlixunbai.workspace.conversation.ConversationStore;
import io.github.qianlixunbai.workspace.knowledge.*;
import io.github.qianlixunbai.workspace.memory.MemoryStore;
import org.postgresql.ds.PGSimpleDataSource;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.json.JsonFactory;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.*;

/** Explicit offline maintenance API. Not a Spring bean, HTTP endpoint or Runtime startup hook.
 * Format 1 is UNENCRYPTED_SYNTHETIC_ONLY; authentication/portable encryption is not implemented.
 * Only backups made locally in the protected maintenance directory are trusted for SQL execution.
 */
public final class UnifiedPostgresqlBackup {
    static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(16384).maxNameLength(128).build()).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final String FORMAT="personal-ai-workspace.postgresql-synthetic-backup";
    private static final Set<String> DERIVED=Set.of("workspace.knowledge_chunks","workspace.knowledge_index_metadata");
    public record Endpoint(String database,String username,String password) {
        public Endpoint {if(!database.matches("[a-z][a-z0-9_]{0,62}")||!username.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("Invalid endpoint");}
        @Override public String toString(){return "PostgresqlEndpoint[private]";}
        public PGSimpleDataSource dataSource() {
            var ds=new PGSimpleDataSource();ds.setServerNames(new String[]{"127.0.0.1"});ds.setPortNumbers(new int[]{5432});
            ds.setDatabaseName(database);ds.setUser(username);ds.setPassword(password);ds.setCurrentSchema("workspace,pg_catalog");
            ds.setConnectTimeout(5);ds.setSocketTimeout(120);return ds;
        }
    }
    public record Table(long rows,String sha256) {}
    public record Snapshot(Map<String,Table> tables,String catalogSha256,Map<String,String> sequences) {}
    public record FileEntry(String path,long bytes,String sha256,String representationSha256) {}
    public record Manifest(String format,int formatVersion,String protection,String status,String createdAt,
        String databaseLogicalIdentity,String serverVersion,int postgresqlMajor,String dumpVersion,String restoreVersion,
        List<String> schemas,List<String> financeVersions,List<String> workspaceVersions,Snapshot snapshot,
        FileEntry archive,List<FileEntry> knowledgeFiles,String indexEngine,boolean rebuildRequired,List<String> excluded) {}
    public record Result(String status,String identity,String windows) {}
    private final Path tools;
    public UnifiedPostgresqlBackup(Path tools) {this.tools=tools.toAbsolutePath().normalize();}

    /** Manual CLI only. Secrets come from an owner-private configuration, never command arguments.
     * backup CONFIG --writers-stopped | restore CONFIG | validate BACKUP_DIRECTORY
     * CONFIG uses the same endpoint/path fields as the local preparation script.
     */
    public static void main(String[] args) throws IOException {
        try {
            require(args.length>=2&&args.length<=3);
            if(args[0].equals("validate")) {
                require(args.length==2);new UnifiedPostgresqlBackup(Path.of("D:/PostgreSQL/bin")).validate(Path.of(args[1]));
                System.out.println("BACKUP_COMPLETE / UNENCRYPTED_SYNTHETIC_ONLY");return;
            }
            Path config=Path.of(args[1]);privatePath(config,false);require(Files.size(config)<16384);
            var c=JSON.readTree(Files.readAllBytes(config));var maintenance=new UnifiedPostgresqlBackup(Path.of(c.path("tools").asString()));
            Path root=Path.of(c.path("maintenanceRoot").asString());privatePath(root,true);
            if(args[0].equals("backup")) {
                require(args.length==3&&args[2].equals("--writers-stopped"));
                maintenance.backup(new Endpoint(c.path("sourceDatabase").asString(),c.path("sourceUsername").asString(),c.path("sourcePassword").asString()),
                    Path.of(c.path("knowledgeRoot").asString()),root.resolve("backup"),true);
                System.out.println("BACKUP_COMPLETE / UNENCRYPTED_SYNTHETIC_ONLY");
            }else {
                require(args[0].equals("restore")&&args.length==2);
                var owner=new Endpoint(c.path("targetDatabase").asString(),c.path("ownerUsername").asString(),c.path("ownerPassword").asString());
                var app=new Endpoint(owner.database,c.path("appUsername").asString(),c.path("appPassword").asString());
                var result=maintenance.restore(root.resolve("backup"),owner,app,c.path("ownershipReceipt").asString(),root.resolve("restored-"+owner.database));
                System.out.println(JSON.writeValueAsString(result));
            }
        }catch(Exception failure){throw stopped("CLI",failure);}
    }

    /** Requires an explicit stopped-writers attestation in addition to DB locks and pinned files.
     * The short-lived source role has SELECT and UPDATE solely for PostgreSQL SHARE locks; no DDL.
     * No source DML is issued. File handles deny write/delete for the entire maintenance window.
     */
    public Manifest backup(Endpoint source,Path knowledgeRoot,Path destination,boolean writersStopped) throws IOException {
        String stage="MAINTENANCE_ADMISSION";
        List<FileChannel> pins=new ArrayList<>();
        try {
            require(writersStopped&&syntheticDatabase(source.database));
            destination=destination.toAbsolutePath().normalize();knowledgeRoot=knowledgeRoot.toAbsolutePath().normalize();
            require(!destination.startsWith(knowledgeRoot)&&!knowledgeRoot.startsWith(destination));
            privatePath(destination.getParent(),true);noLinks(knowledgeRoot);require(Files.isDirectory(knowledgeRoot));
            String dumpVersion=tool("pg_dump",null,List.of("--version"));
            String restoreVersion=tool("pg_restore",null,List.of("--version"));
            Manifest manifest;
            try(var db=source.dataSource().getConnection()) {
                session(db);safeRole(db);require(major(db)==17);checkTools(dumpVersion,restoreVersion,17);
                if(!source.database.equals("personal_ai_workspace_test"))require(value(db,"SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()").matches("workspace-b3:[0-9a-f-]{36}"));
                require(scalar(db,"SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND backend_type='client backend'")==0);
                sql(db,"BEGIN ISOLATION LEVEL REPEATABLE READ");sql(db,"SET LOCAL lock_timeout='3s'");
                require(scalar(db,"SELECT pg_try_advisory_lock(170017,2)::int")==1);
                var tables=tables(db);
                for(String table:tables)sql(db,"LOCK TABLE "+qualified(table)+" IN SHARE MODE NOWAIT");
                safeJobs(db);histories(db);
                var before=snapshot(db);var files=knowledgeFiles(db);
                for(var entry:files)pins.add(pin(resolveKnowledge(knowledgeRoot,entry.path)));
                verifyKnowledge(db,knowledgeRoot,files);
                String exported=value(db,"SELECT pg_export_snapshot()");
                directory(destination);write(destination.resolve("BACKUP_INCOMPLETE"),"UNENCRYPTED_SYNTHETIC_ONLY".getBytes(StandardCharsets.UTF_8));
                stage="DATABASE_ARCHIVE";
                // One complete database archive, one exported PostgreSQL snapshot, both schemas together.
                tool("pg_dump",source,List.of("--format=custom","--no-owner","--no-privileges","--snapshot="+exported,
                    "--file="+destination.resolve("database.dump")));
                forceFile(destination.resolve("database.dump"));
                stage="KNOWLEDGE_FILES";directory(destination.resolve("knowledge"));directory(destination.resolve("knowledge/sources"));
                for(var entry:files) {
                    Path target=resolveKnowledge(destination.resolve("knowledge"),entry.path);
                    if(!Files.exists(target.getParent()))directory(target.getParent());
                    copy(resolveKnowledge(knowledgeRoot,entry.path),target);
                }
                verifyKnowledge(db,destination.resolve("knowledge"),files);verifyKnowledge(db,knowledgeRoot,files);
                require(before.equals(snapshot(db)));
                // Detect changes outside this transaction's snapshot, including nontransactional sequences.
                try(var check=source.dataSource().getConnection()){session(check);require(before.equals(snapshot(check)));}
                stage="MANIFEST_PUBLICATION";
                manifest=new Manifest(FORMAT,1,"UNENCRYPTED_SYNTHETIC_ONLY","BACKUP_COMPLETE",Instant.now().toString(),
                    source.database,value(db,"SHOW server_version"),major(db),dumpVersion,restoreVersion,List.of("public","workspace"),
                    versions(db,"public"),versions(db,"workspace"),before,
                    new FileEntry("database.dump",Files.size(destination.resolve("database.dump")),digest(destination.resolve("database.dump")),null),
                    files,"postgresql-token-bm25-1",true,List.of("Windows tokens and ACL identity","LocalFinanceProfile trusted binding",
                    "Ollama weights, model configuration and caches","historical SQLite backups and lexical.db","PostgreSQL global roles, credentials and grants"));
                write(destination.resolve("manifest.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                validateContents(destination,manifest);
                sql(db,"ROLLBACK");
            }
            // Connection/transaction cleanup must succeed before publishing the durable commit marker.
            // Keep the incomplete evidence file. Only the final marker authorizes use.
            publish(destination.resolve("BACKUP_COMPLETE"),digest(destination.resolve("manifest.json")).getBytes(StandardCharsets.US_ASCII));
            return manifest;
        }catch(Exception failure){throw stopped(stage,failure);}
        finally{for(var handle:pins)try{handle.close();}catch(IOException ignored){}}
    }

    /** Integrity is accidental-damage detection, NOT authenticity. Caller must trust the local source.
     * All bytes are checked before opening any target connection. Backups remain pinned through restore.
     */
    public Manifest validate(Path backup) throws IOException {
        try {
            backup=backup.toAbsolutePath().normalize();privatePath(backup,true);
            var manifestFile=backup.resolve("manifest.json");privatePath(manifestFile,false);require(Files.size(manifestFile)<=4*1024*1024);
            var manifest=JSON.readValue(Files.readAllBytes(manifestFile),Manifest.class);
            privatePath(backup.resolve("BACKUP_COMPLETE"),false);
            require(Files.readString(backup.resolve("BACKUP_COMPLETE")).equals(digest(manifestFile)));
            validateContents(backup,manifest);return manifest;
        }catch(Exception failure){throw stopped("BACKUP_VALIDATION",failure);}
    }
    private void validateContents(Path backup,Manifest m) throws Exception {
        require(FORMAT.equals(m.format)&&m.formatVersion==1&&"BACKUP_COMPLETE".equals(m.status)&&"UNENCRYPTED_SYNTHETIC_ONLY".equals(m.protection));
        Instant.parse(m.createdAt);require(m.postgresqlMajor==17&&syntheticDatabase(m.databaseLogicalIdentity));
        require(m.schemas.equals(List.of("public","workspace"))&&m.financeVersions.equals(expectedVersions(21))&&m.workspaceVersions.equals(expectedVersions(2)));
        require(m.rebuildRequired&&m.indexEngine.equals("postgresql-token-bm25-1"));
        require(m.archive.path.equals("database.dump")&&m.archive.bytes>0&&m.archive.representationSha256==null);
        require(m.knowledgeFiles.size()<=1000&&m.snapshot.tables.size()<=256);
        var unique=new HashSet<String>();Set<Path> expected=new HashSet<>();
        expected.add(backup.resolve("database.dump"));expected.add(backup.resolve("manifest.json"));
        expected.add(backup.resolve("BACKUP_COMPLETE"));expected.add(backup.resolve("BACKUP_INCOMPLETE"));
        verifyFile(backup.resolve("database.dump"),m.archive);
        try(var in=pin(backup.resolve("database.dump"))){var signature=ByteBuffer.allocate(5);while(signature.hasRemaining()&&in.read(signature)>0){}require(Arrays.equals(signature.array(),"PGDMP".getBytes(StandardCharsets.US_ASCII)));}
        for(var file:m.knowledgeFiles) {
            require(unique.add(file.path));require(file.bytes>0&&file.bytes<=8388608&&hex(file.representationSha256));
            Path path=resolveKnowledge(backup.resolve("knowledge"),file.path);expected.add(path);verifyFile(path,file);
        }
        // Reject unknown payloads, linked directories and file/directory substitutions as well.
        try(var walk=Files.walk(backup)) {
            for(Path p:walk.toList()) {
                noLinks(p);
                if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)) {
                    require(p.equals(backup)||p.equals(backup.resolve("knowledge"))||p.equals(backup.resolve("knowledge/sources"))||expected.stream().anyMatch(f->f.getParent().equals(p)));
                    privatePath(p,true);
                }else{require(expected.contains(p));privatePath(p,false);}
            }
        }
    }

    /** Target DB must have been freshly created by the local preparation script with this ownership
     * receipt. No DROP, clean, truncate, retry over an existing restore, or activation is performed.
     */
    public Result restore(Path backup,Endpoint owner,Endpoint app,String ownershipReceipt,Path candidate) throws IOException {
        String stage="BACKUP_VALIDATION";List<FileChannel> pins=new ArrayList<>();
        try {
            backup=backup.toAbsolutePath().normalize();Manifest m=validate(backup);
            pins.add(pin(backup.resolve("manifest.json")));pins.add(pin(backup.resolve("BACKUP_COMPLETE")));pins.add(pin(backup.resolve("database.dump")));
            for(var file:m.knowledgeFiles)pins.add(pin(resolveKnowledge(backup.resolve("knowledge"),file.path)));
            require(m.equals(validate(backup)));
            stage="TARGET_ADMISSION";
            require(owner.database.matches("personal_ai_workspace_b3_[0-9a-f]{12}")&&owner.database.equals(app.database));
            require(ownershipReceipt.matches("workspace-b3:[0-9a-f-]{36}"));
            candidate=candidate.toAbsolutePath().normalize();require(!candidate.startsWith(backup)&&!backup.startsWith(candidate));
            privatePath(candidate.getParent(),true);require(!Files.exists(candidate,LinkOption.NOFOLLOW_LINKS));
            String restoreVersion=tool("pg_restore",null,List.of("--version"));checkTools(m.dumpVersion,restoreVersion,m.postgresqlMajor);
            try(var db=owner.dataSource().getConnection()) {
                session(db);safeRole(db);require(major(db)==m.postgresqlMajor);
                require(value(db,"SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()").equals(owner.username));
                require(value(db,"SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()").equals(ownershipReceipt));
                require(scalar(db,"SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND backend_type='client backend'")==0);
                require(scalar(db,"SELECT count(*) FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname NOT IN ('public','information_schema')")==0);
                require(scalar(db,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'")==0);
                require(scalar(db,"SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public'")==0);
                require(scalar(db,"SELECT count(*) FROM pg_extension WHERE extname<>'plpgsql'")==0);
            }
            directory(candidate);write(candidate.resolve("RESTORE_INCOMPLETE"),"No Runtime activation; retain for diagnosis".getBytes(StandardCharsets.UTF_8));
            stage="DATABASE_RESTORE";
            tool("pg_restore",owner,List.of("--no-owner","--no-privileges","--exit-on-error","--single-transaction",backup.resolve("database.dump").toString()));
            stage="DATABASE_VERIFICATION";
            try(var db=owner.dataSource().getConnection()) {
                session(db);require(m.snapshot.equals(snapshot(db)));histories(db);safeJobs(db);verifyOwnership(db,owner.username);
                grantStore(db,app.username);
            }
            stage="KNOWLEDGE_RESTORE";directory(candidate.resolve("knowledge"));directory(candidate.resolve("knowledge/sources"));
            for(var file:m.knowledgeFiles) {
                Path target=resolveKnowledge(candidate.resolve("knowledge"),file.path);
                if(!Files.exists(target.getParent()))directory(target.getParent());
                copy(resolveKnowledge(backup.resolve("knowledge"),file.path),target);
            }
            try(var db=owner.dataSource().getConnection()){verifyKnowledge(db,candidate.resolve("knowledge"),m.knowledgeFiles);}
            stage="STORE_AND_INDEX_VERIFICATION";readStoresAndRebuild(app,candidate,m);
            try(var db=owner.dataSource().getConnection()) {
                session(db);var after=snapshot(db);
                require(m.snapshot.catalogSha256.equals(after.catalogSha256)&&m.snapshot.sequences.equals(after.sequences));
                var expected=new TreeMap<>(m.snapshot.tables);var actual=new TreeMap<>(after.tables);DERIVED.forEach(expected::remove);DERIVED.forEach(actual::remove);require(expected.equals(actual));
                verifyKnowledge(db,candidate.resolve("knowledge"),m.knowledgeFiles);safeJobs(db);
            }
            var result=new Result("RESTORE_VERIFIED","IDENTITY_REBIND_REQUIRED","WINDOWS_ADMISSION_PENDING");
            publish(candidate.resolve("RESTORE_VERIFIED.json"),JSON.writeValueAsBytes(result));return result;
        }catch(Exception failure){throw stopped(stage,failure);}
        finally{for(var handle:pins)try{handle.close();}catch(IOException ignored){}}
    }

    static void readStoresAndRebuild(Endpoint app,Path candidate,Manifest manifest) throws Exception {
        var ds=app.dataSource();
        try(var db=ds.getConnection();var memory=new MemoryStore(ds,candidate);var conversations=new ConversationStore(ds);var knowledge=new KnowledgeStore(ds)) {
            require(memory.backupSnapshot().size()==manifest.snapshot.tables.get("workspace.memory_items").rows);
            try(var s=db.createStatement();var r=s.executeQuery("SELECT id FROM workspace.memory_items")){while(r.next())require(memory.get(UUID.fromString(r.getString(1)))!=null);}
            try(var s=db.createStatement();var r=s.executeQuery("SELECT id FROM workspace.conversations")) {
                while(r.next()) {var id=UUID.fromString(r.getString(1));int limit=io.github.qianlixunbai.workspace.conversation.ConversationLimits.MAX_LIMIT;
                    var first=conversations.detail(id,0,limit);
                    for(int page=1;page*(long)limit<first.totalTurns();page++)conversations.detail(id,page,limit);}
            }
            try(var s=db.createStatement();var r=s.executeQuery("SELECT doc_id,revision FROM workspace.knowledge_revisions ORDER BY doc_id,revision")) {
                while(r.next()) {knowledge.detail(r.getString(1));var preview=knowledge.preview(r.getString(1),r.getString(2),0);require(!preview.text().isBlank()&&!preview.locators().isEmpty());}
            }
            try(var index=new KnowledgeLexicalIndex(knowledge)) {
                index.rebuild();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(!index.status().state().equals("READY")&&System.nanoTime()<deadline)Thread.sleep(20);
                require(index.status().state().equals("READY"));
                // Execute the real retrieval path even for an empty corpus; fixture assertions check hits.
                index.search("budget",10);
            }
        }
    }
    static void grantStore(Connection db,String app) throws Exception {
        require(app.matches("workspace_b3_app_[0-9a-f]{12}"));
        sql(db,"REVOKE ALL ON SCHEMA public,workspace FROM PUBLIC");
        sql(db,"REVOKE ALL ON ALL TABLES IN SCHEMA public,workspace FROM PUBLIC");
        sql(db,"REVOKE ALL ON ALL SEQUENCES IN SCHEMA public,workspace FROM PUBLIC");
        sql(db,"REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public,workspace FROM PUBLIC");
        sql(db,"GRANT USAGE ON SCHEMA workspace TO "+app);
        sql(db,"GRANT SELECT ON ALL TABLES IN SCHEMA workspace TO "+app);
        sql(db,"GRANT UPDATE ON workspace.knowledge_control TO "+app);
        sql(db,"GRANT INSERT,UPDATE,DELETE ON workspace.knowledge_chunks,workspace.knowledge_index_metadata TO "+app);
        // No Finance table access, receipt insertion, truth writes, schema CREATE or pg_control_system grant.
    }
    static void verifyOwnership(Connection db,String owner) throws Exception {
        require(scalar(db,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('public','workspace') AND pg_get_userbyid(c.relowner)<>current_user")==0);
        require(scalar(db,"SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('public','workspace') AND (p.prosecdef OR (pg_get_userbyid(p.proowner)<>current_user AND NOT EXISTS(SELECT FROM pg_depend d WHERE d.classid='pg_proc'::regclass AND d.objid=p.oid AND d.deptype='e')))")==0);
    }
    static void safeRole(Connection db) throws Exception {
        require(scalar(db,"SELECT count(*) FROM pg_roles WHERE rolname=current_user AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)")==1);
        require(scalar(db,"SELECT count(*) FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user)")==0);
    }
    static void safeJobs(Connection db) throws Exception {
        require(scalar(db,"SELECT count(*) FROM workspace.knowledge_jobs WHERE state IN ('PENDING','PARSING')")==0);
        require(scalar(db,"SELECT count(*) FROM workspace.knowledge_deletes")==0);
        require(scalar(db,"SELECT count(*) FROM workspace.conversation_turns WHERE status='PENDING'")==0);
        require(scalar(db,"SELECT count(*) FROM workspace.knowledge_jobs j WHERE state='READY' AND NOT EXISTS(SELECT FROM workspace.knowledge_revisions r WHERE r.doc_id=j.doc_id AND r.revision=j.revision)")==0);
    }
    static List<FileEntry> knowledgeFiles(Connection db) throws Exception {
        List<FileEntry> files=new ArrayList<>();
        try(var s=db.createStatement();var r=s.executeQuery("SELECT doc_id,revision,bytes,digest,representation_digest FROM workspace.knowledge_revisions ORDER BY doc_id,revision")) {
            while(r.next())files.add(new FileEntry("sources/"+KnowledgeStore.id(r.getString(1))+"/"+KnowledgeStore.version(r.getString(2))+".source",r.getLong(3),r.getString(4),r.getString(5)));
        }return List.copyOf(files);
    }
    static Path resolveKnowledge(Path root,String relative) throws IOException {
        require(relative!=null&&relative.matches("sources/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(?:[1-9]|10)\\.source"));
        Path path=root.resolve(relative).normalize();require(path.startsWith(root));noLinks(path);return path;
    }
    static void verifyKnowledge(Connection db,Path root,List<FileEntry> entries) throws Exception {
        require(entries.equals(knowledgeFiles(db)));
        try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM workspace.knowledge_revisions ORDER BY doc_id,revision")) {
            int i=0;while(r.next()) {
                var entry=entries.get(i++);Path path=resolveKnowledge(root,entry.path);verifyFile(path,entry);
                require(KnowledgeParser.type(KnowledgeParser.filename(r.getString("filename"))).equals(r.getString("type")));
                require(r.getString("parser").equals(KnowledgeParser.PARSER_VERSION)&&r.getString("normalizer").equals(KnowledgeParser.NORMALIZATION_VERSION));
                var parsed=KnowledgeParser.parse(path,r.getString("type"),()->false);
                require(parsed.text().equals(r.getString("text"))&&parsed.locatorJson().equals(r.getString("locators"))&&parsed.digest().equals(entry.representationSha256)&&parsed.lineCount()==r.getInt("lines"));
                require(parsed.text().getBytes(StandardCharsets.UTF_8).length+parsed.locatorJson().getBytes(StandardCharsets.UTF_8).length==r.getLong("artifact_bytes"));
                Instant.parse(r.getString("imported"));
            }
        }
    }
    static void verifyFile(Path path,FileEntry file) throws Exception {
        noLinks(path);require(hex(file.sha256)&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)==file.bytes&&digest(path).equals(file.sha256));
    }
    private static boolean hex(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static boolean syntheticDatabase(String name){return name!=null&&(name.equals("personal_ai_workspace_test")||name.matches("personal_ai_workspace_b3_[0-9a-f]{12}"));}
    static List<String> tables(Connection db) throws Exception {
        require(scalar(db,"SELECT count(*) FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname NOT IN ('public','workspace','information_schema')")==0);
        require(scalar(db,"SELECT count(*) FROM pg_extension WHERE extname NOT IN ('plpgsql','pgcrypto')")==0);
        List<String> tables=new ArrayList<>();
        try(var s=db.createStatement();var r=s.executeQuery("SELECT n.nspname||'.'||c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('public','workspace') AND c.relkind='r' ORDER BY 1")){while(r.next())tables.add(r.getString(1));}
        require(!tables.isEmpty());return tables;
    }
    public static Snapshot snapshot(Connection db) throws Exception {
        session(db);Map<String,Table> rows=new TreeMap<>();Map<String,String> sequences=new TreeMap<>();
        for(String table:tables(db)) {
            var hash=sha256();long count=0;
            try(var s=db.createStatement();var r=s.executeQuery("SELECT row_to_json(t)::text FROM "+qualified(table)+" t ORDER BY row_to_json(t)::text COLLATE \"C\"")) {
                while(r.next()){frame(hash,r.getString(1));count++;}
            }rows.put(table,new Table(count,HexFormat.of().formatHex(hash.digest())));
        }
        try(var s=db.createStatement();var r=s.executeQuery("SELECT schemaname||'.'||sequencename FROM pg_sequences WHERE schemaname IN ('public','workspace') ORDER BY 1")) {
            while(r.next()){String name=r.getString(1);sequences.put(name,value(db,"SELECT last_value::text||':'||is_called::text FROM "+qualified(name)));}
        }
        var hash=sha256();
        for(String query:List.of(
            "SELECT n.nspname,c.relname,c.relkind,c.relpersistence,c.relrowsecurity,a.attnum,a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull,pg_get_expr(d.adbin,d.adrelid) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum WHERE n.nspname IN ('public','workspace') AND c.relkind IN ('r','S','v','m','p') ORDER BY n.nspname,c.relname,a.attnum",
            "SELECT n.nspname,c.relname,x.conname,x.contype,x.convalidated,CASE WHEN x.contype='c' THEN pg_get_expr(x.conbin,x.conrelid,false) ELSE pg_get_constraintdef(x.oid,true) END FROM pg_constraint x JOIN pg_class c ON c.oid=x.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('public','workspace') ORDER BY 1,2,3",
            "SELECT v.schemaname,v.indexname,v.indexdef,v.tablename,pg_get_expr(i.indpred,i.indrelid,false) FROM pg_indexes v JOIN pg_namespace n ON n.nspname=v.schemaname JOIN pg_class c ON c.relnamespace=n.oid AND c.relname=v.indexname JOIN pg_index i ON i.indexrelid=c.oid WHERE v.schemaname IN ('public','workspace') ORDER BY 1,2",
            "SELECT n.nspname,c.relname,t.tgname,t.tgenabled,pg_get_triggerdef(t.oid,true) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE NOT t.tgisinternal AND n.nspname IN ('public','workspace') ORDER BY 1,2,3",
            "SELECT n.nspname,p.proname,pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('public','workspace') AND NOT EXISTS(SELECT FROM pg_depend d WHERE d.classid='pg_proc'::regclass AND d.objid=p.oid AND d.deptype='e') ORDER BY 1,2,3",
            "SELECT extname,extversion FROM pg_extension ORDER BY 1",
            "SELECT schemaname,sequencename,data_type,start_value,min_value,max_value,increment_by,cycle,cache_size FROM pg_sequences WHERE schemaname IN ('public','workspace') ORDER BY 1,2")) {
            try(var s=db.createStatement();var r=s.executeQuery(query)) {while(r.next()){
                for(int i=1;i<=r.getMetaData().getColumnCount();i++) {
                    String cell=r.getString(i);
                    // pg_dump reparses varchar-array casts: the deparsed strings can differ while
                    // the constraint/predicate is identical. Canonicalize with PostgreSQL's planner;
                    // retain constraint names, types, validation and every index property in the hash.
                    if(query.contains("FROM pg_constraint")&&i==6&&r.getString(4).equals("c"))
                        cell=projection(db,r.getString(1)+"."+r.getString(2),cell);
                    if(query.contains("FROM pg_indexes")&&r.getString(5)!=null) {
                        if(i==3){String suffix=" WHERE "+r.getString(5);require(cell.endsWith(suffix));cell=cell.substring(0,cell.length()-suffix.length());}
                        if(i==5)cell=projection(db,r.getString(1)+"."+r.getString(4),cell);
                    }
                    frame(hash,cell);
                }frame(hash,"<ROW>");
            }}
            frame(hash,"<QUERY>");
        }
        return new Snapshot(rows,HexFormat.of().formatHex(hash.digest()),sequences);
    }
    private static String projection(Connection db,String table,String expression) throws Exception {
        String plan=value(db,"EXPLAIN (VERBOSE, FORMAT JSON, COSTS OFF) SELECT ("+expression+") FROM "+qualified(table));
        var output=JSON.readTree(plan).get(0).path("Plan").path("Output");require(output.isArray()&&output.size()==1);return output.toString();
    }
    private static void frame(java.security.MessageDigest hash,String value) {
        byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);hash.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)hash.update(bytes);
    }
    static List<String> versions(Connection db,String schema) throws Exception {
        List<String> versions=new ArrayList<>();try(var s=db.createStatement();var r=s.executeQuery("SELECT version FROM "+schema+".flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank")){while(r.next())versions.add(r.getString(1));}return versions;
    }
    static List<String> expectedVersions(int end){List<String> values=new ArrayList<>();for(int i=1;i<=end;i++)values.add(Integer.toString(i));return values;}
    static void histories(Connection db) throws Exception {
        require(versions(db,"public").equals(expectedVersions(21))&&versions(db,"workspace").equals(expectedVersions(2)));
        require(scalar(db,"SELECT count(*) FROM public.flyway_schema_history WHERE NOT success")==0&&scalar(db,"SELECT count(*) FROM workspace.flyway_schema_history WHERE NOT success")==0);
    }
    private static String qualified(String name) throws IOException {require(name.matches("(public|workspace)\\.[a-z_][a-z0-9_]*"));var parts=name.split("\\.");return "\""+parts[0]+"\".\""+parts[1]+"\"";}
    private static void forceFile(Path path) throws IOException {privatePath(path,false);try(var ch=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ch.force(true);}}
    private static void checkTools(String dump,String restore,int major) throws IOException {require(dump.matches("pg_dump \\(PostgreSQL\\) "+major+"\\..*")&&restore.matches("pg_restore \\(PostgreSQL\\) "+major+"\\..*"));}
    private static int major(Connection db) throws SQLException {return (int)scalar(db,"SELECT current_setting('server_version_num')::int/10000");}
    static void session(Connection db) throws SQLException {sql(db,"SET search_path=workspace,pg_catalog");sql(db,"SET TIME ZONE 'UTC'");sql(db,"SET datestyle='ISO, YMD'");sql(db,"SET extra_float_digits=3");sql(db,"SET bytea_output='hex'");}
    static long scalar(Connection db,String query) throws SQLException {return Long.parseLong(value(db,query));}
    static String value(Connection db,String query) throws SQLException {try(var s=db.createStatement();var r=s.executeQuery(query)){if(!r.next())throw new SQLException();return r.getString(1);}}
    static void sql(Connection db,String query) throws SQLException {try(var s=db.createStatement()){s.execute(query);}}
    private String tool(String name,Endpoint endpoint,List<String> arguments) throws Exception {
        String suffix=System.getProperty("os.name","").startsWith("Windows")?".exe":"";Path binary=tools.resolve(name+suffix);noLinks(binary);require(Files.isRegularFile(binary));
        List<String> command=new ArrayList<>();command.add(binary.toString());
        if(endpoint!=null)command.addAll(List.of("--no-password","--host=127.0.0.1","--port=5432","--username="+endpoint.username,"--dbname="+endpoint.database));command.addAll(arguments);
        var builder=new ProcessBuilder(command);builder.environment().keySet().removeIf(k->k.toUpperCase(Locale.ROOT).startsWith("PG"));
        builder.environment().put("PGCONNECT_TIMEOUT","5");builder.environment().put("PGCLIENTENCODING","UTF8");
        if(endpoint!=null)builder.environment().put("PGPASSWORD",endpoint.password);
        // Child diagnostics can contain private SQL/paths. Never echo them; emit stage and SQLSTATE only.
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        if(endpoint!=null)builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process=builder.start();builder.environment().remove("PGPASSWORD");
        try {
            require(process.waitFor(120,TimeUnit.SECONDS));require(process.exitValue()==0);
            return endpoint==null?new String(process.getInputStream().readNBytes(1024),StandardCharsets.UTF_8).strip():"";
        }finally{if(process.isAlive()){process.destroyForcibly();process.waitFor();}process.getInputStream().close();process.getOutputStream().close();process.getErrorStream().close();}
    }
    private static IOException stopped(String stage,Exception failure) {
        String state=failure instanceof SQLException s&&s.getSQLState()!=null?s.getSQLState():"UNAVAILABLE";
        String code=failure instanceof io.github.qianlixunbai.workspace.common.WorkspaceException w?w.error().code().name():"UNAVAILABLE";
        String location=Arrays.stream(failure.getStackTrace()).filter(f->f.getClassName().equals(UnifiedPostgresqlBackup.class.getName()))
            .findFirst().map(f->f.getMethodName()+":"+f.getLineNumber()).orElse("unknown");
        return new IOException("Offline maintenance stopped: "+stage+"; kind="+failure.getClass().getSimpleName()+"; SQLSTATE="+state+"; code="+code+"; at="+location+"; retain incomplete candidate; no activation");
    }
}
