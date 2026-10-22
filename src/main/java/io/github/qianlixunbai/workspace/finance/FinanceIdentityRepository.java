package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Reads identity facts from the same Finance connection/transaction as the account query. */
final class FinanceIdentityRepository {
    private final JdbcTemplate jdbc;

    FinanceIdentityRepository(DataSource financeDataSource) { jdbc = new JdbcTemplate(financeDataSource); }

    record Identity(long userId, String datasetFingerprint, String userFingerprint) {
        @Override public String toString() { return "FinanceIdentity[private]"; }
    }

    Identity activeIdentity(long userId) {
        var identities = jdbc.query("""
                SELECT control.system_identifier::text AS cluster_id,
                       database.oid::text AS database_id, current_database() AS database_name,
                       users.id, users.username, users.email, users.created_at, users.status
                FROM pg_catalog.pg_control_system() AS control
                JOIN pg_catalog.pg_database AS database ON database.datname = current_database()
                CROSS JOIN public.users AS users
                WHERE users.id = ?
                """, (row, index) -> {
            if (!"ACTIVE".equals(row.getString("status"))) throw invalid();
            return new Identity(row.getLong("id"),
                    digest(row.getString("cluster_id"), row.getString("database_id"), row.getString("database_name")),
                    digest(Long.toString(row.getLong("id")), row.getString("username"), row.getString("email"),
                            row.getObject("created_at", java.time.LocalDateTime.class).toString()));
        }, userId);
        if (identities.size() != 1) throw invalid();
        return identities.getFirst();
    }

    record Candidate(Identity identity, String username, String email, String createdAt) { }
    record Review(String database, String server, String datasetFingerprint, java.util.List<Candidate> candidates) { }

    Review review() {
        // Never offer a Workspace schema or an unreviewed/partial Finance schema for binding.
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user", Boolean.class))
                || jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class) != 21
                || !"21".equals(jdbc.queryForObject("SELECT version FROM public.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1", String.class))
                || jdbc.queryForObject("SELECT to_regnamespace('workspace') IS NOT NULL", Boolean.class)) throw invalid();
        var candidates = jdbc.query("SELECT id, username, email, created_at FROM public.users WHERE status='ACTIVE' ORDER BY id LIMIT 101",
                (row, index) -> new Candidate(activeIdentity(row.getLong("id")), row.getString("username"), row.getString("email"),
                        row.getObject("created_at", java.time.LocalDateTime.class).toString()));
        if (candidates.size() > 100) throw invalid(); // No silent first-user fallback.
        String dataset = jdbc.queryForObject("SELECT current_database()", String.class);
        String server = jdbc.queryForObject("SELECT inet_server_addr()::text || ':' || inet_server_port()::text", String.class);
        String fingerprint = jdbc.queryForObject("SELECT control.system_identifier::text AS cluster, database.oid::text AS id FROM pg_control_system() control JOIN pg_database database ON datname=current_database()",
                (row, index) -> digest(row.getString("cluster"), row.getString("id"), dataset));
        return new Review(dataset, server, fingerprint, candidates);
    }

    Long firstCnyAccountId(long userId) {
        var ids = jdbc.query("SELECT id FROM public.accounts WHERE user_id=? AND currency='CNY' ORDER BY id LIMIT 1",
                (row, index) -> row.getLong(1), userId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    String username(long userId) {
        return jdbc.queryForObject("SELECT username FROM public.users WHERE id=?", String.class, userId);
    }

    static String digest(String... parts) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                if (part == null || part.isBlank()) throw invalid();
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static WorkspaceException invalid() { return new WorkspaceException(ErrorCode.FINANCE_BINDING_INVALID, "FINANCE"); }
}
