package io.github.qianlixunbai.workspace.backup;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

/** Offline synthetic maintenance files only; never substitutes for Windows Runtime admission. */
final class MaintenanceFiles {
    private MaintenanceFiles() {}
    static void noLinks(Path path) throws IOException {
        for (Path part=path.toAbsolutePath(); part!=null; part=part.getParent()) {
            if (!Files.exists(part,LinkOption.NOFOLLOW_LINKS)) continue;
            var a=Files.readAttributes(part,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            require(!a.isSymbolicLink()&&!a.isOther());
        }
    }
    static void privatePath(Path path,boolean directory) throws IOException {
        noLinks(path);
        require(directory?Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS):Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS));
        var user=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        require(Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(user));
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if (acl!=null) {
            var rules=acl.getAcl(); require(!rules.isEmpty());
            for(var rule:rules) require(rule.type()==AclEntryType.ALLOW&&rule.principal().equals(user));
        } else {
            var a=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            require(a.permissions().equals(PosixFilePermissions.fromString(directory?"rwx------":"rw-------")));
        }
    }
    static void directory(Path path) throws IOException {
        privatePath(path.getParent(),true); noLinks(path); Files.createDirectory(path);
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null)posix.setPermissions(PosixFilePermissions.fromString("rwx------"));
        privatePath(path,true);
    }
    static void write(Path path,byte[] bytes) throws IOException {
        privatePath(path.getParent(),true);
        try(var out=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(posix!=null)posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            privatePath(path,false); var buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining())out.write(buffer);out.force(true);
        }
    }
    static void publish(Path path,byte[] bytes) throws IOException {
        noLinks(path);require(!Files.exists(path,LinkOption.NOFOLLOW_LINKS));
        Path pending=path.resolveSibling(path.getFileName()+".pending");
        write(pending,bytes); // An incomplete/failed flush can never produce the final marker name.
        Files.move(pending,path,StandardCopyOption.ATOMIC_MOVE);
    }
    static FileChannel pin(Path file) throws IOException {
        noLinks(file); require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS));
        Set<OpenOption> options=new HashSet<>(Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
        if(System.getProperty("os.name","").startsWith("Windows")) {
            options.add(ExtendedOpenOption.NOSHARE_WRITE);options.add(ExtendedOpenOption.NOSHARE_DELETE);
        }
        return FileChannel.open(file,options);
    }
    static void copy(Path from,Path to) throws IOException {
        try(var input=pin(from)) {
            write(to,new byte[0]);
            try(var output=FileChannel.open(to,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                var buffer=ByteBuffer.allocate(65536);
                while(input.read(buffer)!=-1){buffer.flip();while(buffer.hasRemaining())output.write(buffer);buffer.clear();}output.force(true);
            }
        }
    }
    static String digest(Path path) throws IOException {
        try(var input=pin(path)) {
            var hash=sha256();var buffer=ByteBuffer.allocate(65536);
            while(input.read(buffer)!=-1){buffer.flip();hash.update(buffer);buffer.clear();}
            return HexFormat.of().formatHex(hash.digest());
        }
    }
    static MessageDigest sha256() {
        try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static void require(boolean ok) throws IOException {if(!ok)throw new IOException("Maintenance validation rejected");}
}
