package io.github.qianlixunbai.workspace.memory;

import io.github.qianlixunbai.workspace.backup.WorkspaceBackupService;
import io.github.qianlixunbai.workspace.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspacePublicationTest {
    @TempDir Path temp;
    @Test void stagingFailureRacingNewAndEmptyTargetsAndInsertRollback() throws Exception {
        Path source=temp.resolve("source"),token=temp.resolve("auth/token");
        try(var memory=new MemoryStore(source,token)) {
            var out=new ByteArrayOutputStream();new WorkspaceBackupService(memory,token).export(out);var bytes=out.toByteArray();var original=Files.readAllBytes(memory.databaseFile());
            Path target=temp.resolve("new");
            var fail=new WorkspaceBackupService(memory,token,new MemoryBackupService(source,token,()->{throw new IllegalStateException();}));
            assertThrows(WorkspaceException.class,()->fail.restore(new ByteArrayInputStream(bytes),target.toString()));assertFalse(Files.exists(target));
            for(boolean exists:List.of(false,true)) {
                Path race=temp.resolve(exists?"empty-race":"new-race");if(exists)Files.createDirectory(race);
                var service=new WorkspaceBackupService(memory,token,new MemoryBackupService(source,token,()->{
                    try{if(!Files.exists(race))Files.createDirectory(race);Files.writeString(race.resolve("keep"),"synthetic sentinel");}catch(Exception e){throw new IllegalStateException();}
                }));
                var e=assertThrows(WorkspaceException.class,()->service.restore(new ByteArrayInputStream(bytes),race.toString()));
                assertEquals(ErrorCode.WORKSPACE_RESTORE_TARGET_NOT_EMPTY,e.error().code());assertFalse(Files.exists(race.resolve("memory.db")));assertEquals("synthetic sentinel",Files.readString(race.resolve("keep")));
            }
            try(var staging=new MemoryStore(temp.resolve("rollback"),token)) {
                assertThrows(WorkspaceException.class,()->staging.reconstructWorkspace(db->{
                    try(var s=db.createStatement()) {
                        String id=UUID.randomUUID().toString();s.execute("INSERT INTO conversations VALUES('"+id+"','synthetic','ACTIVE',1,1)");
                        s.execute("INSERT INTO conversations VALUES('"+id+"','synthetic','ACTIVE',1,1)");return List.of();
                    }catch(SQLException e){throw new IllegalStateException();}
                }));
                try(var db=DriverManager.getConnection("jdbc:sqlite:"+staging.databaseFile());var s=db.createStatement();var r=s.executeQuery("SELECT count(*) FROM conversations")){r.next();assertEquals(0,r.getLong(1));}
            }
            assertArrayEquals(original,Files.readAllBytes(memory.databaseFile()));
            try(var files=Files.list(temp)){assertFalse(files.anyMatch(p->p.getFileName().toString().startsWith(".workspace-restore-")));}
        }
    }
    @Test void windowsJunctionReparsePointAndProjectPathsAreRejected() throws Exception {
        Path source=temp.resolve("source"),token=temp.resolve("auth/token"),destination=Files.createDirectory(temp.resolve("destination"));
        try(var memory=new MemoryStore(source,token)) {
            var service=new WorkspaceBackupService(memory,token);var out=new ByteArrayOutputStream();service.export(out);var bytes=out.toByteArray();
            Path project=Files.createDirectory(temp.resolve("project"));Files.createDirectory(project.resolve(".git"));
            assertThrows(WorkspaceException.class,()->service.restore(new ByteArrayInputStream(bytes),project.resolve("data").toString()));
            Path link=temp.resolve("link");
            if(System.getProperty("os.name").startsWith("Windows")) {
                var process=new ProcessBuilder("cmd","/c","mklink","/J",link.toString(),destination.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                assertEquals(0,process.waitFor());
            }else Files.createSymbolicLink(link,destination);
            try { assertThrows(WorkspaceException.class,()->service.restore(new ByteArrayInputStream(bytes),link.resolve("recovered").toString()));assertFalse(Files.exists(destination.resolve("recovered"))); }
            finally { Files.delete(link); }
        }
    }
}
