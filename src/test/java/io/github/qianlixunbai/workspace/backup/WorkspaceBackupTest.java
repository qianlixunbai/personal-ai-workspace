package io.github.qianlixunbai.workspace.backup;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.conversation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.conversation.Conversation.*;

class WorkspaceBackupTest {
    @TempDir Path temp;
    Path token() { return temp.resolve("auth/token"); }
    private byte[] export(WorkspaceBackupService service) { var out=new ByteArrayOutputStream(); service.export(out); return out.toByteArray(); }
    private InputStream input(byte[] bytes) { return new ByteArrayInputStream(bytes); }
    private void invalid(Runnable work) { var e=assertThrows(WorkspaceException.class,work::run); assertNull(e.getCause()); assertFalse(e.getMessage().contains("synthetic")); }
    private static void resign(tools.jackson.databind.node.ObjectNode root) throws Exception {
        var hash=java.security.MessageDigest.getInstance("SHA-256");
        try(var out=new DataOutputStream(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),hash))) {
            var m=root.get("memory");var c=root.get("conversations");
            MemoryBackup.strings(out,root.get("format").asString(),root.get("formatVersion").asString(),root.get("createdAt").asString(),
                    m.get("schemaVersion").asString(),m.get("itemCount").asString(),c.get("schemaVersion").asString(),c.get("conversationCount").asString(),c.get("turnCount").asString(),c.get("messageCount").asString());
            var rows=new ArrayList<tools.jackson.databind.JsonNode>();m.get("items").forEach(rows::add);rows.sort(Comparator.comparing(n->n.get("id").asString()));
            for(var n:rows)for(String field:List.of("id","type","title","content","status","revision","source","createdAt","updatedAt"))MemoryBackup.strings(out,n.get(field).asString());
            rows.clear();c.get("items").forEach(rows::add);rows.sort(Comparator.comparing(n->n.get("id").asString()));
            for(var n:rows)for(String field:List.of("id","title","status","createdAt","updatedAt"))MemoryBackup.strings(out,n.get(field).asString());
            rows.clear();c.get("turns").forEach(rows::add);rows.sort(Comparator.comparing((tools.jackson.databind.JsonNode n)->n.get("conversationId").asString()).thenComparingLong(n->n.get("sequence").asLong()));
            for(var n:rows){
                for(String field:List.of("id","conversationId","sequence","status","failureCode","createdAt","updatedAt"))MemoryBackup.strings(out,n.get(field).isNull()?"":n.get(field).asString());
                MemoryBackup.strings(out,Integer.toString(n.get("messages").size()),Integer.toString(n.get("memories").size()));
                var messages=new ArrayList<tools.jackson.databind.JsonNode>();n.get("messages").forEach(messages::add);messages.sort(Comparator.comparingInt(v->v.get("role").asString().equals("USER")?0:1));
                for(var msg:messages)for(String field:List.of("id","role","content","createdAt"))MemoryBackup.strings(out,msg.get(field).asString());
                var selections=new ArrayList<tools.jackson.databind.JsonNode>();n.get("memories").forEach(selections::add);selections.sort(Comparator.comparingLong(v->v.get("position").asLong()));
                for(var selection:selections)for(String field:List.of("position","memoryId","revision"))MemoryBackup.strings(out,selection.get(field).asString());
            }
        }
        root.put("contentDigest",HexFormat.of().formatHex(hash.digest()));
    }
    @Test void validRecomputedDigestDoesNotAuthenticateInvalidDomainData() throws Exception {
        try(var memory=new MemoryStore(temp.resolve("source"),token());var history=new ConversationStore(memory.databaseFile())) {
            memory.create(MemoryItem.Type.PROJECT_NOTE,"synthetic-title","synthetic-content");var c=history.create("synthetic-conversation");
            var t=history.createTurnWithUserMessage(c.id(),"synthetic-user",UUID.randomUUID(),List.of(new MemoryReference(UUID.randomUUID(),1)));
            history.finalizeExecution(c.id(),t.id(),t.taskId(),TurnStatus.SUCCEEDED,"synthetic-assistant",null);
            var service=new WorkspaceBackupService(memory,token());byte[] valid=export(service);
            for(String mutation:List.of("noAssistant","failedAssistant","twoUsers","system","badFailure","position","revision","sequence","time","nil","count","unknown","duplicateMessage","duplicateSelection","duplicateConversation","duplicateMemory")) {
                var root=(tools.jackson.databind.node.ObjectNode)WorkspaceBackupService.JSON.readTree(valid);
                var turn=(tools.jackson.databind.node.ObjectNode)root.path("conversations").path("turns").get(0);
                var messages=(tools.jackson.databind.node.ArrayNode)turn.get("messages");var selections=(tools.jackson.databind.node.ArrayNode)turn.get("memories");
                var row=(tools.jackson.databind.node.ObjectNode)root.path("memory").path("items").get(0);
                switch(mutation){
                    case "noAssistant" -> messages.remove(1);
                    case "failedAssistant" -> turn.put("status","FAILED");
                    case "twoUsers" -> ((tools.jackson.databind.node.ObjectNode)messages.get(1)).put("role","USER");
                    case "system" -> ((tools.jackson.databind.node.ObjectNode)messages.get(0)).put("role","SYSTEM");
                    case "badFailure" -> {turn.put("status","FAILED");messages.remove(1);turn.put("failureCode","RAW_EXCEPTION");}
                    case "position" -> ((tools.jackson.databind.node.ObjectNode)selections.get(0)).put("position",1);
                    case "revision" -> row.put("revision",0);
                    case "sequence" -> turn.put("sequence",2);
                    case "time" -> turn.put("updatedAt","2026-10-04T00:00:00.000001Z");
                    case "nil" -> row.put("id","00000000-0000-0000-0000-000000000000");
                    case "count" -> ((tools.jackson.databind.node.ObjectNode)root.get("memory")).put("itemCount",1001);
                    case "unknown" -> turn.put("taskId",UUID.randomUUID().toString());
                    case "duplicateMessage" -> ((tools.jackson.databind.node.ObjectNode)messages.get(1)).put("id",messages.get(0).get("id").asString());
                    case "duplicateSelection" -> selections.add(selections.get(0));
                    case "duplicateConversation" -> ((tools.jackson.databind.node.ArrayNode)root.path("conversations").get("items")).add(root.path("conversations").path("items").get(0));
                    case "duplicateMemory" -> ((tools.jackson.databind.node.ArrayNode)root.path("memory").get("items")).add(row);
                }
                resign(root);invalid(()->service.validate(input(root.toString().getBytes(StandardCharsets.UTF_8))));
            }
        }
    }
    @Test void exactRoundTripAllOutcomesHistoricalSelectionNoTaskAndContinue() throws Exception {
        try(var memory=new MemoryStore(temp.resolve("source"),token());var conversations=new ConversationStore(memory.databaseFile())) {
            var item=memory.create(MemoryItem.Type.PROJECT_NOTE,"synthetic-title","synthetic-content 中文😀");
            memory.update(item.id(),1,item.type(),item.title(),item.content());
            var archived=memory.create(MemoryItem.Type.PREFERENCE,"synthetic-archived","synthetic archived"); memory.archive(archived.id(),1);
            var a=conversations.create("synthetic conversation");
            var first=conversations.createTurnWithUserMessage(a.id(),"synthetic USER",UUID.randomUUID(),List.of(new MemoryReference(UUID.randomUUID(),1),new MemoryReference(item.id(),1)));
            conversations.finalizeExecution(a.id(),first.id(),first.taskId(),TurnStatus.SUCCEEDED,"synthetic ASSISTANT",null);
            for(var status:List.of(TurnStatus.FAILED,TurnStatus.CANCELLED,TurnStatus.TIMED_OUT)) {
                var turn=conversations.createTurnWithUserMessage(a.id(),"synthetic "+status,UUID.randomUUID(),List.of());
                conversations.finalizeExecution(a.id(),turn.id(),turn.taskId(),status,null,status==TurnStatus.FAILED?FailureCode.MODEL_UNAVAILABLE:null);
            }
            var b=conversations.create("synthetic archived conversation");var turn=conversations.createTurnWithUserMessage(b.id(),"synthetic archived USER");
            conversations.completeTurnWithAssistantMessage(b.id(),turn.id(),"synthetic archived ASSISTANT");conversations.archive(b.id());
            var service=new WorkspaceBackupService(memory,token());byte[] bytes=export(service);byte[] active=Files.readAllBytes(memory.databaseFile());
            String text=new String(bytes,StandardCharsets.UTF_8);assertFalse(text.contains("taskId"));assertFalse(text.contains("PENDING"));
            var metadata=service.validate(input(bytes));assertEquals(5,metadata.turnCount());assertEquals(7,metadata.messageCount());
            for(String targetName:List.of("new","empty")) {
                Path target=temp.resolve(targetName);if(targetName.equals("empty"))Files.createDirectory(target);
                assertEquals(metadata,service.restore(input(bytes),target.toString()));assertArrayEquals(active,Files.readAllBytes(memory.databaseFile()));
                try(var restored=new MemoryStore(target,token());var history=new ConversationStore(restored.databaseFile())) {
                    assertEquals(memory.backupSnapshot(),restored.backupSnapshot());assertEquals(1,restored.list(null,null,"中文😀",0,20).total());
                    assertEquals(0,history.reconcilePending());assertEquals(conversations.detail(a.id(),0,10).conversation(),history.detail(a.id(),0,10).conversation());
                    var original=conversations.detail(a.id(),0,10).turns();var recovered=history.detail(a.id(),0,10).turns();
                    for(int i=0;i<original.size();i++) {
                        var o=original.get(i);var r=recovered.get(i);assertNull(r.taskId());
                        assertEquals(new Turn(o.id(),o.conversationId(),o.sequence(),o.status(),o.createdAt(),o.updatedAt(),o.userMessage(),o.assistantMessage(),null,o.failureCode(),o.memories()),r);
                    }
                    assertEquals(conversations.detail(b.id(),0,10),history.detail(b.id(),0,10));
                    invalid(()->history.createTurnWithUserMessage(b.id(),"synthetic new USER"));history.unarchive(b.id());
                    var next=history.createTurnWithUserMessage(a.id(),"synthetic continuation");assertEquals(5,next.sequence());history.completeTurnWithAssistantMessage(a.id(),next.id(),"synthetic reply");
                    assertEquals(2,history.successfulHistory(a.id()).size());
                    try(var db=DriverManager.getConnection("jdbc:sqlite:"+restored.databaseFile());var s=db.createStatement()) {
                        assertThrows(SQLException.class,()->s.executeUpdate("UPDATE conversation_messages SET content='mutated'"));
                        assertThrows(SQLException.class,()->s.executeUpdate("UPDATE conversation_turns SET status='PENDING' WHERE status='SUCCEEDED'"));
                    }
                }
            }
        }
    }
    @Test void pendingConflictAndSnapshotReadTransactionDoesNotCombineTwoTimes() throws Exception {
        try(var memory=new MemoryStore(temp.resolve("source"),token());var store=new ConversationStore(memory.databaseFile())) {
            var c=store.create("synthetic");var t=store.createTurnWithUserMessage(c.id(),"synthetic",UUID.randomUUID(),List.of());
            var service=new WorkspaceBackupService(memory,token());var out=new ByteArrayOutputStream();
            var e=assertThrows(WorkspaceException.class,()->service.export(out));assertEquals(ErrorCode.WORKSPACE_BACKUP_CONFLICT,e.error().code());assertEquals(0,out.size());
            store.finalizeExecution(c.id(),t.id(),t.taskId(),TurnStatus.CANCELLED,null,null);assertEquals(1,service.validate(input(export(service))).turnCount());
        }
    }
    @Test void memoryAndConversationBelongToOneReadSnapshotAcrossCommittedGenerations() throws Exception {
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var memory=new MemoryStore(temp.resolve("source"),token());var history=new ConversationStore(memory.databaseFile())) {
            memory.create(MemoryItem.Type.PROJECT_NOTE,"synthetic","generation-0");history.create("generation-0");
            var writer=executor.submit(()->{
                try(var db=DriverManager.getConnection("jdbc:sqlite:"+memory.databaseFile());var s=db.createStatement()) {
                    s.execute("PRAGMA busy_timeout=3000");
                    for(int i=1;i<=20;i++){s.execute("BEGIN IMMEDIATE");s.execute("UPDATE memory_items SET content='generation-"+i+"',revision=revision+1");s.execute("UPDATE conversations SET title='generation-"+i+"'");s.execute("COMMIT");}
                }catch(Exception e){throw new IllegalStateException();}
            });
            var service=new WorkspaceBackupService(memory,token());
            for(int i=0;i<30;i++){
                var root=WorkspaceBackupService.JSON.readTree(export(service));
                assertEquals(root.path("memory").path("items").get(0).path("content").asString(),root.path("conversations").path("items").get(0).path("title").asString());
            }
            writer.get(15,java.util.concurrent.TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
    }
    @Test void strictCorruptionsRejectWholeDocumentBeforePublication() {
        try(var memory=new MemoryStore(temp.resolve("source"),token());var store=new ConversationStore(memory.databaseFile())) {
            memory.create(MemoryItem.Type.PROJECT_NOTE,"synthetic-title","synthetic-content");var c=store.create("synthetic-conversation");
            var t=store.createTurnWithUserMessage(c.id(),"synthetic-user",UUID.randomUUID(),List.of(new MemoryReference(UUID.randomUUID(),1)));
            store.finalizeExecution(c.id(),t.id(),t.taskId(),TurnStatus.SUCCEEDED,"synthetic-assistant",null);
            var service=new WorkspaceBackupService(memory,token());var bytes=export(service);String valid=new String(bytes,StandardCharsets.UTF_8);
            List<String> bad=new ArrayList<>(List.of(valid.substring(0,valid.length()-1),valid+"{}",valid.replace(WorkspaceBackupService.FORMAT,"other"),
                    valid.replace("\"formatVersion\":1","\"formatVersion\":2"),valid.replace("\"schemaVersion\":1","\"schemaVersion\":2"),
                    valid.replace("\"format\":","\"unknown\":0,\"format\":"),valid.replace("\"format\":","\"format\":\"duplicate\",\"format\":"),
                    valid.replace("synthetic-content","tampered"),valid.replace("SUCCEEDED","PENDING"),valid.replace("SUCCEEDED","FAILED"),
                    valid.replace("USER","SYSTEM"),valid.replace("USER","TOOL"),valid.replace("USER","OTHER"),valid.replace("ACTIVE","OTHER"),
                    valid.replace("\"failureCode\":null","\"failureCode\":\"RAW_EXCEPTION\""),valid.replace("\"position\":0","\"position\":1"),
                    valid.replace("\"revision\":1","\"revision\":0"),valid.replace("\"sequence\":1","\"sequence\":0"),
                    valid.replace("synthetic-title","\\uD800"),valid.replace("synthetic-title","\\u0000"),valid.replace("synthetic-user","x".repeat(8193)),
                    valid.replace("synthetic-title","x".repeat(161)),valid.replace(c.id().toString(),"00000000-0000-0000-0000-000000000000")));
            var node=WorkspaceBackupService.JSON.readTree(bytes);
            var turns=(tools.jackson.databind.node.ArrayNode)node.path("conversations").get("turns"); turns.add(turns.get(0));bad.add(node.toString());
            for(String text:bad) { Path target=temp.resolve("reject");invalid(()->service.restore(input(text.getBytes(StandardCharsets.UTF_8)),target.toString()));assertFalse(Files.exists(target)); }
            invalid(()->service.validate(input(new byte[]{'{',(byte)255,'}'})));
        }
    }
    @Test void canonicalDigestSurvivesOrderWhitespaceEscapingAndMemoryOnlyCompatibility() throws Exception {
        try(var memory=new MemoryStore(temp.resolve("source"),token());var store=new ConversationStore(memory.databaseFile())) {
            memory.create(MemoryItem.Type.PROJECT_NOTE,"synthetic","synthetic 中文");
            for(int i=0;i<2;i++){var c=store.create("synthetic "+i);var t=store.createTurnWithUserMessage(c.id(),"synthetic user");store.completeTurnWithAssistantMessage(c.id(),t.id(),"synthetic assistant");}
            var service=new WorkspaceBackupService(memory,token());byte[] bytes=export(service);var root=(tools.jackson.databind.node.ObjectNode)WorkspaceBackupService.JSON.readTree(bytes);
            var items=(tools.jackson.databind.node.ArrayNode)root.path("conversations").get("items");var first=items.remove(0);items.add(first);
            var turns=(tools.jackson.databind.node.ArrayNode)root.path("conversations").get("turns");first=turns.remove(0);turns.add(first);
            var reordered=WorkspaceBackupService.JSON.createObjectNode();reordered.set("conversations",root.get("conversations"));reordered.set("memory",root.get("memory"));
            for(String field:List.of("contentDigest","createdAt","formatVersion","format"))reordered.set(field,root.get(field));
            String pretty=WorkspaceBackupService.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(reordered).replace("中文","\\u4e2d\\u6587");
            assertEquals(service.validate(input(bytes)),service.validate(input(pretty.getBytes(StandardCharsets.UTF_8))));
            var legacy=MemoryBackup.export(memory);var target=temp.resolve("legacy");new MemoryBackupService(temp.resolve("source"),token()).restore(legacy,target.toString());
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+target.resolve("memory.db"));var s=db.createStatement();var r=s.executeQuery("PRAGMA user_version")){r.next();assertEquals(1,r.getInt(1));}
            try(var restored=new MemoryStore(target,token());var history=new ConversationStore(restored.databaseFile())){assertEquals(memory.backupSnapshot(),restored.backupSnapshot());assertEquals(0,history.list(null,0,10).total());}
        }
    }
    @Test void unsafePathsAndNonemptyTargetsLeaveActiveUntouchedAndInputIsBounded() throws Exception {
        try(var memory=new MemoryStore(temp.resolve("source"),token())) {
            var service=new WorkspaceBackupService(memory,token());var bytes=export(service);var active=Files.readAllBytes(memory.databaseFile());
            Path occupied=Files.createDirectory(temp.resolve("occupied"));Files.writeString(occupied.resolve("keep"),"synthetic");
            Path file=Files.writeString(temp.resolve("file"),"synthetic");
            for(Path target:List.of(temp.resolve("source"),temp,temp.resolve("source/child"),occupied,file,temp.resolve("build/target"),temp.resolve(".runtime"),temp.resolve(".git")))
                invalid(()->service.restore(input(bytes),target.toString()));
            assertArrayEquals(active,Files.readAllBytes(memory.databaseFile()));assertEquals("synthetic",Files.readString(occupied.resolve("keep")));
            var limited=new WorkspaceBackupService.LimitedInput(input(new byte[12]),10);assertThrows(WorkspaceException.class,()->limited.readAllBytes());
        }
    }
    @Test void maximumConversationWithWorstEscapingExportsAndValidatesThroughDiskWithoutAggregateBuffer() throws Exception {
        try(var memory=new MemoryStore(temp.resolve("source"),token())) {
            memory.reconstructWorkspace(db->{
                try {
                    String parent=UUID.randomUUID().toString();
                    try(var s=db.prepareStatement("INSERT INTO conversations VALUES(?,?,'ACTIVE',1,1)")){s.setString(1,parent);s.setString(2,"😀".repeat(160));s.executeUpdate();}
                    try(var turns=db.prepareStatement("INSERT INTO conversation_turns(id,conversation_id,sequence,status,created_at,updated_at) VALUES(?,?,?,'SUCCEEDED',1,1)");
                        var messages=db.prepareStatement("INSERT INTO conversation_messages VALUES(?,?,?,?,1)")) {
                        for(int sequence=1;sequence<=1000;sequence++) {
                            String turn=UUID.randomUUID().toString();turns.setString(1,turn);turns.setString(2,parent);turns.setInt(3,sequence);turns.executeUpdate();
                            for(String role:List.of("USER","ASSISTANT")){messages.setString(1,UUID.randomUUID().toString());messages.setString(2,turn);messages.setString(3,role);messages.setString(4,"\u0001".repeat(8192));messages.executeUpdate();}
                        }
                    }
                    return List.of();
                }catch(SQLException e){throw new IllegalStateException();}
            });
            Path file=temp.resolve("large.workspace-backup.json");var service=new WorkspaceBackupService(memory,token());
            try(var out=Files.newOutputStream(file)){service.export(out);}
            assertTrue(Files.size(file)>90L*1024*1024);assertTrue(Files.size(file)<WorkspaceBackupService.MAX_BYTES);
            try(var in=Files.newInputStream(file)){var m=service.validate(in);assertEquals(1000,m.turnCount());assertEquals(2000,m.messageCount());}
        }
    }
}
