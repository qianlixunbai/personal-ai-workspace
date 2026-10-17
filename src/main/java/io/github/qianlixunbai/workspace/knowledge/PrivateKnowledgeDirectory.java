package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Knowledge-owned OS boundary. Deliberately independent of Memory implementation. */
public final class PrivateKnowledgeDirectory {
    private PrivateKnowledgeDirectory() {}
    public static Path prepare(Path data, Path token) throws IOException {
        validate(data,token); directory(data);
        Path root=data.resolve("knowledge"); directory(root); directory(root.resolve("sources")); directory(root.resolve("staging"));
        for(String name:List.of("knowledge.db","knowledge.db-journal","knowledge.db-wal","knowledge.db-shm","knowledge.lock")) {
            Path file=root.resolve(name); if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
                regular(file);
                if(WindowsPrivateAcl.isWindows() && name.startsWith("knowledge.db-"))WindowsPrivateAcl.verifySqliteSidecar(file);
                else protect(file,false);
            }
        }
        return root;
    }
    public static void validate(Path path,Path token) throws IOException {
        Path data=path.toAbsolutePath().normalize(),auth=token.toAbsolutePath().normalize().getParent();
        if(!path.isAbsolute() || data.toString().startsWith("\\\\") || data.equals(Path.of("").toAbsolutePath())
                || data.startsWith(auth)||auth.startsWith(data)) throw new IOException("Invalid Knowledge location");
        for(Path part=data;part!=null;part=part.getParent()) {
            if(Files.exists(part.resolve(".git"),LinkOption.NOFOLLOW_LINKS)||Files.isRegularFile(part.resolve("pom.xml"))
                    ||Files.isRegularFile(part.resolve("build.gradle"))) throw new IOException("Invalid Knowledge location");
        }
        for(Path part:data) if(Set.of("build","target","logs",".git",".runtime").contains(part.toString().toLowerCase(Locale.ROOT)))
            throw new IOException("Invalid Knowledge location");
        noLinks(data);
    }
    public static void noLinks(Path path) throws IOException {
        for(Path part=path.toAbsolutePath();part!=null;part=part.getParent()) if(Files.exists(part,LinkOption.NOFOLLOW_LINKS)) {
            var a=Files.readAttributes(part,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(a.isSymbolicLink()||a.isOther()) throw new IOException("Knowledge links forbidden");
        }
    }
    public static void regular(Path file) throws IOException {
        noLinks(file); if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid Knowledge object");
    }
    public static void directory(Path path) throws IOException {
        noLinks(path);
        if(WindowsPrivateAcl.isWindows())WindowsPrivateAcl.directory(path);else Files.createDirectories(path);
        noLinks(path);
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid Knowledge object");
        protect(path,true);
    }
    public static void file(Path path) throws IOException {
        noLinks(path);
        if(WindowsPrivateAcl.isWindows())WindowsPrivateAcl.createFile(path);else Files.createFile(path);
        protect(path,false);
    }
    public static void protect(Path path,boolean directory) throws IOException {
        if(WindowsPrivateAcl.isWindows()){WindowsPrivateAcl.verify(path,directory);return;}
        var owner=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(owner)) throw new IOException("Knowledge owner required");
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null) { var modes=PosixFilePermissions.fromString(directory?"rwx------":"rw-------");posix.setPermissions(modes);
            if(!posix.readAttributes().permissions().equals(modes))throw new IOException("Private permissions unavailable");return; }
        throw new IOException("Private permissions unavailable");
    }
}
