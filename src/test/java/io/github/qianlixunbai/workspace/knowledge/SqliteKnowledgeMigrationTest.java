package io.github.qianlixunbai.workspace.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqliteKnowledgeMigrationTest {
    @TempDir Path temporary;
    @Test void missingOrChangedSourceStopsBeforeTargetAndLeavesSnapshotReusable()throws Exception{
        var target=mock(DataSource.class);
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")){
            var ids=KnowledgePostgresqlFixture.create(db,temporary);Path file=temporary.resolve("sources").resolve(ids.getFirst()).resolve("1.source");
            byte[] original=Files.readAllBytes(file);Files.delete(file);
            var missing=assertThrows(IOException.class,()->SqliteKnowledgeMigration.importSnapshot(db,temporary,target));
            assertTrue(missing.getMessage().contains("SOURCE_VALIDATION"));
            Files.writeString(file,"changed synthetic bytes");
            assertThrows(IOException.class,()->SqliteKnowledgeMigration.importSnapshot(db,temporary,target));
            verifyNoInteractions(target);
            Files.write(file,original);
            when(target.getConnection()).thenThrow(new SQLException("Synthetic unavailable target","08001"));
            var unavailable=assertThrows(IOException.class,()->SqliteKnowledgeMigration.importSnapshot(db,temporary,target));
            assertTrue(unavailable.getMessage().contains("TARGET_ADMISSION"),unavailable::getMessage);
            assertTrue(unavailable.getMessage().contains("08001"));
            verify(target).getConnection();
            try(var s=db.createStatement()){
                s.execute("BEGIN");try(var r=s.executeQuery("SELECT count(*) FROM revisions")){assertTrue(r.next());assertEquals(7,r.getInt(1));}s.execute("ROLLBACK");
            }
            assertArrayEquals(original,Files.readAllBytes(file));
        }
    }
}
