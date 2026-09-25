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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

    private NativeCache() {}

    /** Opens a classpath resource by file name, or returns null when absent. */
    interface Resources {
        InputStream open(String name) throws IOException;
    }

    /** Extracted dir for System.load, or null when the cache cannot be used. */
    static Path prepare(String platform, List<String> names, Map<String, String> hashes,
                        Resources resources) {
        if (names.isEmpty()) return null;
        for (String name : names) {
            if (!hashes.containsKey(name)) return null;
        }
        try {
            Path platformDir = resolveCacheRoot().resolve(platform);
            Files.createDirectories(platformDir);
            restrictToOwner(platformDir, true);
            boolean verifyFull = "full".equalsIgnoreCase(System.getProperty(VERIFY_PROPERTY));
            return prepareIn(platformDir, names, hashes, cacheKey(hashes), resources, verifyFull);
        } catch (IOException | RuntimeException _) {
            // Cache is an optimization: failures must not stop the native load.
            return null;
        }
    }

    static Path prepareIn(Path platformDir, List<String> names, Map<String, String> hashes,
                          String key, Resources resources, boolean verifyFull) throws IOException {
        Path finalDir = platformDir.resolve(key);
        if (isValidEntry(finalDir, names, hashes, key, verifyFull)) return finalDir;

        Path lockFile = platformDir.resolve(".lock-" + key);
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock _ = channel.lock()) {
            if (isValidEntry(finalDir, names, hashes, key, verifyFull)) return finalDir;
            // A corrupt entry is recoverable only when no process has it mapped.
            deleteRecursively(finalDir);
            Path staging = Files.createTempDirectory(platformDir, STAGING_PREFIX);
            try {
                for (String name : names) {
                    Path target = staging.resolve(name);
                    String actual = copyAndHash(resources, name, target);
                    if (!actual.equalsIgnoreCase(hashes.get(name))) {
                        throw new IOException("checksum mismatch for " + name);
                    }
                    restrictToOwner(target, false);
                }
                Files.writeString(staging.resolve(VERIFIED_MARKER), key);
                publish(staging, finalDir);
            } finally {
                deleteRecursively(staging);
            }
        }
        if (!isValidEntry(finalDir, names, hashes, key, verifyFull)) {
            throw new IOException("cache entry invalid after publish");
        }
        removeObsoleteEntries(platformDir, key, OBSOLETE_MIN_AGE_MILLIS);
        return finalDir;
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

    static Path resolveCacheRoot(String override, String osName, String userHome,
                                 String localAppData, String xdgCacheHome, Path tmpDir) {
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
        return tmpDir.resolve("jpdfium-cache");
    }

    static Path resolveCacheRoot() {
        return resolveCacheRoot(
                System.getProperty(CACHE_DIR_PROPERTY),
                System.getProperty("os.name"),
                System.getProperty("user.home"),
                System.getenv("LOCALAPPDATA"),
                System.getenv("XDG_CACHE_HOME"),
                Path.of(System.getProperty("java.io.tmpdir")));
    }

    /** Removes old content hashes for this platform, best-effort. */
    static void removeObsoleteEntries(Path platformDir, String currentKey, long minAgeMillis) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(platformDir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!Files.isDirectory(entry) || name.equals(currentKey)
                        || name.startsWith(".")) {
                    continue;
                }
                if (isOlderThan(entry, minAgeMillis)) deleteRecursively(entry);
            }
        } catch (IOException _) {
            // Cleanup must never break loading.
        }
    }

    /**
     * Deletes per-JVM extraction dirs left by older versions or fallback runs.
     * Age-gated: a younger dir may belong to a JVM that is still starting.
     */
    static void sweepTempDirs(Path tmpDir, long minAgeMillis, Path exclude) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(tmpDir, "jpdfium-*")) {
            for (Path entry : entries) {
                if (!Files.isDirectory(entry) || entry.equals(exclude)) continue;
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
        MessageDigest digest = sha256();
        try (InputStream in = resources.open(name)) {
            if (in == null) throw new IOException("missing resource " + name);
            try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
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

    private static void restrictToOwner(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(path,
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException | IOException _) {
            // Windows ACLs under LOCALAPPDATA are already user-scoped.
        }
    }
}
