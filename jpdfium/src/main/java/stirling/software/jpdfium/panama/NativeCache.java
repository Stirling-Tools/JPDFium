package stirling.software.jpdfium.panama;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Per-user native cache, one verified extraction per content hash. Windows cannot delete a
 * loaded DLL, so a per-JVM temp dir leaks; a stable cache entry is loaded in place instead.
 */
final class NativeCache {

    static final String CACHE_DIR_PROPERTY = "jpdfium.native.cacheDir";
    static final String CACHE_ENABLED_PROPERTY = "jpdfium.native.cache";
    static final String VERIFY_PROPERTY = "jpdfium.native.verify";
    static final String SWEEP_PROPERTY = "jpdfium.native.sweep";

    // A concurrent JVM may still be writing its own extraction dir.
    static final long SWEEP_MIN_AGE_MILLIS = 60L * 60L * 1000L;
    // Obsolete content hashes are only removed once clearly stale.
    static final long OBSOLETE_MIN_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L;

    private static final int SHA256_HEX_LENGTH = 64;
    private static final int KEY_LENGTH = 32;
    private static final String VERIFIED_MARKER = ".verified";
    private static final String STAGING_PREFIX = ".staging-";
    private static final String READER_PREFIX = ".reader-";
    private static final String FALLBACK_LOCK = ".lock";
    private static final Set<PosixFilePermission> OWNER_DIR_PERMS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_FILE_PERMS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<AclEntryPermission> ACL_WRITE_PERMISSIONS =
            EnumSet.of(
                    AclEntryPermission.WRITE_DATA,
                    AclEntryPermission.APPEND_DATA,
                    AclEntryPermission.WRITE_ATTRIBUTES,
                    AclEntryPermission.WRITE_NAMED_ATTRS,
                    AclEntryPermission.WRITE_ACL,
                    AclEntryPermission.WRITE_OWNER,
                    AclEntryPermission.DELETE,
                    AclEntryPermission.DELETE_CHILD);

    // Locks must outlive the load: PDFium resolves symbols lazily, so the
    // entry must stay on disk for the whole JVM lifetime.
    private static final Map<Path, FileLock> RETAINED = new ConcurrentHashMap<>();

    private NativeCache() {}

    /** A resource does not match its published checksum: never load it. */
    static final class IntegrityException extends IOException {
        private static final long serialVersionUID = 1L;

        IntegrityException(String message) {
            super(message);
        }
    }

    /** Opens a classpath resource by file name, or returns null when absent. */
    interface Resources {
        InputStream open(String name) throws IOException;
    }

    /**
     * Extracted dir for System.load, or null when the cache cannot be used.
     * Integrity failures propagate: a corrupt artifact must not load unverified.
     */
    static Path prepare(String platform, List<String> names, Map<String, String> hashes,
                        Resources resources) throws IOException {
        if (names.isEmpty()) return null;
        for (String name : names) {
            if (!isSafeName(name)) throw new IntegrityException("unsafe native entry: " + name);
            if (!hashes.containsKey(name)) return null;
        }
        Path root = resolveCacheRoot();
        if (root == null) return null;
        try {
            Path platformDir = root.resolve(platform);
            Files.createDirectories(platformDir);
            requireOwnerOnly(platformDir, true);
            return prepareIn(platformDir, names, hashes, cacheKey(hashes), resources, verifyFull());
        } catch (IntegrityException e) {
            throw e;
        } catch (IOException | RuntimeException _) {
            // Cache is an optimization: failures must not stop the native load.
            return null;
        }
    }

