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
