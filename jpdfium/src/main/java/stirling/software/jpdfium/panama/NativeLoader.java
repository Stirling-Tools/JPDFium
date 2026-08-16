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
            boolean isWindows =
                    System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows && !libs.isEmpty()) {
                preloadWindowsDeps(tmpDir, libs, pdfiumName, bridgeName);
            }

            // Load pdfium (Linux/macOS: triggers RUNPATH resolution of its
            // deps; Windows: deps already pre-loaded above).
            Path pdfiumPath = tmpDir.resolve(pdfiumName);
            if (Files.exists(pdfiumPath)) {
                System.load(pdfiumPath.toAbsolutePath().toString());
            }

            // Then load the bridge
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
        } catch (IOException ignored) {
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

    private static void preloadWindowsDeps(
            Path tmpDir, List<String> libs, String pdfiumName, String bridgeName) {
        List<String> remaining = new ArrayList<>();
        for (String lib : libs) {
            if (lib.equals(pdfiumName) || lib.equals(bridgeName)) continue;
            if (isWindowsJvmHazardDll(lib)) continue;
            Path p = tmpDir.resolve(lib);
            if (Files.exists(p)) remaining.add(lib);
        }

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
                // help with). Let pdfium.dll's load surface the real error.
                break;
            }
            remaining = failed;
        }
        maxPasses--;
    }

    /**
     * DLLs that must never be {@link System#load}ed into the JVM. On Windows:
     * 1. PartitionAlloc allocator / raw_ptr: their DllMain replaces the process allocator,
     *    which hard-crashes the JVM (STATUS_ENTRYPOINT_NOT_FOUND / 0xc0000139 on windows-arm64).
     * 2. UCRT API set stubs (api-ms-win-*, ext-ms-*): system-provided forwarders and cannot be loaded directly.
     * Note: VC Redist CRT DLLs (msvcp140*, vcruntime140*, concrt140*) ARE pre-loaded so that
     * dependent libraries (like pdfium.dll and jpdfium.dll) can resolve them on hosts without
     * the newest Visual C++ Redistributable installed.
     */
    private static boolean isWindowsJvmHazardDll(String lib) {
        String l = lib.toLowerCase();
        return l.contains("allocator")
                || l.contains("partition_alloc")
                || l.contains("raw_ptr")
                || l.startsWith("api-ms-win-") || l.startsWith("ext-ms-");
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
            } catch (IOException | RuntimeException ignored) {
                // Directory missing or unreadable; try the next one
            }
        }
        // Fallback: inspect /proc/self/maps. A musl-linked JVM (Alpine) maps the
        // musl loader (ld-musl-<arch>.so.1) into its address space; glibc systems
        // never do. File read only - this layer must not spawn external processes
        // (see VerificationToolsAreTestOnlyTest).
        try {
            return Files.readString(Path.of("/proc/self/maps")).contains("ld-musl");
        } catch (IOException | RuntimeException ignored) {
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
