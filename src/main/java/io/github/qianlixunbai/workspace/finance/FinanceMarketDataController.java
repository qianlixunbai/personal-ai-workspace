package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/finance/market-data")
@ConditionalOnProperty(name = "workspace.finance.enabled", havingValue = "true")
public final class FinanceMarketDataController {
    private final FinanceMarketDataQueries queries;
    FinanceMarketDataController(FinanceMarketDataQueries queries) { this.queries = queries; }
    @GetMapping public FinanceMarketDataQueries.Status status(HttpServletRequest request) { noQuery(request); return queries.status(); }
    @PostMapping("/refresh") public FinanceMarketDataQueries.Status refresh(@RequestBody JsonNode body, HttpServletRequest request) {
        noQuery(request);
        if (body.has("referenceSymbol")) {
            fields(body, "referenceSymbol");
            if (!body.path("referenceSymbol").isString()) throw invalid();
            return queries.refresh("", body.path("referenceSymbol").asString());
        }
        fields(body, "positionId");
        if (!body.path("positionId").isString()) throw invalid();
        return queries.refresh(body.path("positionId").asString());
    }
    /** Native WPF only: no corresponding WebMessage method accepts a credential/configuration payload. */
    @PostMapping("/configuration") public FinanceMarketDataQueries.Status configure(@RequestBody JsonNode body, HttpServletRequest request) {
        noQuery(request); fields(body, "apiKey");
        if (!body.path("apiKey").isString() || !FinanceMarketDataStore.validKey(body.path("apiKey").asString())) throw invalid();
        return queries.configure(body.path("apiKey").asString());
    }
    private static void fields(JsonNode body, String... fields) {
        if (!body.isObject() || body.size() != fields.length) throw invalid();
        for (String field : fields) if (!body.has(field)) throw invalid();
    }
    private static void noQuery(HttpServletRequest request) { if (request.getQueryString() != null) throw invalid(); }
    private static WorkspaceException invalid() { return new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE"); }
}
