package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@ConditionalOnProperty(name = "workspace.finance.enabled", havingValue = "true")
public final class FinanceAnalyticsController {
    private final FinanceAnalyticsQueries queries;
    public FinanceAnalyticsController(FinanceAnalyticsQueries queries) { this.queries = queries; }
    @GetMapping("/api/v1/finance/annual")
    public FinanceAnalyticsQueries.Annual annual(@RequestParam(defaultValue = "0") int year, HttpServletRequest request) {
        if (request.getParameterMap().entrySet().stream().anyMatch(e -> !e.getKey().equals("year") || e.getValue().length != 1
                || !e.getValue()[0].matches("0|[12][0-9]{3}"))) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE");
        return queries.annual(year);
    }
    @RequestMapping(value = "/api/v1/finance/annual", method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public void unsupported(HttpServletRequest request) throws org.springframework.web.HttpRequestMethodNotSupportedException {
        throw new org.springframework.web.HttpRequestMethodNotSupportedException(request.getMethod(), Set.of("GET"));
    }
}
