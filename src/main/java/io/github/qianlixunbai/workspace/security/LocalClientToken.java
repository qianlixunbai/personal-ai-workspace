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
    private enum Stage { DIRECTORY_PREPARE, ACL_READ_OR_PROTECT, TEMP_FILE_CREATE, TEMP_FILE_PROTECT,
        TOKEN_WRITE, ATOMIC_MOVE, FINAL_FILE_VERIFY, TOKEN_READ, CLEANUP }

    public LocalClientToken(RuntimeProperties properties) {
        Path file = properties.security().tokenFile().toAbsolutePath().normalize();
        Stage stage = Stage.DIRECTORY_PREPARE;
        try {
            Path parent = file.getParent();
            if (WindowsPrivateAcl.isWindows()) WindowsPrivateAcl.directory(parent);
            else Files.createDirectories(parent);
            for (Path part = parent; part != null; part = part.getParent()) {
                var attributes = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IllegalStateException("Token location must not use links");
            }
            stage = Stage.ACL_READ_OR_PROTECT;
            restrict(parent, true);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] random = new byte[32];
                new SecureRandom().nextBytes(random);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
                stage = Stage.TEMP_FILE_CREATE;
                Path temporary;
                if (WindowsPrivateAcl.isWindows()) {
                    temporary = parent.resolve(".token-" + UUID.randomUUID() + ".tmp");
                    WindowsPrivateAcl.createFile(temporary);
                } else temporary = Files.createTempFile(parent, ".token-", ".tmp");
                Exception primary = null;
                try {
                    stage = Stage.TEMP_FILE_PROTECT;
                    restrict(temporary, false);
                    stage = Stage.TOKEN_WRITE;
                    Files.writeString(temporary, token, StandardCharsets.US_ASCII);
                    stage = Stage.ATOMIC_MOVE;
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.io.IOException | RuntimeException failure) { primary = failure; throw failure; }
                finally {
                    try { Files.deleteIfExists(temporary); }
                    catch (java.io.IOException | RuntimeException cleanup) {
                        if (primary == null) { stage = Stage.CLEANUP; throw cleanup; }
                        // Preserve the original stage. Initialization still fails; no raw cause is published.
                    }
                }
            }
            stage = Stage.FINAL_FILE_VERIFY;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 128)
                throw new IllegalStateException("Invalid local client token file");
            restrict(file, false);
            stage = Stage.TOKEN_READ;
            String token = Files.readString(file, StandardCharsets.US_ASCII).strip();
            if (!token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalStateException("Invalid local client token format");
            expected = token.getBytes(StandardCharsets.US_ASCII);
        } catch (java.io.IOException | RuntimeException failure) {
            String category = failure instanceof WindowsPrivateAcl.NativeFailure nativeFailure
                    ? "WIN32_" + Integer.toUnsignedString(nativeFailure.code)
                    : failure instanceof AccessDeniedException ? "ACCESS_DENIED"
                    : failure instanceof FileAlreadyExistsException ? "ALREADY_EXISTS"
                    : failure instanceof NoSuchFileException ? "NOT_FOUND"
                    : failure instanceof java.io.IOException ? "IO_FAILURE" : "VALIDATION_FAILURE";
            throw new IllegalStateException("Cannot initialize private local client credentials: " + stage + "/" + category);
        }
    }

    static void restrict(Path path, boolean directory) throws java.io.IOException {
        if (WindowsPrivateAcl.isWindows()) { WindowsPrivateAcl.verify(path, directory); return; }
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        throw new java.io.IOException("Private permissions unavailable");
    }

    public boolean matches(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.length() != 50) return false;
        return MessageDigest.isEqual(expected, header.substring(7).getBytes(StandardCharsets.US_ASCII));
    }
}
