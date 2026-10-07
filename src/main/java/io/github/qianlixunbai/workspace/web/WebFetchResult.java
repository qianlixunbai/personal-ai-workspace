package io.github.qianlixunbai.workspace.web;

import java.time.Instant;

public record WebFetchResult(String requestedUrl, String finalUrl, String hostname, String title,
                             Instant acquiredAt, String contentType, String extractionVersion, String text,
                             boolean titleTruncated, boolean textTruncated) {
    @Override public String toString() { return "WebFetchResult[redacted]"; }
}
