import io.github.qianlixunbai.workspace.persistence.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.security.*;
import org.postgresql.ds.PGSimpleDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Actual NativeGate + transferred exclusion + kernel source owner, on a new synthetic Profile.
 * Parent probes file sharing from another process; no reflection or admission substitution. */
public final class SqliteMaintenanceOwnerProbe {
    public static void main(String[] args) throws Exception {
        PrintStream output = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); System.setErr(System.out);
        String stage = "GATE";
        try {
            if (args.length > 1 || args.length == 1 && !Set.of("contend", "inspect").contains(args[0])) throw new IOException();
            WindowsNativeGate.admit();
            output.println("OWNER_READY " + ProcessHandle.current().pid()); output.flush();
            var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = input.readLine(); require(line != null && line.length() < 16384);
            var json = JsonMapper.builder().build().readTree(line);
            var target = new PGSimpleDataSource(); target.setServerNames(new String[]{"127.0.0.1"});
            int port = json.path("port").asInt(); require(port >= 49152 && port <= 65535);
            target.setPortNumbers(new int[]{port}); target.setDatabaseName("cutover_fixture");
            target.setUser("cutover_app"); target.setPassword(json.path("appPassword").asString());
            target.setCurrentSchema("workspace,pg_catalog"); target.setConnectTimeout(3); target.setSocketTimeout(30);
            try (var exclusion = WindowsNativeGate.receiveMaintenanceExclusion(json.path("handle").asLong())) {
                Path root = WindowsNativeGate.maintenanceProfileRoot(exclusion);
                if (args.length == 1) {
                    refused(() -> { try (var ignored = SqliteMaintenanceOwner.begin(exclusion)) { } });
                    if (args[0].equals("inspect")) {
                        var observed = SqliteMaintenanceOwner.inspect(exclusion, json.path("operation").asString(), target);
                        require(observed.lastStage() == SqliteMaintenanceOwner.Stage.TARGET_VERIFIED
                                && observed.target() == SqliteCombinedMigration.Inspection.MATCHING_DATA_AND_RECEIPTS);
                    }
                    output.println(args[0].equals("inspect") ? "FRESH_PROCESS_RECORDS_MATCH_REENTRY_REFUSED" : "CONCURRENT_OWNER_REFUSED");
                    output.flush(); return;
                }
                stage = "BUSY_SOURCE";
                refused(() -> { try (var ignored = SqliteMaintenanceOwner.begin(exclusion)) { } });
                output.println("OWNER_BUSY_REFUSED"); output.flush();
                require("RELEASED".equals(input.readLine()));
                stage = "OWN_SOURCE_AND_BACKUP";
                String operation;
                var owner = SqliteMaintenanceOwner.begin(exclusion);
                try {
                    operation = owner.operationId(); owner.createBackup(); owner.requireHeld();
                    output.println("OWNER_HELD " + operation); output.flush();
                    require("IMPORT".equals(input.readLine()));
                    stage = "OWNED_TARGET_IMPORT";
                    owner.importTarget(target); owner.requireHeld();
                    require(!Files.exists(root.resolve("data/database.dpapi")));
                    output.println("OWNER_IMPORTED"); output.flush();
                    require("CLOSE".equals(input.readLine()));
                } finally { owner.close(); }
                refused(owner::requireHeld);
                stage = "RECOVER_OBSERVATIONS";
                var recovery = SqliteMaintenanceOwner.inspect(exclusion, operation, target);
                require(recovery.lastStage() == SqliteMaintenanceOwner.Stage.TARGET_VERIFIED
                        && recovery.target() == SqliteCombinedMigration.Inspection.MATCHING_DATA_AND_RECEIPTS);
                output.println("RECOVERY_TARGET_MATCHED"); output.flush();
                require("CONTINUE".equals(input.readLine()));
                Path directory = root.resolve("evidence/sqlite-maintenance").resolve(operation);
                Path pending = directory.resolve("0005.pending");
                byte[] marker = "synthetic interrupted next record".getBytes(StandardCharsets.UTF_8);
                WindowsPrivateAcl.createFile(pending); Files.write(pending, marker);
                require(SqliteMaintenanceOwner.inspect(exclusion, operation, target).state().equals("JOURNAL_UNCONFIRMED"));
                require(Arrays.equals(marker, Files.readAllBytes(pending))); Files.delete(pending);
                output.println("RECOVERY_PENDING_REFUSED_WITHOUT_REPLAY"); output.flush();
                stage = "CHANGED_CONFIGURATION_PRESERVED";
                Path config = root.resolve("data/database.dpapi");
                byte[] later = "synthetic later config; never decrypt or overwrite".getBytes(StandardCharsets.UTF_8);
                WindowsPrivateAcl.createFile(config); Files.write(config, later);
                require(SqliteMaintenanceOwner.inspect(exclusion, operation, target).state().equals("CONFIGURATION_CHANGED_OR_UNREADABLE"));
                require(Arrays.equals(later, Files.readAllBytes(config))); Files.delete(config);
                output.println("RECOVERY_LATER_CONFIGURATION_PRESERVED"); output.flush();
                stage = "LATER_SOURCE_DATA_PRESERVED";
                UUID added;
                try (var memory = new MemoryStore(root.resolve("data"), root.resolve("credentials/client-token"))) {
                    added = memory.create(MemoryItem.Type.PROJECT_NOTE, "Later source", "Do not overwrite after owner exit").id();
                }
                require(SqliteMaintenanceOwner.inspect(exclusion, operation, target).state().equals("SOURCE_CHANGED"));
                try (var memory = new MemoryStore(root.resolve("data"), root.resolve("credentials/client-token"))) {
                    require(memory.get(added).content().equals("Do not overwrite after owner exit"));
                }
                output.println("RECOVERY_LATER_SOURCE_DATA_PRESERVED_NO_ACTIVATION"); output.flush();
            }
            output.println("OWNER_CLOSED_EXCLUSION_RELEASED"); output.flush();
        } catch (Exception failure) {
            output.println("OWNER_PROBE_STOP " + stage + " " + failure.getClass().getSimpleName()); output.flush(); System.exit(10);
        }
    }
    private interface Work { void run() throws Exception; }
    private static void refused(Work work) throws Exception {
        try { work.run(); } catch (IOException expected) { return; }
        throw new IllegalStateException("Ownership refusal expected");
    }
    private static void require(boolean value) { if (!value) throw new IllegalStateException(); }
}
