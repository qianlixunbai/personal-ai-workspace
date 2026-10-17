package io.github.qianlixunbai.workspace.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConnection;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqliteWorkspaceMigrationTest {
    @TempDir Path temporary;

    @Test void consistentBackupRetainsSourceAndUnsettledTurnsStopBeforeTargetAndReleaseSnapshot() throws Exception {
        Path source = temporary.resolve("synthetic.db"), backup = temporary.resolve("backup.db");
        try (var writer = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            WorkspacePostgresqlIntegrationTest.source(writer);
            try (var statement = writer.createStatement()) {
                statement.execute("INSERT INTO conversation_turns(id,conversation_id,sequence,status,created_at,updated_at) VALUES('30000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000001',2,'PENDING',1234567890123,1234567890999)");
                statement.execute("INSERT INTO conversation_messages VALUES('40000000-0000-0000-0000-000000000003','30000000-0000-0000-0000-000000000002','USER','unsettled synthetic',1234567890123)");
            }
            try (var reader = DriverManager.getConnection("jdbc:sqlite:" + source); var lock = writer.createStatement()) {
                lock.execute("BEGIN IMMEDIATE");
                assertEquals(0, reader.unwrap(SQLiteConnection.class).getDatabase().backup("main", backup.toString(), null));
                lock.execute("ROLLBACK");
            }
        }
        byte[] sourceBefore = Files.readAllBytes(source), backupBefore = Files.readAllBytes(backup);
        var target = mock(DataSource.class);
        try (var snapshot = DriverManager.getConnection("jdbc:sqlite:" + backup)) {
            assertThrows(IOException.class, () -> SqliteWorkspaceMigration.importSnapshot(snapshot, target));
            try (var statement = snapshot.createStatement()) {
                // Import always releases its source transaction, including validation failures.
                statement.execute("BEGIN");
                try (var rows = statement.executeQuery("SELECT count(*) FROM conversation_turns WHERE status='PENDING'")) {
                    assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
                }
                statement.execute("ROLLBACK");
            }
        }
        verifyNoInteractions(target);
        assertArrayEquals(sourceBefore, Files.readAllBytes(source));
        assertArrayEquals(backupBefore, Files.readAllBytes(backup));
    }
}
