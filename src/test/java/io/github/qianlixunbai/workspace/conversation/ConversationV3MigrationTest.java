package io.github.qianlixunbai.workspace.conversation;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.memory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationV3MigrationTest {
    @TempDir Path temp;
    Path data(){return temp.resolve("data");}Path token(){return temp.resolve("auth/token");}
    void v2() throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+data().resolve("memory.db"));var s=db.createStatement()) {
            s.execute("DROP TRIGGER conversation_terminal_immutable");s.execute("DROP TRIGGER conversation_selection_immutable");
            s.execute("DROP TABLE conversation_memory_selections");s.execute("DROP INDEX conversation_task_id");
            s.execute("ALTER TABLE conversation_turns DROP COLUMN task_id");s.execute("ALTER TABLE conversation_turns DROP COLUMN failure_code");s.execute("PRAGMA user_version=2");
        }
    }
    @Test void v2ToV3PreservesM4ATurnsMessagesAndExactM3Source() throws Exception {
        MemoryItem item;Conversation.Detail detail;
        try(var memory=new MemoryStore(data(),token());var store=new ConversationStore(memory.databaseFile())) {
            item=memory.create(MemoryItem.Type.PROJECT_NOTE,"Synthetic preserved note","Synthetic preserved source");
            var c=store.create("Synthetic preserved title");var t=store.createTurnWithUserMessage(c.id(),"Synthetic USER");store.completeTurnWithAssistantMessage(c.id(),t.id(),"Synthetic ASSISTANT");
            detail=store.detail(c.id(),0,10);
        }
        v2();
        try(var memory=new MemoryStore(data(),token());var store=new ConversationStore(memory.databaseFile())) {
            assertEquals(item,memory.get(item.id()));assertEquals(detail,store.detail(detail.conversation().id(),0,10));
            assertEquals(1,MemoryBackup.export(memory).metadata().schemaVersion());assertEquals(0,store.reconcilePending());
        }
    }
    @Test void failedV2MigrationRollsBackAllNewColumnsAndPreservesHistoryAndVersion() throws Exception {
        try(var memory=new MemoryStore(data(),token());var store=new ConversationStore(memory.databaseFile())) {
            memory.create(MemoryItem.Type.PROJECT_NOTE,"Synthetic preserved note","Synthetic preserved source");
            var c=store.create(null);store.createTurnWithUserMessage(c.id(),"Synthetic USER");
        }
        v2();
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+data().resolve("memory.db"));var s=db.createStatement()) {s.execute("CREATE TABLE conversation_memory_selections(sentinel TEXT)");}
        assertEquals(ErrorCode.MEMORY_STORAGE_UNAVAILABLE,assertThrows(WorkspaceException.class,()->new MemoryStore(data(),token())).error().code());
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+data().resolve("memory.db"));var s=db.createStatement()) {
            try(var r=s.executeQuery("PRAGMA user_version")){r.next();assertEquals(2,r.getInt(1));}
            try(var r=s.executeQuery("SELECT count(*) FROM pragma_table_info('conversation_turns') WHERE name IN ('task_id','failure_code')")){r.next();assertEquals(0,r.getInt(1));}
            for(String table:new String[]{"memory_items","conversation_turns","conversation_messages"}) try(var r=s.executeQuery("SELECT count(*) FROM "+table)){r.next();assertEquals(1,r.getInt(1));}
        }
    }
}