    static Path prepareIn(Path platformDir, List<String> names, Map<String, String> hashes,
                          String key, Resources resources, boolean verifyFull) throws IOException {
        for (String name : names) {
            if (!isSafeName(name)) throw new IntegrityException("unsafe native entry: " + name);
        }
        requireOwnerOnly(platformDir, true);
        reclaimStaleStaging(platformDir, SWEEP_MIN_AGE_MILLIS);
        Path finalDir = platformDir.resolve(key);

        // Validate under a shared reader lock so cleanup cannot delete the
        // entry between the check and the caller's System.load.
        FileLock reader = acquireReaderLock(platformDir, key, false);
        if (reader != null && isValidEntry(finalDir, names, hashes, key, verifyFull)) {
            return finalDir;
        }
        releaseReaderLock(platformDir, key, reader);

        Path keyLock = platformDir.resolve(".lock-" + key);
        try (FileChannel channel = FileChannel.open(keyLock,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock _ = channel.lock()) {
            reader = acquireReaderLock(platformDir, key, false);
            if (reader != null && isValidEntry(finalDir, names, hashes, key, verifyFull)) {
                return finalDir;
            }
            releaseReaderLock(platformDir, key, reader);
            if (!deleteEntryIfUnused(platformDir, key, finalDir)) {
                throw new IOException("cache entry is being loaded by another JVM");
            }
            Path staging = Files.createTempDirectory(platformDir, STAGING_PREFIX);
            try {
                requireOwnerOnly(staging, true);
                for (String name : names) {
                    Path target = staging.resolve(name);
                    String actual = copyAndHash(resources, name, target);
                    if (!actual.equalsIgnoreCase(hashes.get(name))) {
                        throw new IntegrityException("checksum mismatch for " + name);
                    }
                    requireOwnerOnly(target, false);
                }
                createOwnerOnlyFile(staging.resolve(VERIFIED_MARKER));
                Files.writeString(staging.resolve(VERIFIED_MARKER), key);
                publish(staging, finalDir);
            } finally {
                deleteRecursively(staging);
            }
        }
        if (!isValidEntry(finalDir, names, hashes, key, verifyFull)) {
            throw new IOException("cache entry invalid after publish");
        }
        acquireReaderLock(platformDir, key, false);
        removeObsoleteEntries(platformDir, key, OBSOLETE_MIN_AGE_MILLIS);
        return finalDir;
    }

    /** Verifies while copying; a mismatch is fatal, never silently loaded. */
    static void copyVerified(InputStream in, Path target, String expected) throws IOException {
        String actual = copyAndHash(in, target);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IntegrityException("checksum mismatch for " + target.getFileName());
        }
    }

    private static void publish(Path staging, Path finalDir) throws IOException {
        try {
            Files.move(staging, finalDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException _) {
            Files.move(staging, finalDir);
        } catch (FileAlreadyExistsException | DirectoryNotEmptyException _) {
            // Another JVM won the publish race; the caller re-validates below.
        }
    }

    static boolean isValidEntry(Path dir, List<String> names, Map<String, String> hashes,
                                String key, boolean verifyFull) {
        if (!Files.isDirectory(dir)) return false;
        try {
            Path marker = dir.resolve(VERIFIED_MARKER);
            if (!Files.isRegularFile(marker) || !key.equals(Files.readString(marker).trim())) {
                return false;
            }
            for (String name : names) {
                Path file = dir.resolve(name);
                if (!Files.isRegularFile(file)) return false;
                if (verifyFull && !sha256Hex(file).equalsIgnoreCase(hashes.get(name))) return false;
            }
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    /** Full hashes by default; {@code -Djpdfium.native.verify=marker} opts out. */
    static boolean verifyFull() {
        return !"marker".equalsIgnoreCase(System.getProperty(VERIFY_PROPERTY));
    }

    /** A manifest name must be one file name, never a path. */
    static boolean isSafeName(String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return false;
        return Path.of(name).getNameCount() == 1;
    }

    static String cacheKey(Map<String, String> hashes) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(hashes).entrySet()) {
            builder.append(entry.getValue()).append("  ").append(entry.getKey()).append('\n');
        }
        return sha256Hex(builder.toString().getBytes(StandardCharsets.UTF_8)).substring(0, KEY_LENGTH);
    }

    static Map<String, String> parseChecksums(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int split = line.indexOf(' ');
            if (split != SHA256_HEX_LENGTH) continue;
            String name = line.substring(split).trim();
            if (name.isEmpty()) continue;
            result.put(name, line.substring(0, SHA256_HEX_LENGTH).toLowerCase());
        }
        return result;
    }

