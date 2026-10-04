package stirling.software.jpdfium.panama;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
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
 * repository over the same transport. The cache slot is additionally bound
 * to the repository authority: a bundle fetched from one repository is
 * never reused after the configured repository changes, because the
 * embedded hashes are self-attested by whoever served the bundle and prove
 * nothing about a different authority.
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
        Path cached = downloadCachePath(platform, version, repo);
        // Claim the cache directory before the cache hit so a pre-existing
        // file at the slot is only adopted after the directory is ours
        // (owner-only permissions enforced below).
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
        return downloadCachePath(platform, version, repoBase());
    }

    /**
     * Cache slot for a downloaded jar, bound to the repository authority.
     *
     * <p>A bundle cached from one repository must never satisfy a fetch
     * configured for another: the jar's embedded hashes are self-attested by
     * whoever served it, so accepting a stale slot after a repository change
     * would execute the previous authority's native code under the new
     * authority's trust. The slot directory therefore carries a SHA-256 of
     * the normalized repository URL.
     */
    static Path downloadCachePath(String platform, String version, String repo) {
        // Private per-user cache only: falling back to the shared system temp
        // directory would place executable native code where other local users
        // can pre-create or observe it (CodeQL java/local-temp-file-disclosure).
        // When no private root exists, return null and let the caller fail
        // closed to System.loadLibrary instead of using a shared location.
        Path root = NativeCache.resolveCacheRoot();
        if (root == null) return null;
        root = root.resolve("downloads").resolve("repo-" + repoId(repo));
        return root.resolve("jpdfium-natives-" + platform + "-" + version + ".jar");
    }

    /** Filesystem-safe identity of a normalized repository URL. */
    static String repoId(String repo) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(repo.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * True when the file opens as a zip holding this platform's manifest with
     * intact payloads. Structure and names alone are not enough: a cached file
     * can rot while the ZIP stays readable, and reusing it would fail every
     * later JVM run the same way instead of downloading a replacement. Every
     * payload file is therefore hashed against the manifest embedded in the
     * same jar. (Self-attested hashes detect corruption, not a hostile
     * authority - see the class trust model. Jars predating hash manifests
     * keep the structural check.)
     */
    static boolean isUsableJar(Path jar, String platform) {
        if (jar == null || !Files.isRegularFile(jar)) return false;
        String prefix = entryPrefix(platform);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry(prefix + "native-libs.txt") == null) return false;
            var names = new ArrayList<String>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.startsWith(prefix)) continue;
                String shortName = name.substring(prefix.length());
                if (!NativeCache.isSafeName(shortName)) {
                    return false;
                }
                if (!shortName.equals("native-libs.txt") && !shortName.equals("native-libs.sha256")) {
                    names.add(shortName);
                }
            }
            var shaEntry = zip.getEntry(prefix + "native-libs.sha256");
            if (shaEntry == null) return true;
            Map<String, String> hashes;
            try (InputStream in = zip.getInputStream(shaEntry)) {
                hashes = NativeCache.parseChecksums(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            for (String shortName : names) {
                String expected = hashes.get(shortName);
                if (expected == null) return false;
                var payload = zip.getEntry(prefix + shortName);
                if (payload == null) return false;
                sha.reset();
                try (InputStream in = zip.getInputStream(payload)) {
                    int n;
                    while ((n = in.read(buf)) != -1) sha.update(buf, 0, n);
                }
                if (!HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(expected)) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException | NoSuchAlgorithmException _) {
            return false;
        }
    }

    /**
     * Largest accepted natives jar. Natives bundles are tens of megabytes; the
     * cap only stops a misbehaving repository (or error page) from exhausting
     * the cache filesystem. Fail-closed with a clear message when exceeded.
     */
    static final long MAX_DOWNLOAD_BYTES = 1024L * 1024 * 1024;

    /** Fetches a URL to the cache path atomically. Throws on any failure. */
    static Path download(String url, Path destination) {
        if (destination == null) {
            throw new NativeLoadException("No writable location for the natives download.");
        }
        try {
            // The download holds native code, so tighten the directory before
            // anything is written into it and fail closed if that is impossible.
            NativeCache.requirePrivateDirectory(destination.getParent());
        } catch (IOException e) {
            throw new NativeLoadException("Cannot create a private natives download directory.", e);
        }
        Path staging = null;
        try {
            staging = Files.createTempFile(destination.getParent(), ".download-", ".jar");
            // Never follow redirects: repoBase() already allows plain HTTP
            // for loopback test servers, and a redirect from there (or from a
            // compromised mirror) to non-loopback HTTP would deliver native
            // code over cleartext. Legitimate Maven repositories serve
            // artifacts without redirects; a 3xx fails closed below.
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            // Stream the body (do not use ofFile): the status must be checked
            // before a single byte is written, and the byte count must be
            // bounded while streaming so a huge artifact or error body cannot
            // exhaust the cache filesystem. Timeouts alone do not limit bytes.
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new NativeLoadException("Natives download failed with HTTP "
                            + response.statusCode() + ": " + url);
                }
                try (var out = Files.newOutputStream(staging,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    byte[] buf = new byte[8192];
                    long total = 0;
                    int n;
                    while ((n = body.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_DOWNLOAD_BYTES) {
                            throw new NativeLoadException("Refusing oversized natives download (> "
                                    + MAX_DOWNLOAD_BYTES + " bytes): " + url);
                        }
                        out.write(buf, 0, n);
                    }
                }
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
