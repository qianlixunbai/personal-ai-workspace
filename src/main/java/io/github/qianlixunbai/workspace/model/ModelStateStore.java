package io.github.qianlixunbai.workspace.model;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import com.sun.nio.file.ExtendedOpenOption;

/** Private, single-writer state. Neither selection nor execution guards belong to domain backups. */
public final class ModelStateStore implements AutoCloseable {
    public static final long MAX_REVISION = 9007199254740991L;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public record Selection(long selectionRevision, String provider, String model, String digest) {
        public Selection {
            if (selectionRevision < 1 || selectionRevision > MAX_REVISION || !"ollama".equals(provider)
                    || !validModel(model) || !validDigest(digest)) throw new IllegalArgumentException("Invalid model selection");
        }
        @Override public String toString() { return "ModelSelection[private]"; }
    }
    public record ValidationRequired(long selectionRevision, String provider, String model, String digest) {
        public ValidationRequired {
            if (selectionRevision < 0 || selectionRevision > MAX_REVISION || !"ollama".equals(provider)
                    || !validModel(model) || !validDigest(digest)) throw new IllegalArgumentException("Invalid validation binding");
        }
        @Override public String toString() { return "ModelValidationRequired[private]"; }
    }
    private final Path root;
    private final UserPrincipal account;
    private final Object rootKey;
    private final FileChannel channel;
    private final FileLock lock;
    private final Object lockKey;
    private final boolean windows;
    private FileChannel selectionPin, guardPin, validationPin;
    private ValidationRequired validation;
    private byte[] validationBytes;
    private Object validationKey;
    private Selection selection;
    private byte[] committed;
    private Object selectionKey;
    private boolean unresolved;
    private Object guardKey;
    private boolean closed;

