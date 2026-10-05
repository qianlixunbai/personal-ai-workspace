package io.github.qianlixunbai.workspace.knowledge;

public final class KnowledgeLimits {
    private KnowledgeLimits() {}
    public static final int DOCUMENTS=500, REVISIONS_PER_DOCUMENT=10, REVISIONS=2000;
    public static final int SOURCE_BYTES=8*1024*1024, TEXT_BYTES=2*1024*1024, CODE_POINTS=500_000;
    public static final int LOCATOR_BYTES=2*1024*1024, LINES=100_000, BLOCKS=10_000;
    public static final long CORPUS_BYTES=2L*1024*1024*1024, ARTIFACT_BYTES=256L*1024*1024;
    public static final int PREVIEW_UNITS=4096, PAGE_SIZE=20;
    // Exact source + normalized artifacts + <= 2000*8KiB metadata + framing, below 3 GiB.
    public static final long BACKUP_BYTES=CORPUS_BYTES+ARTIFACT_BYTES+32L*1024*1024;
}
