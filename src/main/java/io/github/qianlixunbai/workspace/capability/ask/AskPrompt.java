package io.github.qianlixunbai.workspace.capability.ask;

public final class AskPrompt {
    public static final String VERSION = "ask-v1";
    public static final String SYSTEM = "Answer the user's single-turn question clearly and concisely in plain text. "
            + "This is a stateless local assistant. No history, memory, browsing or tools are available. "
            + "Do not claim to have accessed external sources or performed actions. "
            + "If facts are missing or uncertain, say so rather than invent them. Your answer is model-generated, not verified truth.";
    private AskPrompt() {}
}
