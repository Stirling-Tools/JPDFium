package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.NativeLoadException;
import stirling.software.jpdfium.exception.NativeNotFoundException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.foreign.SymbolLookup;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class NativeLoader {

    private static volatile boolean loaded = false;

    /**
     * Classpath for a Maven-downloaded natives jar. Consulted only when the
     * application classpath lacks the resource, so bundled jars always win.
     * Retained for the JVM lifetime: one jar per platform at most.
     */
    private static volatile ClassLoader supplementalLoader;

    /** Re-entry guard for the ABI handshake; see verifyBridgeAbi(). */
    private static boolean verifyingBridgeAbi;
    private static volatile Throwable loadError = null;
    private static volatile Boolean muslLibc = null;

    private NativeLoader() {}

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        if (loadError != null) {
            throw new NativeLoadException("Native library failed to load previously", loadError);
        }
        try {
            tryLoadFromClasspath();
            loaded = true;
        } catch (NativeNotFoundException classpathMiss) {
            if (tryDownloadedLoad(classpathMiss)) {
                loaded = true;
                return;
            }
            try {
                System.loadLibrary("jpdfium");
                verifyBridgeAbi();
                loaded = true;
            } catch (UnsatisfiedLinkError e) {
                loadError = classpathMiss;
                // Carry both earlier failures: classpathMiss holds the suppressed
                // version/repository/jar-validation error from the opt-in
                // download, which is exactly what a caller needs to fix it.
                NativeNotFoundException failure = new NativeNotFoundException(
                        detectPlatform() + ". Also tried System.loadLibrary(\"jpdfium\") and failed.");
                failure.addSuppressed(classpathMiss);
                failure.addSuppressed(e);
                // Cache the complete failure, not classpathMiss: a later
                // ensureLoaded() reports loadError, and that must carry the
                // system-load error too.
                loadError = failure;
                throw failure;
            }
        } catch (Throwable t) {
            loadError = t;
            throw (t instanceof NativeLoadException nle) ? nle
                    : new NativeLoadException("Failed to load native library", t);
        }
    }

    /**
     * Handshakes the freshly loaded bridge: version, pointer width, unsigned
     * long width, struct geometry, file-writer version, and feature identity
     * must match this Java artifact. A bridge predating the probe surface or
     * any probe is rejected loudly, silent pass would let an ancient native
     * process documents with unchecked layout assumptions. Mismatched bridges
     * fail the load.
     */
    private static void verifyBridgeAbi() {
        if (SymbolLookup.loaderLookup()
                .find("jpdfium_abi_version")
                .isEmpty()) {
            throw new NativeLoadException(
                    "Native bridge predates ABI handshake (missing jpdfium_abi_version); "
                            + "rebuild natives for " + detectPlatform());
        }
        // checkAbiCompatible() initializes JpdfiumLib, whose static initializer
        // calls back into ensureLoaded(). Without this guard that re-enters
        // tryLoadFromClasspath() and repeats extraction and System.load. `loaded`
        // stays false until the handshake succeeds, so an ABI mismatch still
        // leaves the loader correctly marked as not loaded.
        if (verifyingBridgeAbi) {
            return;
        }
        verifyingBridgeAbi = true;
        try {
            JpdfiumLib.checkAbiCompatible();
        } finally {
            verifyingBridgeAbi = false;
        }
    }

    /**
     * Fetches the platform natives jar when opted in, then loads from it.
     * Returns false when downloads are disabled or the attempt fails, so the
     * caller falls through to its original error with details attached.
     */
    private static boolean tryDownloadedLoad(NativeNotFoundException classpathMiss) {
        Path jar;
        try {
            jar = NativeDownloader.fetchNativesJar(detectPlatform());
        } catch (NativeLoadException e) {
            classpathMiss.addSuppressed(e);
            return false;
        }
        if (jar == null) return false;
        try {
            installSupplementalLoader(jar);
            tryLoadFromClasspath();
            return true;
        } catch (IOException | NativeNotFoundException | NativeLoadException
                | UnsatisfiedLinkError e) {
            // Every failure mode of the downloaded bundle is recoverable here:
            // a missing entry, a bad checksum, an incomplete manifest, or a
            // missing transitive dependency. They must fall through to
            // System.loadLibrary instead of escaping, because this runs inside
            // ensureLoaded's catch block - an escape would skip the system-load
            // fallback and never be cached as loadError.
            classpathMiss.addSuppressed(e);
            return false;
        }
    }

    /** Points resource lookup at a downloaded natives jar. */
    static void installSupplementalLoader(Path jar) throws IOException {
        try {
            // Null parent: URLClassLoader would otherwise delegate to the app
            // loader first and could return classpath manifests/binaries
            // alongside the downloaded bridge, mixing two bundles in one load
            // attempt (checksum mismatch or cross-bundle dependencies).
            // The downloaded jar holds only natives resources, so isolation
            // is both safe and required for single-origin loading.
            supplementalLoader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
        } catch (MalformedURLException e) {
            throw new NativeLoadException("Downloaded natives jar path is not a URL.", e);
        }
    }

    /**
     * Test-only reset for the downloaded-jar loader: closes the jar (releasing
     * the file lock that would otherwise pin temp directories on Windows) and
     * clears the field so later tests resolve resources from the classpath
     * again. Production never resets: one jar per platform per JVM lifetime.
     */
    static void resetSupplementalLoaderForTests() throws IOException {
        ClassLoader installed = supplementalLoader;
        supplementalLoader = null;
        if (installed instanceof URLClassLoader urlLoader) {
            urlLoader.close();
        }
    }

    /** Looks up a resource in the downloaded jar first, then the classpath. */
    static URL findResource(String absolutePath) {
        return findResource(absolutePath, false);
    }

    /**
     * Single-origin lookup for one load attempt: when {@code requireSupplemental}
     * is true, only the downloaded jar is consulted and classpath resources are
     * never mixed in. The whole {@code tryLoadFromClasspath} attempt passes the
     * same flag so manifests, checksums, and binaries come from one bundle.
     */
    static URL findResource(String absolutePath, boolean requireSupplemental) {
        String stripped = absolutePath.startsWith("/") ? absolutePath.substring(1) : absolutePath;
        ClassLoader extra = supplementalLoader;
        if (extra != null) {
            URL url = extra.getResource(stripped);
            if (url != null) return url;
            if (requireSupplemental) return null;
        } else if (requireSupplemental) {
            return null;
        }
        return NativeLoader.class.getResource(absolutePath);
    }

    /** Opens a resource with the same lookup order as {@link #findResource}. */
    static InputStream openResource(String absolutePath) throws IOException {
        return openResource(absolutePath, false);
    }

    static InputStream openResource(String absolutePath, boolean requireSupplemental)
            throws IOException {
        URL url = findResource(absolutePath, requireSupplemental);
        return url == null ? null : url.openStream();
    }

    private static void tryLoadFromClasspath() {
        String platform    = detectPlatform();
        String resourceBase = "/natives/" + platform + "/";
        String bridgeName  = nativeFilename("jpdfium");
        String pdfiumName  = nativeFilename("pdfium");
        String indexResource = resourceBase + "native-libs.txt";

        if (findResource(resourceBase + bridgeName) == null)
            throw new NativeNotFoundException(platform);
        // Single-origin attempt: if the bridge resolved from the downloaded
        // jar, every other resource (manifests, checksums, binaries) must come
        // from that same jar. Mixing classpath manifests with a downloaded
        // bridge would reject valid downloads or load cross-bundle deps.
        boolean requireSupplemental = supplementalLoader != null
                && supplementalLoader.getResource(
                        (resourceBase + bridgeName).substring(1)) != null;

        try {
            List<String> libs = readLibraryIndex(indexResource, requireSupplemental);
            for (String lib : libs) {
                if (!NativeCache.isSafeName(lib)) {
                    throw new NativeLoadException("unsafe native entry: " + lib);
                }
            }
            Map<String, String> checksums =
                    readChecksumIndex(resourceBase + "native-libs.sha256", requireSupplemental);
            if (!checksums.isEmpty()) {
                for (String lib : libs) {
                    if (!checksums.containsKey(lib)) {
                        throw new NativeLoadException(
                                "incomplete native checksum manifest for " + platform);
                    }
                }
            } else if (!libs.isEmpty()) {
                // Fail closed: a manifest listing libraries without hashes would
                // otherwise be extracted and loaded without verification.
                // (A missing manifest detects corruption or cache substitution
                // relative to the published artifact; it cannot attest a
                // wholly substituted artifact whose hashes were replaced too.)
                failClosedOnMissingChecksums(platform);
            }
            if (!"false".equalsIgnoreCase(System.getProperty(NativeCache.SWEEP_PROPERTY))) {
                NativeCache.sweepTempDirs(Path.of(System.getProperty("java.io.tmpdir")),
                        NativeCache.SWEEP_MIN_AGE_MILLIS, NativeCache.resolveCacheRoot());
            }

            // The verified per-user cache avoids the Windows per-JVM temp leak.
            Path tmpDir = null;
            final boolean singleOrigin = requireSupplemental;
            if (!"false".equalsIgnoreCase(System.getProperty(NativeCache.CACHE_ENABLED_PROPERTY))
                    && !libs.isEmpty() && !checksums.isEmpty()) {
                tmpDir = NativeCache.prepare(platform, libs, checksums,
                        name -> openResource(resourceBase + name, singleOrigin));
            }
            if (tmpDir == null) {
                tmpDir = NativeCache.createFallbackDir();

                // Extract all libraries from the manifest to tmpDir so the dynamic
                // linker can resolve NEEDED dependencies via RUNPATH=$ORIGIN
                for (String lib : libs) {
                    extractToDir(resourceBase + lib, tmpDir, checksums.get(lib), singleOrigin);
                }

                // If no manifest was found, fall back to extracting just libpdfium
                if (libs.isEmpty()) {
                    failClosedOnMissingChecksums(platform);
                    extractToDir(resourceBase + pdfiumName, tmpDir, null, singleOrigin);
                }
            }

            // On Linux/macOS, RUNPATH=$ORIGIN in pdfium.so/.dylib makes the
            // dynamic linker find its sibling component libs in the same dir.
            // Windows has no equivalent - LoadLibrary doesn't search the
            // loaded DLL's own directory. So pre-load every dependency by
            // absolute path here. Once a DLL is loaded by name, subsequent
            // references by name (from pdfium.dll's import table) resolve
            // against the already-loaded module instead of re-searching disk.
            //
            // Multi-pass: deps have their own inter-dependencies and we don't
            // know the topological order at runtime. Keep retrying failed
            // loads until either all succeed or a pass makes no progress.
            // Pre-load bundled dependencies in tmpDir before loading pdfium and bridge.
            // On Windows this ensures LoadLibrary finds sibling DLLs.
            // On Linux/macOS, RUNPATH=$ORIGIN / @loader_path resolves sibling dependencies
            // hermetically; preloading via System.load causes host symbol collisions (e.g. ICU).
            boolean isWindows =
                    System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows && !libs.isEmpty()) {
                preloadDependencies(tmpDir, libs, pdfiumName, bridgeName);
            }

            // Order matters: pdfium first so the bridge resolves against it.
            Path pdfiumPath = tmpDir.resolve(pdfiumName);
            if (Files.exists(pdfiumPath)) {
                System.load(pdfiumPath.toAbsolutePath().toString());
            }

            Path bridge = tmpDir.resolve(bridgeName);
            if (!Files.exists(bridge)) {
                bridge = extractLib(resourceBase + bridgeName, tmpDir, bridgeName, singleOrigin);
            }
            System.load(bridge.toAbsolutePath().toString());
            verifyBridgeAbi();
        } catch (IOException e) {
            throw new NativeLoadException("Failed to extract native library", e);
        }
    }

    private static List<String> readLibraryIndex(String resource, boolean requireSupplemental)
            throws IOException {
        List<String> result = new ArrayList<>();
        try (InputStream is = openResource(resource, requireSupplemental)) {
            if (is == null) return result;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && trimmed.charAt(0) != '#') {
                        result.add(trimmed);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Fails closed when a natives jar ships libraries without an integrity manifest,
     * unless the {@code jpdfium.natives.allowUnsigned} system property opts out
     * (local development against hand-built jars only - never in production).
     */
    // Package-private so the fail-closed contract is directly testable.
    static void failClosedOnMissingChecksums(String platform) {
        if (Boolean.getBoolean("jpdfium.natives.allowUnsigned")) return;
        throw new NativeLoadException(
                "natives jar for "
                        + platform
                        + " ships no native-libs.sha256 integrity manifest; refusing to load "
                        + "unverified native libraries. Use a current natives artifact or opt out "
                        + "explicitly with -Djpdfium.natives.allowUnsigned=true");
    }

    private static Map<String, String> readChecksumIndex(String resource, boolean requireSupplemental)
            throws IOException {
        try (InputStream is = openResource(resource, requireSupplemental)) {
            if (is == null) return Map.of();  // older natives jars ship no checksums
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> parsed = NativeCache.parseChecksums(text);
            if (parsed.isEmpty() && !text.isBlank()) {
                throw new NativeLoadException("unreadable native checksum manifest");
            }
            return parsed;
        }
    }

    private static void extractToDir(
            String resource, Path dir, String expectedHash, boolean requireSupplemental)
            throws IOException {
        try (InputStream is = openResource(resource, requireSupplemental)) {
            if (is == null) return;
            Path target = dir.resolve(resource.substring(resource.lastIndexOf('/') + 1));
            if (expectedHash == null) {
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                NativeCache.copyVerified(is, target, expectedHash);
            }
            target.toFile().deleteOnExit();
        }
    }

    private static void preloadDependencies(
            Path tmpDir, List<String> libs, String pdfiumName, String bridgeName) {
        List<String> remaining = new ArrayList<>();
        for (String lib : libs) {
            if (lib.equals(pdfiumName) || lib.equals(bridgeName)) continue;
            if (isJvmHazardLib(lib)) continue;
            Path p = tmpDir.resolve(lib);
            if (Files.exists(p)) {
                remaining.add(lib);
            }
        }
        // Windows looks for deps in the app dir, System32 and PATH, not in
        // the extract dir. Sort leaves first so bundled copies are already
        // loaded when consumers need them. Wrong order crashed windows-arm64
        // with 0xC0000139 when harfbuzz-ng picked up the OS icuuc.dll.
        // Tiers: 0 = CRT + leaves, 1 = needs tier 0, 2 = freetype/qpdf,
        // 3 = harfbuzz, 4 = harfbuzz-subset.
        remaining.sort((a, b) -> {
            int ta = windowsLoadTier(a);
            int tb = windowsLoadTier(b);
            if (ta != tb) return Integer.compare(ta, tb);
            return a.compareToIgnoreCase(b);
        });

        int maxPasses = 8;
        while (maxPasses > 0 && !remaining.isEmpty()) {
            maxPasses--;
            List<String> failed = new ArrayList<>();
            for (String lib : remaining) {
                try {
                    System.load(tmpDir.resolve(lib).toAbsolutePath().toString());
                } catch (UnsatisfiedLinkError e) {
                    failed.add(lib);
                }
            }
            if (failed.size() == remaining.size()) {
                // No progress this pass - remaining libs likely depend on
                // something not in the manifest (e.g. a system DLL we can't
                // help with). Let pdfium/bridge load surface the real error if any.
                break;
            }
            remaining = failed;
        }
    }

    private static boolean isJvmHazardLib(String lib) {
        String l = lib.toLowerCase();
        return l.contains("allocator_shim")
                || l.contains("raw_ptr")
                || l.startsWith("api-ms-win-") || l.startsWith("ext-ms-");
    }

    /**
     * Load tier for Windows preload order, lower first.
     * From dumpbin of the windows bundles. Unknown names go to tier 2.
     */
    static int windowsLoadTier(String lib) {
        String l = lib.toLowerCase();
        // Tier 0: CRT + leaves with no bundled deps.
        if (l.contains("libc++")
                || l.startsWith("vcruntime")
                || l.startsWith("msvcp")
                || l.startsWith("concrt")
                || l.startsWith("vcomp")
                || l.startsWith("icudt")
                || l.equals("z.dll")
                || l.equals("third_party_zlib.dll")
                || l.equals("brotlicommon.dll")
                || l.equals("bz2.dll")
                || l.startsWith("jpeg")
                || l.startsWith("pcre2-")
                || l.equals("pugixml.dll")) {
            return 0;
        }
        // Tier 1: needs only tier 0.
        if (l.equals("brotlidec.dll")
                || l.equals("libpng16.dll")
                || l.startsWith("icuuc")
                || l.equals("third_party_libpng.dll")
                || (l.contains("allocator_base") && !l.contains("shim"))
                || l.contains("abseil")) {
            return 1;
        }
        // Tier 2: freetype, allocator_core, qpdf.
        if (l.equals("freetype.dll")
                || l.contains("allocator_core")
                || l.startsWith("qpdf")) {
            return 2;
        }
        // Tier 3: harfbuzz needs freetype, harfbuzz-ng needs icuuc.
        // Keep after tier 1/2 or the OS copy gets picked up.
        if (l.contains("harfbuzz") && !l.contains("subset")) {
            return 3;
        }
        // Tier 4: subset needs harfbuzz.
        if (l.contains("harfbuzz-subset") || l.contains("harfbuzz_subset")) {
            return 4;
        }
        // Unknown later PDFium split: load after leaves, before harfbuzz.
        return 2;
    }

    private static Path extractLib(
            String resource, Path dir, String filename, boolean requireSupplemental)
            throws IOException {
        try (InputStream is = openResource(resource, requireSupplemental)) {
            if (is == null) throw new NativeNotFoundException(detectPlatform());
            Path target = dir.resolve(filename);
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            return target;
        }
    }

    public static String detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows-" + Architecture.detect().key();
        if (os.contains("mac")) return "darwin-" + Architecture.detect().key();
        // Linux natives are libc-specific: musl (Alpine) cannot load glibc
        // binaries, so a musl host must resolve the linux-musl-<arch> artifacts.
        String libc = isMuslLibc() ? "musl-" : "";
        return "linux-" + libc + Architecture.detect().key();
    }

    static boolean isMuslLibc() {
        Boolean cached = muslLibc;
        if (cached != null) return cached;
        boolean result = detectMuslLibc();
        muslLibc = result;
        return result;
    }

    private static boolean detectMuslLibc() {
        // Primary signal: musl ships its loader as /lib/ld-musl-<arch>.so.1
        // (Alpine). Some distributions place it under /usr/lib instead.
        String[] libDirs = {"/lib", "/usr/lib"};
        for (String libDir : libDirs) {
            try (var dir = Files.newDirectoryStream(Path.of(libDir), "ld-musl-*")) {
                if (dir.iterator().hasNext()) return true;
            } catch (IOException | RuntimeException _) {
                // Directory missing or unreadable; try the next one
            }
        }
        // Fallback: inspect /proc/self/maps. A musl-linked JVM (Alpine) maps the
        // musl loader (ld-musl-<arch>.so.1) into its address space; glibc systems
        // never do. File read only - this layer must not spawn external processes
        // (see VerificationToolsAreTestOnlyTest).
        try {
            return Files.readString(Path.of("/proc/self/maps")).contains("ld-musl");
        } catch (IOException | RuntimeException _) {
            // /proc unavailable; assume glibc
        }
        return false;
    }

    static String nativeFilename(String lib) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return lib + ".dll";
        if (os.contains("mac")) return "lib" + lib + ".dylib";
        return "lib" + lib + ".so";
    }
}