    /** Null when no user-private cache location exists; temp extraction is used instead. */
    static Path resolveCacheRoot(String override, String osName, String userHome,
                                 String localAppData, String xdgCacheHome) {
        if (override != null && !override.isBlank()) return Path.of(override);
        String os = osName == null ? "" : osName.toLowerCase();
        if (os.contains("win")) {
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData, "jpdfium", "native");
            }
            if (userHome != null && !userHome.isBlank()) {
                return Path.of(userHome, "AppData", "Local", "jpdfium", "native");
            }
        } else if (os.contains("mac")) {
            if (userHome != null && !userHome.isBlank()) {
                return Path.of(userHome, "Library", "Caches", "jpdfium", "native");
            }
        } else {
            if (xdgCacheHome != null && !xdgCacheHome.isBlank()) {
                return Path.of(xdgCacheHome, "jpdfium", "native");
            }
            if (userHome != null && !userHome.isBlank()) {
                return Path.of(userHome, ".cache", "jpdfium", "native");
            }
        }
        return null;
    }

    static Path resolveCacheRoot() {
        return resolveCacheRoot(
                System.getProperty(CACHE_DIR_PROPERTY),
                System.getProperty("os.name"),
                System.getProperty("user.home"),
                System.getenv("LOCALAPPDATA"),
                System.getenv("XDG_CACHE_HOME"));
    }

    /** Removes old content hashes, never one another JVM still has open. */
    static void removeObsoleteEntries(Path platformDir, String currentKey, long minAgeMillis) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(platformDir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!Files.isDirectory(entry) || name.equals(currentKey)
                        || name.startsWith(".")) {
                    continue;
                }
                if (!isOlderThan(entry, minAgeMillis)) continue;
                if (deleteEntryIfUnused(platformDir, name, entry)) {
                    Files.deleteIfExists(platformDir.resolve(READER_PREFIX + name));
                }
            }
        } catch (IOException _) {
            // Cleanup must never break loading.
        }
    }

    /** Leftover staging dirs are not published entries but still hold disk. */
    static void reclaimStaleStaging(Path platformDir, long minAgeMillis) {
        try (DirectoryStream<Path> entries =
                Files.newDirectoryStream(platformDir, STAGING_PREFIX + "*")) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry) && isOlderThan(entry, minAgeMillis)) {
                    deleteRecursively(entry);
                }
            }
        } catch (IOException _) {
            // Best-effort; a later start retries.
        }
    }

    /**
     * Creates the per-JVM fallback dir and holds its lock, so a sweep can tell
     * a running JVM's dir from a leaked one.
     */
    static Path createFallbackDir() throws IOException {
        Path dir = Files.createTempDirectory("jpdfium-");
        try {
            Path lockFile = dir.resolve(FALLBACK_LOCK);
            createOwnerOnlyFile(lockFile);
            FileChannel channel =
                    FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
            RETAINED.put(lockFile.toAbsolutePath().normalize(), channel.lock());
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException | RuntimeException e) {
            deleteRecursively(dir);
            throw e;
        }
    }

    /**
     * Deletes per-JVM extraction dirs left by older versions or crashed runs.
     * Age-gated, and locked dirs belong to a running JVM.
     */
    static void sweepTempDirs(Path tmpDir, long minAgeMillis, Path exclude) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(tmpDir, "jpdfium-*")) {
            for (Path entry : entries) {
                if (!Files.isDirectory(entry) || entry.equals(exclude)) continue;
                if (!tryExclusive(entry.resolve(FALLBACK_LOCK))) continue;
                if (isOlderThan(entry, minAgeMillis)) deleteRecursively(entry);
            }
        } catch (IOException | RuntimeException _) {
            // Best-effort; a later JVM retries.
        }
    }

    /** Best-effort recursive delete: locked files are retried by a later sweep. */
    static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException _) {
                    // Windows keeps loaded DLLs locked; a later sweep retries.
                }
            });
        } catch (IOException _) {
        }
    }

    static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String copyAndHash(Resources resources, String name, Path target)
            throws IOException {
        try (InputStream in = resources.open(name)) {
            if (in == null) throw new IOException("missing resource " + name);
            return copyAndHash(in, target);
        }
    }

    private static String copyAndHash(InputStream in, Path target) throws IOException {
        MessageDigest digest = sha256();
        try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Shared for readers, exclusive for cleanup, keyed outside the entry so a
     * held lock never blocks deleting the entry dir on Windows.
     */
    private static FileLock acquireReaderLock(Path platformDir, String key, boolean exclusive)
            throws IOException {
        Path lockFile = platformDir.resolve(READER_PREFIX + key);
        Path mapKey = lockFile.toAbsolutePath().normalize();
        FileLock existing = RETAINED.get(mapKey);
        if (!exclusive && existing != null && existing.isValid()) return existing;
        createOwnerOnlyFile(lockFile);
        FileChannel channel =
                FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock = exclusive ? channel.tryLock() : channel.lock(0L, Long.MAX_VALUE, true);
            if (lock == null) {
                channel.close();
                return null;
            }
            RETAINED.put(mapKey, lock);
            return lock;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static void releaseReaderLock(Path platformDir, String key, FileLock lock) {
        if (lock == null) return;
        Path mapKey = platformDir.resolve(READER_PREFIX + key).toAbsolutePath().normalize();
        RETAINED.remove(mapKey, lock);
        try {
            lock.channel().close();
        } catch (IOException _) {
        }
    }

    private static boolean deleteEntryIfUnused(Path platformDir, String key, Path dir) {
        if (!Files.isDirectory(dir)) return true;
        FileLock exclusive;
        try {
            exclusive = acquireReaderLock(platformDir, key, true);
        } catch (IOException | RuntimeException _) {
            return false;
        }
        if (exclusive == null) return false;
        try {
            deleteRecursively(dir);
            return true;
        } finally {
            releaseReaderLock(platformDir, key, exclusive);
        }
    }

    /** True when the lock is free; a held lock means a live reader. */
    private static boolean tryExclusive(Path lockFile) {
        if (!Files.exists(lockFile)) return true;
        try (FileChannel channel =
                     FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            return lock != null;
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    private static void createOwnerOnlyFile(Path file) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_FILE_PERMS));
        } catch (FileAlreadyExistsException _) {
            // Another JVM created it first.
        } catch (UnsupportedOperationException _) {
            try {
                Files.createFile(file);
            } catch (FileAlreadyExistsException _) {
                // Another JVM created it first.
            }
        }
    }

    /** Fail-closed: a cache path readable or writable by others is not used. */
    private static void requireOwnerOnly(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path, directory ? OWNER_DIR_PERMS : OWNER_FILE_PERMS);
        } catch (UnsupportedOperationException _) {
            // Non-POSIX: isPrivate checks the ACL instead.
        }
        if (!isPrivate(path)) throw new IOException("cache path is not private: " + path);
    }

    static boolean isPrivate(Path path) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            return perms.stream()
                    .noneMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"));
        } catch (UnsupportedOperationException _) {
            AclFileAttributeView acl =
                    Files.getFileAttributeView(path, AclFileAttributeView.class);
            return acl != null && isPrivateAcl(acl);
        } catch (IOException _) {
            // Unreadable permissions are not provably private.
            return false;
        }
    }

    /** Fail closed: write or delete for any principal other than the owner or system. */
    static boolean isPrivateAcl(AclFileAttributeView view) {
        return isPrivateAcl(
                view,
                lookupPrincipal("NT AUTHORITY\\SYSTEM", false),
                lookupPrincipal("BUILTIN\\Administrators", true));
    }

    static boolean isPrivateAcl(AclFileAttributeView view, UserPrincipal system,
                                UserPrincipal administrators) {
        try {
            UserPrincipal owner = view.getOwner();
            for (AclEntry entry : view.getAcl()) {
                if (entry.type() != AclEntryType.ALLOW) continue;
                if (Collections.disjoint(entry.permissions(), ACL_WRITE_PERMISSIONS)) continue;
                if (!isTrustedPrincipal(entry.principal(), owner, system, administrators)) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    private static UserPrincipal lookupPrincipal(String name, boolean group) {
        try {
            UserPrincipalLookupService lookup =
                    FileSystems.getDefault().getUserPrincipalLookupService();
            return group
                    ? lookup.lookupPrincipalByGroupName(name)
                    : lookup.lookupPrincipalByName(name);
        } catch (IOException | RuntimeException _) {
            // Unresolvable well-known principal: fail closed.
            return null;
        }
    }

    private static boolean isTrustedPrincipal(UserPrincipal principal, UserPrincipal owner,
                                              UserPrincipal system, UserPrincipal administrators) {
        if (principal.equals(owner)) return true;
        if (system != null && principal.equals(system)) return true;
        if (administrators != null && principal.equals(administrators)) return true;
        // S-1-3-0 and S-1-3-4 have no account name to resolve; compare exactly.
        String name = principal.getName().toUpperCase(Locale.ROOT);
        int slash = name.lastIndexOf('\\');
        String leaf = slash >= 0 ? name.substring(slash + 1) : name;
        return leaf.equals("CREATOR OWNER") || leaf.equals("OWNER RIGHTS");
    }

    private static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean isOlderThan(Path path, long ageMillis) {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return System.currentTimeMillis() - time.toMillis() > ageMillis;
        } catch (IOException _) {
            return false;
        }
    }
}
