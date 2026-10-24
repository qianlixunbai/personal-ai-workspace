package io.github.qianlixunbai.workspace.finance;

import com.financeos.configuration.FinanceInfrastructureConfiguration;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.account.service.AccountQueryService;
import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit destructive-scope opt-in: only the authorized test database, never a Boot startup hook.
 * Run using scripts/verification/run-finance-postgresql-test.ps1, which always removes credentials.
 * A Windows profile IO refusal is recorded separately, never counted as a successful real binding.
 */
@EnabledIfSystemProperty(named = "finance.postgresql.test", matches = "true")
class FinancePostgresqlIntegrationTest {
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/personal_ai_workspace_test";
    private static final String APP = "personal_ai_workspace_test_app";
    private static final String MIGRATOR = "personal_ai_workspace_test_migrator";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Map<String, Object> evidence = new LinkedHashMap<>();

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "FINANCE_DRILLDOWN_TEST_ROOT", matches = ".+")
    void dashboardDrilldownUsesExactReadOnlyScopeAndBoundUserOnExistingSyntheticPostgresql() throws Exception {
        Path root = Path.of(System.getenv("FINANCE_DRILLDOWN_TEST_ROOT")).toAbsolutePath();
        String suffix = root.getFileName().toString();
        assertTrue(suffix.matches("[a-f0-9]{12}"));
        assertEquals(Path.of("D:/PersonalAiWorkspace-Fnext1", suffix).toAbsolutePath(), root);
        Path file = root.resolve("finance.dpapi"); verifyPrivateCredential(file);
        byte[] plain = com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(Files.readAllBytes(file), null, 1, null);
        var config = JSON.readTree(plain); Arrays.fill(plain, (byte) 0);
        int port = config.path("port").asInt(); assertTrue(port >= 55000 && port <= 55999);
        assertEquals("127.0.0.1", config.path("host").asString());
        assertEquals("fnext1_finance_" + suffix, config.path("database").asString());
        assertEquals("fnext1_reader_" + suffix, config.path("username").asString());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("drilldown-read-only-fixture", Map.of(
                    "workspace.finance.enabled", "true", "workspace.finance.flyway.enabled", "false",
                    "workspace.finance.datasource.url", "jdbc:postgresql://127.0.0.1:" + port + "/fnext1_finance_" + suffix,
                    "workspace.finance.datasource.username", "fnext1_reader_" + suffix,
                    "workspace.finance.datasource.password", config.path("password").asString())));
            context.register(FinanceInfrastructureConfiguration.class); context.refresh(); config = null;
            var source = context.getBean("financeDataSource", PGSimpleDataSource.class);
            var jdbc = new JdbcTemplate(source);
            assertEquals("FNEXT1_SYNTHETIC_" + suffix, jdbc.queryForObject("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()", String.class));
            assertEquals("on", jdbc.queryForObject("SHOW default_transaction_read_only", String.class));
            var identities = new FinanceIdentityRepository(source); var profile = mock(LocalFinanceProfile.class);
            when(profile.requireBinding()).thenReturn(binding(identities.activeIdentity(1)));
            var accounts = new AccountQueryService(context.getBean(AccountMapper.class));
            var identity = new FinanceAccountQueries(profile, identities, accounts, new FinanceAccountListing(source),
                    context.getBean("financeTransactionManager", JdbcTransactionManager.class));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(ClientIdentity.NATIVE, "unused",
                    List.of(new SimpleGrantedAuthority("ROLE_LOCAL_CLIENT"))));
            var dashboard = new FinanceDashboardQueries(identity, accounts, source,
                    java.time.Clock.fixed(java.time.Instant.parse("2026-10-24T04:00:00Z"), java.time.ZoneOffset.UTC));
            var transactions = new FinanceTransactionQueries(identity, source);
            for (long user : List.of(1L, 2L)) {
                when(profile.requireBinding()).thenReturn(binding(identities.activeIdentity(user)));
                var snapshot = dashboard.dashboard();
                for (var flow : snapshot.monthlyCashFlow()) {
                    for (String type : List.of("INCOME", "EXPENSE")) {
                        BigDecimal sum = BigDecimal.ZERO; int page = 0; var ids = new HashSet<String>();
                        FinanceTransactionQueries.TransactionPage rows;
                        do {
                            assertTrue(page < 30, "Synthetic fixture must stay bounded");
                            rows = transactions.list(page++, 2, "", flow.month() + "-01", "", type, flow.month(), snapshot.asOf());
                            for (var t : rows.items()) {
                                assertEquals("TRANSACTION", t.kind()); assertEquals("CNY", t.currency()); assertEquals(type, t.type());
                                assertTrue(t.transactedAt().compareTo(snapshot.asOf()) < 0); assertTrue(t.transactedAt().startsWith(flow.month()));
                                assertTrue(ids.add(t.id())); sum = sum.add(new BigDecimal(t.amount()));
                                assertNotEquals(user == 1 ? "3" : "1", t.id());
                            }
                        } while (page * rows.size() < Long.parseLong(rows.total()));
                        assertEquals(Long.parseLong(rows.total()), ids.size());
                        assertEquals(0, sum.compareTo(new BigDecimal(type.equals("INCOME") ? flow.income() : flow.expense())));
                    }
                }
            }
            when(profile.requireBinding()).thenReturn(binding(identities.activeIdentity(1)));
            assertEquals("9007199254740993.10", transactions.transaction("TRANSACTION", 1).amount());
            assertEquals("0.10", transactions.transaction("TRANSFER", 1).amount());
            code(ErrorCode.FINANCE_TRANSACTION_NOT_FOUND, () -> transactions.transaction("TRANSACTION", 3));
            code(ErrorCode.INVALID_REQUEST, () -> transactions.list(0, 10, "", "2026-10-01", "", "ALL", "2026-10", "2026-10-24T12:00"));
            code(ErrorCode.INVALID_REQUEST, () -> transactions.list(0, 10, "", "2026-10-01", "2026-10-24", "INCOME", "2026-10", "2026-10-24T12:00"));
            var actual = identities.activeIdentity(1);
            when(profile.requireBinding()).thenReturn(new LocalFinanceProfile.Binding(1, UUID.randomUUID(), actual.userId(), "0".repeat(64), actual.userFingerprint()));
            code(ErrorCode.FINANCE_BINDING_INVALID, () -> transactions.list(0, 10, "", "2026-10-01", "", "INCOME", "2026-10", "2026-10-24T12:00"));
            System.out.println("D1_REAL_POSTGRESQL_READ_ONLY_SIX_MONTHS_TWO_USERS_PAGINATION_CNY_EXCLUSIVE_BOUNDARY_AND_SOURCE_KIND_PASS");
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "FINANCE_DASHBOARD_TEST_CONFIG", matches = ".+")
    void dashboardUsesNewSyntheticPostgresqlWithExactSnapshotAndSourceSemantics() throws Exception {
        Path file = Path.of(System.getenv("FINANCE_DASHBOARD_TEST_CONFIG"));
        verifyPrivateCredential(file);
        var config = JSON.readTree(Files.readAllBytes(file));
        String suffix = config.path("suffix").asString(); int port = config.path("port").asInt();
        assertTrue(suffix.matches("[a-f0-9]{12}") && port >= 55000 && port <= 55999);
        assertEquals(Path.of("D:/PersonalAiWorkspace-Fnext1", suffix).toAbsolutePath(), file.getParent().toAbsolutePath());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("new-dashboard-fixture", Map.of(
                    "workspace.finance.enabled", "true", "workspace.finance.flyway.enabled", "false",
                    "workspace.finance.datasource.url", "jdbc:postgresql://127.0.0.1:" + port + "/fnext1_finance_" + suffix,
                    "workspace.finance.datasource.username", "fnext1_reader_" + suffix,
                    "workspace.finance.datasource.password", config.path("readerPassword").asString())));
            context.register(FinanceInfrastructureConfiguration.class); context.refresh(); config = null;
            var source = context.getBean("financeDataSource", PGSimpleDataSource.class);
            var jdbc = new JdbcTemplate(source);
            assertEquals("FNEXT1_SYNTHETIC_" + suffix, jdbc.queryForObject("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()", String.class));
            var identities = new FinanceIdentityRepository(source);
            var profile = mock(LocalFinanceProfile.class); // Only profile IO is substituted; every financial result executes real SQL.
            when(profile.requireBinding()).thenReturn(binding(identities.activeIdentity(1)));
            var accounts = new AccountQueryService(context.getBean(AccountMapper.class));
            var identity = new FinanceAccountQueries(profile, identities, accounts, new FinanceAccountListing(source),
                    context.getBean("financeTransactionManager", JdbcTransactionManager.class));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(ClientIdentity.NATIVE, "unused",
                    List.of(new SimpleGrantedAuthority("ROLE_LOCAL_CLIENT"))));
            var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-24T04:00:00Z"), java.time.ZoneOffset.UTC);
            var query = new FinanceDashboardQueries(identity, accounts, source, clock);
            identity.query(id -> {
                assertEquals("on", jdbc.queryForObject("SHOW transaction_read_only", String.class));
                assertEquals("repeatable read", jdbc.queryForObject("SHOW transaction_isolation", String.class)); return null;
            });
            var d = query.dashboard();
            assertEquals("2026-10-24T12:00", d.asOf());
            assertEquals("9007199254740961.30", d.cnyAccountBalance()); // F-next-8 synthetic investment account is -32.00.
            assertEquals("9007199254741093.38", d.monthIncome());
            assertEquals("1.45", d.monthExpense()); assertEquals("9007199254741091.93", d.monthNet());
            assertEquals(List.of("2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10"), d.monthlyCashFlow().stream().map(FinanceDashboardQueries.Flow::month).toList());
            assertEquals("1.90", d.monthlyCashFlow().getFirst().net());
            assertEquals("0", d.monthlyCashFlow().get(1).income()); assertEquals("0", d.monthlyCashFlow().get(3).net());
            assertEquals("3.00", d.monthlyCashFlow().get(4).income());
            var a = d.allocation(); assertEquals("24", a.total()); assertFalse(a.complete()); assertEquals(20, a.items().size());
            assertEquals(0, new BigDecimal("18014398509482007.34691356").compareTo(new BigDecimal(a.referenceTotal())));
            assertEquals(List.of("1", "5", "11"), a.items().subList(0, 3).stream().map(FinanceDashboardQueries.AllocationItem::id).toList());
            assertEquals("0.0000", a.items().get(3).percentage()); // Full denominator, never renormalized to the visible subset.
            assertEquals(List.of("307", "306", "305", "304", "303"), d.recentTransactions().stream().map(FinanceDashboardQueries.Recent::id).toList());
            assertNull(d.recentTransactions().get(2).categoryName()); assertNull(d.recentTransactions().get(3).categoryName());
            when(profile.requireBinding()).thenReturn(binding(identities.activeIdentity(2)));
            var bob = query.dashboard(); assertEquals("88.88", bob.cnyAccountBalance()); assertEquals("88.89", bob.monthIncome());
            assertTrue(bob.recentTransactions().stream().noneMatch(t -> t.id().equals("307")));
            assertEquals("1", bob.allocation().total()); assertTrue(bob.allocation().complete());
            System.out.println("FNEXT10_REAL_SQL_FIXED_SHANGHAI_BOUNDARIES_EXACT_MONEY_SIX_MONTHS_FULL_DENOMINATOR_NAMES_AND_TWO_USERS_PASS");
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void originalSchemaAndTrustedAccountReadsUseRealPostgresql() throws Exception {
        Path credentials = Path.of(".runtime/finance/a2-test-credentials.json");
        verifyPrivateCredential(credentials);
        var config = JSON.readTree(Files.readAllBytes(credentials));
        if (!URL.equals(config.path("url").asString()) || !APP.equals(config.path("appUsername").asString())
                || !MIGRATOR.equals(config.path("migrationUsername").asString())) {
            throw new IllegalStateException("Unexpected isolated database credentials");
        }
        Set<String> expectedTables = verifyMigrationCopies();
        Path output = Path.of(".verification/f2.3-a2-finance-postgresql");
        Files.createDirectories(output);
        try (var migrationContext = context(MIGRATOR, config.path("migrationPassword").asString(), true);
             var appContext = context(APP, config.path("appPassword").asString(), false)) {
            config = null;
            var migration = migrationContext.getBean("financeDataSource", PGSimpleDataSource.class);
            var app = appContext.getBean("financeDataSource", PGSimpleDataSource.class);
            var owner = new JdbcTemplate(migration);
            var jdbc = new JdbcTemplate(app);
            verifyTarget(owner, MIGRATOR);
            verifyTarget(jdbc, APP);
            assertEquals(owner.queryForObject("SELECT system_identifier::text FROM pg_control_system()", String.class),
                    jdbc.queryForObject("SELECT system_identifier::text FROM pg_control_system()", String.class));
            evidence.put("database", "personal_ai_workspace_test");
            evidence.put("postgresql", jdbc.queryForObject("SHOW server_version", String.class));
            assertFalse(jdbc.queryForObject("SELECT has_schema_privilege(current_user,'public','CREATE')", Boolean.class));
            assertFalse(jdbc.queryForObject("SELECT has_database_privilege(current_user,current_database(),'CREATE')", Boolean.class));
            evidence.put("connectionsAndRestrictedAppRole", true);

            Flyway flyway = migrationContext.getBean("financeFlyway", Flyway.class);
            if (owner.queryForObject("SELECT to_regclass('public.flyway_schema_history') IS NULL", Boolean.class)) {
                // Recheck the actual connection and ALL public objects immediately before migrate().
                verifyTarget(owner, MIGRATOR);
                assertEquals(0, owner.queryForObject("""
                        SELECT (SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace)
                             + (SELECT count(*) FROM pg_proc WHERE pronamespace='public'::regnamespace)
                             + (SELECT count(*) FROM pg_type WHERE typnamespace='public'::regnamespace)
                             + (SELECT count(*) FROM pg_namespace WHERE nspname NOT IN ('public','information_schema')
                                AND nspname NOT LIKE 'pg_%')
                             + (SELECT count(*) FROM pg_extension WHERE extname <> 'plpgsql')
                        """, Integer.class), "STOP: database is not empty");
                assertEquals(21, flyway.migrate().migrationsExecuted);
                evidence.put("initializedFromEmptyDatabase", true);
            } else {
                // Existing/partial schemas are NEVER migrated, cleaned, repaired or baselined here.
                evidence.put("initializedFromEmptyDatabase", false);
            }
            flyway.validate();
            var versions = owner.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
            assertEquals(java.util.stream.IntStream.rangeClosed(1, 21).mapToObj(Integer::toString).toList(), versions);
            assertEquals(21, owner.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
            expectedTables.add("flyway_schema_history");
            assertEquals(expectedTables, new TreeSet<>(owner.queryForList(
                    "SELECT tablename FROM pg_tables WHERE schemaname='public'", String.class)));
            assertEquals("numeric(18,2)", owner.queryForObject("""
                    SELECT format_type(atttypid,atttypmod) FROM pg_attribute
                    WHERE attrelid='public.accounts'::regclass AND attname='balance'
                    """, String.class));
            assertEquals(0, owner.queryForObject("SELECT count(*) FROM pg_index WHERE NOT indisvalid", Integer.class));
            assertEquals(0, owner.queryForObject("SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgenabled='D'", Integer.class));
            assertEquals("1.3", owner.queryForObject("SELECT extversion FROM pg_extension WHERE extname='pgcrypto'", String.class));
            evidence.put("flywayHistory", versions);
            evidence.put("schemaObjects", owner.queryForMap("""
                    SELECT (SELECT count(*) FROM pg_tables WHERE schemaname='public') AS tables,
                           (SELECT count(*) FROM pg_indexes WHERE schemaname='public') AS indexes,
                           (SELECT count(*) FROM pg_constraint WHERE connamespace='public'::regnamespace) AS constraints,
                           (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
                            WHERE c.relnamespace='public'::regnamespace AND NOT t.tgisinternal) AS triggers
                    """));
            System.out.println("A2: original Flyway V1-V21 and schema verification passed");

            // Only the actual SELECT contract: no writes, sequence access, other tables or password hash.
            owner.execute("GRANT USAGE ON SCHEMA public TO " + APP);
            owner.execute("GRANT SELECT (id,username,email,created_at,status) ON public.users TO " + APP);
            owner.execute("GRANT SELECT ON public.accounts TO " + APP);
            assertFalse(jdbc.queryForObject("SELECT has_column_privilege(current_user,'public.users','password_hash','SELECT')", Boolean.class));
            assertFalse(jdbc.queryForObject("SELECT has_table_privilege(current_user,'public.accounts','INSERT,UPDATE,DELETE')", Boolean.class));
            assertFalse(jdbc.queryForObject("SELECT has_table_privilege(current_user,'public.transactions','SELECT')", Boolean.class));
            evidence.put("leastPrivilegeGrants", true);
            verifyQueries(appContext, owner, jdbc);
        } finally {
            SecurityContextHolder.clearContext();
            Files.writeString(output.resolve("database-evidence.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        }
    }

    private void verifyQueries(AnnotationConfigApplicationContext context, JdbcTemplate owner, JdbcTemplate jdbc) throws Exception {
        List<Long> users = new ArrayList<>();
        try {
            long first = user(owner, users);
            long second = user(owner, users);
            long missing = user(owner, users);
            owner.update("DELETE FROM users WHERE id=?", missing);
            long cny = account(owner, first, "CNY", "9007199254740993.10", "ACTIVE");
            long cents = account(owner, first, "CNY", "0.20", "INACTIVE");
            account(owner, first, "USD", "999.99", "ACTIVE");
            long other = account(owner, second, "CNY", "88.88", "ACTIVE");
            var app = context.getBean("financeDataSource", PGSimpleDataSource.class);
            var manager = context.getBean("financeTransactionManager", JdbcTransactionManager.class);
            assertSame(app, manager.getDataSource());
            assertSame(app, context.getBean("financeSqlSessionFactory", SqlSessionFactory.class)
                    .getConfiguration().getEnvironment().getDataSource());
            var identities = new FinanceIdentityRepository(app);
            var identity = identities.activeIdentity(first);
            var binding = new AtomicReference<>(binding(identity));
            var profile = mock(LocalFinanceProfile.class);
            when(profile.requireBinding()).thenAnswer(call -> binding.get());
            when(profile.createConfirmed(identity)).thenAnswer(call -> binding.get());
            var observedConnection = new AtomicReference<Connection>();
            var accounts = spy(context.getBean(AccountQueryService.class));
            doAnswer(call -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                assertEquals("on", jdbc.queryForObject("SHOW transaction_read_only", String.class));
                assertEquals("repeatable read", jdbc.queryForObject("SHOW transaction_isolation", String.class));
                assertEquals("personal_ai_workspace_test", jdbc.queryForObject("SELECT current_database()", String.class));
                observedConnection.set(DataSourceUtils.getConnection(app));
                return call.callRealMethod();
            }).when(accounts).sumBalanceByUser(anyLong());
            var queries = new FinanceAccountQueries(profile, identities, accounts, new FinanceAccountListing(app), manager);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    ClientIdentity.NATIVE, null, List.of(new SimpleGrantedAuthority("ROLE_LOCAL_CLIENT"))));
            assertEquals("9007199254740993.30", queries.cnyBalance().balance());
            assertTrue(observedConnection.get().isClosed());
            assertFalse(TransactionSynchronizationManager.hasResource(app));
            assertEquals("9007199254740993.10", queries.account(cny).balance());
            assertEquals("0.20", queries.account(cents).balance()); // Preserve the source's lack of a status filter.
            code(ErrorCode.FINANCE_ACCOUNT_NOT_FOUND, () -> queries.account(other));
            var mapper = context.getBean(AccountMapper.class);
            assertNull(mapper.findByUserIdAndId(first, other));
            assertEquals(List.of(cny), mapper.findOwnedByIds(first, List.of(cny, other)).stream().map(a -> a.getId()).toList());
            assertEquals(Set.of(cny), accounts.mapNamesByUser(first, Set.of(cny, other)).keySet());
            binding.set(binding(identities.activeIdentity(second)));
            assertEquals("88.88", queries.cnyBalance().balance());
            code(ErrorCode.FINANCE_ACCOUNT_NOT_FOUND, () -> queries.account(cny));
            binding.set(binding(identity));
            assertEquals(binding.get(), queries.confirmHistoricalBinding(identity));
            evidence.put("accountSqlBigDecimalCnyAndTwoWayUserIsolation", true);

            binding.set(new LocalFinanceProfile.Binding(1, UUID.randomUUID(), first, "0".repeat(64), identity.userFingerprint()));
            code(ErrorCode.FINANCE_BINDING_INVALID, queries::cnyBalance);
            binding.set(binding(identity));
            owner.update("UPDATE users SET username=? WHERE id=?", "changed_" + UUID.randomUUID(), first);
            code(ErrorCode.FINANCE_BINDING_INVALID, queries::cnyBalance);
            binding.set(binding(identities.activeIdentity(first)));
            owner.update("UPDATE users SET status='INACTIVE' WHERE id=?", first);
            code(ErrorCode.FINANCE_BINDING_INVALID, queries::cnyBalance);
            owner.update("UPDATE users SET status='ACTIVE' WHERE id=?", first);
            binding.set(new LocalFinanceProfile.Binding(1, UUID.randomUUID(), missing, identity.datasetFingerprint(), identity.userFingerprint()));
            code(ErrorCode.FINANCE_BINDING_INVALID, queries::cnyBalance);
            binding.set(binding(identities.activeIdentity(first)));
            evidence.put("realDatabaseIdentityStatusFingerprintAndDatasetMismatch", true);
            evidence.put("pgControlSystemAsApplicationRole", true);

            // Real PostgreSQL snapshot: another committed Finance connection changes the row in between reads.
            var reads = new TransactionTemplate(manager);
            reads.setReadOnly(true);
            reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            reads.executeWithoutResult(status -> {
                assertEquals(new BigDecimal("9007199254740993.10"), accounts.findAccessibleAccount(first, cny).getBalance());
                owner.update("UPDATE accounts SET balance=balance+1 WHERE id=?", cny);
                assertEquals(new BigDecimal("9007199254740993.10"),
                        jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?", BigDecimal.class, cny));
            });
            assertEquals("9007199254740994.10", queries.account(cny).balance());
            var failedConnection = new AtomicReference<Connection>();
            assertThrows(org.springframework.dao.DataAccessException.class, () -> reads.executeWithoutResult(status -> {
                failedConnection.set(DataSourceUtils.getConnection(app));
                jdbc.execute("SELECT 1/0");
            }));
            assertTrue(failedConnection.get().isClosed());
            assertFalse(TransactionSynchronizationManager.hasResource(app));
            assertEquals("9007199254740994.30", queries.cnyBalance().balance());
            var sqlite = new DriverManagerDataSource("jdbc:sqlite::memory:");
            var sqliteTransactions = new TransactionTemplate(new JdbcTransactionManager(sqlite));
            sqliteTransactions.executeWithoutResult(status -> {
                assertEquals("9007199254740994.30", queries.cnyBalance().balance());
                assertTrue(TransactionSynchronizationManager.hasResource(sqlite));
                assertFalse(TransactionSynchronizationManager.hasResource(app));
            }); // In-memory sentinel only; no Workspace SQLite file is opened or migrated.
            evidence.put("readOnlyRepeatableReadSnapshotRollbackConnectionReleaseAndSqliteSeparation", true);
            System.out.println("A2: real Account SQL, database identity checks and transactions passed");

            // Exercise the real profile where permitted. Never mock or loosen WindowsNativeGate/ACL.
            Path temporary = Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".finance-a2-");
            try {
                var real = new LocalFinanceProfile(temporary.resolve("data"), temporary.resolve("credentials/token"));
                var realQueries = new FinanceAccountQueries(real, identities, accounts, new FinanceAccountListing(app), manager);
                code(ErrorCode.FINANCE_NOT_BOUND, realQueries::cnyBalance);
                try {
                    realQueries.confirmHistoricalBinding(identities.activeIdentity(first));
                } catch (WorkspaceException denied) {
                    if (!System.getProperty("os.name").startsWith("Windows") || denied.error().code() != ErrorCode.FINANCE_BINDING_INVALID) throw denied;
                    evidence.put("realLocalProfileFileIo", "BLOCKED: Windows private profile could not be provisioned in this independent JVM; no Gate bypass");
                    System.out.println("A2: real LocalFinanceProfile provisioning BLOCKED; database-binding checks used a profile IO substitute");
                    return;
                }
                assertEquals("9007199254740994.30", realQueries.cnyBalance().balance());
                evidence.put("realLocalProfileFileIo", "PASSED");
            } finally {
                // This test only owns these exact synthetic paths, never daily Workspace state.
                Files.deleteIfExists(temporary.resolve("data/finance-identity/binding.json"));
                Files.deleteIfExists(temporary.resolve("data/finance-identity"));
                Files.deleteIfExists(temporary.resolve("data"));
                Files.deleteIfExists(temporary);
            }
        } finally {
            for (Long user : users) {
                owner.update("DELETE FROM accounts WHERE user_id=?", user);
                owner.update("DELETE FROM users WHERE id=?", user);
            }
            evidence.put("syntheticFixturesRemoved", true);
            System.out.println("A2: synthetic fixtures removed");
        }
    }

    private static AnnotationConfigApplicationContext context(String role, String password, boolean flyway) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("private-test-only", Map.of(
                "workspace.finance.enabled", "true", "workspace.finance.flyway.enabled", Boolean.toString(flyway),
                "workspace.finance.datasource.url", URL, "workspace.finance.datasource.username", role,
                "workspace.finance.datasource.password", password)));
        context.register(FinanceInfrastructureConfiguration.class);
        context.refresh();
        return context;
    }

    private static void verifyTarget(JdbcTemplate jdbc, String role) {
        assertEquals("personal_ai_workspace_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(role, jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals("127.0.0.1", jdbc.queryForObject("SELECT host(inet_server_addr())", String.class));
        assertEquals(5432, jdbc.queryForObject("SELECT inet_server_port()", Integer.class));
        assertEquals(17, jdbc.queryForObject("SELECT current_setting('server_version_num')::int / 10000", Integer.class));
        assertFalse(jdbc.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user)", Integer.class));
    }

    private static Set<String> verifyMigrationCopies() throws Exception {
        var manifest = JSON.readTree(Files.readAllBytes(Path.of("finance-domain/migration-provenance.json")));
        assertEquals("c6c87a0627b746e70a45b1c7896f63defd76a810", manifest.path("sourceCommit").asString());
        assertEquals(21, manifest.path("files").size());
        Set<String> tables = new TreeSet<>();
        for (var entry : manifest.path("files")) {
            String filename = Path.of(entry.path("workspacePath").asString()).getFileName().toString();
            byte[] bytes;
            try (var stream = FinancePostgresqlIntegrationTest.class.getResourceAsStream("/db/finance/migration/" + filename)) {
                assertNotNull(stream);
                bytes = stream.readAllBytes();
            }
            assertEquals(entry.path("sha256").asString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            var matcher = Pattern.compile("(?im)^CREATE TABLE (?:IF NOT EXISTS )?([a-z_]+)").matcher(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            while (matcher.find()) tables.add(matcher.group(1));
        }
        return tables;
    }

    private static void verifyPrivateCredential(Path path) throws Exception {
        for (Path item = path.toAbsolutePath(); item != null; item = item.getParent()) {
            assertFalse(Files.isSymbolicLink(item));
            assertFalse(Files.readAttributes(item, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther());
        }
        assertTrue(Files.size(path) < 16384);
        var account = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        assertEquals(account, Files.getOwner(path));
        for (var entry : Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl()) {
            assertEquals(account, entry.principal());
            assertEquals(AclEntryType.ALLOW, entry.type());
        }
    }

    private static long user(JdbcTemplate owner, List<Long> cleanup) {
        String suffix = UUID.randomUUID().toString();
        long id = owner.queryForObject("INSERT INTO users(username,email,password_hash) VALUES (?,?,?) RETURNING id",
                Long.class, "a2_" + suffix, suffix + "@example.invalid", "!not-a-login-hash!" + UUID.randomUUID());
        cleanup.add(id);
        return id;
    }

    private static long account(JdbcTemplate owner, long user, String currency, String amount, String status) {
        return owner.queryForObject("INSERT INTO accounts(user_id,name,type,currency,balance,status) VALUES (?,'synthetic-a2','BANK',?,?,?) RETURNING id",
                Long.class, user, currency, new BigDecimal(amount), status);
    }

    private static LocalFinanceProfile.Binding binding(FinanceIdentityRepository.Identity identity) throws Exception {
        return LocalFinanceProfile.parse(JSON.writeValueAsBytes(new LocalFinanceProfile.Binding(1, UUID.randomUUID(),
                identity.userId(), identity.datasetFingerprint(), identity.userFingerprint())));
    }

    private static void code(ErrorCode expected, org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected, assertThrows(WorkspaceException.class, call).error().code());
    }
}
