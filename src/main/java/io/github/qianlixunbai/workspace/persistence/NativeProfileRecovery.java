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

/** Recovery preparation/binding is not maintenance admission. No Spring or target provisioning. */
public final class NativeProfileRecovery {
    private NativeProfileRecovery() {}

    /** Freeze the already-admitted authority's Workspace configuration for one maintenance
     * attempt. Full stopped-writer proof is still required before any personal backup runs. */
    public static TrustedDatabaseConfiguration.RecoverySource freezeSource(MaintenanceExclusion exclusion) throws IOException {
        return TrustedDatabaseConfiguration.freezeRecoverySource(exclusion);
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