    public ModelStateStore(Path directory, Path tokenFile, Path dataDirectory) throws IOException {
        root = directory.toAbsolutePath().normalize();
        Path credentials = tokenFile.toAbsolutePath().normalize().getParent();
        Path data = dataDirectory.toAbsolutePath().normalize();
        if (overlaps(root, credentials) || overlaps(root, data) || root.getParent() == null)
            throw new IOException("Model state must be separate");
        for (Path ancestor = root; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(ancestor.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Model state must be outside the project");
        }
        account = root.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        windows = Files.getFileAttributeView(nearestExisting(root), PosixFileAttributeView.class) == null
                && Files.getFileAttributeView(nearestExisting(root), DosFileAttributeView.class) != null;
        noLinks(root);
        createDirectories(root);
        rootKey = verify(root, true);
        Path lockFile = root.resolve(".lock");
        createPrivate(lockFile);
        lockKey = verify(lockFile, false);
        // Windows NIO supplies no fileKey. An OS handle denies lock deletion/writing and root rename.
        channel = windows ? FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_DELETE, ExtendedOpenOption.NOSHARE_WRITE)
                : FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Model state already has a writer");
            lock = acquired;
            checkPaths();
            Path file = root.resolve("active-model.json");
            if (exists(file)) {
                selectionPin = pin(file);
                committed = read(file, 4096);
                selection = parseSelection(committed);
                selectionKey = verify(file, false);
            }
            Path pending = root.resolve("active-model.pending");
            if (exists(pending)) {
                // A committed file is the only authority; never promote a pending selection.
                parseSelection(read(pending, 4096));
                if (selection == null) throw new IOException("Interrupted model selection");
            }
            // Even malformed guard state is unresolved. No startup path deletes it.
            unresolved = exists(root.resolve("execution-guard.json")) || exists(root.resolve("execution-guard.pending"));
            if (unresolved) {
                for (String name : List.of("execution-guard.json", "execution-guard.pending")) {
                    Path guard = root.resolve(name);
                    if (exists(guard)) {
                        try { parseGuard(read(guard, 128)); }
                        catch (IOException invalid) { /* Remains unresolved, including permission/type errors. */ }
                    }
                }
            }
            // An interrupted marker is never promoted or ignored, even alongside a valid commit.
            if (exists(root.resolve("validation-required.pending"))) throw new IOException("Interrupted validation requirement");
            Path marker = root.resolve("validation-required.json");
            if (exists(marker)) {
                validationPin = pin(marker);
                validationBytes = read(marker, 4096);
                validation = parseValidation(validationBytes);
                validationKey = verify(marker, false);
            }
        } catch (IOException | RuntimeException failure) {
            if (selectionPin != null) selectionPin.close();
            if (validationPin != null) validationPin.close();
            if (acquired != null) acquired.release();
            channel.close();
            throw failure;
        }
    }

    public synchronized Selection selection() throws IOException { checkSelection(); return selection; }
    public synchronized boolean unresolved() { return unresolved; }

    public synchronized ValidationRequired validationRequired() throws IOException {
        checkSelection();
        Path marker = root.resolve("validation-required.json");
        if (exists(root.resolve("validation-required.pending"))) throw new IOException("Interrupted validation requirement");
        if (validation == null) {
            if (exists(marker)) throw new IOException("Unexpected validation requirement");
        } else if (!exists(marker) || !Objects.equals(validationKey, verify(marker, false))
                || !Arrays.equals(validationBytes, read(marker, 4096))) throw new IOException("Validation requirement changed externally");
        return validation;
    }

    /** Publish before recovery clears its guard, or before a confirmed mutation can evict the selection. */
    synchronized void requireValidation(long revision, String model, String digest) throws IOException {
        var current = validationRequired();
        var next = new ValidationRequired(revision, "ollama", model, digest);
        if (selection != null ? revision != selection.selectionRevision() || !model.equals(selection.model()) || !digest.equals(selection.digest())
                : revision != 0) throw new IOException("Validation selection binding changed");
        if (current != null) {
            if (!current.equals(next)) throw new IOException("Validation identity changed");
            return;
        }
        byte[] bytes = JSON.writeValueAsBytes(Map.of("version", 1, "validationRequired", true,
                "selectionRevision", revision, "provider", "ollama", "model", model, "digest", digest));
        atomicWrite("validation-required.json", "validation-required.pending", bytes, 4096, false);
        validationPin = pin(root.resolve("validation-required.json"));
        byte[] actual = read(root.resolve("validation-required.json"), 4096);
        if (!Arrays.equals(bytes, actual)) throw new IOException("Validation publication outcome unknown");
        validation = parseValidation(actual);
        validationBytes = actual;
        validationKey = verify(root.resolve("validation-required.json"), false);
    }

    /** Only the model owner after successful explicitly confirmed validation/selection publication. */
    synchronized void clearValidation() throws IOException {
        if (validationRequired() == null) return;
        Path marker = root.resolve("validation-required.json");
        if (validationPin != null) { validationPin.close(); validationPin = null; }
        try { Files.delete(marker); }
        catch (IOException failure) { validationPin = pin(marker); throw failure; }
        if (exists(marker)) throw new IOException("Validation removal outcome unknown");
        validation = null; validationBytes = null; validationKey = null;
    }

    public synchronized Selection commit(long expectedRevision, String model, String digest) throws IOException {
        checkSelection();
        long revision = selection == null ? 0 : selection.selectionRevision();
        if (revision != expectedRevision || revision == MAX_REVISION) throw new IOException("Model selection revision conflict");
        Selection next = new Selection(revision + 1, "ollama", model, digest);
        byte[] bytes = JSON.writeValueAsBytes(Map.of("version", 1, "selectionRevision", next.selectionRevision(),
                "provider", next.provider(), "model", next.model(), "digest", next.digest()));
        atomicWrite("active-model.json", "active-model.pending", bytes, 4096, true);
        // Read back the commit before publication. A failure here means outcome unknown, never rollback.
        byte[] actual = read(root.resolve("active-model.json"), 4096);
        if (!Arrays.equals(bytes, actual)) throw new IOException("Model commit outcome unknown");
        selection = parseSelection(actual);
        committed = actual;
        selectionKey = verify(root.resolve("active-model.json"), false);
        return selection;
    }

    /** Called before transport send, serialized with all operation exits by the model owner. */
    public synchronized void armGuard() throws IOException {
        checkSelection();
        if (unresolved) {
            if (guardKey == null || !Objects.equals(guardKey, verify(root.resolve("execution-guard.json"), false)))
                throw new IOException("Execution guard identity changed");
            parseGuard(read(root.resolve("execution-guard.json"), 128));
            if (exists(root.resolve("execution-guard.pending"))) throw new IOException("Interrupted execution guard");
            return;
        }
        if (exists(root.resolve("execution-guard.json")) || exists(root.resolve("execution-guard.pending")))
            throw new IOException("Unexpected execution guard");
        atomicWrite("execution-guard.json", "execution-guard.pending",
                "{\"version\":1,\"unresolved\":true}".getBytes(StandardCharsets.UTF_8), 128, false);
        unresolved = true;
        guardPin = pin(root.resolve("execution-guard.json"));
        guardKey = verify(root.resolve("execution-guard.json"), false);
    }

    public synchronized void clearGuard() throws IOException {
        checkSelection();
        if (!unresolved) return;
        Path guard = root.resolve("execution-guard.json");
        if (guardKey == null || !Objects.equals(guardKey, verify(guard, false))) throw new IOException("Execution guard identity changed");
        parseGuard(read(guard, 128));
        if (exists(root.resolve("execution-guard.pending"))) throw new IOException("Interrupted execution guard");
        checkPaths();
        if (guardPin != null) { guardPin.close(); guardPin = null; }
        try { Files.delete(guard); }
        catch (IOException failure) { guardPin = pin(guard); throw failure; }
        unresolved = false;
        guardKey = null;
    }

    /** Explicit native recovery only. Startup guard remains unresolved until this action succeeds. */
    synchronized void recoverGuard() throws IOException {
        checkSelection();
        if (!unresolved || exists(root.resolve("execution-guard.pending"))) throw new IOException("Unrecoverable execution guard");
        Path guard = root.resolve("execution-guard.json");
        parseGuard(read(guard, 128));
        Object observed = verify(guard, false);
        if (guardKey != null && !Objects.equals(guardKey, observed)) throw new IOException("Execution guard identity changed");
        if (guardPin == null) guardPin = pin(guard);
        guardKey = observed;
        clearGuard();
    }

    private void atomicWrite(String targetName, String pendingName, byte[] bytes, int limit, boolean selectionWrite) throws IOException {
        if (bytes.length > limit) throw new IOException("Model state exceeds budget");
        Path pending = root.resolve(pendingName), target = root.resolve(targetName);
        if (exists(pending)) throw new IOException("Pending model state exists");
        Files.createFile(pending, permissions(false));
        Object pendingKey = verify(pending, false);
        try (var out = FileChannel.open(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) out.write(buffer);
            out.force(true);
        }
        // Keep failed artifacts for strict recovery; do not delete an uncertain commit.
        checkPaths();
        if (selectionWrite) {
            if (committed == null) {
                if (exists(target)) throw new IOException("Unexpected model selection");
            } else checkSelection();
        }
        if (!Objects.equals(pendingKey, verify(pending, false))) throw new IOException("Model pending identity changed");
        if (exists(target)) verify(target, false);
        if (selectionWrite && selectionPin != null) { selectionPin.close(); selectionPin = null; }
        try { Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { if (selectionWrite && exists(target)) selectionPin = pin(target); }
    }

    private void checkSelection() throws IOException {
        checkPaths();
        Path file = root.resolve("active-model.json");
        if (committed == null) {
            if (exists(file) || exists(root.resolve("active-model.pending"))) throw new IOException("Unexpected model selection");
        } else if (!exists(file) || !Objects.equals(selectionKey, verify(file, false))
                || !Arrays.equals(committed, read(file, 4096))) throw new IOException("Model selection changed externally");
    }
    private void checkPaths() throws IOException {
        if (closed || !lock.isValid() || !Objects.equals(rootKey, verify(root, true))
                || !Objects.equals(lockKey, verify(root.resolve(".lock"), false))) throw new IOException("Model writer unavailable");
    }
    private byte[] read(Path file, int limit) throws IOException {
        Object key = verify(file, false);
        ByteBuffer buffer = ByteBuffer.allocate(limit + 1);
        try (var input = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (buffer.hasRemaining() && input.read(buffer) != -1) { }
        }
        if (buffer.position() > limit || !Objects.equals(key, verify(file, false))) throw new IOException("Invalid model state size or identity");
        return Arrays.copyOf(buffer.array(), buffer.position());
    }
    private static JsonNode parse(byte[] bytes) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) throw new IOException("Invalid model state encoding");
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isObject()) throw new IOException("Invalid model state object");
            return node;
        } catch (RuntimeException failure) { throw new IOException("Invalid model state JSON"); }
    }
    private static Selection parseSelection(byte[] bytes) throws IOException {
        JsonNode node = parse(bytes);
        exact(node, Set.of("version", "selectionRevision", "provider", "model", "digest"));
        if (!node.path("version").isIntegralNumber() || !node.path("version").canConvertToLong() || node.path("version").asLong() != 1
                || !node.path("selectionRevision").isIntegralNumber() || !node.path("selectionRevision").canConvertToLong()
                || !node.path("provider").isString() || !node.path("model").isString() || !node.path("digest").isString())
            throw new IOException("Invalid model selection schema");
        try { return new Selection(node.path("selectionRevision").asLong(), node.path("provider").asString(),
                node.path("model").asString(), node.path("digest").asString()); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid model selection values"); }
    }
    private static void parseGuard(byte[] bytes) throws IOException {
        JsonNode node = parse(bytes);
        exact(node, Set.of("version", "unresolved"));
        if (!node.path("version").isIntegralNumber() || !node.path("version").canConvertToLong() || node.path("version").asLong() != 1
                || !node.path("unresolved").isBoolean() || !node.path("unresolved").asBoolean()) throw new IOException("Invalid execution guard");
    }
    private static ValidationRequired parseValidation(byte[] bytes) throws IOException {
        JsonNode node = parse(bytes);
        exact(node, Set.of("version", "validationRequired", "selectionRevision", "provider", "model", "digest"));
        if (!node.path("version").isIntegralNumber() || !node.path("version").canConvertToLong() || node.path("version").asLong() != 1
                || !node.path("validationRequired").isBoolean() || !node.path("validationRequired").asBoolean()
                || !node.path("selectionRevision").isIntegralNumber() || !node.path("selectionRevision").canConvertToLong()
                || !node.path("provider").isString() || !node.path("model").isString() || !node.path("digest").isString())
            throw new IOException("Invalid validation requirement schema");
        try { return new ValidationRequired(node.path("selectionRevision").asLong(), node.path("provider").asString(),
                node.path("model").asString(), node.path("digest").asString()); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid validation requirement values"); }
    }
    private static void exact(JsonNode node, Set<String> expected) throws IOException {
        Set<String> fields = new HashSet<>();
        node.propertyNames().forEach(fields::add);
        if (!fields.equals(expected)) throw new IOException("Unknown model state fields");
    }
    public static boolean validModel(String model) {
        // Supported local library[/namespace]/name:tag subset. No remote host, URL, alias or implicit tag.
        return model != null && model.getBytes(StandardCharsets.UTF_8).length <= 256
                && model.matches("[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)?:[a-z0-9][a-z0-9._-]*")
                && !model.contains("..") && !model.endsWith(":cloud") && !model.endsWith("-cloud") && !model.endsWith(":local");
    }
    static String canonicalModel(String reference) {
        if (reference == null || reference.getBytes(StandardCharsets.UTF_8).length > 256) return null;
        String model = reference.strip();
        if (model.endsWith(":local")) model = model.substring(0, model.length() - 6);
        if (model.startsWith("registry.ollama.ai/")) model = model.substring(19);
        if (model.startsWith("library/")) model = model.substring(8);
        if (!model.contains(":")) model += ":latest";
        return validModel(model) ? model : null;
    }
    public static boolean validDigest(String digest) { return digest != null && digest.matches("[0-9a-f]{64}"); }
    private static boolean overlaps(Path a, Path b) { return a.startsWith(b) || b.startsWith(a); }
    private static boolean exists(Path path) throws IOException {
        try { Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException missing) { return false; }
    }
    private static void noLinks(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) {
            if (exists(part)) {
                var attr = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attr.isSymbolicLink() || attr.isOther()) throw new IOException("Model state cannot use links or reparse points");
            }
        }
    }
    private FileAttribute<?> permissions(boolean directory) throws IOException {
        if (Files.getFileStore(nearestExisting(root)).supportsFileAttributeView(PosixFileAttributeView.class))
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        var builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(account)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        List<AclEntry> value = List.of(builder.build());
        return new FileAttribute<List<AclEntry>>() {
            public String name() { return "acl:acl"; }
            public List<AclEntry> value() { return value; }
        };
    }
    private static Path nearestExisting(Path path) throws IOException { while (!exists(path)) path = path.getParent(); return path; }
    private void createDirectories(Path path) throws IOException {
        if (exists(path)) return;
        createDirectories(path.getParent());
        Files.createDirectory(path, permissions(true));
    }
    private void createPrivate(Path file) throws IOException {
        if (!exists(file)) {
            try { Files.createFile(file, permissions(false)); }
            catch (FileAlreadyExistsException ignored) { /* Verify the existing identity and permissions. */ }
        }
    }
    private Object verify(Path path, boolean directory) throws IOException {
        noLinks(path);
        var attr = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if ((directory ? !attr.isDirectory() : !attr.isRegularFile()) || (!windows && attr.fileKey() == null)
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(account)) throw new IOException("Invalid model state ownership or type");
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            if (!posix.readAttributes().permissions().equals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")))
                throw new IOException("Model state permissions are not private");
        } else {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw new IOException("Private model state permissions unavailable");
            boolean allowed = false;
            for (AclEntry entry : acl.getAcl()) {
                if (entry.type() == AclEntryType.ALLOW) {
                    if (!entry.principal().equals(account)) throw new IOException("Model state permissions are not private");
                    if (entry.permissions().containsAll(EnumSet.allOf(AclEntryPermission.class))) allowed = true;
                } else throw new IOException("Unsupported model state ACL");
            }
            if (!allowed) throw new IOException("Model state owner access unavailable");
        }
        return attr.fileKey() != null ? attr.fileKey() : new WindowsIdentity(path.toRealPath(LinkOption.NOFOLLOW_LINKS), attr.creationTime());
    }
    private record WindowsIdentity(Path path, FileTime created) {}
    private FileChannel pin(Path file) throws IOException {
        return windows ? FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_DELETE, ExtendedOpenOption.NOSHARE_WRITE) : null;
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            if (selectionPin != null) selectionPin.close();
            if (guardPin != null) guardPin.close();
            if (validationPin != null) validationPin.close();
        } finally { try { lock.release(); } finally { channel.close(); } }
    }
}
