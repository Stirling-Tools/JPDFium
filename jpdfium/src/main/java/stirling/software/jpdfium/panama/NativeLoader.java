package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.NativeLoadException;
import stirling.software.jpdfium.exception.NativeNotFoundException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class NativeLoader {

    private static volatile boolean loaded = false;
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
            try {
                System.loadLibrary("jpdfium");
                loaded = true;
            } catch (UnsatisfiedLinkError e) {
                loadError = classpathMiss;
                throw new NativeNotFoundException(
                        detectPlatform() + ". Also tried System.loadLibrary(\"jpdfium\") and failed.");
            }
        } catch (Throwable t) {
            loadError = t;
            throw (t instanceof NativeLoadException nle) ? nle
                    : new NativeLoadException("Failed to load native library", t);
        }
    }

    private static void tryLoadFromClasspath() {
        String platform    = detectPlatform();
        String resourceBase = "/natives/" + platform + "/";
        String bridgeName  = nativeFilename("jpdfium");
        String pdfiumName  = nativeFilename("pdfium");
        String indexResource = resourceBase + "native-libs.txt";

        if (NativeLoader.class.getResource(resourceBase + bridgeName) == null)
            throw new NativeNotFoundException(platform);

        try {
            Path tmpDir = Files.createTempDirectory("jpdfium-");
            tmpDir.toFile().deleteOnExit();

            // Extract all libraries from the manifest to tmpDir so the dynamic
            // linker can resolve NEEDED dependencies via RUNPATH=$ORIGIN
            List<String> libs = readLibraryIndex(indexResource);
            for (String lib : libs) {
                extractToDir(resourceBase + lib, tmpDir);
            }

            // If no manifest was found, fall back to extracting just libpdfium
            if (libs.isEmpty()) {
                extractToDir(resourceBase + pdfiumName, tmpDir);
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
                bridge = extractLib(resourceBase + bridgeName, tmpDir, bridgeName);
            }
            System.load(bridge.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new NativeLoadException("Failed to extract native library", e);
        }
    }

    private static List<String> readLibraryIndex(String resource) {
        List<String> result = new ArrayList<>();
        try (InputStream is = NativeLoader.class.getResourceAsStream(resource)) {
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
        } catch (IOException _) {
            // Missing index is not fatal; fall through with empty list
        }
        return result;
    }

    private static void extractToDir(String resource, Path dir) throws IOException {
        try (InputStream is = NativeLoader.class.getResourceAsStream(resource)) {
            if (is == null) return;
            Path target = dir.resolve(resource.substring(resource.lastIndexOf('/') + 1));
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
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

    private static Path extractLib(String resource, Path dir, String filename) throws IOException {
        try (InputStream is = NativeLoader.class.getResourceAsStream(resource)) {
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
