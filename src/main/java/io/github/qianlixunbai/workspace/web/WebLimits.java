package io.github.qianlixunbai.workspace.web;

/** Fixed W1A ceilings; no configurable security bypass or budget expansion. */
final class WebLimits {
    static final int URL = 2048, DNS_ENTRIES = 16, ADDRESS_ATTEMPTS = 2, REDIRECTS = 2;
    static final int BODY = 512 * 1024, DECODED = 512 * 1024;
    static final int TEXT_UNITS = 4096, TEXT_BYTES = 8192, TITLE_SCALARS = 160, TITLE_BYTES = 640;
    static final int HEADER_BYTES = 32 * 1024, HEADER_FIELDS = 64, HEADER_LINE = 8192;
    static final int DNS_SECONDS = 3, CONNECT_SECONDS = 3, TLS_SECONDS = 3, HEADERS_SECONDS = 5;
    static final int BODY_IDLE_SECONDS = 3, HOP_SECONDS = 12, QUEUE_SECONDS = 5, TOTAL_SECONDS = 30;
    static final int QUEUED = 2, RETAINED = 16, RETENTION_SECONDS = 120;
    private WebLimits() {}
}
