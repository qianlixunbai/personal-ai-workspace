package io.github.qianlixunbai.workspace.web;

import java.io.*;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.io.DefaultHttpResponseParserFactory;
import org.apache.hc.core5.http.io.*;
import org.apache.hc.core5.http.message.BasicLineParser;
import org.apache.hc.core5.util.CharArrayBuffer;

/** Counts header lines while parsing, including informational responses, before body admission. */
final class WebHeaderParser implements HttpMessageParserFactory<ClassicHttpResponse> {
    private final Http1Config config;
    private final WebExecution execution;
    private int bytes, fields;
    WebHeaderParser(Http1Config config, WebExecution execution) { this.config = config; this.execution = execution; }
    @Override public HttpMessageParser<ClassicHttpResponse> create(Http1Config ignored) { return create(); }
    @Override public HttpMessageParser<ClassicHttpResponse> create() {
        var parser = new DefaultHttpResponseParserFactory(config, BasicLineParser.INSTANCE, null).create();
        return (buffer, stream) -> parser.parse(new SessionInputBuffer() {
            boolean first = true;
            public int readLine(CharArrayBuffer line, InputStream input) throws IOException {
                execution.check();
                int read = buffer.readLine(line, input);
                if (read >= 0) {
                    bytes += read + 2;
                    if (!first && read > 0) fields++;
                    first = false;
                    if (bytes > WebLimits.HEADER_BYTES || fields > WebLimits.HEADER_FIELDS)
                        throw WebContentExtractor.tooLarge();
                    // Obsolete folding obscures duplicate fields/parameter boundaries. Reject it.
                    if (read > 0 && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) throw WebContentExtractor.invalid();
                }
                return read;
            }
            public int length() { return buffer.length(); }
            public int capacity() { return buffer.capacity(); }
            public int available() { return buffer.available(); }
            public int read(byte[] b, int off, int len, InputStream input) throws IOException { return buffer.read(b, off, len, input); }
            public int read(byte[] b, InputStream input) throws IOException { return buffer.read(b, input); }
            public int read(InputStream input) throws IOException { return buffer.read(input); }
            public HttpTransportMetrics getMetrics() { return buffer.getMetrics(); }
        }, stream);
    }
}
