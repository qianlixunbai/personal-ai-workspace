import java.io.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PasswordUtil;
import tools.jackson.databind.json.JsonMapper;

/** F-next-1 only: a NEW private test cluster and NEW synthetic databases. */
public final class FinanceNextPreparation {
    private static int port;
    private static String password;
    private static PGSimpleDataSource source(String database) {
        var ds = new PGSimpleDataSource(); ds.setServerNames(new String[]{"127.0.0.1"}); ds.setPortNumbers(new int[]{port});
        ds.setDatabaseName(database); ds.setUser("fnext_admin"); ds.setPassword(password); ds.setConnectTimeout(5); return ds;
    }
    private static void dashboardFixture(Connection db, String reader) throws Exception {
        sql(db, "INSERT INTO categories(id,user_id,name,type,is_system) VALUES(301,1,'Alice category','INCOME',false),(302,2,'Bob secret category','INCOME',false),(303,NULL,'Not a system category','INCOME',false)");
        sql(db, "GRANT SELECT ON public.categories TO " + reader);
        String[][] rows = {
            {"301","INCOME","100.00","2026-10-01 00:00:00","301"},
            {"302","EXPENSE","1.25","2026-10-24 11:59:59.999999","1"},
            {"303","INCOME","77.00","2026-10-24 12:00:00","1"},
            {"304","INCOME","88.00","2026-11-01 00:00:00","302"},
            {"305","REFUND","50.00","2026-11-01 00:00:00","303"},
            {"306","ADJUSTMENT","20.00","2026-11-01 00:00:00","301"},
            {"307","TRANSFER","30.00","2026-11-01 00:00:00","1"},
            {"308","INCOME","2.10","2026-05-01 00:00:00","1"},
            {"309","EXPENSE","0.20","2026-05-31 23:59:59.999999","1"},
            {"310","INCOME","999.00","2026-04-30 23:59:59.999999","1"},
            {"311","INCOME","3.00","2026-09-30 23:59:59.999999","1"}
        };
        for (var row : rows) sql(db, "INSERT INTO transactions(id,user_id,account_id,category_id,type,amount,currency,transacted_at) VALUES(" + row[0] + ",1,1," + row[4] + ",'" + row[1] + "'," + row[2] + ",'CNY','" + row[3] + "')");
        for (int i=301;i<=321;i++) sql(db, "INSERT INTO assets(id,user_id,name,type,currency,quantity,avg_cost,current_price) VALUES(" + i + ",1,'Repeated synthetic asset','OTHER','CNY',1,1,1)");
        sql(db, "INSERT INTO assets(id,user_id,name,type,currency,quantity,avg_cost,current_price) VALUES(322,1,'Excluded USD','OTHER','USD',1,1,999),(323,1,'Closed quantity','OTHER','CNY',0,1,1)");
    }
    private static void sql(Connection db, String text) throws SQLException { try (var statement = db.createStatement()) { statement.execute(text); } }
    private static void require(boolean condition) throws IOException { if (!condition) throw new IOException(); }
    public static void main(String[] args) {
        PrintStream output = System.out; System.setOut(new PrintStream(OutputStream.nullOutputStream())); System.setErr(System.out);
        byte[] input = null; String stage = "INPUT";
        try {
            require(args.length == 1 && Set.of("prepare", "identity-change", "identity-restore", "swap", "unswap", "empty", "accounts-restore", "offline", "online").contains(args[0]));
            input = System.in.readNBytes(16385); require(input.length <= 16384);
            var config = JsonMapper.builder().build().readTree(input);
            port = config.path("port").asInt(); require(port >= 55000 && port <= 55999);
            password = config.path("adminPassword").asString();
            String suffix = config.path("suffix").asString(); require(suffix.matches("[a-f0-9]{12}"));
            String finance = "fnext1_finance_" + suffix, workspace = "fnext1_workspace_" + suffix;
            String reader = "fnext1_reader_" + suffix, writer = "fnext1_writer_" + suffix;
            stage = "LOCAL_CLUSTER";
            try (var db = source("postgres").getConnection()) {
                try (var statement = db.createStatement(); var result = statement.executeQuery("SELECT current_user, inet_server_port(), current_setting('data_directory')")) {
                    require(result.next() && result.getString(1).equals("fnext_admin") && result.getInt(2) == port
                        && result.getString(3).replace('\\', '/').endsWith("/PersonalAiWorkspace-Fnext1/" + suffix + "/pgdata"));
                }
                if (!args[0].equals("prepare")) {
                    stage = args[0];
                    try (var statement = db.prepareStatement("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=?")) {
                        statement.setString(1, finance);
                        try (var result = statement.executeQuery()) { require(result.next() && ("FNEXT1_SYNTHETIC_" + suffix).equals(result.getString(1))); }
                    }
                    if (args[0].equals("swap")) {
                        sql(db, "CREATE DATABASE fnext1_clone_" + suffix + " TEMPLATE " + finance);
                        sql(db, "COMMENT ON DATABASE fnext1_clone_" + suffix + " IS 'FNEXT1_SYNTHETIC_" + suffix + "'");
                        sql(db, "ALTER DATABASE " + finance + " RENAME TO fnext1_original_" + suffix);
                        sql(db, "ALTER DATABASE fnext1_clone_" + suffix + " RENAME TO " + finance);
                    } else if (args[0].equals("unswap")) {
                        sql(db, "ALTER DATABASE " + finance + " RENAME TO fnext1_swapped_" + suffix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
                        sql(db, "ALTER DATABASE fnext1_original_" + suffix + " RENAME TO " + finance);
                    } else if (args[0].equals("offline") || args[0].equals("online")) {
                        sql(db, "ALTER ROLE " + reader + (args[0].equals("offline") ? " NOLOGIN" : " LOGIN"));
                    } else try (var data = source(finance).getConnection()) {
                        sql(data, switch (args[0]) {
                            case "identity-change" -> "UPDATE users SET email='changed@example.invalid' WHERE id=1";
                            case "identity-restore" -> "UPDATE users SET email='alice@example.invalid' WHERE id=1";
                            case "empty" -> "UPDATE accounts SET currency='USD' WHERE user_id=1";
                            case "accounts-restore" -> "UPDATE accounts SET currency='CNY' WHERE user_id=1 AND id IN (1,2)";
                            default -> throw new IOException();
                        });
                    }
                    output.println("FNEXT_FIXTURE_PASS_" + stage); return;
                }
                for (String name : List.of(finance, workspace, reader, writer)) {
                    try (var statement = db.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname=?) OR EXISTS(SELECT 1 FROM pg_roles WHERE rolname=?)")) {
                        statement.setString(1, name); statement.setString(2, name);
                        try (var result = statement.executeQuery()) { require(result.next() && !result.getBoolean(1)); }
                    }
                }
                for (String role : List.of(reader, writer)) {
                    char[] secret = config.path(role.equals(reader) ? "readerPassword" : "writerPassword").asString().toCharArray();
                    try { sql(db, "CREATE ROLE " + role + " LOGIN PASSWORD '" + PasswordUtil.encodeScramSha256(secret) + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS"); }
                    finally { Arrays.fill(secret, '\0'); }
                }
                sql(db, "CREATE DATABASE " + finance); sql(db, "CREATE DATABASE " + workspace);
                for (String database : List.of(finance, workspace)) sql(db, "COMMENT ON DATABASE " + database + " IS 'FNEXT1_SYNTHETIC_" + suffix + "'");
                for (String database : List.of(finance, workspace)) sql(db, "REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
                sql(db, "GRANT CONNECT ON DATABASE " + finance + " TO " + reader);
                sql(db, "GRANT CONNECT ON DATABASE " + workspace + " TO " + writer);
                sql(db, "ALTER ROLE " + reader + " SET default_transaction_read_only=on");
            }
            stage = "FINANCE_V1_V21";
            var migrations = Flyway.configure().dataSource(source(finance)).locations("classpath:db/finance/migration").cleanDisabled(true).load();
            require(migrations.migrate().migrationsExecuted == 21); migrations.validate();
            try (var db = source(finance).getConnection()) {
                sql(db, "INSERT INTO users(id,username,email,password_hash) VALUES(1,'Synthetic Alice','alice@example.invalid','not-a-login'),(2,'Synthetic Bob','bob@example.invalid','not-a-login')");
                sql(db, "INSERT INTO accounts(id,user_id,name,type,currency,balance,status) VALUES(1,1,'合成 CNY 账户','BANK','CNY',9007199254740993.10,'ACTIVE'),(2,1,'合成零钱','CASH','CNY',0.20,'INACTIVE'),(3,2,'Bob private','BANK','CNY',88.88,'ACTIVE'),(4,1,'USD excluded','BANK','USD',999.99,'ACTIVE')");
                if (config.path("transactions").asBoolean(false))
                    sql(db, "INSERT INTO accounts(id,user_id,name,type,currency,balance,status) VALUES(5,2,'Bob private destination','CASH','CNY',0.00,'ACTIVE')");
                sql(db, "REVOKE ALL ON SCHEMA public FROM PUBLIC"); sql(db, "GRANT USAGE ON SCHEMA public TO " + reader);
                sql(db, "GRANT SELECT ON public.users,public.accounts,public.flyway_schema_history TO " + reader);
                if (config.path("transactions").asBoolean(false)) {
                    sql(db, "INSERT INTO categories(id,name,type,is_system) VALUES(1,'F-next-3 synthetic','INCOME',true)");
                    sql(db, "INSERT INTO transactions(id,user_id,account_id,category_id,type,amount,currency,description,transacted_at) VALUES "
                            + "(1,1,1,1,'INCOME',9007199254740993.10,'CNY','F-next-3 exact income','2026-10-23 12:00:00'),"
                            + "(2,1,1,1,'EXPENSE',0.20,'CNY','F-next-3 expense','2026-10-23 12:00:00'),"
                            + "(3,2,3,1,'INCOME',88.88,'CNY','Bob private transaction','2026-10-23 12:00:00'),"
                            + "(4,1,1,1,'ADJUSTMENT',-0.10,'CNY',NULL,'2026-10-22 23:59:59.999999'),"
                            + "(5,1,4,1,'REFUND',12.34,'USD','Historical refund','2026-10-21 08:00:00'),"
                            + "(6,1,1,1,'TRANSFER',5.00,'CNY','Legacy transfer without destination','2026-10-20 08:00:00')");
                    sql(db, "INSERT INTO transfers(id,user_id,from_account_id,to_account_id,amount,currency,description,transacted_at) VALUES "
                            + "(1,1,1,2,0.10,'CNY','F-next-3 transfer','2026-10-23 12:00:00'),"
                            + "(2,2,3,5,1.00,'CNY','Bob private transfer','2026-10-23 12:00:00')");
                    sql(db, "GRANT SELECT ON public.transactions,public.transfers TO " + reader);
                }
                sql(db, "GRANT EXECUTE ON FUNCTION pg_catalog.pg_control_system() TO " + reader);
                if (config.path("assets").asBoolean(false)) {
                    // NEW opt-in fixture only. All quotes are labelled synthetic fixed snapshots, never live data.
                    sql(db, "INSERT INTO assets(id,user_id,name,symbol,type,market,currency,quantity,avg_cost,current_price,market_value,created_at) VALUES "
                            + "(1,1,'合成精确资产','SYNUSD','STOCK','US','CNY',9007199254740993.10,0.10,1.0000,123.45,'2026-10-24 08:00:00'),"
                            + "(2,1,'合成缺汇率资产','SYNJPY','ETF','US','CNY',0.12345678,2.34567890,NULL,NULL,'2026-10-24 08:00:00'),"
                            + "(3,1,'合成未知价格基金',NULL,'FUND','CN','CNY',2.00000000,1.00000000,NULL,NULL,'2026-10-24 08:00:00'),"
                            + "(4,1,'合成无行情资产',NULL,'STOCK','US','CNY',10.00000000,1.00000000,0.0000,0.00,'2026-10-24 08:00:00'),"
                            + "(5,1,'合成 CNY 参考资产','SYNCNY','ETF','US','CNY',0.12345678,0.10000000,1.0000,0.12,'2026-10-24 08:00:00'),"
                            + "(6,2,'Bob private asset','SYNUSD','STOCK','US','CNY',999.00000000,1.00000000,2.0000,1998.00,'2026-10-24 08:00:00')");
                    sql(db, "INSERT INTO market_quotes(market,symbol,currency,price,quote_time,fetched_at,provider,created_at,updated_at) VALUES "
                            + "('US','SYNUSD','USD',0.10000000,'2026-10-01 08:00:00Z','2026-10-01 08:01:00Z','SYNTHETIC_FIXED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                            + "('US','SYNJPY','JPY',1.23456789,'2026-10-01 08:00:00Z','2026-10-01 08:01:00Z','SYNTHETIC_FIXED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                            + "('US','SYNCNY','CNY',0.12345678,'2026-10-01 08:00:00Z',CURRENT_TIMESTAMP,'SYNTHETIC_FIXED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                    sql(db, "INSERT INTO exchange_rates(base_currency,quote_currency,rate,rate_time,fetched_at,provider) VALUES "
                            + "('USD','CNY',7.000000000000,'2026-10-01 08:00:00Z','2026-10-01 08:01:00Z','SYNTHETIC_FIXED')");
                    sql(db, "GRANT SELECT ON public.assets,public.market_quotes,public.exchange_rates TO " + reader);
                }
                if (config.path("investments").asBoolean(false)) {
                    // Only the NEW fixture initialization path. Ledger facts and their explicit projections;
                    // no production writes, replay implementation, or mutation of earlier fixtures.
                    sql(db, "INSERT INTO accounts(id,user_id,name,type,currency,balance,status) VALUES "
                            + "(6,1,'合成投资账户 A','BROKERAGE','CNY',0.00,'ACTIVE'),"
                            + "(7,1,'合成投资账户 B','BROKERAGE','CNY',0.00,'INACTIVE'),"
                            + "(8,2,'Bob private brokerage','BROKERAGE','CNY',0.00,'ACTIVE')");
                    sql(db, "INSERT INTO investment_instruments(id,user_id,symbol,name,market,asset_class,quote_currency,status) VALUES "
                            + "(11,1,'SYNUSD','合成精确持仓','US','STOCK','USD','ACTIVE'),"
                            + "(12,1,'SYNJPY','合成缺汇率持仓','US','ETF','JPY','ACTIVE'),"
                            + "(13,1,'SYNNONE','合成无行情持仓','US','STOCK','USD','INACTIVE'),"
                            + "(14,1,'SYNCNY','合成 CNY 参考持仓','US','ETF','CNY','ACTIVE'),"
                            + "(15,2,'SYNUSD','Bob private instrument','US','STOCK','USD','ACTIVE'),"
                            + "(16,1,'SYNHK','合成非 US 持仓','HK','FUND','HKD','ACTIVE')");
                    sql(db, "INSERT INTO assets(id,user_id,account_id,instrument_id,name,symbol,type,market,currency,quantity,avg_cost,total_cost,realized_profit_loss,position_status,position_mode,projection_version,current_price,market_value) VALUES "
                            + "(11,1,6,11,'精确投影','SYNUSD','STOCK','US','CNY',9007199254740993.12345678,1.00000000,9007199254740993.12,1.00,'OPEN','TRANSACTION_DRIVEN',2,1.0000,123.45),"
                            + "(12,1,7,11,'已平仓投影','SYNUSD','STOCK','US','CNY',0.00000000,0.00000000,0.00,-3.00,'CLOSED','TRANSACTION_DRIVEN',2,NULL,NULL),"
                            + "(13,1,6,12,'缺汇率投影','SYNJPY','ETF','US','CNY',0.12345678,2.34900019,0.29,0.00,'OPEN','TRANSACTION_DRIVEN',1,NULL,NULL),"
                            + "(14,1,6,13,'无行情投影','SYNNONE','STOCK','US','CNY',2.00000000,3.00000000,6.00,0.00,'OPEN','TRANSACTION_DRIVEN',1,NULL,NULL),"
                            + "(15,1,6,14,'CNY 参考投影','SYNCNY','ETF','US','CNY',0.12345678,0.08100001,0.01,0.00,'OPEN','TRANSACTION_DRIVEN',1,NULL,NULL),"
                            + "(16,2,8,15,'Bob private position','SYNUSD','STOCK','US','CNY',999.00000000,1.00000000,999.00,0.00,'OPEN','TRANSACTION_DRIVEN',1,NULL,NULL),"
                            + "(17,1,6,16,'非 US 投影','SYNHK','FUND','HK','CNY',3.00000000,4.00000000,12.00,0.00,'OPEN','TRANSACTION_DRIVEN',1,NULL,NULL)");
                    sql(db, "INSERT INTO investment_transactions(id,user_id,account_id,asset_id,transaction_type,quantity,unit_price,gross_amount,net_amount,trade_time,settlement_time,source,idempotency_key,request_hash) VALUES "
                            + "(11,1,6,11,'OPENING_POSITION',9007199254740994.12345678,1.00000000,9007199254740994.12,0,'2026-10-01 08:00:00Z','2026-10-01 08:00:00Z','MIGRATION','f6-opening-11',repeat('a',64)),"
                            + "(12,1,7,12,'OPENING_POSITION',2,10,20,0,'2026-10-01 08:00:00Z','2026-10-01 08:00:00Z','MIGRATION','f6-opening-12',repeat('a',64)),"
                            + "(13,1,6,13,'OPENING_POSITION',0.12345678,2.34567890,0.29,0,'2026-10-01 08:00:00Z','2026-10-01 08:00:00Z','MIGRATION','f6-opening-13',repeat('a',64)),"
                            + "(14,1,6,14,'OPENING_POSITION',2,3,6,0,'2026-10-01 08:00:00Z','2026-10-01 08:00:00Z','MIGRATION','f6-opening-14',repeat('a',64)),"
                            + "(15,1,6,15,'OPENING_POSITION',0.12345678,0.10000000,0.01,0,'2026-10-01 08:00:00Z','2026-10-01 08:00:00Z','MIGRATION','f6-opening-15',repeat('a',64)),"
                            + "(16,2,8,16,'OPENING_POSITION',999,1,999,0,'2026-10-01 08:00:00Z','2026-10-01 08:00:00Z','MIGRATION','f6-opening-16',repeat('a',64)),"
                            + "(17,1,6,17,'OPENING_POSITION',3,4,12,0,'2026-10-01 08:00:00Z','2026-10-01 08:00:00Z','MIGRATION','f6-opening-17',repeat('a',64))");
                    sql(db, "INSERT INTO investment_transactions(id,user_id,account_id,asset_id,transaction_type,quantity,unit_price,gross_amount,net_amount,released_cost_amount,realized_profit_loss,trade_time,settlement_time,source,idempotency_key,request_hash,account_balance_after,position_quantity_after,position_avg_cost_after,position_total_cost_after,position_realized_profit_loss_after,position_status_after,projection_version_after) VALUES "
                            + "(21,1,6,11,'SELL',1,2,2,2,1,1,'2026-10-02 08:00:00Z','2026-10-02 08:00:00Z','MANUAL','f6-sell-11',repeat('b',64),0,9007199254740993.12345678,1,9007199254740993.12,1,'OPEN',2),"
                            + "(22,1,7,12,'SELL',2,8.5,17,17,20,-3,'2026-10-02 08:00:00Z','2026-10-02 08:00:00Z','MANUAL','f6-sell-12',repeat('b',64),0,0,0,0,-3,'CLOSED',2)");
                    sql(db, "UPDATE assets SET last_transaction_id=CASE id WHEN 11 THEN 21 WHEN 12 THEN 22 ELSE id END WHERE position_mode='TRANSACTION_DRIVEN'");
                    sql(db, "GRANT SELECT ON public.investment_instruments,public.investment_transactions TO " + reader);
                }
            }
            if (config.path("history").asBoolean(false)) {
                stage = "NEW_HISTORY_FACTS";
                try (var db = source(finance).getConnection()) { history(db, reader); }
            }
            if (config.path("portfolio").asBoolean(false)) {
                stage = "PORTFOLIO_SCENARIOS";
                try (var db = source(finance).getConnection()) {
                    // Additional source-query scenarios, initialized only in this NEW cluster. Disabled users
                    // are excluded from Native binding candidates; the source probe is an internal test only.
                    for (int user = 101; user <= 106; user++) {
                        sql(db, "INSERT INTO users(id,username,email,password_hash,status) VALUES(" + user + ",'Portfolio scenario " + user + "','scenario" + user + "@example.invalid','not-a-login','DISABLED')");
                        for (int slot = 1; slot <= 2; slot++) {
                            int id = user * 10 + slot;
                            sql(db, "INSERT INTO accounts(id,user_id,name,type,currency,balance,status) VALUES(" + id + "," + user + ",'Portfolio scenario','BROKERAGE','CNY',0,'ACTIVE')");
                            String symbol = user == 104 || user == 106 && slot == 1 ? "SYNUSD" : user == 105 || user == 106 ? (slot == 1 ? "SYNNONE" : "SYNJPY") : "SYNCNY";
                            String currency = symbol.equals("SYNCNY") ? "CNY" : symbol.equals("SYNJPY") ? "JPY" : "USD";
                            if (slot == 1 || user >= 105)
                                sql(db, "INSERT INTO investment_instruments(id,user_id,symbol,name,market,asset_class,quote_currency,status) VALUES(" + id + "," + user + ",'" + symbol + "','Portfolio scenario','US','STOCK','" + currency + "','ACTIVE')");
                            if (user == 101) continue;
                            String values = user == 102 ? "0,0,0," + (slot == 1 ? "5.13" : "-7.25") + ",'CLOSED'" : "0.12345678,1,0.12," + (slot == 1 ? "5.13" : "-7.25") + ",'OPEN'";
                            // Scenario 103 deliberately holds the same Instrument in two different accounts.
                            int instrument = user <= 104 ? user * 10 + 1 : id;
                            sql(db, "INSERT INTO assets(id,user_id,account_id,instrument_id,name,symbol,market,type,currency,quantity,avg_cost,total_cost,realized_profit_loss,position_status,position_mode,projection_version) VALUES(" + id + "," + user + "," + id + "," + instrument + ",'Portfolio scenario','" + symbol + "','US','STOCK','CNY'," + values + ",'TRANSACTION_DRIVEN',1)");
                        }
                    }
                }
            }
            if (config.path("imports").asBoolean(false)) {
                stage = "NEW_IMPORT_RECEIPTS";
                try (var db = source(finance).getConnection()) { FinanceImportFixture.prepare(db, reader); }
            }
            if (config.path("dashboard").asBoolean(false)) {
                stage = "NEW_DASHBOARD";
                try (var db = source(finance).getConnection()) { dashboardFixture(db, reader); }
            }
            stage = "WORKSPACE_V1_V2";
            try (var db = source(workspace).getConnection()) {
                sql(db, "REVOKE ALL ON SCHEMA public FROM PUBLIC"); sql(db, "CREATE SCHEMA workspace"); sql(db, "REVOKE ALL ON SCHEMA workspace FROM PUBLIC");
            }
            var memory = Flyway.configure().dataSource(source(workspace)).schemas("workspace").defaultSchema("workspace")
                .createSchemas(false).locations("classpath:db/workspace/migration").cleanDisabled(true).load();
            require(memory.migrate().migrationsExecuted == 2); memory.validate();
            try (var db = source(workspace).getConnection()) {
                sql(db, "GRANT USAGE ON SCHEMA workspace TO " + writer);
                sql(db, "GRANT SELECT ON ALL TABLES IN SCHEMA workspace TO " + writer);
                sql(db, "GRANT UPDATE ON workspace.write_lock,workspace.knowledge_control TO " + writer);
                sql(db, "GRANT INSERT,UPDATE,DELETE ON workspace.memory_items,workspace.conversations,workspace.conversation_turns,workspace.conversation_messages,workspace.conversation_memory_selections,workspace.knowledge_documents,workspace.knowledge_revisions,workspace.knowledge_jobs,workspace.knowledge_deletes,workspace.knowledge_index_metadata,workspace.knowledge_chunks TO " + writer);
                sql(db, "REVOKE ALL ON FUNCTION workspace.reject_immutable_update() FROM PUBLIC");
            }
            output.println("FNEXT_FIXTURE_PASS_prepare");
        } catch (Exception | LinkageError rejected) {
            output.println("FNEXT_FIXTURE_STOP_" + stage + "_" + (rejected instanceof SQLException sql ? sql.getSQLState() : rejected.getClass().getSimpleName())); System.exit(10);
        } finally { if (input != null) Arrays.fill(input, (byte) 0); password = null; }
    }
    /** Only reached after CREATE DATABASE and all original migrations in a NEW marked cluster. */
    private static void history(Connection db, String reader) throws SQLException {
        db.setAutoCommit(false);
        sql(db, "INSERT INTO investment_instruments(id,user_id,symbol,name,market,asset_class,quote_currency) VALUES(17,1,'SYNAUDIT','合成审计持仓','US','STOCK','CNY')");
        sql(db, "INSERT INTO assets(id,user_id,account_id,instrument_id,name,symbol,market,type,currency,quantity,avg_cost,total_cost,realized_profit_loss,position_status,position_mode,projection_version) VALUES(18,1,6,17,'合成审计投影','SYNAUDIT','US','STOCK','CNY',3,11,33,0,'OPEN','TRANSACTION_DRIVEN',7)");
        // Historical posting receipts are immutable snapshots, deliberately different from current projections.
        sql(db, """
                INSERT INTO investment_transactions(id,user_id,account_id,asset_id,transaction_type,quantity,unit_price,gross_amount,net_amount,
                    trade_time,settlement_time,source,idempotency_key,request_hash,account_balance_after,position_quantity_after,
                    position_avg_cost_after,position_total_cost_after,position_realized_profit_loss_after,position_status_after,projection_version_after,created_at,note,external_reference)
                VALUES
                (101,1,6,18,'BUY',1,10,10,10,'2026-10-02 09:00:00.123456Z','2026-10-04 09:00:00Z','MANUAL','f8-101',repeat('a',64),-10,1,10,10,0,'OPEN',1,'2026-10-02 09:00:01Z','Synthetic unchanged','SYN-101'),
                (102,1,6,18,'BUY',2,10,20,20,'2026-10-03 09:00:00Z','2026-10-04 09:00:00Z','MANUAL','f8-102',repeat('a',64),-30,3,10,30,0,'OPEN',2,'2026-10-03 09:00:01Z','Synthetic reversed','SYN-102'),
                (103,1,6,18,'BUY',1,10,10,10,'2026-10-03 09:00:00Z','2026-10-04 09:00:00Z','MANUAL','f8-103',repeat('a',64),-40,4,10,40,0,'OPEN',3,'2026-10-03 09:00:02Z','Synthetic original <script>','SYN-103'),
                (201,2,8,16,'BUY',1,10,10,10,'2026-10-02 09:00:00Z','2026-10-04 09:00:00Z','MANUAL','f8-201',repeat('a',64),-10,1000,1.00900000,1009,0,'OPEN',2,'2026-10-02 09:00:01Z','Bob private original','BOB-PRIVATE')
                """);
        sql(db, """
                INSERT INTO investment_transactions(id,user_id,account_id,asset_id,transaction_type,quantity,unit_price,gross_amount,net_amount,
                    trade_time,settlement_time,source,idempotency_key,request_hash,original_transaction_id,correction_reason,cash_delta,
                    account_balance_after,position_quantity_after,position_avg_cost_after,position_total_cost_after,position_realized_profit_loss_after,position_status_after,projection_version_after,created_at)
                VALUES
                (104,1,6,18,'REVERSAL',2,10,20,20,'2026-10-04 09:00:00Z','2026-10-04 09:00:00Z','CORRECTION','f8-104',repeat('b',64),102,'Synthetic standalone reversal',20,-20,2,10,20,0,'OPEN',4,'2026-10-04 09:00:01Z'),
                (202,2,8,16,'REVERSAL',1,10,10,10,'2026-10-04 09:00:00Z','2026-10-04 09:00:00Z','CORRECTION','f8-202',repeat('b',64),201,'Bob private reversal',10,0,999,1,999,0,'OPEN',3,'2026-10-04 09:00:01Z')
                """);
        String group = "'00000000-0000-4000-8000-000000000008'::uuid";
        sql(db, "INSERT INTO investment_transactions(id,user_id,account_id,asset_id,transaction_type,quantity,unit_price,gross_amount,net_amount,trade_time,settlement_time,source,idempotency_key,request_hash,original_transaction_id,correction_reason,cash_delta,correction_group_id,created_at) VALUES(105,1,6,18,'REVERSAL',1,10,10,10,'2026-10-05 09:00:00Z','2026-10-05 09:00:00Z','CORRECTION','f8-105',repeat('b',64),103,'Synthetic replacement',10," + group + ",'2026-10-05 09:00:01Z')");
        sql(db, "INSERT INTO investment_transactions(id,user_id,account_id,asset_id,transaction_type,quantity,unit_price,gross_amount,fee_amount,net_amount,trade_time,settlement_time,source,idempotency_key,request_hash,cash_delta,correction_group_id,replay_anchor_transaction_id,replay_sequence,account_balance_after,position_quantity_after,position_avg_cost_after,position_total_cost_after,position_realized_profit_loss_after,position_status_after,projection_version_after,created_at,note) VALUES(106,1,6,18,'BUY',3,11,33,1,34,'2026-10-03 09:00:00Z','2026-10-04 09:00:00Z','CORRECTION','f8-106',repeat('c',64),-34," + group + ",103,1,-44,4,11,44,0,'OPEN',5,'2026-10-05 09:00:02Z','Synthetic replacement fact')");
        sql(db, "INSERT INTO investment_transaction_corrections(correction_group_id,user_id,account_id,asset_id,instrument_id,original_transaction_id,transaction_type,correction_kind,idempotency_key,request_hash,correction_reason,reversal_transaction_id,replacement_transaction_id,reversal_cash_delta,replacement_cash_delta,command_cash_delta,balance_after,position_quantity_after,position_avg_cost_after,position_total_cost_after,position_realized_profit_loss_after,position_status_after,projection_version,last_transaction_id,created_at) VALUES(" + group + ",1,6,18,17,103,'BUY','REPLACEMENT','f8-command',repeat('d',64),'Synthetic replacement',105,106,10,-34,-24,-44,4,11,44,0,'OPEN',5,106,'2026-10-05 09:00:02Z')");
        sql(db, """
                INSERT INTO investment_transactions(id,user_id,account_id,asset_id,transaction_type,quantity,unit_price,gross_amount,fee_amount,tax_amount,net_amount,released_cost_amount,realized_profit_loss,
                    trade_time,settlement_time,source,idempotency_key,request_hash,account_balance_after,position_quantity_after,position_avg_cost_after,position_total_cost_after,position_realized_profit_loss_after,position_status_after,projection_version_after,created_at)
                VALUES
                (107,1,6,18,'SELL',1,12,12,0.5,0.5,11,11,0,'2026-10-06 09:00:00Z','2026-10-06 09:00:00Z','MANUAL','f8-107',repeat('e',64),-33,3,11,33,0,'OPEN',6,'2026-10-06 09:00:01Z'),
                (108,1,6,18,'DIVIDEND',NULL,NULL,1,0,0,1,0,0,'2026-10-07 09:00:00Z','2026-10-07 09:00:00Z','MANUAL','f8-108',repeat('e',64),-32,3,11,33,0,'OPEN',7,'2026-10-07 09:00:01Z')
                """);
        sql(db, "UPDATE accounts SET balance=-32 WHERE id=6 AND user_id=1");
        sql(db, "GRANT SELECT ON public.investment_transaction_corrections TO " + reader);
        db.commit(); db.setAutoCommit(true);
    }
}
