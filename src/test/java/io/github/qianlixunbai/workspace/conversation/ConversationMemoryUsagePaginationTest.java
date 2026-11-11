package io.github.qianlixunbai.workspace.conversation;

import org.junit.jupiter.api.Test;

class ConversationMemoryUsagePaginationTest {
    private final ConversationMemoryUsagePaginationScenarios scenarios = new ConversationMemoryUsagePaginationScenarios();

    @Test void elevenMatchesStayAcrossPagesWhenTheOtherConversationIsUpdated() throws Exception {
        scenarios.elevenMatchesStayAcrossPagesWhenTheOtherConversationIsUpdated();
    }
    @Test void equalCreationTimesHaveAStableConversationAndSequenceBoundary() throws Exception {
        scenarios.equalCreationTimesHaveAStableConversationAndSequenceBoundary();
    }
    @Test void memoryRevisionAndTerminalScopeArePreservedAndEmptyPagesRemainEmpty() throws Exception {
        scenarios.memoryRevisionAndTerminalScopeArePreservedAndEmptyPagesRemainEmpty();
    }
    @Test void newlyInsertedMatchesRemainLiveAndARefreshStartsFromPageZero() throws Exception {
        scenarios.newlyInsertedMatchesRemainLiveAndARefreshStartsFromPageZero();
    }
}
