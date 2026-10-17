package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/finance/accounts")
@ConditionalOnProperty(name = "workspace.finance.enabled", havingValue = "true", matchIfMissing = false)
public final class FinanceAccountController {
    private final FinanceAccountQueries queries;

    public FinanceAccountController(FinanceAccountQueries queries) { this.queries = queries; }

    @GetMapping("/balance")
    public FinanceAccountQueries.CnyBalance balance(HttpServletRequest request) {
        noParameters(request);
        return queries.cnyBalance();
    }

    @GetMapping("/{accountId}")
    public FinanceAccountQueries.AccountView account(@PathVariable long accountId, HttpServletRequest request) {
        noParameters(request);
        return queries.account(accountId);
    }

    private static void noParameters(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE");
    }
}
