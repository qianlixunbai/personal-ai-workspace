package io.github.qianlixunbai.workspace.conversation;

import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.memory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.conversation.Conversation.*;

class ConversationContextTest {
    @TempDir Path temp;
    @Test void successfulOnlyWholeTurnsOrderedCurrentOnceAndMetadataExcluded() {
        try(var memory=new MemoryStore(temp.resolve("data"),temp.resolve("auth/token"));var store=new ConversationStore(memory.databaseFile())) {
            var c=store.create("TITLE_MARKER");
            for(var state:TurnStatus.values()) {
                var t=store.createTurnWithUserMessage(c.id(),state==TurnStatus.SUCCEEDED?"OLD_USER":"EXCLUDED_"+state);
                if(state==TurnStatus.SUCCEEDED) store.completeTurnWithAssistantMessage(c.id(),t.id(),"OLD_ASSISTANT");
                else if(state!=TurnStatus.PENDING)store.markTurnTerminated(c.id(),t.id(),state);
            }
            var context=ConversationContext.assemble(TestSettings.ask(),"CURRENT_USER",List.of(),store.detail(c.id(),0,10).turns());
            assertEquals(List.of("OLD_USER","OLD_ASSISTANT","CURRENT_USER"),context.messages().stream().map(x->x.content()).toList());
            assertEquals(List.of("user","assistant","user"),context.messages().stream().map(x->x.role()).toList());
            assertEquals(0,context.memoryCount());assertTrue(context.inputCharacters()<=3000);assertTrue(context.inputBytes()<=5632);
            assertFalse(context.toString().contains("CURRENT_USER"));assertTrue(ConversationContext.SYSTEM.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=512);
        }
    }
    @Test void recentContiguousSuccessfulWindowDropsOldestWithoutSplittingOrTruncatingMessages() {
        try(var memory=new MemoryStore(temp.resolve("data"),temp.resolve("auth/token"));var store=new ConversationStore(memory.databaseFile())) {
            var c=store.create(null);
            for(int i=1;i<=4;i++) {var t=store.createTurnWithUserMessage(c.id(),"USER_"+i+"x".repeat(500));store.completeTurnWithAssistantMessage(c.id(),t.id(),"ASSISTANT_"+i+"x".repeat(500));}
            var context=ConversationContext.assemble(TestSettings.ask(),"CURRENT",List.of(),store.successfulHistory(c.id()));
            assertEquals(List.of(3L,4L),context.admittedSequences());assertEquals(5,context.messages().size());
            assertEquals("USER_3"+"x".repeat(500),context.messages().getFirst().content());assertEquals("CURRENT",context.messages().getLast().content());
        }
    }
    @Test void explicitMemoryOrderAndCurrentPriorityRemainWithinActualSerializedBudget() {
        var a=new MemorySnapshot(UUID.randomUUID(),MemoryItem.Type.PROJECT_NOTE,"A","MEMORY_A",1);
        var b=new MemorySnapshot(UUID.randomUUID(),MemoryItem.Type.PREFERENCE,"B","MEMORY_B",2);
        var context=ConversationContext.assemble(TestSettings.ask(),"CURRENT",List.of(a,b),List.of());
        String data=context.messages().getFirst().content();assertTrue(data.indexOf("MEMORY_A")<data.indexOf("MEMORY_B"));assertEquals(2,context.memoryCount());
        assertEquals("CURRENT",context.messages().getLast().content());assertEquals("user",context.messages().getFirst().role());
        for(String oversized:List.of("x".repeat(3000),"中".repeat(1900),"\n".repeat(1500)+"CURRENT")) {
            assertEquals(ErrorCode.INVALID_REQUEST,assertThrows(WorkspaceException.class,()->ConversationContext.assemble(TestSettings.ask(),oversized,List.of(a,b),List.of())).error().code());
        }
    }
    @Test void escapedSystemAndProviderEnvelopeAreReservedWithinContextAndOutputBudget() {
        var profile=TestSettings.ask();
        var context=ConversationContext.assemble(profile,"中".repeat(1810),List.of(),List.of());
        var messages=new ArrayList<Map<String,String>>();messages.add(Map.of("role","system","content",ConversationContext.SYSTEM));
        for(var message:context.messages())messages.add(Map.of("role",message.role(),"content",message.content()));
        byte[] payload=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(Map.of("model",profile.model(),"stream",false,"think",false,
            "messages",messages,"options",Map.of("num_ctx",profile.contextBudget(),"num_predict",profile.outputBudget(),"temperature",profile.temperature())));
        assertTrue(payload.length+profile.outputBudget()<=profile.contextBudget());
        var longModel=new io.github.qianlixunbai.workspace.model.ModelProfile(profile.id(),profile.provider(),"x".repeat(1000),profile.locality(),profile.version(),profile.contextBudget(),profile.outputBudget(),profile.temperature(),profile.maxTextCharacters());
        assertEquals(ErrorCode.INVALID_REQUEST,assertThrows(WorkspaceException.class,()->ConversationContext.assemble(longModel,"中".repeat(1810),List.of(),List.of())).error().code());
    }
}
