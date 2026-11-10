package io.github.qianlixunbai.workspace.persistence;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.nio.file.ExtendedOpenOption;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Offline source/backup/import/publication owner, deliberately stopping before native activation.
 * No record is an authorization token; closing suspends ownership and keeps durable startup fencing.
 * Every attempted write is preceded by a durable, CurrentUser-protected stage. */
public final class SqliteMaintenanceOwner implements AutoCloseable {
    public enum Stage { SOURCE_FROZEN, BACKUP_READY, TARGET_PREPARED, COMMIT_ATTEMPTED,
        STOPPED_BEFORE_COMMIT, COMMIT_UNCONFIRMED, TARGET_VERIFIED }
    public record Recovery(String state, Stage lastStage, SqliteCombinedMigration.Inspection target) {}
    private record Entry(int version, String kind, String profile, String operation, int sequence,
                         String previous, String source, String backup, Stage stage, SqliteCombinedMigration.Expected expected) {}
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final SqliteSourceLease source;
    private final Path directory, backup;
    private final String operation, sourceDigest;
    private final List<SqliteSourceLease.DirectoryPin> directories = new ArrayList<>();
    private final List<SqliteSourceLease.FilePin> records = new ArrayList<>(), backupFiles = new ArrayList<>();
    private Entry latest;
    private boolean closed, importAttempted, backupCreated;
    private String backupDigest;
    private FileChannel ownerLock;
    private String ownerLockIdentity;
    private MaintenanceConfiguration.Publication publication;
    private boolean publicationAttempted,publicationFailed;
    private boolean handoffAttempted;

    /** Called only after the native user's source/target choice. This API itself grants no such
     * choice: it consumes the real Gate-owned exclusion and only handles that fixed source. */
    public static SqliteMaintenanceOwner begin(MaintenanceExclusion exclusion) throws IOException {
        return new SqliteMaintenanceOwner(SqliteSourceLease.acquire(exclusion));
    }

    private SqliteMaintenanceOwner(SqliteSourceLease source) throws IOException {
        this.source = source; operation = UUID.randomUUID().toString().replace("-", "");
        Path registry = source.root().resolve("evidence/sqlite-maintenance");
        directory = registry.resolve(operation); backup = directory.resolve("backup");
        try {
            sourceDigest = sourceDigest(source.fingerprint());
            WindowsPrivateAcl.directory(registry); directories.add(new SqliteSourceLease.DirectoryPin(registry));
            Path lock = registry.resolve("owner.lock");
            try { WindowsPrivateAcl.createFile(lock); } catch (FileAlreadyExistsException existing) { /* Verify, never truncate. */ }
            WindowsPrivateAcl.verify(lock, false);
            ownerLockIdentity = TrustedDatabaseConfiguration.Identity.read(lock, false);
            ownerLock = FileChannel.open(lock, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
                    ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE);
            requireOwnerLock();
            // A previous operation is never made retryable by process death, a new operation ID,
            // an empty target, or an in-memory flag reset. Only inspection exists for now.
            try (var entries = Files.list(registry)) {
                if (entries.anyMatch(p -> !p.getFileName().toString().equals("owner.lock"))) throw stopped();
            }
            SqliteSourceLease.absent(directory); WindowsPrivateAcl.directory(directory);
            directories.add(new SqliteSourceLease.DirectoryPin(directory));
            append(Stage.SOURCE_FROZEN, null);
        } catch (Exception failure) { close(); throw stopped(); }
    }

    public synchronized String operationId() throws IOException { requireHeld(); return operation; }

    synchronized Path configurationRoot() throws IOException { requireHeld(); return source.root(); }
    synchronized SqliteCombinedMigration.Expected configurationTarget() throws IOException {
        requireHeld();
        if(latest.stage!=Stage.TARGET_VERIFIED || latest.expected.binding()==null) throw stopped();
        return latest.expected;
    }

    /** Stages a protected configuration; neither publication nor Runtime activation is implicit. */
    public synchronized void prepareConfiguration(byte[] configuration) throws IOException {
        MaintenanceConfiguration.prepare(this,configuration);
    }

