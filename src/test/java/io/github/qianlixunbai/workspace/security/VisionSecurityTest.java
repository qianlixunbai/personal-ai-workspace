package io.github.qianlixunbai.workspace.security;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.capability.vision.VisionRequest;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.time.Instant;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisionSecurityTest {
    @Test void browserAndAuthenticationDenyBeforeBodyAndExceptionIsExactNativePostOnly() throws Exception {
        LocalClientToken token=mock(LocalClientToken.class);BrowserClients clients=mock(BrowserClients.class);
        when(token.matches("Bearer synthetic")).thenReturn(true);
        when(clients.authenticateCredential("Bearer br1.synthetic")).thenReturn(new ClientIdentity("fixture","browser","fixture",null,Instant.EPOCH,Set.of("translate")));
        var filter=new LocalClientFilter(token,clients);AtomicInteger reads=new AtomicInteger(),accepted=new AtomicInteger();
        class Request extends MockHttpServletRequest {
            Request(String method,String path,String credential) {super(method,path);setServletPath(path);setRemoteAddr("127.0.0.1");addHeader("Authorization",credential);addHeader("Sec-Fetch-Site","none");addHeader("Sec-Fetch-Mode","cors");addHeader("Sec-Fetch-Dest","empty");}
            @Override public ServletInputStream getInputStream() {reads.incrementAndGet();throw new AssertionError("Body must not be consumed");}
        }
        try {
            for(var request:List.of(new Request("GET","/api/v1/capabilities/vision/readiness","Bearer br1.synthetic"),new Request("POST","/api/v1/vision/tasks","Bearer br1.synthetic"),new Request("POST","/api/v1/vision/tasks","Bearer incorrect"))) {
                var response=new MockHttpServletResponse();filter.doFilter(request,response,(a,b)->accepted.incrementAndGet());assertTrue(response.getStatus()==401||response.getStatus()==403);assertEquals("no-store",response.getHeader("Cache-Control"));
            }
            var request=new Request("POST","/api/v1/vision/tasks","Bearer synthetic");var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(a,b)->{assertTrue(ClientIdentity.current().allowedCapabilities().contains("vision"));accepted.incrementAndGet();});
            assertEquals(1,accepted.get());assertEquals(0,reads.get());assertEquals("no-store",response.getHeader("Cache-Control"));
            var unrelated=new MockHttpServletRequest("POST","/api/v1/vision/tasks/other");unrelated.setServletPath("/api/v1/vision/tasks/other");unrelated.setRemoteAddr("127.0.0.1");unrelated.addHeader("Authorization","Bearer synthetic");unrelated.setContent(new byte[32769]);
            response=new MockHttpServletResponse();filter.doFilter(unrelated,response,(a,b)->fail("Global body limit was expanded"));assertEquals(413,response.getStatus());
        } finally {SecurityContextHolder.clearContext();}
    }
}
