package stirling.software.jpdfium.panama;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Properties;
import java.util.zip.ZipFile;

import stirling.software.jpdfium.exception.NativeLoadException;

/**
 * Opt-in Maven fallback for missing platform natives.
 *
 * <p>Off by default. When {@code -Djpdfium.native.download=true} is set and
 * the classpath has no natives jar for the current platform, the matching
 * {@code com.stirling:jpdfium-natives-<platform>:<version>} jar is fetched
 * from Maven Central (or {@code -Djpdfium.native.repo=...}), cached under the
 * native cache root for offline reuse, and loaded through the same
 * checksum-verified extraction path as a bundled jar.
 *
 * <p>Trust model: TLS authenticates the repository (plain http is refused
 * except for loopback test servers), and every extracted file is still
 * SHA-256 verified against the jar's own manifest by {@link NativeCache}.
 * This matches build-time dependency resolution, which trusts the same
 * repository over the same transport.
 */
final class NativeDownloader {

    static final String ENABLE_PROPERTY = "jpdfium.native.download";
    static final String VERSION_PROPERTY = "jpdfium.native.version";
    static final String REPO_PROPERTY = "jpdfium.native.repo";
    static final String DEFAULT_REPO = "https://repo1.maven.org/maven2";
    static final String GROUP_PATH = "com/stirling";
    static final String VERSION_RESOURCE = "jpdfium-version.properties";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private NativeDownloader() {}

    /** True only when the user explicitly opts into runtime downloads. */
    static boolean isDownloadEnabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    /** Explicit version override, or null when unset/blank. */
    static String configuredVersion() {
        String version = System.getProperty(VERSION_PROPERTY);
        return version == null || version.isBlank() ? null : version.trim();
    }

    /**
     * Version of the running library: explicit property first, then the
     * build-stamped resource inside this jar. Null when neither exists.
     */
    static String libraryVersion() {
        String explicit = configuredVersion();
        if (explicit != null) return explicit;
        try (InputStream in = NativeDownloader.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in == null) return null;
            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty("version");
            return version == null || version.isBlank() ? null : version.trim();
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    /** Repository base URL. Only https is accepted, except loopback test servers. */
    static String repoBase() {
        String repo = System.getProperty(REPO_PROPERTY, DEFAULT_REPO).trim();
        while (repo.endsWith("/")) repo = repo.substring(0, repo.length() - 1);
        URI uri;
        try {
            uri = URI.create(repo);
        } catch (IllegalArgumentException e) {
            throw new NativeLoadException("Invalid natives repository URL: " + repo, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("[::1]");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
            throw new NativeLoadException(
                    "Refusing non-https natives repository (binary substitution risk): " + repo);
        }
        return repo;
    }

    /** Central-layout URL for a natives jar. Pure function, no I/O. */
    static String jarUrl(String platform, String version, String repo) {
        String artifact = "jpdfium-natives-" + platform;
        return repo + "/" + GROUP_PATH + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + ".jar";
    }

    /** Resource prefix every required entry of a natives jar lives under. */
    static String entryPrefix(String platform) {
        return "natives/" + platform + "/";
    }

    /**
     * Local jar path for the platform, downloading once when absent.
     * Returns null only when downloads are disabled (caller keeps its original
     * error). Enabled-but-impossible states (unknown or snapshot version,
     * bad repository, network or integrity failure) throw.
     */
    static Path fetchNativesJar(String platform) {
        if (!isDownloadEnabled()) return null;
        return fetchNativesJar(platform, libraryVersion());
    }

    /** Same as above with an explicit version; null means unknown. */
    static Path fetchNativesJar(String platform, String version) {
        if (!isDownloadEnabled()) return null;
        if (version == null) {
            throw new NativeLoadException(
                    "Natives download enabled but the jpdfium version is unknown; set -D"
                            + VERSION_PROPERTY + "=<version> to the version of the jpdfium jar in use.");
        }
        if (version.endsWith("-SNAPSHOT")) {
            throw new NativeLoadException("Natives download refused for snapshot version " + version
                    + ": snapshots are not published to Maven Central. Add the natives jar manually.");
        }
        String repo = repoBase();
        Path cached = downloadCachePath(platform, version);
        // Claim the cache directory before the cache hit. When the root falls
        // back to the shared system temp directory, anything already sitting
        // there - including another local user's jar - must not be adopted
        // before we have made the directory ours.
        if (cached != null) {
            try {
                NativeCache.requirePrivateDirectory(cached.getParent());
            } catch (IOException e) {
                throw new NativeLoadException(
                        "Cannot create a private natives download directory.", e);
            }
        }
        if (cached != null && isUsableJar(cached, platform)) return cached;
        Path downloaded = download(jarUrl(platform, version, repo), cached);
        if (!isUsableJar(downloaded, platform)) {
            try {
                Files.deleteIfExists(downloaded);
            } catch (IOException _) {
                // Already reporting the integrity failure below.
            }
            throw new NativeLoadException(
                    "Downloaded natives jar is not a usable " + platform + " bundle.");
        }
        return downloaded;
    }

    /** Cache slot for a downloaded jar; null only when no location exists. */
    static Path downloadCachePath(String platform, String version) {
        Path root = NativeCache.resolveCacheRoot();
        if (root == null) {
            String tmpDir = System.getProperty("java.io.tmpdir");
            if (tmpDir == null || tmpDir.isBlank()) return null;
            root = Path.of(tmpDir).resolve("jpdfium-downloads");
        } else {
            root = root.resolve("downloads");
        }
        return root.resolve("jpdfium-natives-" + platform + "-" + version + ".jar");
    }

    /** True when the file opens as a zip holding this platform's manifest. */
    static boolean isUsableJar(Path jar, String platform) {
        if (jar == null || !Files.isRegularFile(jar)) return false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry(entryPrefix(platform) + "native-libs.txt") == null) return false;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.startsWith(entryPrefix(platform))) continue;
                if (!NativeCache.isSafeName(name.substring(entryPrefix(platform).length()))) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    /** Fetches a URL to the cache path atomically. Throws on any failure. */
    static Path download(String url, Path destination) {
        if (destination == null) {
            throw new NativeLoadException("No writable location for the natives download.");
        }
        try {
            // The cache root can fall back to the system temp directory, which
            // is shared. This holds native code, so tighten the directory before
            // anything is written into it and fail closed if that is impossible.
            NativeCache.requirePrivateDirectory(destination.getParent());
        } catch (IOException e) {
            throw new NativeLoadException("Cannot create a private natives download directory.", e);
        }
        Path staging = null;
        try {
            staging = Files.createTempFile(destination.getParent(), ".download-", ".jar");
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Path> response = client.send(request,
                    HttpResponse.BodyHandlers.ofFile(staging));
            if (response.statusCode() != 200) {
                throw new NativeLoadException(
                        "Natives download failed with HTTP " + response.statusCode() + ": " + url);
            }
            try {
                return Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                return Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new NativeLoadException("Natives download failed: " + url, e);
        } finally {
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException _) {
                    // Download already failed; staging cleanup is best effort.
                }
            }
        }
    }

}
