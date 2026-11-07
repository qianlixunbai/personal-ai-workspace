package io.github.qianlixunbai.workspace.persistence;

import java.io.IOException;
import com.sun.nio.file.ExtendedOpenOption;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;

/** Recovery preparation/binding is not maintenance admission. No Spring or target provisioning. */
public final class NativeProfileRecovery {
    private NativeProfileRecovery() {}

    /** Freeze the already-admitted authority's Workspace configuration for one maintenance
     * attempt. Full stopped-writer proof is still required before any personal backup runs. */
    public static TrustedDatabaseConfiguration.RecoverySource freezeSource(MaintenanceExclusion exclusion) throws IOException {
        return TrustedDatabaseConfiguration.freezeRecoverySource(exclusion);
    }

    /** Actual product dispatch, read-only and terminal. It never starts Spring, creates a
     * source role, repairs a guard or publishes a successful source/maintenance receipt. */
    public static int runInspection(String[] arguments, java.io.InputStream input, java.io.OutputStream output) {
        var json = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        try {
            WindowsNativeGate.admit(); // Original helper/DLL/ACL gate BEFORE any handshake/input.
            String profileId = System.getProperty("workspace.native.development-profile", "");
            if (!profileId.matches("[0-9a-f]{32}")) throw stopped();
            var pipe = new java.io.DataOutputStream(output);
            writeFrame(pipe, ("WORKSPACE_MAINTENANCE_V1\t" + ProcessHandle.current().pid() + "\t" + profileId)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            var request = new java.io.DataInputStream(input);
            int size = request.readInt(); if (size < 1 || size > 16384) throw stopped();
            byte[] bytes = request.readNBytes(size);
            if (bytes.length != size || input.read() != -1) throw stopped();
            var call = json.readTree(bytes);
            if (!call.isObject() || call.size() != 3 || !call.path("Version").isIntegralNumber()
                    || !call.path("Version").canConvertToInt() || call.path("Version").intValue() != 1
                    || !call.path("Action").isString() || !call.path("Action").asString().equals("INSPECT_SOURCE")
                    || !call.path("ExclusionHandle").isIntegralNumber() || !call.path("ExclusionHandle").canConvertToLong()) throw stopped();
            try (var exclusion = WindowsNativeGate.receiveMaintenanceExclusion(call.path("ExclusionHandle").longValue())) {
                WindowsNativeGate.requireMaintenanceArguments(arguments, exclusion);
                Path root = WindowsNativeGate.maintenanceProfileRoot(exclusion);
                String model = "NO_GUARD_FILE_OBSERVED";
                try (var entries = Files.list(root.resolve("model-state"))) {
                    var names = entries.limit(5).map(path -> path.getFileName().toString()).toList();
                    if (names.size() > 3 || names.stream().anyMatch(name -> !java.util.Set.of(
                            ".lock", "active-model.json", "validation-required.json").contains(name))) model = "UNRESOLVED_MODEL_STATE";
                }
                String sourceState = "NOT_READ_MODEL_STATE_UNRESOLVED", identity = null, digest = null;
                long databaseOid = 0, appRoleOid = 0;
                if (model.equals("NO_GUARD_FILE_OBSERVED")) {
                    try (var source = freezeSource(exclusion)) {
                        var observed = source.inspectApplication();
                        sourceState = "BOUND_READ_ONLY"; identity = source.configurationIdentity(); digest = source.configurationSha256();
                        databaseOid = observed.databaseOid(); appRoleOid = observed.applicationRoleOid();
                    } catch (TrustedDatabaseConfiguration.Failure failure) { sourceState = failure.reason().name(); }
                    catch (IOException failure) { sourceState = "CONFIGURATION_UNAVAILABLE"; }
                }
                exclusion.requireHeld(root);
                var result = new java.util.LinkedHashMap<String, Object>();
                result.put("Version", 1); result.put("Kind", "WORKSPACE_MAINTENANCE_INSPECTION");
                result.put("ChildPid", ProcessHandle.current().pid()); result.put("ProfileId", profileId);
                result.put("Admission", "BLOCKED"); result.put("WriterCoverage", "UNREGISTERED_WRITERS_UNPROVEN");
                result.put("ModelState", model); result.put("SourceState", sourceState);
                result.put("SourceConfigurationIdentity", identity); result.put("SourceConfigurationSha256", digest);
                result.put("SourceDatabaseOid", databaseOid); result.put("SourceApplicationRoleOid", appRoleOid);
                writeFrame(pipe, json.writeValueAsBytes(result));
                return 0; // Successful inspection, explicitly not successful maintenance admission.
            }
        } catch (Exception | LinkageError failure) {
            // Fixed frame only; no messages, paths, credential material or raw child diagnostics.
            try { writeFrame(new java.io.DataOutputStream(output), "WORKSPACE_MAINTENANCE_V1\tINSPECTION_DENIED"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII)); } catch (IOException ignored) { }
            return 10;
        }
    }

