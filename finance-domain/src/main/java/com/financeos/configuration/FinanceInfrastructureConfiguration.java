package com.financeos.configuration;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.regex.Pattern;

/** Explicit, local PostgreSQL wiring. Bean creation does not connect or run SQL. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "workspace.finance.enabled", havingValue = "true", matchIfMissing = false)
@Import({FinanceAccountQueryConfiguration.class, FinanceInfrastructureConfiguration.MigrationConfiguration.class})
public class FinanceInfrastructureConfiguration {
    private static final String PREFIX = "workspace.finance.datasource.";
    // No URL options, credentials, multiple hosts, implicit database or remote endpoints.
    private static final Pattern LOCAL_URL = Pattern.compile(
            "jdbc:postgresql://(127\\.0\\.0\\.1|localhost|\\[::1\\]):([0-9]{1,5})/([A-Za-z0-9_][A-Za-z0-9_.-]*)");

    @Bean(name = "financeDataSource", defaultCandidate = false)
    PGSimpleDataSource financeDataSource(Environment environment) {
        String url = required(environment, "url");
        String username = required(environment, "username");
        String password = required(environment, "password");
        var match = LOCAL_URL.matcher(url);
        if (!match.matches()) {
            throw new IllegalStateException(PREFIX + "url must use an explicit loopback host, port and database; URL options are not allowed");
        }
        int port = Integer.parseInt(match.group(2));
        if (port < 1 || port > 65535) {
            throw new IllegalStateException(PREFIX + "url must specify a port between 1 and 65535");
        }

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[]{match.group(1)});
        dataSource.setPortNumbers(new int[]{port});
        dataSource.setDatabaseName(match.group(3));
        dataSource.setCurrentSchema("public");
        dataSource.setUser(username);
        dataSource.setPassword(password);
        dataSource.setConnectTimeout(5);
        dataSource.setSocketTimeout(30);
        return dataSource;
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(PREFIX + name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Finance requires " + PREFIX + name);
        }
        return value;
    }

    /** A separate opt-in creates only a migration definition, never a startup initializer. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = {"workspace.finance.enabled", "workspace.finance.flyway.enabled"},
            havingValue = "true", matchIfMissing = false)
    static class MigrationConfiguration {
        @Bean(name = "financeFlyway", defaultCandidate = false)
        Flyway financeFlyway(@Qualifier("financeDataSource") DataSource dataSource) {
            return Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/finance/migration")
                    .defaultSchema("public")
                    .schemas("public")
                    .createSchemas(false)
                    .baselineOnMigrate(false)
                    .cleanDisabled(true)
                    .failOnMissingLocations(true)
                    .validateMigrationNaming(true)
                    .load();
        }
    }
}
