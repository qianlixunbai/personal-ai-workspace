package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/finance/investment/positions")
@ConditionalOnProperty(name = "workspace.finance.enabled", havingValue = "true")
public final class FinanceInvestmentController {
    private final FinanceInvestmentQueries queries;
    public FinanceInvestmentController(FinanceInvestmentQueries queries) { this.queries = queries; }

    @GetMapping
    public FinanceInvestmentQueries.PositionPage list(@RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(required = false) Long accountId, @RequestParam(required = false) Long instrumentId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "10") int size,
            HttpServletRequest request) {
        if (request.getParameterMap().entrySet().stream().anyMatch(e ->
                !Set.of("status", "accountId", "instrumentId", "cursor", "size").contains(e.getKey())
                        || e.getValue().length != 1 || e.getValue()[0].isBlank())) throw invalid();
        return queries.list(status, accountId, instrumentId, cursor, size);
    }

    @GetMapping("/{id}")
    public FinanceInvestmentQueries.Detail position(@PathVariable long id, HttpServletRequest request) {
        if (request.getQueryString() != null) throw invalid();
        return queries.position(id);
    }

    // Spring otherwise supplies implicit HEAD/OPTIONS handlers. This fixed boundary declares GET only.
    @RequestMapping(value = {"", "/{id}"}, method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public void unsupported(HttpServletRequest request) throws org.springframework.web.HttpRequestMethodNotSupportedException {
        throw new org.springframework.web.HttpRequestMethodNotSupportedException(request.getMethod(), Set.of("GET"));
    }
    private static WorkspaceException invalid() { return new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE"); }
}
