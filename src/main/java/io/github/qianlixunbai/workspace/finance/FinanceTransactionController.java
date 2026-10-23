package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/finance/transactions")
@ConditionalOnProperty(name = "workspace.finance.enabled", havingValue = "true")
public final class FinanceTransactionController {
    private final FinanceTransactionQueries queries;
    public FinanceTransactionController(FinanceTransactionQueries queries) { this.queries = queries; }

    @GetMapping
    public FinanceTransactionQueries.TransactionPage list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size, @RequestParam(defaultValue = "") String accountId,
            @RequestParam(defaultValue = "") String startDate, @RequestParam(defaultValue = "") String endDate,
            @RequestParam(defaultValue = "ALL") String type, HttpServletRequest request) {
        if (request.getParameterMap().entrySet().stream().anyMatch(entry ->
                !Set.of("page", "size", "accountId", "startDate", "endDate", "type").contains(entry.getKey()) || entry.getValue().length != 1))
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE");
        return queries.list(page, size, accountId, startDate, endDate, type);
    }

    @GetMapping("/{kind}/{id}")
    public FinanceTransactionQueries.TransactionView transaction(@PathVariable String kind, @PathVariable long id, HttpServletRequest request) {
        if (request.getQueryString() != null) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE");
        return queries.transaction(kind, id);
    }
}
