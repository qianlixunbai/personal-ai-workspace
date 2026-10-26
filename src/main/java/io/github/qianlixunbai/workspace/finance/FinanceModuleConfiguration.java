package io.github.qianlixunbai.workspace.finance;

import com.financeos.configuration.FinanceInfrastructureConfiguration;
import com.financeos.module.account.service.AccountQueryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Path;

/** Imports only the property-gated Finance slice, without widening component scanning. */
@Configuration(proxyBeanMethods = false)
@Import({FinanceInfrastructureConfiguration.class, FinanceModuleConfiguration.AccountQueriesConfiguration.class})
public class FinanceModuleConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "workspace.finance.enabled", havingValue = "true", matchIfMissing = false)
    static class AccountQueriesConfiguration {
        @Bean
        LocalFinanceProfile localFinanceProfile(
                @Value("${workspace.data-directory:${user.home}/.personal-ai-workspace/data}") Path data,
                @Value("${workspace.security.token-file:.runtime/client-token}") Path tokenFile) {
            return new LocalFinanceProfile(data, tokenFile);
        }

        @Bean
        FinanceIdentityRepository financeIdentities(@Qualifier("financeDataSource") DataSource dataSource) {
            return new FinanceIdentityRepository(dataSource);
        }

        @Bean
        FinanceAccountListing financeAccountListing(@Qualifier("financeDataSource") DataSource dataSource) {
            return new FinanceAccountListing(dataSource);
        }

        @Bean
        FinanceMarketDataStore financeMarketDataStore(FinanceIdentityRepository identities) {
            return new FinanceMarketDataStore(java.time.Clock.systemUTC(), identities::dataContext);
        }

        @Bean
        FinanceMarketDataQueries financeMarketDataQueries(FinanceAccountQueries identity, FinanceInvestmentQueries positions, FinanceMarketDataStore store) {
            return new FinanceMarketDataQueries(identity, positions, store, new MassiveClient(store.clock));
        }

        @Bean
        com.financeos.module.asset.marketdata.service.MarketQuoteQueryService financeQuoteQueries(
                com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper mapper, FinanceMarketDataStore store) {
            return FinanceMarketDataQueries.quotes(mapper, store);
        }

        @Bean
        com.financeos.module.asset.valuation.service.ReferenceValuationService financeReferenceValuations(
                com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper mapper, FinanceMarketDataStore store) {
            return new com.financeos.module.asset.valuation.service.ReferenceValuationService(FinanceMarketDataQueries.rates(mapper, store), store.clock);
        }

        @Bean
        FinanceAssetQueries financeAssetQueries(FinanceAccountQueries identity,
                @Qualifier("financeDataSource") DataSource dataSource,
                com.financeos.module.asset.marketdata.service.MarketQuoteQueryService quotes,
                com.financeos.module.asset.valuation.service.ReferenceValuationService valuations) {
            return new FinanceAssetQueries(identity, dataSource, quotes, valuations);
        }

        @Bean
        FinanceInvestmentQueries financeInvestmentQueries(FinanceAccountQueries identity,
                com.financeos.module.investment.read.mapper.InvestmentReadMapper mapper,
                com.financeos.module.asset.marketdata.service.MarketQuoteQueryService quotes,
                com.financeos.module.asset.valuation.service.ReferenceValuationService valuations) {
            // Direct source services: the outer identity.query owns the Finance transaction manager.
            return new FinanceInvestmentQueries(identity,
                    new com.financeos.module.investment.read.service.InvestmentPositionReadQueryService(mapper, quotes, valuations),
                    new com.financeos.module.investment.read.service.InvestmentPositionDetailQueryService(mapper, quotes, valuations),
                    new com.financeos.module.investment.read.service.InvestmentPortfolioQueryService(mapper, quotes, valuations));
        }

        @Bean
        FinanceInvestmentHistoryQueries financeInvestmentHistoryQueries(FinanceAccountQueries identity,
                com.financeos.module.investment.read.mapper.InvestmentReadMapper reads,
                com.financeos.module.investment.mapper.InvestmentTransactionMapper facts,
                com.financeos.module.investment.mapper.InvestmentTransactionCorrectionMapper corrections) {
            return new FinanceInvestmentHistoryQueries(identity,
                    new com.financeos.module.investment.read.service.InvestmentLogicalTransactionReadQueryService(reads),
                    new com.financeos.module.investment.read.service.InvestmentLogicalTransactionDetailQueryService(facts, corrections, reads));
        }

        @Bean
        FinanceDashboardQueries financeDashboardQueries(FinanceAccountQueries identity, AccountQueryService accounts,
                @Qualifier("financeDataSource") DataSource source) {
            return new FinanceDashboardQueries(identity, accounts, source, java.time.Clock.system(java.time.ZoneId.of("Asia/Shanghai")));
        }

        @Bean
        FinanceAnalyticsQueries financeAnalyticsQueries(FinanceAccountQueries identity,
                @Qualifier("financeDataSource") DataSource source) {
            return new FinanceAnalyticsQueries(identity, source, java.time.Clock.system(java.time.ZoneId.of("Asia/Shanghai")));
        }

        @Bean
        FinanceImportQueries financeImportQueries(FinanceAccountQueries identity,
                @Qualifier("financeDataSource") DataSource source) {
            return new FinanceImportQueries(identity, source);
        }

        @Bean
        FinanceTransactionQueries financeTransactionQueries(FinanceAccountQueries identity,
                @Qualifier("financeDataSource") DataSource dataSource) {
            return new FinanceTransactionQueries(identity, dataSource);
        }

        @Bean
        FinanceAccountQueries financeAccountQueries(LocalFinanceProfile profile, FinanceIdentityRepository identities,
                AccountQueryService accounts, FinanceAccountListing listing,
                @Qualifier("financeTransactionManager") PlatformTransactionManager transactions) {
            return new FinanceAccountQueries(profile, identities, accounts, listing, transactions);
        }
    }
}
