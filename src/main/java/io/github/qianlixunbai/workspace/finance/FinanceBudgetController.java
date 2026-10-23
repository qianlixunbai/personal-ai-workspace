package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import io.github.qianlixunbai.workspace.finance.FinanceAccountQueries.BudgetAvailability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The frozen source has no budget model or statistics contract. Never report this as an empty budget list. */
@RestController
public final class FinanceBudgetController {
    private final ObjectProvider<FinanceAccountQueries> queries;
    public FinanceBudgetController(ObjectProvider<FinanceAccountQueries> queries) { this.queries = queries; }

    @GetMapping("/api/v1/finance/budgets")
    public BudgetAvailability availability(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE");
        var service = queries.getIfAvailable();
        if (service == null) return new BudgetAvailability("DISABLED");
        try { return service.budgetAvailability(); }
        catch (WorkspaceException rejected) {
            return new BudgetAvailability(switch (rejected.error().code()) {
                case FINANCE_NOT_BOUND -> "NOT_BOUND";
                case FINANCE_BINDING_INVALID -> "BINDING_INVALID";
                case FINANCE_UNAVAILABLE -> "UNAVAILABLE";
                default -> throw rejected;
            });
        }
    }
}
