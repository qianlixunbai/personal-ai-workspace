import com.financeos.configuration.FinanceInfrastructureConfiguration;
import com.financeos.module.asset.marketdata.config.MarketDataProperties;
import com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import com.financeos.module.asset.marketdata.fx.config.FxDataProperties;
import com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateQueryService;
import com.financeos.module.asset.valuation.service.ReferenceValuationService;
import com.financeos.module.investment.read.dto.InvestmentPortfolioResponse;
import com.financeos.module.investment.read.mapper.InvestmentReadMapper;
import com.financeos.module.investment.read.service.InvestmentPortfolioQueryService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.io.*;
import java.time.Clock;
import java.util.*;

/** One real PostgreSQL source-query flow; only SELECT through the isolated reader role. */
public final class FinancePortfolioVerification {
    private static void require(boolean condition, String invariant) {
        if (!condition) throw new IllegalStateException(invariant);
    }
    public static void main(String[] args) {
        PrintStream output = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); System.setErr(System.out);
        byte[] input = null;
        try {
            input = System.in.readNBytes(16385); require(input.length <= 16384, "INPUT");
            var config = JsonMapper.builder().build().readTree(input);
            String suffix = config.path("suffix").asString(); int port = config.path("port").asInt();
            boolean baseline = args.length == 1 && args[0].equals("--existing-positions-only");
            require((args.length == 0 || baseline) && suffix.matches("[a-f0-9]{12}") && port >= 55000 && port <= 55999
                && (baseline ? config.path("investments").asBoolean(false) : config.path("portfolio").asBoolean(false)), "ISOLATED_PORTFOLIO_FIXTURE");
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-portfolio", Map.of(
                    "workspace.finance.enabled", "true", "workspace.finance.datasource.url", "jdbc:postgresql://127.0.0.1:" + port + "/fnext1_finance_" + suffix,
                    "workspace.finance.datasource.username", "fnext1_reader_" + suffix, "workspace.finance.datasource.password", config.path("readerPassword").asString())));
                context.register(FinanceInfrastructureConfiguration.class); context.refresh();
                var data = context.getBean("financeDataSource", DataSource.class);
                var jdbc = new JdbcTemplate(data);
                require(("FNEXT1_SYNTHETIC_" + suffix).equals(jdbc.queryForObject("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()", String.class)), "DATASET_MARKER");
                // Make source freshness cases reproducible after this fixture's real cache TTL has elapsed.
                // The Windows product continues to use its ordinary system clock.
                var clock = Clock.fixed(jdbc.queryForObject("SELECT fetched_at FROM market_quotes WHERE market='US' AND symbol='SYNCNY' AND provider='SYNTHETIC_FIXED'",
                    (row, index) -> row.getTimestamp(1).toInstant()), java.time.ZoneOffset.UTC);
                var service = new InvestmentPortfolioQueryService(context.getBean(InvestmentReadMapper.class),
                    new MarketQuoteQueryService(context.getBean(MarketQuoteMapper.class), new MarketDataProperties(), clock),
                    new ReferenceValuationService(new ExchangeRateQueryService(context.getBean(ExchangeRateMapper.class), new FxDataProperties(), clock), clock));
                var tx = new TransactionTemplate(context.getBean("financeTransactionManager", PlatformTransactionManager.class));
                tx.setReadOnly(true); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.executeWithoutResult(status -> {
                    require("on".equals(jdbc.queryForObject("SHOW transaction_read_only", String.class)) && "repeatable read".equals(jdbc.queryForObject("SHOW transaction_isolation", String.class)), "READ_SNAPSHOT");
                    var before = jdbc.queryForList("SELECT id,xmin::text,quantity,total_cost,realized_profit_loss,current_price,market_value,projection_version FROM assets ORDER BY id");
                    if (!baseline) {
                    check(service.get(101L), 0, 0, 0, "0.00", "0.00", null, 0, "UNAVAILABLE");
                    check(service.get(102L), 2, 0, 2, "0.00", "-2.12", null, 0, "UNAVAILABLE");
                    check(service.get(103L), 2, 2, 0, "0.24", "-2.12", "0.04", 2, "FRESH");
                    var stale = service.get(104L);
                    check(stale, 2, 2, 0, "0.24", "-2.12", "0.18", 2, "STALE");
                    require(stale.referenceValuation().warnings().size() == 4 && stale.referenceValuation().warnings().stream().anyMatch(w -> w.code().equals("FX_STALE")), "STALE_WARNINGS");
                    var missing = service.get(105L);
                    check(missing, 2, 2, 0, "0.24", "-2.12", null, 0, "UNAVAILABLE");
                    require(missing.referenceValuation().warnings().stream().anyMatch(w -> w.code().equals("QUOTE_MISSING")) && missing.referenceValuation().warnings().stream().anyMatch(w -> w.code().equals("FX_MISSING")), "MISSING_WARNINGS");
                    var partial = service.get(106L);
                    check(partial, 2, 2, 0, "0.24", "-2.12", "0.09", 1, "PARTIAL");
                    require(partial.referenceValuation().warnings().stream().anyMatch(w -> w.code().equals("QUOTE_STALE")), "PARTIAL_RETAINS_STALE");
                    }
                    check(service.get(1L), 6, 5, 1, "9007199254741011.42", "-2.00", "6305039478318695.21", 2, "PARTIAL");
                    check(service.get(2L), 1, 1, 0, "999.00", "0.00", "699.30", 1, "STALE");
                    require(before.equals(jdbc.queryForList("SELECT id,xmin::text,quantity,total_cost,realized_profit_loss,current_price,market_value,projection_version FROM assets ORDER BY id")), "READS_PRESERVE_PROJECTIONS");
                });
            }
            output.println(baseline ? "FNEXT7_EXISTING_FNEXT6_ALICE_BOB_SOURCE_PORTFOLIO_READONLY_PASS"
                : "FNEXT7_REAL_POSTGRES_SOURCE_PORTFOLIO_EMPTY_CLOSED_OPEN_MIXED_EXACT_SAME_INSTRUMENT_ROUNDING_PARTIAL_STALE_MISSING_QUOTE_FX_READONLY_PASS");
        } catch (Exception | LinkageError error) {
            for (var frame : error.getStackTrace()) output.println(frame);
            output.println("FNEXT7_SOURCE_STOP_" + error.getClass().getSimpleName() + (error instanceof IllegalStateException ? "_" + error.getMessage().replaceAll("[^A-Z_]", "") : "")); System.exit(10);
        } finally { if (input != null) Arrays.fill(input, (byte)0); }
    }
    private static void check(InvestmentPortfolioResponse p, int count, int open, int closed, String cost, String realized, String value, int valued, String state) {
        require(p.currency().equals("CNY") && p.positionCount() == count && p.openPositionCount() == open && p.closedPositionCount() == closed, "COUNTS");
        require(p.openTotalCost().equals(cost) && p.cumulativeRealizedProfitLoss().equals(realized), "ACCOUNTING");
        var r = p.referenceValuation();
        require(r.baseCurrency().equals("CNY") && Objects.equals(r.value(), value) && r.valuedPositionCount() == valued && r.totalOpenPositionCount() == open && r.freshness().equals(state), "VALUATION");
    }
}
