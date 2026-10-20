package io.github.qianlixunbai.workspace.memory;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static io.github.qianlixunbai.workspace.memory.MemoryLimits.error;

/** Maintenance publishes a fully closed, checked DB; it never opens the active store. */
public final class MemoryBackupService {
    private final Path current, token;
    private final Runnable beforePublish;
    public MemoryBackupService(Path current, Path token) { this(current, token, () -> {}); }
    MemoryBackupService(Path current, Path token, Runnable beforePublish) {
        this.current = current.toAbsolutePath().normalize(); this.token = token; this.beforePublish = beforePublish;
    }
    public MemoryBackup.Metadata restore(MemoryBackup backup, String targetDirectory) {
        restoreDatabase(targetDirectory, staging -> {
            try (var store = new MemoryStore(staging, token, false)) { store.reconstruct(backup.items()); }
        });
        return backup.metadata();
    }
    /** Shared ADR-006 publication boundary; builder only receives the task-owned staging directory. */
    public void restoreDatabase(String targetDirectory, java.util.function.Consumer<Path> builder) {
        restoreDatabase(targetDirectory, builder, false);
    }
    public void restoreWorkspaceDatabase(String targetDirectory, java.util.function.Consumer<Path> builder) {
        restoreDatabase(targetDirectory, builder, true);
    }
    private void restoreDatabase(String targetDirectory, java.util.function.Consumer<Path> builder, boolean workspace) {
        Path staging = null;
        try {
            if (targetDirectory == null || targetDirectory.length() > 8192) throw error(ErrorCode.MEMORY_RESTORE_FAILED);
            Path target = Path.of(targetDirectory);
            if (!target.isAbsolute()) throw error(ErrorCode.MEMORY_RESTORE_FAILED);
            target = target.normalize();
            if (target.startsWith(current) || current.startsWith(target)) throw error(ErrorCode.MEMORY_RESTORE_TARGET_NOT_EMPTY);
            PrivateMemoryDirectory.validateLocation(target, token);
            Path parent = target.getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw error(ErrorCode.MEMORY_RESTORE_FAILED);
            boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            emptyOrNew(target);
            var directoryIdentity = existed ? Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS) : null;
            if (WindowsPrivateAcl.isWindows()) {
                staging = parent.resolve((workspace ? ".workspace-restore-" : ".memory-restore-") + UUID.randomUUID());
                PrivateMemoryDirectory.directory(staging);
            } else staging = Files.createTempDirectory(parent, workspace ? ".workspace-restore-" : ".memory-restore-");
            builder.accept(staging);
            beforePublish.run();
            // Recheck after the potentially slow reconstruction. Unknown states are never replaced.
            PrivateMemoryDirectory.noLinks(target); emptyOrNew(target);
            if (!sameVolume(staging, parent)) throw error(ErrorCode.MEMORY_RESTORE_FAILED);
            if (existed != Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw error(ErrorCode.MEMORY_RESTORE_TARGET_NOT_EMPTY);
            if (existed) {
                var identityNow = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                // The JDK Windows provider can return no fileKey. Retain creation-time checks on that provider.
                if (!Objects.equals(directoryIdentity.fileKey(), identityNow.fileKey())
                        || !directoryIdentity.creationTime().equals(identityNow.creationTime())) throw error(ErrorCode.MEMORY_RESTORE_TARGET_NOT_EMPTY);
                if (!sameVolume(staging, target)) throw error(ErrorCode.MEMORY_RESTORE_FAILED);
                // A same-volume, no-replace rename publishes one complete closed database. No rows can be partially visible.
                // The user's empty directory itself is retained; never delete it to make room.
                PrivateMemoryDirectory.protect(target, true);
                emptyOrNew(target);
                Files.move(staging.resolve("memory.db"), target.resolve("memory.db"));
            } else {
                // No REPLACE_EXISTING, no cross-volume copy, no fallback that could overwrite a racing target.
                Files.move(staging, target); staging = null;
            }
        } catch (WorkspaceException controlled) {
            if (controlled.error().code().name().startsWith("WORKSPACE_BACKUP_")) throw controlled;
            if (controlled.error().code() == ErrorCode.MEMORY_RESTORE_TARGET_NOT_EMPTY) throw controlled;
            throw error(ErrorCode.MEMORY_RESTORE_FAILED);
        } catch (FileAlreadyExistsException ignored) { throw error(ErrorCode.MEMORY_RESTORE_TARGET_NOT_EMPTY); }
        catch (Exception ignored) { throw error(ErrorCode.MEMORY_RESTORE_FAILED); }
        finally {
            if (staging != null) {
                boolean cleanupFailed = false;
                // Only the unpredictable task-owned sibling and known SQLite files; never recursive user-directory deletion.
                for (String name : List.of("memory.db", "memory.db-journal", "memory.db-wal", "memory.db-shm")) {
                    try { Files.deleteIfExists(staging.resolve(name)); } catch (Exception ignored) { cleanupFailed = true; }
                }
                try { Files.delete(staging); } catch (Exception ignored) { cleanupFailed = true; }
                if (workspace && cleanupFailed) throw error(ErrorCode.WORKSPACE_RESTORE_FAILED);
            }
        }
    }
    private static boolean sameVolume(Path first, Path second) throws IOException {
        if (!WindowsPrivateAcl.isWindows()) return Files.getFileStore(first).equals(Files.getFileStore(second));
        // JDK 21 FileStore equality compares root spellings: C:\ and \\?\C:\ differ.
        // Staging creation has already passed NativeGate and the private-directory checks.
        return windowsVolume(first).equalsIgnoreCase(windowsVolume(second));
    }
    private static String windowsVolume(Path path) throws IOException {
        char[] mount = new char[32768], volume = new char[64];
        var kernel = Kernel32.INSTANCE;
        if (!kernel.GetVolumePathName("\\\\?\\" + path, mount, mount.length)
                || !kernel.GetVolumeNameForVolumeMountPoint(Native.toString(mount), volume, volume.length))
            throw new IOException("Restore volume identity unavailable");
        return Native.toString(volume);
    }
    private static void emptyOrNew(Path target) throws Exception {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw error(ErrorCode.MEMORY_RESTORE_TARGET_NOT_EMPTY);
        try (var files = Files.newDirectoryStream(target)) {
            if (files.iterator().hasNext()) throw error(ErrorCode.MEMORY_RESTORE_TARGET_NOT_EMPTY);
        }
    }
}
