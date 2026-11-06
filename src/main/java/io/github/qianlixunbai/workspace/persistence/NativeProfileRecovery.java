package io.github.qianlixunbai.workspace.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Recovery preparation is not admission. No SQL, DPAPI, Spring or target provisioning here. */
public final class NativeProfileRecovery {
    private NativeProfileRecovery() {}

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