    private static void writeFrame(java.io.DataOutputStream pipe, byte[] bytes) throws IOException {
        if (bytes.length < 1 || bytes.length > 16384) throw stopped();
        pipe.writeInt(bytes.length); pipe.write(bytes); pipe.flush();
    }

    /** Cooperating processes share a read lease; native maintenance requests WRITE on the same
     * empty file. Win32 sharing excludes either side, across sessions, before credentials/SQL.
     * This is not evidence that older binaries or external database clients are absent. */
    public static RuntimeLease acquireRuntimeLease(Path verifiedProfileRoot) throws IOException {
        requireOrdinaryProfile(verifiedProfileRoot);
        Path evidence = verifiedProfileRoot.resolve("evidence");
        Path file = evidence.resolve("runtime-admission.lock");
        // The native gate has already pinned the canonical Profile AND evidence directory.
        WindowsPrivateAcl.verify(evidence, true);
        try { WindowsPrivateAcl.createFile(file); }
        catch (FileAlreadyExistsException existing) { /* Verify; never truncate or repair. */ }
        WindowsPrivateAcl.verify(file, false);
        String identity = TrustedDatabaseConfiguration.Identity.read(file, false);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE);
        try {
            var lease = new RuntimeLease(verifiedProfileRoot, file, identity, channel);
            lease.requireHeld(verifiedProfileRoot);
            return lease;
        } catch (IOException | RuntimeException | Error failure) { channel.close(); throw failure; }
    }

    public static final class RuntimeLease implements AutoCloseable {
        private final Path root, file;
        private final String identity;
        private final FileChannel channel;
        private RuntimeLease(Path root, Path file, String identity, FileChannel channel) {
            this.root = root; this.file = file; this.identity = identity; this.channel = channel;
        }
        public void requireHeld(Path expectedRoot) throws IOException {
            if (!root.equals(expectedRoot) || !channel.isOpen() || channel.size() != 0
                    || !identity.equals(TrustedDatabaseConfiguration.Identity.read(file, false))) throw stopped();
            WindowsPrivateAcl.verify(file.getParent(), true);
            WindowsPrivateAcl.verify(file, false);
        }
        // WindowsNativeGate retains this object until OS process termination. It deliberately
        // has no shutdown hook: releasing it before all Spring/DB shutdown work would be unsafe.
        @Override public void close() throws IOException { channel.close(); }
    }

    /** Additive denial after the existing helper verifies the canonical Profile identity.
     * No caller flag/receipt can authorize a candidate before publication and activation exist. */
    public static void requireOrdinaryProfile(Path verifiedProfileRoot) throws IOException {
        Path profiles = verifiedProfileRoot.getParent();
        if (profiles == null || !profiles.getFileName().toString().equals("Profiles")
                || profiles.getParent() == null
                || !verifiedProfileRoot.getFileName().toString().matches("[0-9a-f]{32}")) throw stopped();
        Path recovery = profiles.getParent().resolve("Recovery");
        BasicFileAttributes registry;
        try { registry = attributes(recovery); }
        catch (NoSuchFileException absent) { return; }
        requireDirectory(recovery, registry);
        Path targets = recovery.resolve("targets");
        // An incomplete, linked, unreadable or malformed registry never means "unregistered".
        requireDirectory(targets, attributes(targets));
        Path syntheticTargets=recovery.resolve("synthetic-targets");
        try {
            requireDirectory(syntheticTargets,attributes(syntheticTargets));
            try {attributes(syntheticTargets.resolve(verifiedProfileRoot.getFileName()));throw stopped();}
            catch(NoSuchFileException absent){/* An unregistered Profile still uses the original Gate. */}
        }catch(NoSuchFileException absent){/* No synthetic target registry. */}
        try { attributes(targets.resolve(verifiedProfileRoot.getFileName())); }
        catch (NoSuchFileException absent) { return; }
        throw stopped();
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireDirectory(Path path, BasicFileAttributes attributes) throws IOException {
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                || !path.equals(path.toRealPath())) throw stopped();
    }

    private static IOException stopped() { return new IOException("Recovery candidate or registration unavailable"); }
}