    /** Publish once and retain source, backup, configuration and the verified target transaction.
     * requireHeld remains meaningful after publication. A failure poisons further use, but all
     * acquired handles stay owned until close. Neither success nor close authorizes activation. */
    public synchronized String publishConfiguration() throws IOException {
        Path root=configurationRoot(); var expected=configurationTarget();
        if(publicationAttempted)throw stopped();publicationAttempted=true;
        try {
            publication=MaintenanceConfiguration.holdPublication(root,operation,expected);
            String result=publication.publish(this::requireHeld);
            // Existing matching bytes alone are observational, never this owner's live publication.
            source.retainPublication(publication);requireHeld();return result;
        }catch(Exception failure){publicationFailed=true;throw stopped();}
    }

    /** The challenge is generated by the newly Gate-admitted Runtime on its private pipe.
     * The native owner must keep both actual Process objects; this record alone grants nothing. */
    public synchronized void prepareRuntimeHandoff(long runtimePid,String challenge)throws IOException {
        requireHeld();if(publication==null||!source.hasPublication()||handoffAttempted)throw stopped();
        handoffAttempted=true;
        MaintenanceRuntime.prepare(this,runtimePid,challenge,publication.configurationDigest());requireHeld();
    }

    static final class RuntimeSource implements AutoCloseable {
        final SqliteSourceLease source;final List<AutoCloseable> pins=new ArrayList<>();
        private FileChannel lock;private String lockIdentity;
        RuntimeSource(SqliteSourceLease source){this.source=source;}
        void takeOwnerLock()throws IOException {
            requireHeld();if(lock!=null)throw stopped();Path path=source.root().resolve("evidence/sqlite-maintenance/owner.lock");
            lockIdentity=TrustedDatabaseConfiguration.Identity.read(path,false);
            lock=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS,
                    ExtendedOpenOption.NOSHARE_WRITE,ExtendedOpenOption.NOSHARE_DELETE);requireHeld();
        }
        void requireHeld()throws IOException {
            source.requireHeld();
            if(lock!=null&&(!lock.isOpen()||lock.size()!=0||!lockIdentity.equals(TrustedDatabaseConfiguration.Identity.read(
                    source.root().resolve("evidence/sqlite-maintenance/owner.lock"),false))))throw stopped();
            for(var pin:pins)if(pin instanceof SqliteSourceLease.FilePin file)file.verify();
                else if(pin instanceof SqliteSourceLease.DirectoryPin directory)directory.verify();
        }
        public void close(){source.close();for(int i=pins.size()-1;i>=0;i--)try{pins.get(i).close();}catch(Exception ignored){}
            if(lock!=null)try{lock.close();}catch(Exception ignored){}}
    }

    /** Overlapping read pins are acquired while the publishing owner still holds its originals.
     * The exclusive owner.lock is taken later, only after the native parent observes source exit. */
    static RuntimeSource retainRuntimeSource(MaintenanceExclusion exclusion,String operation,
            MaintenanceConfiguration.ActivationConfiguration configuration)throws IOException {
        var result=new RuntimeSource(SqliteSourceLease.acquireForRuntime(exclusion,configuration));
        try {
            Path registry=result.source.root().resolve("evidence/sqlite-maintenance"),directory=registry.resolve(operation);
            result.pins.add(new SqliteSourceLease.DirectoryPin(registry));result.pins.add(new SqliteSourceLease.DirectoryPin(directory));
            List<String> names;
            try(var entries=Files.list(registry)){if(!entries.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet())
                    .equals(Set.of("owner.lock",operation)))throw stopped();}
            try(var entries=Files.list(directory)){names=entries.map(p->p.getFileName().toString()).sorted().limit(10).toList();}
            Entry last=null;String previous="0".repeat(64);int sequence=0;
            for(String name:names){
                if(name.equals("backup"))continue;if(!name.equals(name(sequence,".dpapi"))||sequence>7)throw stopped();
                var pin=new SqliteSourceLease.FilePin(directory.resolve(name));result.pins.add(pin);
                var next=read(pin,result.source.root().getFileName().toString(),operation,sequence);
                if(!next.previous.equals(previous)||!transition(last==null?null:last.stage,next.stage)
                        ||last!=null&&(!next.source.equals(last.source)||last.backup!=null&&!last.backup.equals(next.backup)
                        ||last.expected!=null&&!last.expected.equals(next.expected)))throw stopped();
                last=next;previous=pin.digest();sequence++;
            }
            if(last==null||last.stage!=Stage.TARGET_VERIFIED||!configuration.expected().equals(last.expected)
                    ||!last.source.equals(sourceDigest(result.source.fingerprint()))||!names.contains("backup")||names.size()!=sequence+1)throw stopped();
            var backups=new ArrayList<SqliteSourceLease.FilePin>();Set<Path> directories=new HashSet<>();Path backup=directory.resolve("backup");
            for(String relative:new TreeSet<>(result.source.fingerprint().files().keySet())){
                Path file=backup.resolve(relative);
                for(Path dir=file.getParent();dir.startsWith(backup);dir=dir.getParent())
                    if(directories.add(dir))result.pins.add(new SqliteSourceLease.DirectoryPin(dir));
                var pin=new SqliteSourceLease.FilePin(file);backups.add(pin);result.pins.add(pin);
            }
            if(!last.backup.equals(backupDigest(result.source.fingerprint(),backups)))throw stopped();
            result.requireHeld();return result;
        }catch(Exception failure){result.close();throw stopped();}
    }

    public synchronized void createBackup() throws IOException {
        requireHeld();
        if (latest.stage != Stage.SOURCE_FROZEN || backupDigest != null) throw stopped();
        // A partial backup is retained. CREATE_NEW prevents reusing the same attempt after failure.
        SqliteSourceLease.absent(backup); WindowsPrivateAcl.directory(backup); backupCreated = true;
        source.backupTo(backup);
        pinBackup(source.fingerprint());
        backupDigest = backupDigest(source.fingerprint(), backupFiles);
        append(Stage.BACKUP_READY, null);
    }

    public synchronized SqliteCombinedMigration.Expected importTarget(DataSource target) throws IOException {
        requireHeld();
        if (importAttempted || latest.stage != Stage.BACKUP_READY) throw stopped();
        importAttempted = true; // An uncertain stage write cannot replay this operation.
        try (var memory = SqliteSourceLease.readOnly(backup.resolve("memory.db"));
             var knowledge = SqliteSourceLease.readOnly(backup.resolve("knowledge/knowledge.db"))) {
            var result = SqliteCombinedMigration.importSnapshots(memory, knowledge, backup.resolve("knowledge"), target,
                    new SqliteCombinedMigration.Observer() {
                        public void prepared(SqliteCombinedMigration.Expected expected) throws IOException {
                            requireHeld(); append(Stage.TARGET_PREPARED, expected);
                        }
                        public void committing() throws IOException {
                            requireHeld(); append(Stage.COMMIT_ATTEMPTED, latest.expected);
                        }
                    }, operation);
            requireHeld(); append(Stage.TARGET_VERIFIED, result);
            // No database.dpapi creation and no implicit restart/authority switch.
            return result;
        } catch (SqliteCombinedMigration.Failure failure) {
            try {
                if (latest.stage == Stage.TARGET_PREPARED || latest.stage == Stage.COMMIT_ATTEMPTED)
                    append(failure.outcome() == SqliteCombinedMigration.Outcome.COMMIT_UNCONFIRMED
                            ? Stage.COMMIT_UNCONFIRMED : Stage.STOPPED_BEFORE_COMMIT, failure.expected());
            } catch (IOException unknownJournal) { /* Pending/partial evidence remains; no replay. */ }
            throw failure;
        } catch (Exception failure) { throw stopped(); }
    }

    public synchronized void requireHeld() throws IOException {
        if (closed||publicationFailed) throw stopped();
        source.requireHeld();
        requireOwnerLock(); requireJournalNames();
        for (var pin : directories) pin.verify();
        for (var pin : records) pin.verify();
        for (var pin : backupFiles) pin.verify();
        if (backupDigest != null && !backupDigest.equals(backupDigest(source.fingerprint(), backupFiles))) throw stopped();
        // During publish the absent-source invariant still applies; after the transition both
        // the exact published configuration and the original live target transaction must survive.
        if(publication!=null&&source.hasPublication())publication.requireHeld(source.root());
    }

    private void pinBackup(SqliteSourceLease.Fingerprint fingerprint) throws IOException {
        Set<Path> pinned = new HashSet<>();
        for (String relative : new TreeSet<>(fingerprint.files().keySet())) {
            Path file = backup.resolve(relative);
            for (Path dir = file.getParent(); dir.startsWith(backup); dir = dir.getParent())
                if (pinned.add(dir)) directories.add(new SqliteSourceLease.DirectoryPin(dir));
            backupFiles.add(new SqliteSourceLease.FilePin(file));
        }
    }

    private void append(Stage stage, SqliteCombinedMigration.Expected expected) throws IOException {
        source.requireHeld();
        requireOwnerLock(); requireJournalNames();
        int sequence = latest == null ? 0 : latest.sequence + 1;
        if (!transition(latest == null ? null : latest.stage, stage) || sequence > 7) throw stopped();
        String previous = records.isEmpty() ? "0".repeat(64) : records.getLast().digest();
        Entry next = new Entry(1, "SQLITE_MAINTENANCE_OBSERVATION", source.root().getFileName().toString(), operation,
                sequence, previous, sourceDigest, backupDigest, stage, expected);
        byte[] plain = null, protectedBytes = null;
        Path pending = directory.resolve(name(sequence, ".pending")), complete = directory.resolve(name(sequence, ".dpapi"));
        try {
            plain = JSON.writeValueAsBytes(next);
            if (plain.length > 16384) throw stopped();
            protectedBytes = Crypt32Util.cryptProtectData(plain, entropy(next.profile, operation, sequence), 1, "SQLite maintenance observation", null);
            if (protectedBytes.length > 16384) throw stopped();
            WindowsPrivateAcl.createFile(pending);
            try (var output = FileChannel.open(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = ByteBuffer.wrap(protectedBytes); while (bytes.hasRemaining()) output.write(bytes); output.force(true);
            }
            Files.move(pending, complete); // No REPLACE_EXISTING. Any uncertainty is retained.
            var pin = new SqliteSourceLease.FilePin(complete); records.add(pin);
            if (!next.equals(read(pin, next.profile, operation, sequence))) throw stopped();
            latest = next;
        } catch (Exception failure) { throw stopped(); }
        finally { if (plain != null) Arrays.fill(plain, (byte)0); if (protectedBytes != null) Arrays.fill(protectedBytes, (byte)0); }
    }

    private void requireOwnerLock() throws IOException {
        Path lock = directory.getParent().resolve("owner.lock");
        if (ownerLock == null || !ownerLock.isOpen() || ownerLock.size() != 0
                || !ownerLockIdentity.equals(TrustedDatabaseConfiguration.Identity.read(lock, false))) throw stopped();
        WindowsPrivateAcl.verify(lock, false);
    }

    private void requireJournalNames() throws IOException {
        Set<String> expected = new HashSet<>();
        for (var record : records) expected.add(record.path.getFileName().toString());
        if (backupCreated) expected.add("backup");
        try (var entries = Files.list(directory)) {
            var actual = entries.map(p -> p.getFileName().toString()).limit(10).toList();
            if (actual.size() != expected.size() || !expected.containsAll(actual)) throw stopped();
        }
        try (var entries = Files.list(directory.getParent())) {
            var names = entries.map(p -> p.getFileName().toString()).limit(3).toList();
            if (names.size() != 2 || !names.contains("owner.lock") || !names.contains(operation)) throw stopped();
        }
    }

    /** Reacquire a fresh kernel proof and inspect durable observations. A changed configuration
     * is never decrypted, deleted or restored. No resume/import/publish method consumes these results. */
    public static Recovery inspect(MaintenanceExclusion exclusion, String operation, DataSource target) throws IOException {
        Path root = WindowsNativeGate.maintenanceProfileRoot(exclusion);
        if (operation == null || !operation.matches("[0-9a-f]{32}")) throw stopped();
        try { SqliteSourceLease.absent(root.resolve("data/database.dpapi")); }
        catch (IOException changed) { return new Recovery("CONFIGURATION_CHANGED_OR_UNREADABLE", null, null); }
        try (var source = SqliteSourceLease.acquire(exclusion)) {
            Path registry = root.resolve("evidence/sqlite-maintenance"), directory = registry.resolve(operation);
            var pins = new ArrayList<AutoCloseable>();
            try {
                pins.add(new SqliteSourceLease.DirectoryPin(registry)); pins.add(new SqliteSourceLease.DirectoryPin(directory));
                List<String> names;
                try (var files = Files.list(directory)) { names = files.map(p -> p.getFileName().toString()).sorted().limit(10).toList(); }
                if (names.size() > 9 || names.stream().anyMatch(n -> !n.equals("backup") && !n.matches("000[0-7]\\.dpapi")))
                    return new Recovery("JOURNAL_UNCONFIRMED", null, null);
                Entry last = null; String previous = "0".repeat(64); int sequence = 0;
                for (String name : names) {
                    if (name.equals("backup")) continue;
                    if (!name.equals(name(sequence, ".dpapi"))) throw stopped();
                    var pin = new SqliteSourceLease.FilePin(directory.resolve(name)); pins.add(pin);
                    Entry next = read(pin, root.getFileName().toString(), operation, sequence);
                    if (!next.previous.equals(previous) || !transition(last == null ? null : last.stage, next.stage)
                            || last != null && (!next.source.equals(last.source)
                            || last.backup != null && !last.backup.equals(next.backup)
                            || last.expected != null && !last.expected.equals(next.expected))) throw stopped();
                    last = next; previous = pin.digest(); sequence++;
                }
                if (last == null) return new Recovery("JOURNAL_UNCONFIRMED", null, null);
                if (!last.source.equals(sourceDigest(source.fingerprint()))) return new Recovery("SOURCE_CHANGED", last.stage, null);
                if (last.backup != null) {
                    var backups = new ArrayList<SqliteSourceLease.FilePin>(); Set<Path> dirs = new HashSet<>();
                    Path backup = directory.resolve("backup");
                    for (String relative : new TreeSet<>(source.fingerprint().files().keySet())) {
                        Path file = backup.resolve(relative);
                        for (Path dir = file.getParent(); dir.startsWith(backup); dir = dir.getParent())
                            if (dirs.add(dir)) pins.add(new SqliteSourceLease.DirectoryPin(dir));
                        var pin = new SqliteSourceLease.FilePin(file); backups.add(pin); pins.add(pin);
                    }
                    if (!last.backup.equals(backupDigest(source.fingerprint(), backups))) return new Recovery("BACKUP_CHANGED", last.stage, null);
                }
                var observed = last.expected == null ? null : SqliteCombinedMigration.inspect(target, last.expected);
                source.requireHeld();
                return new Recovery(last.expected == null ? "NO_TARGET_COMMIT_OBSERVED" : "TARGET_INSPECTED_ACTIVATION_NOT_AUTHORIZED", last.stage, observed);
            } catch (Exception failure) { throw stopped(); }
            finally { for (int i = pins.size() - 1; i >= 0; i--) try { pins.get(i).close(); } catch (Exception ignored) {} }
        }
    }

    private static Entry read(SqliteSourceLease.FilePin pin, String profile, String operation, int sequence) throws IOException {
        byte[] plain = null;
        try {
            if (pin.channel.size() < 1 || pin.channel.size() > 16384) throw stopped();
            var bytes = ByteBuffer.allocate((int)pin.channel.size()); long at = 0;
            while (bytes.hasRemaining()) { int n = pin.channel.read(bytes, at); if (n < 1) throw stopped(); at += n; }
            plain = Crypt32Util.cryptUnprotectData(bytes.array(), entropy(profile, operation, sequence), 1, null);
            Entry result = JSON.readValue(plain, Entry.class);
            if (result.version != 1 || !"SQLITE_MAINTENANCE_OBSERVATION".equals(result.kind)
                    || !profile.equals(result.profile) || !operation.equals(result.operation) || result.sequence != sequence
                    || result.stage == null || !result.source.matches("[0-9a-f]{64}") || !result.previous.matches("[0-9a-f]{64}")
                    || (result.stage == Stage.SOURCE_FROZEN ? result.backup != null : result.backup == null)
                    || result.backup != null && !result.backup.matches("[0-9a-f]{64}")
                    || (result.stage == Stage.SOURCE_FROZEN || result.stage == Stage.BACKUP_READY) != (result.expected == null)) throw stopped();
            pin.verify(); return result;
        } catch (Exception failure) { throw stopped(); }
        finally { if (plain != null) Arrays.fill(plain, (byte)0); }
    }
    private static boolean transition(Stage before, Stage after) {
        if (before == null) return after == Stage.SOURCE_FROZEN;
        return switch (before) {
            case SOURCE_FROZEN -> after == Stage.BACKUP_READY;
            case BACKUP_READY -> after == Stage.TARGET_PREPARED;
            case TARGET_PREPARED -> after == Stage.COMMIT_ATTEMPTED || after == Stage.STOPPED_BEFORE_COMMIT;
            case COMMIT_ATTEMPTED -> after == Stage.TARGET_VERIFIED || after == Stage.COMMIT_UNCONFIRMED || after == Stage.STOPPED_BEFORE_COMMIT;
            default -> false;
        };
    }
    private static byte[] entropy(String profile, String operation, int sequence) {
        return ("PersonalAIWorkspace/SqliteMaintenance/observation/v1/" + profile + "/" + operation + "/" + sequence).getBytes(StandardCharsets.US_ASCII);
    }
    private static String name(int sequence, String extension) { return String.format(Locale.ROOT, "%04d", sequence) + extension; }
    private static String sourceDigest(SqliteSourceLease.Fingerprint source) throws IOException {
        return digest(source.rootIdentity(), source.files());
    }
    private static String backupDigest(SqliteSourceLease.Fingerprint source, List<SqliteSourceLease.FilePin> pins) throws IOException {
        var names = new TreeSet<>(source.files().keySet()).iterator(); Map<String, String> values = new TreeMap<>();
        for (var pin : pins) { if (!names.hasNext()) throw stopped(); values.put(names.next(), pin.digest()); }
        if (names.hasNext()) throw stopped();
        return digest("backup-v1", values);
    }
    private static String digest(String prefix, Map<String, String> values) throws IOException {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            try (var output = new DataOutputStream(new java.security.DigestOutputStream(OutputStream.nullOutputStream(), sha))) {
                output.writeUTF(prefix);
                for (var entry : new TreeMap<>(values).entrySet()) { output.writeUTF(entry.getKey()); output.writeUTF(entry.getValue()); }
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (Exception failure) { throw stopped(); }
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        for (var pin : records) try { pin.close(); } catch (IOException ignored) {}
        for (var pin : backupFiles) try { pin.close(); } catch (IOException ignored) {}
        for (int i = directories.size() - 1; i >= 0; i--) directories.get(i).close();
        source.close(); // The caller still owns its independent NativeGate exclusion.
        if(publication!=null)publication.close();
        if (ownerLock != null) try { ownerLock.close(); } catch (IOException ignored) {}
    }
    private static IOException stopped() { return new IOException("Maintenance observation incomplete; preserve source, backup and configuration; no replay or activation"); }
    @Override public String toString() { return "SqliteMaintenanceOwner[activation-blocked]"; }
}
