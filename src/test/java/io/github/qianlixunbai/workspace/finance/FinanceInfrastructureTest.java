package io.github.qianlixunbai.workspace.finance;

import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.account.service.AccountQueryService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Configuration boundaries only: no PostgreSQL connection, SQL or migration execution. */
class FinanceInfrastructureTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FinanceModuleConfiguration.class);

    @Test
    void disabledFinanceLeavesExistingSqliteAloneEvenWithMigrationOptIn() throws Exception {
        var sqlite = new DriverManagerDataSource("jdbc:sqlite::memory:");
        try (var connection = sqlite.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE workspace_sentinel (value TEXT)");
            statement.execute("INSERT INTO workspace_sentinel VALUES ('preserved')");
            runner.withBean("workspaceDataSource", DataSource.class, () -> sqlite)
                    .withPropertyValues("workspace.finance.flyway.enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(DataSource.class)
                                .doesNotHaveBean("financeDataSource")
                                .doesNotHaveBean(SqlSessionFactory.class)
                                .doesNotHaveBean(SqlSessionTemplate.class)
                                .doesNotHaveBean(PlatformTransactionManager.class)
                                .doesNotHaveBean(Flyway.class)
                                .doesNotHaveBean(AccountMapper.class)
                                .doesNotHaveBean(AccountQueryService.class);
                        assertThat(context.getBean(DataSource.class)).isSameAs(sqlite);
                    });
            try (var result = statement.executeQuery("SELECT value FROM workspace_sentinel")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("preserved");
            }
        }
    }

    @Test
    void enabledFinanceRejectsMissingConfigurationAndRemoteDatabaseWithoutEchoingValues() {
        runner.withPropertyValues("workspace.finance.enabled=true").run(context ->
                assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Finance requires workspace.finance.datasource.url"));

        runner.withPropertyValues("workspace.finance.enabled=true",
                "workspace.finance.datasource.url=jdbc:postgresql://127.0.0.1:1/unused",
                "workspace.finance.datasource.username=configuration-test").run(context ->
                assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Finance requires workspace.finance.datasource.password"));

        configuredRunner().withPropertyValues(
                "workspace.finance.datasource.url=jdbc:postgresql://remote.invalid:5432/unused")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage(
                        "workspace.finance.datasource.url must use an explicit loopback host, port and database; URL options are not allowed"));
    }

    @Test
    void enabledWiringStaysDedicatedAndFlywayNeverRunsAtContextStartup() {
        var sqlite = new DriverManagerDataSource("jdbc:sqlite::memory:");
        var existingTransactions = new JdbcTransactionManager(sqlite);
        var configured = configuredRunner()
                .withBean("workspaceDataSource", DataSource.class, () -> sqlite)
                .withBean("workspaceTransactionManager", PlatformTransactionManager.class, () -> existingTransactions)
                .withBean(UnqualifiedJdbcConsumer.class);

        configured.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(Flyway.class);
            var finance = context.getBean("financeDataSource", PGSimpleDataSource.class);
            var factory = context.getBean("financeSqlSessionFactory", SqlSessionFactory.class);
            var template = context.getBean("financeSqlSessionTemplate", SqlSessionTemplate.class);
            var transactions = context.getBean("financeTransactionManager", JdbcTransactionManager.class);
            assertThat(factory.getConfiguration().getEnvironment().getDataSource()).isSameAs(finance);
            assertThat(factory.getConfiguration().getEnvironment().getTransactionFactory())
                    .isInstanceOf(SpringManagedTransactionFactory.class);
            assertThat(template.getSqlSessionFactory()).isSameAs(factory);
            assertThat(context.getBean("&accountMapper", MapperFactoryBean.class).getSqlSession()).isSameAs(template);
            assertThat(context.getBean(AccountQueryService.class)).isNotNull();
            assertThat(transactions.getDataSource()).isSameAs(finance);
            var consumer = context.getBean(UnqualifiedJdbcConsumer.class);
            assertThat(consumer.dataSource()).isSameAs(sqlite);
            assertThat(consumer.transactionManager()).isSameAs(existingTransactions);
        });

        configured.withPropertyValues("workspace.finance.flyway.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            var migration = context.getBean("financeFlyway", Flyway.class).getConfiguration();
            assertThat(migration.getDataSource()).isSameAs(context.getBean("financeDataSource"));
            assertThat(migration.getLocations()).extracting(Object::toString)
                    .containsExactly("classpath:db/finance/migration");
            assertThat(migration.getDefaultSchema()).isEqualTo("public");
            assertThat(migration.isBaselineOnMigrate()).isFalse();
            assertThat(migration.isCleanDisabled()).isTrue();
            assertThat(migration.isCreateSchemas()).isFalse();
            assertThat(migration.isFailOnMissingLocations()).isTrue();
        });
    }

    private ApplicationContextRunner configuredRunner() {
        return runner.withPropertyValues("workspace.finance.enabled=true",
                "workspace.finance.datasource.url=jdbc:postgresql://127.0.0.1:1/unused",
                "workspace.finance.datasource.username=configuration-test",
                "workspace.finance.datasource.password=not-a-real-password");
    }

    record UnqualifiedJdbcConsumer(DataSource dataSource, PlatformTransactionManager transactionManager) {}
}
