package io.github.qianlixunbai.workspace.memory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** OS account/filesystem protection, not encryption. No personal text or path in errors. */
final class PrivateMemoryDirectory {
    private PrivateMemoryDirectory() {}

    static Path prepare(Path directory, Path tokenFile) throws IOException {
        Path data = directory.toAbsolutePath().normalize();
        Path credentials = tokenFile.toAbsolutePath().normalize().getParent();
        Path working = Path.of("").toAbsolutePath().normalize();
        if (data.equals(working) || insideProject(data) || data.startsWith(credentials) || credentials.startsWith(data))
            throw new IOException("Memory location is not separate");
        for (Path part : data) {
            if (Set.of("build", "target", "logs", ".git", ".runtime").contains(part.toString().toLowerCase(Locale.ROOT)))
                throw new IOException("Memory location is not private data");
        }
        noLinks(data);
        Files.createDirectories(data);
        noLinks(data);
        protect(data, true);
        for (String name : List.of("memory.db", "memory.db-wal", "memory.db-shm", "memory.db-journal")) {
            Path file = data.resolve(name);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid Memory file");
                protect(file, false);
            }
        }
        Path database = data.resolve("memory.db");
        if (!Files.exists(database, LinkOption.NOFOLLOW_LINKS)) {
            // Create privately before JDBC writes any data; sidecars inherit the private directory ACL.
            try { Files.createFile(database); }
            catch (FileAlreadyExistsException ignored) { /* Another Runtime may have created it. */ }
        }
        if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid Memory file");
        protect(database, false);
        return database;
    }

    private static boolean insideProject(Path data) {
        for (Path part = data; part != null; part = part.getParent()) {
            if (Files.exists(part.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                    || Files.isRegularFile(part.resolve("pom.xml"))
                    || Files.isRegularFile(part.resolve("build.gradle"))) return true;
        }
        return false;
    }

    private static void noLinks(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther())
                    throw new IOException("Memory location cannot use links or reparse points");
            }
        }
    }

    private static void protect(Path path, boolean directory) throws IOException {
        var lookup = path.getFileSystem().getUserPrincipalLookupService();
        UserPrincipal account = lookup.lookupPrincipalByName(System.getProperty("user.name"));
        if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(account))
            throw new IOException("Memory location must belong to current account");
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            var permissions = PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------");
            posix.setPermissions(permissions);
            if (!posix.readAttributes().permissions().equals(permissions)) throw new IOException("Private permissions unavailable");
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("Private permissions unavailable");
        var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(account)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        List<AclEntry> expected = List.of(entry.build());
        acl.setAcl(expected);
        if (!acl.getAcl().equals(expected)) throw new IOException("Private permissions unavailable");
    }
}
