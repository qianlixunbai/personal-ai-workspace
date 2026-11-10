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
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;

/** Private, single-writer state. Neither selection nor execution guards belong to domain backups. */
public final class ModelStateStore implements AutoCloseable {
    public static final long MAX_REVISION = 9007199254740991L;
    private static final int MAX_GUARD_BYTES = 65536, MAX_GUARD_REQUESTS = 128;
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
    private final WindowsPrivateAcl.OwnerIdentity windowsOwner;
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
    private byte[] guardBytes;
    private final UUID ownerSession = UUID.randomUUID();
    private final String stateBinding;
    private final Map<UUID, RequestBinding> guardRequests = new LinkedHashMap<>();
    private boolean closed;

    /** Metadata only: never includes prompts, response bodies, client tokens or provider credentials. */
    public record RequestBinding(UUID requestId, UUID reservationId, String endpoint, String operation,
                                 String model, String runner, String digest, long selectionRevision) {
        public RequestBinding {
            Objects.requireNonNull(requestId); Objects.requireNonNull(reservationId);
            var uri = java.net.URI.create(endpoint);
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                    || uri.getPort() < 1 || uri.getPort() == 11435 || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !("chat".equals(operation) && "/api/chat".equals(uri.getPath())
                        || "unload".equals(operation) && "/api/generate".equals(uri.getPath()))
                    || !validModel(model) || !validDigest(digest) || !Set.of("ggml", "llamacpp", "mlx").contains(runner)
                    || selectionRevision < 0 || selectionRevision > MAX_REVISION)
                throw new IllegalArgumentException("Invalid external request binding");
        }
        @Override public String toString() { return "ModelRequestBinding[private]"; }
    }

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
        windows = Files.getFileAttributeView(nearestExisting(root), PosixFileAttributeView.class) == null
                && Files.getFileAttributeView(nearestExisting(root), DosFileAttributeView.class) != null;
        // Windows Profile admission and private ACLs bind the effective TokenUser, not a JVM property.
        windowsOwner = windows ? WindowsPrivateAcl.bindCurrentOwner() : null;
        account = windows ? null : root.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        noLinks(root);
        createDirectories(root);
        rootKey = verify(root, true);
        try {
            stateBinding = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((root + "\n" + rootKey).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IOException("State identity unavailable"); }
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
                        try { parseGuard(read(guard, MAX_GUARD_BYTES)); }
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

    record GuardInspection(String state, List<RequestBinding> requests) {
        GuardInspection { requests = List.copyOf(requests); }
        @Override public String toString() { return "GuardInspection[private]"; }
    }
    private record ParsedGuard(UUID owner, String stateBinding, List<RequestBinding> requests) {}

    /** Local diagnostic read only. Parsed historical identities never become live capabilities. */
    synchronized GuardInspection inspectGuard() throws IOException {
        checkSelection();
        Path file = root.resolve("execution-guard.json");
        if (exists(root.resolve("execution-guard.pending"))) return new GuardInspection("INCOMPLETE", List.of());
        if (!exists(file)) return new GuardInspection(unresolved ? "UNREADABLE" : "NONE", List.of());
        try {
            byte[] bytes = read(file, MAX_GUARD_BYTES);
            var parsed = parseGuard(bytes);
            if (!Arrays.equals(bytes, read(file, MAX_GUARD_BYTES))) throw new IOException("Guard changed during inspection");
            if (guardBytes != null) checkLiveGuard(false);
            if (parsed.owner() == null) return new GuardInspection("LEGACY", List.of());
            boolean current = guardBytes != null && ownerSession.equals(parsed.owner()) && stateBinding.equals(parsed.stateBinding());
            return new GuardInspection(current ? "CURRENT_SESSION" : "PREVIOUS_SESSION", parsed.requests());
        } catch (IOException | RuntimeException invalid) { return new GuardInspection("UNREADABLE", List.of()); }
    }

    /** Checks this actual writer, without discovering/loading a model or changing selection. */
    public synchronized void requireMaintenanceOwnership(Path expectedRoot) throws IOException {
        if (!root.equals(expectedRoot) || unresolved) throw new IOException("Model ownership unavailable");
        checkSelection(); validationRequired();
    }
    public synchronized boolean maintenanceClosed() { return closed && !channel.isOpen() && !lock.isValid(); }

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
        if (!guardRequests.isEmpty()) throw new IOException("Bound guard cannot be used as a legacy guard");
        if (unresolved) {
            checkLiveGuard(false);
            return;
        }
        publishGuard("{\"version\":1,\"unresolved\":true}".getBytes(StandardCharsets.UTF_8));
    }

    /** Called by the real request owner before the first possible socket write. */
    public synchronized void armGuard(RequestBinding binding) throws IOException {
        Objects.requireNonNull(binding);
        checkSelection();
        if (unresolved) {
            checkLiveGuard(false);
            if (guardRequests.isEmpty()) throw new IOException("Legacy guard has no request owner");
        }
        if (guardRequests.size() >= MAX_GUARD_REQUESTS || guardRequests.containsKey(binding.requestId()))
            throw new IOException("Execution guard request limit or reused identity");
        var next = new LinkedHashMap<>(guardRequests);
        next.put(binding.requestId(), binding);
        byte[] bytes = JSON.writeValueAsBytes(Map.of("version", 2, "unresolved", true,
            "ownerSession", ownerSession, "stateBinding", stateBinding, "requests", next.values()));
        if (bytes.length > MAX_GUARD_BYTES) throw new IOException("Execution guard exceeds budget");
        publishGuard(bytes);
        guardRequests.put(binding.requestId(), binding);
    }

    private void publishGuard(byte[] bytes) throws IOException {
        if (unresolved) checkLiveGuard(false);
        else if (exists(root.resolve("execution-guard.json")) || exists(root.resolve("execution-guard.pending")))
            throw new IOException("Unexpected execution guard");
        // Fail closed even if durable publication fails before its final read-back.
        unresolved = true;
        atomicWrite("execution-guard.json", "execution-guard.pending", bytes, MAX_GUARD_BYTES, false);
        byte[] actual = read(root.resolve("execution-guard.json"), MAX_GUARD_BYTES);
        if (!Arrays.equals(bytes, actual)) throw new IOException("Execution guard publication outcome unknown");
        guardBytes = actual;
        guardKey = verify(root.resolve("execution-guard.json"), false);
    }

    private void checkLiveGuard(boolean ownPending) throws IOException {
        Path guard = root.resolve("execution-guard.json");
        if (guardBytes == null || guardKey == null || !Objects.equals(guardKey, verify(guard, false))
                || !Arrays.equals(guardBytes, read(guard, MAX_GUARD_BYTES)))
            throw new IOException("Execution guard owner or identity changed");
        if (!ownPending && exists(root.resolve("execution-guard.pending"))) throw new IOException("Interrupted execution guard");
    }

    public synchronized void clearGuard() throws IOException {
        checkSelection();
        if (!unresolved) return;
        Path guard = root.resolve("execution-guard.json");
        checkLiveGuard(false);
        checkPaths();
        if (guardPin != null) { guardPin.close(); guardPin = null; }
        try { Files.delete(guard); }
        catch (IOException failure) { guardPin = pin(guard); throw failure; }
        if (exists(guard)) throw new IOException("Execution guard removal outcome unknown");
        unresolved = false;
        guardKey = null; guardBytes = null; guardRequests.clear();
    }

    /** Historical guards have no request/owner binding. Native consent cannot recover them. */
    synchronized void recoverGuard() throws IOException {
        throw new IOException("External execution ownership and completion are unproven; preserve guard");
    }

    private void atomicWrite(String targetName, String pendingName, byte[] bytes, int limit, boolean selectionWrite) throws IOException {
        if (bytes.length > limit) throw new IOException("Model state exceeds budget");
        Path pending = root.resolve(pendingName), target = root.resolve(targetName);
        if (exists(pending)) throw new IOException("Pending model state exists");
        if (windows) WindowsPrivateAcl.createFile(pending);
        else Files.createFile(pending, permissions(false));
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
        boolean guardWrite = targetName.equals("execution-guard.json");
        if (guardWrite && guardBytes != null) checkLiveGuard(true);
        if (selectionWrite && selectionPin != null) { selectionPin.close(); selectionPin = null; }
        if (guardWrite && guardPin != null) { guardPin.close(); guardPin = null; }
        try { Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally {
            if (selectionWrite && exists(target)) selectionPin = pin(target);
            if (guardWrite && exists(target)) guardPin = pin(target);
        }
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
    private static ParsedGuard parseGuard(byte[] bytes) throws IOException {
        JsonNode node = parse(bytes);
        if (node.path("version").isIntegralNumber() && node.path("version").canConvertToLong() && node.path("version").asLong() == 2) {
            exact(node, Set.of("version", "unresolved", "ownerSession", "stateBinding", "requests"));
            if (!node.path("unresolved").isBoolean() || !node.path("unresolved").asBoolean()
                    || !node.path("stateBinding").isString() || !validDigest(node.path("stateBinding").asString())
                    || !node.path("ownerSession").isString() || !node.path("requests").isArray()
                    || node.path("requests").isEmpty() || node.path("requests").size() > MAX_GUARD_REQUESTS)
                throw new IOException("Invalid bound execution guard");
            try {
                UUID owner = UUID.fromString(node.path("ownerSession").asString());
                var ids = new HashSet<UUID>();
                var requests = new ArrayList<RequestBinding>();
                for (JsonNode entry : node.path("requests")) {
                    exact(entry, Set.of("requestId", "reservationId", "endpoint", "operation", "model", "runner", "digest", "selectionRevision"));
                    var binding = JSON.treeToValue(entry, RequestBinding.class);
                    if (!ids.add(binding.requestId())) throw new IOException("Repeated guard request");
                    requests.add(binding);
                }
                return new ParsedGuard(owner, node.path("stateBinding").asString(), List.copyOf(requests));
            } catch (RuntimeException invalid) { throw new IOException("Invalid bound execution guard"); }
        }
        exact(node, Set.of("version", "unresolved"));
        if (!node.path("version").isIntegralNumber() || !node.path("version").canConvertToLong() || node.path("version").asLong() != 1
                || !node.path("unresolved").isBoolean() || !node.path("unresolved").asBoolean()) throw new IOException("Invalid execution guard");
        return new ParsedGuard(null, null, List.of());
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
        if (windows) { WindowsPrivateAcl.directory(path, windowsOwner); return; }
        if (exists(path)) return;
        createDirectories(path.getParent());
        Files.createDirectory(path, permissions(true));
    }
    private void createPrivate(Path file) throws IOException {
        if (!exists(file)) {
            try {
                if (windows) WindowsPrivateAcl.createFile(file, windowsOwner);
                else Files.createFile(file, permissions(false));
            }
            catch (FileAlreadyExistsException ignored) { /* Verify the existing identity and permissions. */ }
        }
    }
    private Object verify(Path path, boolean directory) throws IOException {
        if (windows) WindowsPrivateAcl.verify(path, directory, windowsOwner);
        noLinks(path);
        var attr = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if ((directory ? !attr.isDirectory() : !attr.isRegularFile()) || (!windows && attr.fileKey() == null))
            throw new IOException("Invalid model state ownership or type");
        if (windows) WindowsPrivateAcl.verifyOwner(path, directory, windowsOwner);
        else if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(account))
            throw new IOException("Invalid model state ownership or type");
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            if (!posix.readAttributes().permissions().equals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")))
                throw new IOException("Model state permissions are not private");
        } else if (!windows) {
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
