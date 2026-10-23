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
        FinanceAssetQueries financeAssetQueries(FinanceAccountQueries identity,
                @Qualifier("financeDataSource") DataSource dataSource,
                com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper quoteMapper,
                com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper rateMapper) {
            var clock = java.time.Clock.systemUTC();
            var quotes = new com.financeos.module.asset.marketdata.service.MarketQuoteQueryService(
                    quoteMapper,
                    new com.financeos.module.asset.marketdata.config.MarketDataProperties(), clock);
            var rates = new com.financeos.module.asset.marketdata.fx.service.ExchangeRateQueryService(
                    rateMapper,
                    new com.financeos.module.asset.marketdata.fx.config.FxDataProperties(), clock);
            return new FinanceAssetQueries(identity, dataSource, quotes,
                    new com.financeos.module.asset.valuation.service.ReferenceValuationService(rates, clock));
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
