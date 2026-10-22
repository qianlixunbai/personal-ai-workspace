package io.github.qianlixunbai.workspace.finance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.account.service.AccountQueryService;
import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Deterministic JDBC/Mapper substitutes; these tests do not execute PostgreSQL SQL. */
class FinanceAccountQueriesTest {
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void applicationBoundaryIsConditionalAndUsesTheDedicatedFinanceComponents() {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(FinanceModuleConfiguration.class, FinanceAccountController.class);
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(LocalFinanceProfile.class).doesNotHaveBean(FinanceAccountQueries.class)
                .doesNotHaveBean(FinanceAccountController.class));
        runner.withPropertyValues("workspace.finance.enabled=true",
                "workspace.finance.datasource.url=jdbc:postgresql://127.0.0.1:1/unused",
                "workspace.finance.datasource.username=configuration-test",
                "workspace.finance.datasource.password=not-a-real-password")
                .withBean("unrelatedTransactionManager", PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FinanceAccountQueries.class)
                            .hasSingleBean(FinanceAccountController.class);
                    assertEquals("public", context.getBean("financeDataSource", PGSimpleDataSource.class).getCurrentSchema());
                });
    }

    @Test
    void nativeAuthenticationAndConfirmedBindingAreRequiredBeforeDatabaseAccess() {
        var profile = mock(LocalFinanceProfile.class);
        var database = mock(DataSource.class);
        var transactions = mock(PlatformTransactionManager.class);
        var accounts = mock(AccountQueryService.class);
        var queries = new FinanceAccountQueries(profile, new FinanceIdentityRepository(database), accounts, new FinanceAccountListing(database), transactions);
        assertCode(ErrorCode.POLICY_DENIED, queries::cnyBalance); // No implicit Native fallback.
        authenticate(new ClientIdentity("fixture", "browser", "fixture", null, Instant.EPOCH, Set.of("translate")));
        assertCode(ErrorCode.POLICY_DENIED, queries::cnyBalance);
        verifyNoInteractions(profile, database, accounts, new FinanceAccountListing(database), transactions);
        authenticate(ClientIdentity.NATIVE);
        when(profile.requireBinding()).thenThrow(new WorkspaceException(ErrorCode.FINANCE_NOT_BOUND, "FINANCE"));
        assertCode(ErrorCode.FINANCE_NOT_BOUND, queries::cnyBalance);
        verifyNoInteractions(database, accounts, new FinanceAccountListing(database), transactions);
    }

    @Test
    void bindingParserRejectsAmbiguousOrUnversionedIdentityAndKeepsThePortableProfile() {
        UUID id = UUID.randomUUID();
        String binding = "{\"format\":1,\"profileId\":\"" + id + "\",\"financeUserId\":42,"
                + "\"datasetFingerprint\":\"" + "a".repeat(64) + "\",\"userFingerprint\":\"" + "b".repeat(64) + "\"}";
        assertEquals(id, LocalFinanceProfile.parse(binding.getBytes(StandardCharsets.UTF_8)).profileId());
        assertCode(ErrorCode.FINANCE_BINDING_INVALID, () -> LocalFinanceProfile.parse(
                binding.replace("\"format\":1", "\"format\":1,\"format\":2").getBytes(StandardCharsets.UTF_8)));
        assertCode(ErrorCode.FINANCE_BINDING_INVALID, () -> LocalFinanceProfile.parse(
                binding.replace("\"format\":1", "\"format\":4294967297").getBytes(StandardCharsets.UTF_8)));
        assertCode(ErrorCode.FINANCE_BINDING_INVALID, () -> LocalFinanceProfile.parse(
                binding.replace("\"financeUserId\":42", "\"userId\":42").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void trustedReadPreservesAmountsUserScopeAndTransactionThenRejectsChangedIdentity() throws Exception {
        authenticate(ClientIdentity.NATIVE);
        var fixture = new ReadFixture();
        var identity = fixture.identity();
        var binding = new LocalFinanceProfile.Binding(1, UUID.randomUUID(), 42,
                identity.datasetFingerprint(), identity.userFingerprint());
        var profile = mock(LocalFinanceProfile.class);
        when(profile.requireBinding()).thenReturn(binding);
        when(profile.createConfirmed(identity)).thenReturn(binding);
        var queries = new FinanceAccountQueries(profile, fixture.identities,
                new AccountQueryService(fixture.mapper), new FinanceAccountListing(fixture.database), new JdbcTransactionManager(fixture.database));
        var controller = new FinanceAccountController(queries);
        var request = new MockHttpServletRequest("GET", "/api/v1/finance/accounts/balance");
        var balance = controller.balance(request);
        assertEquals("9007199254740993.30", balance.balance());
        var json = JsonMapper.builder().build().valueToTree(balance);
        assertTrue(json.get("balance").isString());
        assertEquals("9007199254740993.30", json.get("balance").asString());
        assertEquals("9007199254740993.10", queries.account(101).balance());
        assertCode(ErrorCode.FINANCE_ACCOUNT_NOT_FOUND, () -> queries.account(202));
        request.setQueryString("userId=77");
        assertCode(ErrorCode.INVALID_REQUEST, () -> controller.balance(request));

        assertEquals(binding, queries.confirmHistoricalBinding(identity));
        int readsBeforeMismatch = fixture.accountReads.get();
        fixture.cluster = "another-cluster"; // Same numeric users.id in a different database is insufficient.
        assertCode(ErrorCode.FINANCE_BINDING_INVALID, queries::cnyBalance);
        assertCode(ErrorCode.FINANCE_BINDING_INVALID, () -> queries.list(0, 2, "ALL", "ID_ASC"));
        assertCode(ErrorCode.FINANCE_BINDING_INVALID, () -> queries.confirmHistoricalBinding(identity));
        fixture.cluster = "cluster-one";
        fixture.status = "DISABLED";
        assertCode(ErrorCode.FINANCE_BINDING_INVALID, queries::cnyBalance);
        fixture.status = "ACTIVE";
        fixture.userExists = false;
        assertCode(ErrorCode.FINANCE_BINDING_INVALID, queries::cnyBalance);
        assertEquals(readsBeforeMismatch, fixture.accountReads.get());
        verify(profile, times(1)).createConfirmed(identity);
        verify(fixture.connection, atLeastOnce()).commit();
        verify(fixture.connection, atLeastOnce()).rollback();
    }

    private static void authenticate(ClientIdentity client) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                client, null, List.of(new SimpleGrantedAuthority("ROLE_LOCAL_CLIENT"))));
    }

    private static void assertCode(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(WorkspaceException.class, action).error().code());
    }

    private static final class ReadFixture {
        final DataSource database = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final AccountMapper mapper = mock(AccountMapper.class);
        final FinanceIdentityRepository identities = new FinanceIdentityRepository(database);
        final AtomicInteger accountReads = new AtomicInteger();
        final LocalDateTime created = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        String cluster = "cluster-one", status = "ACTIVE";
        boolean userExists = true;

        ReadFixture() throws Exception {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Account.class);
            when(database.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                assertReadTransaction();
                String sql = invocation.getArgument(0);
                assertTrue(sql.contains("pg_catalog.pg_control_system()"));
                assertTrue(sql.contains("public.users"));
                assertTrue(sql.contains("WHERE users.id = ?"));
                assertFalse(sql.contains("password_hash"));
                var statement = mock(PreparedStatement.class);
                var selected = new AtomicReference<Object>();
                doAnswer(call -> { selected.set(call.getArgument(1)); return null; })
                        .when(statement).setObject(eq(1), any());
                when(statement.executeQuery()).thenAnswer(call -> {
                    assertEquals(42L, selected.get());
                    var rows = mock(ResultSet.class);
                    when(rows.next()).thenReturn(userExists, false);
                    when(rows.getLong("id")).thenReturn(42L);
                    when(rows.getString("cluster_id")).thenReturn(cluster);
                    when(rows.getString("database_id")).thenReturn("17001");
                    when(rows.getString("database_name")).thenReturn("isolated-fixture");
                    when(rows.getString("username")).thenReturn("confirmed-user");
                    when(rows.getString("email")).thenReturn("fixture@example.invalid");
                    when(rows.getString("status")).thenReturn(status);
                    when(rows.getObject("created_at", LocalDateTime.class)).thenReturn(created);
                    return rows;
                });
                return statement;
            });
            when(mapper.selectList(any())).thenAnswer(call -> {
                assertReadTransaction();
                accountReads.incrementAndGet();
                LambdaQueryWrapper<Account> wrapper = call.getArgument(0);
                assertThat(wrapper.getSqlSegment()).contains("user_id", "currency");
                assertThat(wrapper.getParamNameValuePairs().values()).containsExactlyInAnyOrder(42L, "CNY");
                return List.of(account(101, "9007199254740993.10"), account(102, "0.20"));
            });
            when(mapper.selectOne(any())).thenAnswer(call -> {
                assertReadTransaction();
                accountReads.incrementAndGet();
                LambdaQueryWrapper<Account> wrapper = call.getArgument(0);
                assertThat(wrapper.getSqlSegment()).contains("user_id", "id");
                assertThat(wrapper.getParamNameValuePairs().values()).contains(42L);
                return wrapper.getParamNameValuePairs().containsValue(101L) ? account(101, "9007199254740993.10") : null;
            });
        }

        FinanceIdentityRepository.Identity identity() {
            return new FinanceIdentityRepository.Identity(42,
                    FinanceIdentityRepository.digest(cluster, "17001", "isolated-fixture"),
                    FinanceIdentityRepository.digest("42", "confirmed-user", "fixture@example.invalid", created.toString()));
        }

        void assertReadTransaction() {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ,
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            assertTrue(DataSourceUtils.isConnectionTransactional(connection, database));
        }

        static Account account(long id, String amount) {
            var account = new Account();
            account.setId(id); account.setUserId(42L); account.setName("fixture");
            account.setType("BANK"); account.setCurrency("CNY"); account.setStatus("ACTIVE");
            account.setBalance(new BigDecimal(amount));
            return account;
        }
    }
}
