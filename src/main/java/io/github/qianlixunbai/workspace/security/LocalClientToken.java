package io.github.qianlixunbai.workspace.security;

import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

@Component
public final class LocalClientToken {
    private final byte[] expected;

    public LocalClientToken(RuntimeProperties properties) {
        Path file = properties.security().tokenFile().toAbsolutePath().normalize();
        try {
            Path parent = file.getParent();
            Files.createDirectories(parent);
            for (Path part = parent; part != null; part = part.getParent()) {
                if (Files.isSymbolicLink(part)) throw new IllegalStateException("Token location must not use symbolic links");
            }
            restrict(parent, true);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] random = new byte[32];
                new SecureRandom().nextBytes(random);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
                Path temporary = Files.createTempFile(parent, ".token-", ".tmp");
                try {
                    restrict(temporary, false);
                    Files.writeString(temporary, token, StandardCharsets.US_ASCII);
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(temporary); }
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 128)
                throw new IllegalStateException("Invalid local client token file");
            restrict(file, false);
            String token = Files.readString(file, StandardCharsets.US_ASCII).strip();
            if (!token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalStateException("Invalid local client token format");
            expected = token.getBytes(StandardCharsets.US_ASCII);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot initialize private local client credentials");
        }
    }

    static void restrict(Path path, boolean directory) throws java.io.IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new java.io.IOException("Private permissions unavailable");
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        acl.setAcl(List.of(entry.build()));
    }

    public boolean matches(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.length() != 50) return false;
        return MessageDigest.isEqual(expected, header.substring(7).getBytes(StandardCharsets.US_ASCII));
    }
}
