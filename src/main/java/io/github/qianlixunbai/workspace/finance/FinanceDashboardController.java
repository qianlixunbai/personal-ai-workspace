package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@ConditionalOnProperty(name = "workspace.finance.enabled", havingValue = "true")
public final class FinanceDashboardController {
    private final FinanceDashboardQueries queries;
    public FinanceDashboardController(FinanceDashboardQueries queries) { this.queries = queries; }
    @GetMapping("/api/v1/finance/dashboard")
    public FinanceDashboardQueries.Dashboard dashboard(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE");
        return queries.dashboard();
    }
    @RequestMapping(value = "/api/v1/finance/dashboard", method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public void unsupported(HttpServletRequest request) throws org.springframework.web.HttpRequestMethodNotSupportedException {
        throw new org.springframework.web.HttpRequestMethodNotSupportedException(request.getMethod(), Set.of("GET"));
    }
}
