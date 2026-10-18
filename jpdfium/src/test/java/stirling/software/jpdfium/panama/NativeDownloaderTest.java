package stirling.software.jpdfium.panama;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.exception.NativeLoadException;

import com.sun.net.httpserver.HttpHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the opt-in Maven natives download without touching the real network. */
class NativeDownloaderTest {

    private static final String PLATFORM = "test-xyz";
    private static final String VERSION = "9.9.9";

    @TempDir
    private Path tempDir;

    private HttpServer server;

    private String savedEnable;
    private String savedVersion;
    private String savedRepo;
    private String savedCacheDir;

    @AfterEach
    void clearPropertiesAndServer() throws IOException {
        restoreProperty(NativeDownloader.ENABLE_PROPERTY, savedEnable);
        restoreProperty(NativeDownloader.VERSION_PROPERTY, savedVersion);
        restoreProperty(NativeDownloader.REPO_PROPERTY, savedRepo);
        restoreProperty(NativeCache.CACHE_DIR_PROPERTY, savedCacheDir);
        savedEnable = savedVersion = savedRepo = savedCacheDir = null;
        // downloadedJarSupplementsClasspathLookup installs a process-global
        // loader; without a reset later tests would resolve resources through
        // the deleted temp jar (and on Windows the open jar would pin cleanup).
        NativeLoader.resetSupplementalLoaderForTests();
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static void restoreProperty(String key, String saved) {
        if (saved == null) System.clearProperty(key);
        else System.setProperty(key, saved);
    }

    @BeforeEach
    void snapshotProperties() {
        savedEnable = System.getProperty(NativeDownloader.ENABLE_PROPERTY);
        savedVersion = System.getProperty(NativeDownloader.VERSION_PROPERTY);
        savedRepo = System.getProperty(NativeDownloader.REPO_PROPERTY);
        savedCacheDir = System.getProperty(NativeCache.CACHE_DIR_PROPERTY);
    }

    @Test
    void downloadsAreDisabledByDefault() {
        // Explicit absent state: a caller-provided -D flag must neither fail
        // this assertion nor be deleted for other tests (see teardown).
        System.clearProperty(NativeDownloader.ENABLE_PROPERTY);
        assertFalse(NativeDownloader.isDownloadEnabled());
        assertNull(NativeDownloader.fetchNativesJar(PLATFORM),
                "disabled downloads must not touch the network");
    }

    @Test
    void explicitVersionBeatsTheStampedOne() {
        System.setProperty(NativeDownloader.VERSION_PROPERTY, VERSION);
        assertEquals(VERSION, NativeDownloader.libraryVersion());
    }

    @Test
    void stampedVersionIsWiredIntoTestResources() {
        assertNotNull(NativeDownloader.libraryVersion(),
                "processResources must stamp jpdfium-version.properties");
    }

    @Test
    void jarUrlUsesCentralLayout() {
        assertEquals(
                "https://repo1.maven.org/maven2/com/stirling/jpdfium-natives-windows-x64"
                        + "/1.1.5/jpdfium-natives-windows-x64-1.1.5.jar",
                NativeDownloader.jarUrl("windows-x64", "1.1.5", "https://repo1.maven.org/maven2"));
    }

    @Test
    void repoBaseTrimsSlashesAndRejectsPlainHttp() {
        System.setProperty(NativeDownloader.REPO_PROPERTY, "https://repo1.maven.org/maven2///");
        assertEquals("https://repo1.maven.org/maven2", NativeDownloader.repoBase());
        System.setProperty(NativeDownloader.REPO_PROPERTY, "http://example.com/maven2");
        assertThrows(NativeLoadException.class, NativeDownloader::repoBase);
    }

    @Test
    void unknownVersionExplainsItself() {
        System.setProperty(NativeDownloader.ENABLE_PROPERTY, "true");
        NativeLoadException e = assertThrows(NativeLoadException.class,
                () -> NativeDownloader.fetchNativesJar(PLATFORM, null));
        assertTrue(e.getMessage().contains(NativeDownloader.VERSION_PROPERTY), e.getMessage());
    }

    @Test
    void snapshotVersionsAreRefusedBeforeAnyDownload() {
        System.setProperty(NativeDownloader.ENABLE_PROPERTY, "true");
        System.setProperty(NativeDownloader.VERSION_PROPERTY, "1.0.0-SNAPSHOT");
        NativeLoadException e = assertThrows(NativeLoadException.class,
                () -> NativeDownloader.fetchNativesJar(PLATFORM));
        assertTrue(e.getMessage().contains("SNAPSHOT"), e.getMessage());
    }

    @Test
    void downloadsOnceThenReusesTheCache() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        startServer(exchange -> {
            hits.incrementAndGet();
            byte[] jar = nativesJar(PLATFORM, false);
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, jar.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(jar);
            }
        });
        enableDownload();
        Path first = NativeDownloader.fetchNativesJar(PLATFORM);
        assertTrue(NativeDownloader.isUsableJar(first, PLATFORM));
        Path second = NativeDownloader.fetchNativesJar(PLATFORM);
        assertEquals(first, second);
        assertEquals(1, hits.get(), "second fetch must come from the cache");
        assertTrue(readManifest(first).contains("libtest.so"));
    }

    @Test
    void changingRepositoryDoesNotReuseThePreviousBundle() throws Exception {
        AtomicInteger firstHits = new AtomicInteger();
        startServer(exchange -> {
            firstHits.incrementAndGet();
            byte[] jar = nativesJar(PLATFORM, false);
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, jar.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(jar);
            }
        });
        enableDownload();
        Path first = NativeDownloader.fetchNativesJar(PLATFORM);
        assertTrue(NativeDownloader.isUsableJar(first, PLATFORM));

        // Same cache root, different repository authority: the previous
        // bundle's embedded hashes are self-attested by the first server, so
        // the slot must not satisfy the fetch from the second one.
        server.stop(0);
        AtomicInteger secondHits = new AtomicInteger();
        startServer(exchange -> {
            secondHits.incrementAndGet();
            byte[] jar = nativesJar(PLATFORM, false);
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, jar.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(jar);
            }
        });
        System.setProperty(NativeDownloader.REPO_PROPERTY,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/maven2");
        Path second = NativeDownloader.fetchNativesJar(PLATFORM);
        assertTrue(NativeDownloader.isUsableJar(second, PLATFORM));
        assertEquals(1, secondHits.get(), "a new repository authority must be contacted");
        assertNotEquals(first, second, "different authorities must use different cache slots");
    }

    @Test
    void corruptCacheEntryFailsValidation() throws Exception {
        byte[] jar = nativesJar(PLATFORM, false);
        Path intact = tempDir.resolve("intact.jar");
        Files.write(intact, jar);
        assertTrue(NativeDownloader.isUsableJar(intact, PLATFORM));
        // Rot one byte: the ZIP stays readable but the payload hash breaks, so
        // the damaged cache entry must be rejected instead of reused forever.
        jar[jar.length - 1] = (byte) (jar[jar.length - 1] ^ 0xFF);
        Path damaged = tempDir.resolve("damaged.jar");
        Files.write(damaged, jar);
        assertFalse(NativeDownloader.isUsableJar(damaged, PLATFORM),
                "a corrupt cached bundle must fail validation");
    }

    @Test
    void httpErrorsSurfaceTheStatus() {
        startServer(exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        enableDownload("0.0.0-missing");
        NativeLoadException e = assertThrows(NativeLoadException.class,
                () -> NativeDownloader.fetchNativesJar(PLATFORM));
        assertTrue(e.getMessage().contains("404"), e.getMessage());
    }

    @Test
    void zipSlipEntriesFailValidation() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        startServer(exchange -> {
            hits.incrementAndGet();
            byte[] jar = nativesJar(PLATFORM, true);
            exchange.sendResponseHeaders(200, jar.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(jar);
            }
        });
        enableDownload();
        NativeLoadException e = assertThrows(NativeLoadException.class,
                () -> NativeDownloader.fetchNativesJar(PLATFORM));
        assertTrue(e.getMessage().contains("usable"), e.getMessage());
    }

    @Test
    void downloadedJarSupplementsClasspathLookup() throws Exception {
        startServer(exchange -> {
            byte[] jar = nativesJar(PLATFORM, false);
            exchange.sendResponseHeaders(200, jar.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(jar);
            }
        });
        enableDownload();
        Path jar = NativeDownloader.fetchNativesJar(PLATFORM);
        NativeLoader.installSupplementalLoader(jar);
        String resource = "/natives/" + PLATFORM + "/native-libs.txt";
        assertNotNull(NativeLoader.findResource(resource));
        try (InputStream in = NativeLoader.openResource(resource)) {
            assertNotNull(in);
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("libtest.so"));
        }
    }

    private void enableDownload() {
        enableDownload(VERSION);
    }

    private void enableDownload(String version) {
        System.setProperty(NativeDownloader.ENABLE_PROPERTY, "true");
        System.setProperty(NativeDownloader.VERSION_PROPERTY, version);
        System.setProperty(NativeDownloader.REPO_PROPERTY,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/maven2");
        System.setProperty(NativeCache.CACHE_DIR_PROPERTY, tempDir.toString());
    }

    private void startServer(HttpHandler handler) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start loopback test server", e);
        }
    }

    private static byte[] nativesJar(String platform, boolean zipSlip) throws IOException {
        byte[] payload = {0x7F, 0x45, 0x4C, 0x46};
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            addEntry(zip, "natives/" + platform + "/native-libs.txt", "libtest.so\n");
            // Real digest: isUsableJar hashes every payload against this manifest,
            // so a placeholder would fail cache validation like a corrupt bundle.
            addEntry(zip, "natives/" + platform + "/native-libs.sha256",
                    sha256Hex(payload) + "  libtest.so\n");
            addEntry(zip, "natives/" + platform + "/libtest.so", payload);
            if (zipSlip) {
                addEntry(zip, "natives/" + platform + "/../../evil.txt", "evil");
            }
        }
        return bos.toByteArray();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            var hex = new StringBuilder();
            for (byte b : sha.digest(bytes)) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void addEntry(ZipOutputStream zip, String name, String text) throws IOException {
        addEntry(zip, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void addEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String readManifest(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("natives/" + PLATFORM + "/native-libs.txt");
            assertNotNull(entry);
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
