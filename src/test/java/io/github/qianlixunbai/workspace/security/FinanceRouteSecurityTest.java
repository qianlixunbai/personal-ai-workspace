package io.github.qianlixunbai.workspace.security;

import io.github.qianlixunbai.workspace.finance.FinanceAccountController;
import io.github.qianlixunbai.workspace.finance.FinanceAccountQueries;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceRouteSecurityTest {
    @Test void financeGetRemainsNativeBearerOnlyUnderTheExistingFilter() throws Exception {
        var token = mock(LocalClientToken.class);
        var clients = mock(BrowserClients.class);
        when(token.matches("Bearer synthetic")).thenReturn(true);
        when(clients.authenticateCredential("Bearer br1.synthetic")).thenReturn(
                new ClientIdentity("fixture", "browser", "fixture", null, Instant.EPOCH, Set.of("translate")));
        var queries = mock(FinanceAccountQueries.class);
        var controller = new FinanceAccountController(queries);
        var filter = new LocalClientFilter(token, clients);
        try {
            for (String credential : new String[]{"Bearer incorrect", "Bearer br1.synthetic", "Bearer synthetic"}) {
                SecurityContextHolder.clearContext();
                var request = new MockHttpServletRequest("GET", "/api/v1/finance/accounts/balance");
                request.setServletPath("/api/v1/finance/accounts/balance");
                request.setRemoteAddr("127.0.0.1"); request.addHeader("Authorization", credential);
                request.addHeader("Sec-Fetch-Site", "none"); request.addHeader("Sec-Fetch-Mode", "cors");
                request.addHeader("Sec-Fetch-Dest", "empty");
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, (incoming, outgoing) -> {
                    assertSame(ClientIdentity.NATIVE, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
                    controller.balance(request);
                });
                assertEquals(credential.equals("Bearer synthetic") ? 200 : credential.startsWith("Bearer br1.") ? 403 : 401,
                        response.getStatus());
                assertEquals("no-store", response.getHeader("Cache-Control"));
            }
            verify(queries, times(1)).cnyBalance();
        } finally { SecurityContextHolder.clearContext(); }
    }
}
