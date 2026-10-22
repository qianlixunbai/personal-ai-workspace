package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import io.github.qianlixunbai.workspace.memory.PrivateMemoryDirectory;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration;
import com.sun.nio.file.ExtendedOpenOption;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Server-owned, portable profile and one-time confirmed binding; never a client credential. */
public final class LocalFinanceProfile {
    private static final int LIMIT = 2048;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Path root;
    private final Path tokenFile;

    public LocalFinanceProfile(Path dataDirectory, Path tokenFile) {
        this.root = dataDirectory.toAbsolutePath().normalize().resolve("finance-identity");
        this.tokenFile = tokenFile;
    }

    public record Binding(int format, UUID profileId, long financeUserId,
                          String datasetFingerprint, String userFingerprint) {
        public Binding {
            if (format != 1 || profileId == null || profileId.equals(new UUID(0, 0)) || financeUserId < 1
                    || !fingerprint(datasetFingerprint) || !fingerprint(userFingerprint)) {
                throw new IllegalArgumentException("Invalid Finance binding");
            }
        }
        @Override public String toString() { return "FinanceBinding[private]"; }
    }

    public Binding requireBinding() {
        try {
            PrivateMemoryDirectory.validateLocation(root, tokenFile);
            Path file = root.resolve("binding.json");
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw failure(ErrorCode.FINANCE_NOT_BOUND);
            PrivateMemoryDirectory.noLinks(file);
            PrivateMemoryDirectory.protect(root, true);
            PrivateMemoryDirectory.protect(file, false);
            var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException();
            Object before = fileIdentity(file);
            ByteBuffer buffer = ByteBuffer.allocate(LIMIT + 1);
            Set<OpenOption> options = new java.util.HashSet<>(Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            if (WindowsPrivateAcl.isWindows()) options.addAll(Set.of(ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE));
            try (var channel = FileChannel.open(file, options)) {
                while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
                PrivateMemoryDirectory.noLinks(file);
                PrivateMemoryDirectory.protect(file, false);
                if (before == null || !Objects.equals(before, fileIdentity(file))) throw new IOException();
                return parse(Arrays.copyOf(buffer.array(), buffer.position()));
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw failure(ErrorCode.FINANCE_BINDING_INVALID);
        }
    }

    private static Object fileIdentity(Path file) throws IOException {
        return WindowsPrivateAcl.isWindows() ? TrustedDatabaseConfiguration.Identity.read(file, false)
                : Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    /** Internal provisioning only, after an explicitly confirmed database/user proof is rechecked. */
    Binding createConfirmed(FinanceIdentityRepository.Identity identity) {
        try {
            PrivateMemoryDirectory.validateLocation(root, tokenFile);
            PrivateMemoryDirectory.directory(root);
            Path file = root.resolve("binding.json");
            Binding binding = new Binding(1, UUID.randomUUID(), identity.userId(),
                    identity.datasetFingerprint(), identity.userFingerprint());
            byte[] bytes = JSON.writeValueAsBytes(binding);
            // CREATE_NEW is also the cross-process no-rebind boundary. Never truncate/recover an existing file.
            if (WindowsPrivateAcl.isWindows()) WindowsPrivateAcl.createFile(file);
            else Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            PrivateMemoryDirectory.protect(file, false);
            try (var channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (!binding.equals(requireBinding())) throw new IOException();
            return binding;
        } catch (IOException | IllegalArgumentException failure) {
            // An interrupted/unknown publication stays on disk and requires explicit recovery; never auto-rebind.
            throw failure(ErrorCode.FINANCE_BINDING_INVALID);
        }
    }

    static Binding parse(byte[] bytes) {
        try {
            if (bytes.length == 0 || bytes.length > LIMIT) throw new IOException();
            var node = JSON.readTree(bytes);
            if (node == null || !node.isObject() || node.size() != 5
                    || !node.propertyNames().equals(Set.of("format", "profileId", "financeUserId", "datasetFingerprint", "userFingerprint"))
                    || !node.get("format").isIntegralNumber() || !node.get("format").canConvertToInt()
                    || !node.get("financeUserId").isIntegralNumber()
                    || !node.get("financeUserId").canConvertToLong() || !node.get("profileId").isString()
                    || !node.get("datasetFingerprint").isString() || !node.get("userFingerprint").isString()) {
                throw new IOException();
            }
            String profile = node.get("profileId").asString();
            UUID id = UUID.fromString(profile);
            if (!id.toString().equals(profile)) throw new IOException();
            return new Binding(node.get("format").intValue(), id, node.get("financeUserId").longValue(),
                    node.get("datasetFingerprint").asString(), node.get("userFingerprint").asString());
        } catch (IOException | RuntimeException failure) {
            throw failure(ErrorCode.FINANCE_BINDING_INVALID);
        }
    }

    private static boolean fingerprint(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static WorkspaceException failure(ErrorCode code) { return new WorkspaceException(code, "FINANCE"); }
}
