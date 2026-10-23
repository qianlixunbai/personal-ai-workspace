package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/finance/assets")
@ConditionalOnProperty(name = "workspace.finance.enabled", havingValue = "true")
public final class FinanceAssetController {
    private final FinanceAssetQueries queries;
    public FinanceAssetController(FinanceAssetQueries queries) { this.queries = queries; }

    @GetMapping
    public FinanceAssetQueries.AssetPage list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size, @RequestParam(defaultValue = "ALL") String type,
            @RequestParam(defaultValue = "CREATED_DESC") String sort, HttpServletRequest request) {
        if (request.getParameterMap().entrySet().stream().anyMatch(e ->
                !Set.of("page", "size", "type", "sort").contains(e.getKey()) || e.getValue().length != 1))
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE");
        return queries.list(page, size, type, sort);
    }

    @GetMapping("/{id}")
    public FinanceAssetQueries.AssetView asset(@PathVariable long id, HttpServletRequest request) {
        if (request.getQueryString() != null) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE");
        return queries.asset(id);
    }
}
