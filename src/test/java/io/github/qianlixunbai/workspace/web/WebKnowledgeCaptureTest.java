package io.github.qianlixunbai.workspace.web;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.knowledge.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.web.WebPolicyTest.assertCode;

class WebKnowledgeCaptureTest {
    @TempDir Path temporary;
    private static String text(KnowledgeStore store,String document) {
        var output=new StringBuilder();int offset=0;
        do {var page=store.preview(document,"1",offset);output.append(page.text());if(page.nextOffset()==null)return output.toString();offset=page.nextOffset();}while(true);
    }
    private static void ready(KnowledgeStore store,KnowledgeLexicalIndex index,String request)throws Exception {
        long end=System.nanoTime()+10_000_000_000L;
        while((!store.job(request).state().equals("READY")||!index.status().state().equals("READY"))&&System.nanoTime()<end)Thread.sleep(5);
        assertEquals("READY",store.job(request).state());assertEquals("READY",index.status().state());
    }
    private static WebFetchService.View fetched(WebFetchService service,String id,String url)throws Exception {
        var result=service.submit(ClientIdentity.NATIVE,id,url);long end=System.nanoTime()+2_000_000_000L;
        while(result.state()==WebFetchService.State.QUEUED||result.state()==WebFetchService.State.RUNNING){assertTrue(System.nanoTime()<end);Thread.sleep(5);result=service.get(ClientIdentity.NATIVE,id);}
        assertEquals(WebFetchService.State.SUCCEEDED,result.state());return result;
    }
    @Test void captureReopensSearchesAndRestoresExactBoundedSnapshotWithoutRefetching()throws Exception {
        var timer=WebFetchService.timer();var clock=new AtomicLong();var calls=new AtomicInteger();
        String html="<title>合成网页</title><script>must-not-survive()</script><p>琥珀项目预算为五百元😀。</p><p>只适用于本项目。</p>"+"<p>背景资料。</p>".repeat(1000);
        var service=new WebFetchService(WebPolicy.ASK_EVERY_TIME,(target,execution)->{
            calls.incrementAndGet();
            var extracted=new WebContentExtractor().extract(html.getBytes(StandardCharsets.UTF_8),new WebContentExtractor.Admission("text/html",StandardCharsets.UTF_8));
            return new WebFetchResult(target.url(),target.url(),target.hostname(),extracted.title(),Instant.EPOCH,"text/html","web-extract-1",
                    target.url().endsWith("/empty")?"":extracted.text(),extracted.titleTruncated(),extracted.textTruncated());
        },timer,clock::get);
        String document,expected;byte[] archive;Path auth=temporary.resolve("auth/token");
        try(service;var store=new KnowledgeStore(temporary.resolve("source"),auth);var ingestion=new KnowledgeIngestion(store);var index=new KnowledgeLexicalIndex(store)) {
            var capture=new WebKnowledgeCapture(service,ingestion);String id=UUID.randomUUID().toString(),request=UUID.randomUUID().toString();
            assertCode(ErrorCode.POLICY_DENIED,()->capture.save(new ClientIdentity("browser","browser","b",null,Instant.EPOCH,Set.of("translate")),id,request));
            var result=fetched(service,id,"https://example.com/reference");assertTrue(result.result().textTruncated());
            assertEquals(0,store.list("ACTIVE",0).total()); // Fetching alone never writes Knowledge.
            var job=capture.save(ClientIdentity.NATIVE,id,request);document=job.documentId();ready(store,index,request);
            expected=WebKnowledgeCapture.snapshot(result.result());assertEquals(expected,text(store,document));
            assertTrue(expected.contains("正文已截断：是，内容不完整"));assertTrue(expected.contains("https://example.com/reference"));assertFalse(expected.contains("must-not-survive"));
            assertEquals(document,capture.save(ClientIdentity.NATIVE,id,request).documentId());assertEquals(1,store.list("ACTIVE",0).total());assertEquals(1,calls.get());
            assertEquals(document,index.search("五百",10).hits().getFirst().documentId());
            var bytes=new ByteArrayOutputStream();new KnowledgeBackup(store,auth).export(bytes);archive=bytes.toByteArray();
            String empty=UUID.randomUUID().toString();fetched(service,empty,"https://example.com/empty");
            assertCode(ErrorCode.WEB_CONTENT_INVALID,()->capture.save(ClientIdentity.NATIVE,empty,UUID.randomUUID().toString()));
            clock.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(WebLimits.RETENTION_SECONDS+1));
            assertCode(ErrorCode.WEB_FETCH_NOT_FOUND,()->capture.save(ClientIdentity.NATIVE,id,UUID.randomUUID().toString()));assertEquals(1,store.list("ACTIVE",0).total());
        }finally{timer.shutdownNow();}
        try(var reopened=new KnowledgeStore(temporary.resolve("source"),auth);var index=new KnowledgeLexicalIndex(reopened)) {
            long end=System.nanoTime()+10_000_000_000L;while(!index.status().state().equals("READY")&&System.nanoTime()<end)Thread.sleep(5);
            assertEquals(expected,text(reopened,document));assertEquals(document,index.search("五百",10).hits().getFirst().documentId());
            new KnowledgeBackup(reopened,auth).restore(new ByteArrayInputStream(archive),temporary.resolve("restored").toString());
        }
        try(var restored=new KnowledgeStore(temporary.resolve("restored"),auth)) {assertEquals(expected,text(restored,document));}
        assertEquals(2,calls.get()); // Only the two explicitly submitted fetches ran.
    }
}
