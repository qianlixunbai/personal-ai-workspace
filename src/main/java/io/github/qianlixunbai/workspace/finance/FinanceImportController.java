package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/finance/imports/transactions/batches")
@ConditionalOnProperty(name = "workspace.finance.enabled", havingValue = "true")
public final class FinanceImportController {
    private final FinanceImportQueries queries;
    public FinanceImportController(FinanceImportQueries queries) { this.queries = queries; }
    @GetMapping
    public FinanceImportQueries.Page list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size, HttpServletRequest request) {
        parameters(request, Set.of("page", "size"));
        return queries.list(page, size);
    }
    @GetMapping("/{id}")
    public FinanceImportQueries.Segment detail(@PathVariable String id, @RequestParam(defaultValue = "0") int transactionPage,
            @RequestParam(defaultValue = "0") int accountImpactPage, HttpServletRequest request) {
        parameters(request, Set.of("transactionPage", "accountImpactPage"));
        return queries.detail(id, transactionPage, accountImpactPage);
    }
    @RequestMapping(value = {"", "/{id}"}, method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public void unsupported(HttpServletRequest request) throws org.springframework.web.HttpRequestMethodNotSupportedException {
        throw new org.springframework.web.HttpRequestMethodNotSupportedException(request.getMethod(), Set.of("GET"));
    }
    private static void parameters(HttpServletRequest request, Set<String> allowed) {
        if (request.getParameterMap().entrySet().stream().anyMatch(e -> !allowed.contains(e.getKey()) || e.getValue().length != 1
                || !e.getValue()[0].matches("0|[1-9][0-9]{0,5}"))) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE");
    }
}
