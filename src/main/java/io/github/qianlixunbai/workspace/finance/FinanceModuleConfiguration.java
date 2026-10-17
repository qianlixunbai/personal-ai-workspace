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
        FinanceAccountQueries financeAccountQueries(LocalFinanceProfile profile, FinanceIdentityRepository identities,
                AccountQueryService accounts,
                @Qualifier("financeTransactionManager") PlatformTransactionManager transactions) {
            return new FinanceAccountQueries(profile, identities, accounts, transactions);
        }
    }
}
