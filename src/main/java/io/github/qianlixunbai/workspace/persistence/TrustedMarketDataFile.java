package io.github.qianlixunbai.workspace.persistence;

import com.sun.nio.file.ExtendedOpenOption;
import io.github.qianlixunbai.workspace.memory.PrivateMemoryDirectory;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;

/** Fixed, device-local DPAPI file. Never included in Finance or Workspace backups. */
public final class TrustedMarketDataFile {
    private static final int LIMIT = 262144;
    private Path directory() throws IOException {
        var root = WindowsNativeGate.profileRoot(); // Existing admission, no alternate path/configuration.
        var data = root.resolve("data");
        PrivateMemoryDirectory.validateLocation(data, root.resolve("credentials/client-token"));
        PrivateMemoryDirectory.protect(data, true);
        return data;
    }
    public byte[] read() throws IOException {
        var data = directory(); var file = data.resolve("massive-data.dpapi");
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        PrivateMemoryDirectory.noLinks(file); WindowsPrivateAcl.verify(file, false);
        var identity = TrustedDatabaseConfiguration.Identity.read(file, false);
        var buffer = ByteBuffer.allocate(LIMIT + 1);
        byte[] encrypted = null;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)) {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
            if (buffer.position() < 1 || buffer.position() > LIMIT) throw new IOException();
            directory(); WindowsPrivateAcl.verify(file, false);
            if (!identity.equals(TrustedDatabaseConfiguration.Identity.read(file, false))) throw new IOException();
            encrypted = Arrays.copyOf(buffer.array(), buffer.position());
            return TrustedDatabaseConfiguration.Protection.unprotect(encrypted);
        } finally { Arrays.fill(buffer.array(), (byte) 0); if (encrypted != null) Arrays.fill(encrypted, (byte) 0); }
    }
    public void write(byte[] plaintext) throws IOException {
        if (plaintext.length > LIMIT / 2) throw new IOException();
        var data = directory(); var file = data.resolve("massive-data.dpapi");
        var dataIdentity = TrustedDatabaseConfiguration.Identity.read(data, true);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            PrivateMemoryDirectory.noLinks(file); WindowsPrivateAcl.verify(file, false);
            TrustedDatabaseConfiguration.Identity.read(file, false);
        }
        var temporary = data.resolve("market-data-" + java.util.UUID.randomUUID() + ".tmp");
        byte[] encrypted = TrustedDatabaseConfiguration.Protection.protect(plaintext);
        boolean created = false;
        try {
            WindowsPrivateAcl.createFile(temporary);
            created = true;
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
                    ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)) {
                var buffer = ByteBuffer.wrap(encrypted);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true); WindowsPrivateAcl.verify(temporary, false);
            }
            directory();
            if (!dataIdentity.equals(TrustedDatabaseConfiguration.Identity.read(data, true))) throw new IOException();
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            WindowsPrivateAcl.verify(file, false);
        } finally { Arrays.fill(encrypted, (byte) 0); if (created) Files.deleteIfExists(temporary); }
    }
}
