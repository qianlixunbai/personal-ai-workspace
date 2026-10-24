package io.github.qianlixunbai.workspace.finance;

import com.financeos.module.investment.read.model.LogicalTransactionListQuery;
import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/finance/investment/transactions")
@ConditionalOnProperty(name = "workspace.finance.enabled", havingValue = "true")
public final class FinanceInvestmentHistoryController {
    private final FinanceInvestmentHistoryQueries queries;
    public FinanceInvestmentHistoryController(FinanceInvestmentHistoryQueries queries) { this.queries = queries; }
    @GetMapping
    public FinanceInvestmentHistoryQueries.Page list(@RequestParam(required = false) Long positionId,
            @RequestParam(required = false) Long accountId, @RequestParam(required = false) Long instrumentId,
            @RequestParam(required = false) String type, @RequestParam(required = false) String correctionStatus,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "10") int size, HttpServletRequest request) {
        if (request.getParameterMap().entrySet().stream().anyMatch(e -> !Set.of("positionId", "accountId", "instrumentId", "type", "correctionStatus", "from", "to", "cursor", "size").contains(e.getKey())
                || e.getValue().length != 1 || e.getValue()[0].isBlank())) throw invalid();
        return queries.list(new LogicalTransactionListQuery(positionId, accountId, instrumentId, type, correctionStatus, from, to, cursor, size));
    }
    @GetMapping("/{id}")
    public FinanceInvestmentHistoryQueries.Detail detail(@PathVariable long id, HttpServletRequest request) {
        if (request.getQueryString() != null) throw invalid();
        return queries.detail(id);
    }
    @GetMapping("/{id}/audit-timeline")
    public FinanceInvestmentHistoryQueries.Timeline timeline(@PathVariable long id, HttpServletRequest request) {
        if (request.getQueryString() != null) throw invalid();
        return queries.timeline(id);
    }
    @RequestMapping(value = {"", "/{id}", "/{id}/audit-timeline"}, method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public void unsupported(HttpServletRequest request) throws org.springframework.web.HttpRequestMethodNotSupportedException {
        throw new org.springframework.web.HttpRequestMethodNotSupportedException(request.getMethod(), Set.of("GET"));
    }
    private static WorkspaceException invalid() { return new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE"); }
}
