package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.WorkspaceException;
import io.github.qianlixunbai.workspace.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
public final class FinanceOverviewController {
    private final ObjectProvider<FinanceAccountQueries> queries;
    public FinanceOverviewController(ObjectProvider<FinanceAccountQueries> queries) { this.queries = queries; }

    @GetMapping("/api/v1/finance/overview")
    public FinanceAccountQueries.Overview overview(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE");
        var service = queries.getIfAvailable();
        if (service == null) return new FinanceAccountQueries.Overview("DISABLED", null, null, null, null);
        try { return service.overview(); }
        catch (WorkspaceException rejected) {
            String state = switch (rejected.error().code()) {
                case FINANCE_NOT_BOUND -> "NOT_BOUND";
                case FINANCE_BINDING_INVALID -> "BINDING_INVALID";
                case FINANCE_UNAVAILABLE -> "UNAVAILABLE";
                default -> throw rejected;
            };
            return new FinanceAccountQueries.Overview(state, null, null, null, null);
        }
    }
}
