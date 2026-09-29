package stirling.software.jpdfium.vips;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import stirling.software.jpdfium.panama.NativeLoader;

/**
 * Extracts a bundled libvips (+ glib/gobject + codec chain) from the optional
 * {@code jpdfium-natives-vips-<platform>} jar and points vips-ffm at it by
 * setting the {@code vipsffm.libpath.{vips,glib,gobject}.override} system
 * properties before {@code Vips.init()} runs.
 */
public final class VipsNatives {

    private static volatile boolean configured;

    private VipsNatives() {}

    /**
     * Idempotent. Extract bundled libvips if present and set the vips-ffm
     * override properties. Safe to call before every {@code Vips.init()}.
     */
    public static synchronized void configure() {
        if (configured) {
            return;
        }
        String platform = NativeLoader.detectPlatform();
        String base = "/natives/vips-" + platform + "/";
        List<String> libs = readIndex(base + "native-libs.txt");
        if (libs.isEmpty()) {
            discoverSystemLibs();
            configured = true;
            return; // no bundled vips; rely on system libvips / caller overrides
        }
        try {
            Path dir = Files.createTempDirectory("jpdfium-vips-");
            dir.toFile().deleteOnExit();
            String vips = null;
            String glib = null;
            String gobject = null;
            List<Path> extracted = new ArrayList<>();
            for (String lib : libs) {
                Path out = extract(base + lib, dir);
                if (out == null) {
                    continue;
                }
                extracted.add(out);
                String n = out.getFileName().toString();
                if (vips == null && isVipsLib(n)) {
                    vips = out.toString();
                } else if (glib == null && isGlibLib(n)) {
                    glib = out.toString();
                } else if (gobject == null && isGobjectLib(n)) {
                    gobject = out.toString();
                }
            }
            if (vips != null) {
                System.setProperty("vipsffm.libpath.vips.override", vips);
            }
            if (glib != null) {
                System.setProperty("vipsffm.libpath.glib.override", glib);
            }
            if (gobject != null) {
                System.setProperty("vipsffm.libpath.gobject.override", gobject);
            }
            // Point VIPS_MODULE_PATH to the extracted directory so dynamic plugins (vips-heif, vips-jxl, etc.) are loaded
            System.setProperty("vipsffm.modulepath.override", dir.toAbsolutePath().toString());
            if (!extracted.isEmpty()
                    && System.getProperty("os.name", "").toLowerCase().contains("win")) {
                addWindowsDllDirectory(dir);
                preloadWindows(extracted);
                loadVipsPlugins(dir, extracted);
            }
        } catch (IOException e) {
            // Extraction failed - leave vips-ffm to its defaults (system libvips)
        } finally {
            configured = true;
        }
    }

    private static List<String> readIndex(String resource) {
        List<String> out = new ArrayList<>();
        try (InputStream is = VipsNatives.class.getResourceAsStream(resource)) {
            if (is == null) {
                return out;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (!t.isEmpty() && t.charAt(0) != '#') {
                        out.add(t);
                    }
                }
            }
        } catch (IOException ignored) {
            // Missing index is non-fatal
        }
        return out;
    }

    /**
     * Makes the Windows loader resolve bundled dependencies from the extract
     * directory. LoadLibrary otherwise searches only the app dir, System32 and
     * PATH, so sibling DLLs next to an explicitly loaded library are not
     * found (Linux/macOS solve the same problem with $ORIGIN/@loader_path).
     */
    private static void addWindowsDllDirectory(Path dir) {
        Linker linker = Linker.nativeLinker();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment addr = SymbolLookup.libraryLookup("kernel32", arena)
                    .find("SetDllDirectoryW")
                    .orElseThrow(() -> new UnsatisfiedLinkError("SetDllDirectoryW not found"));
            MethodHandle handle = linker.downcallHandle(
                    addr, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MemorySegment path = arena.allocateFrom(
                    dir.toAbsolutePath().toString(), StandardCharsets.UTF_16LE);
            int ok = (int) handle.invokeExact(path);
            if (ok == 0) {
                throw new UnsatisfiedLinkError("SetDllDirectoryW failed for " + dir);
            }
        } catch (UnsatisfiedLinkError e) {
            throw e;
        } catch (Throwable t) {
            throw new UnsatisfiedLinkError("Could not set DLL directory " + dir + ": " + t);
        }
    }

    /**
     * Windows resolves a DLL's dependencies without looking in that DLL's own
     * directory (Linux/macOS bundles carry an $ORIGIN/@loader_path rpath
     * instead), so load every bundled library up front in dependency order.
     * Repeat passes until no progress; anything left cannot be loaded at all.
     */
    private static void preloadWindows(List<Path> libs) {
        List<Path> remaining = new ArrayList<>(libs);
        Map<Path, String> firstError = new LinkedHashMap<>();
        boolean progress = true;
        while (!remaining.isEmpty() && progress) {
            progress = false;
            Iterator<Path> it = remaining.iterator();
            while (it.hasNext()) {
                Path lib = it.next();
                try {
                    System.load(lib.toAbsolutePath().toString());
                    it.remove();
                    progress = true;
                } catch (UnsatisfiedLinkError e) {
                    firstError.putIfAbsent(lib, String.valueOf(e.getMessage()));
                }
            }
        }
        if (!remaining.isEmpty()) {
            throw new UnsatisfiedLinkError(
                    "Could not preload bundled libs: " + remaining + " first errors: " + firstError);
        }
    }

    /**
     * Opens staged vips codec plugins (e.g. vips-jxl.dll) via glib's module
     * loader, mirroring what {@code vips --plugin} does. Opening a module
     * runs its init entry and registers its operations. Needed on Windows
     * where such codecs ship as separate files that libvips never scans for
     * (it only looks next to its own install dir, which is our temp dir
     * without the versioned modules subdirectory).
     */
    private static void loadVipsPlugins(Path dir, List<Path> extracted) {
        List<Path> plugins = new ArrayList<>();
        for (Path lib : extracted) {
            String n = lib.getFileName().toString();
            if (isVipsLib(n) || isGlibLib(n) || isGobjectLib(n)) {
                continue;
            }
            String lower = n.toLowerCase();
            if (lower.startsWith("vips-")
                    && (lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib"))) {
                plugins.add(lib);
            }
        }
        if (plugins.isEmpty()) {
            return;
        }
        Linker linker = Linker.nativeLinker();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment openAddr = SymbolLookup.loaderLookup()
                    .find("g_module_open")
                    .orElseThrow(() -> new UnsatisfiedLinkError("g_module_open not found"));
            MethodHandle open = linker.downcallHandle(
                    openAddr,
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MemorySegment errorAddr = SymbolLookup.loaderLookup()
                    .find("g_module_error")
                    .orElseThrow(() -> new UnsatisfiedLinkError("g_module_error not found"));
            MethodHandle errorFn = linker.downcallHandle(
                    errorAddr, FunctionDescriptor.of(ValueLayout.ADDRESS));
            for (Path plugin : plugins) {
                MemorySegment path = arena.allocateFrom(
                        plugin.toAbsolutePath().toString(), StandardCharsets.UTF_8);
                MemorySegment module = (MemorySegment) open.invokeExact(path, 1);
                if (module.equals(MemorySegment.NULL)) {
                    MemorySegment message =
                            (MemorySegment) errorFn.invokeExact();
                    throw new UnsatisfiedLinkError("g_module_open failed for "
                            + plugin.getFileName() + ": "
                            + message.getString(0, StandardCharsets.UTF_8));
                }
            }
        } catch (UnsatisfiedLinkError e) {
            throw e;
        } catch (Throwable t) {
            throw new UnsatisfiedLinkError("Could not load vips plugins: " + t);
        }
    }

    private static Path extract(String resource, Path dir) throws IOException {
        try (InputStream is = VipsNatives.class.getResourceAsStream(resource)) {
            if (is == null) {
                return null;
            }
            String name = resource.substring(resource.lastIndexOf('/') + 1);
            Path target = dir.resolve(name);
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            return target;
        }
    }

    /** Core libvips (excludes the C++ wrapper libvips-cpp and codec plugins). */
    static boolean isVipsLib(String n) {
        String lower = n.toLowerCase();
        if (lower.startsWith("libvips-cpp")
                || lower.startsWith("vips-cpp")
                || lower.startsWith("vips-jxl")
                || lower.startsWith("vips-heif")
                || lower.startsWith("vips-magick")
                || lower.startsWith("vips-poppler")
                || lower.startsWith("vips-modules")
                || lower.startsWith("vips-plugins")) {
            return false;
        }
        return lower.equals("vips.dll")
                || lower.startsWith("vips-42")
                || lower.startsWith("vips.")
                || lower.startsWith("libvips.")
                || lower.startsWith("libvips-42");
    }

    static boolean isGlibLib(String n) {
        String lower = n.toLowerCase();
        return lower.startsWith("libglib-2.0") || lower.startsWith("glib-2.0");
    }

    static boolean isGobjectLib(String n) {
        String lower = n.toLowerCase();
        return lower.startsWith("libgobject-2.0") || lower.startsWith("gobject-2.0");
    }

    private static void discoverSystemLibs() {
        if (System.getProperty("vipsffm.libpath.vips.override") != null) {
            return;
        }
        List<Path> searchDirs = new ArrayList<>();
        String dyld = System.getenv("DYLD_LIBRARY_PATH");
        if (dyld != null) {
            for (String p : dyld.split(":")) {
                if (!p.isBlank()) searchDirs.add(Path.of(p));
            }
        }
        String ld = System.getenv("LD_LIBRARY_PATH");
        if (ld != null) {
            for (String p : ld.split(":")) {
                if (!p.isBlank()) searchDirs.add(Path.of(p));
            }
        }
        // Well-known system library locations
        searchDirs.add(Path.of("/opt/homebrew/lib"));
        searchDirs.add(Path.of("/usr/local/lib"));
        searchDirs.add(Path.of("/opt/local/lib"));
        searchDirs.add(Path.of("/usr/lib64"));
        searchDirs.add(Path.of("/usr/lib"));
        searchDirs.add(Path.of("/usr/lib/x86_64-linux-gnu"));
        searchDirs.add(Path.of("/usr/lib/aarch64-linux-gnu"));
        searchDirs.add(Path.of("/usr/lib/arm-linux-gnueabihf"));
        searchDirs.add(Path.of("/lib/x86_64-linux-gnu"));
        searchDirs.add(Path.of("/lib/aarch64-linux-gnu"));

        Path vips = findFirstLib(searchDirs, "libvips", ".dylib", ".so", ".dll");
        if (vips == null) {
            vips = findFirstLib(searchDirs, "vips", ".dylib", ".so", ".dll");
        }
        if (vips != null) {
            System.setProperty("vipsffm.libpath.vips.override", vips.toAbsolutePath().toString());
            Path glib = findFirstLib(searchDirs, "libglib-2.0", ".dylib", ".so", ".dll");
            if (glib == null) {
                glib = findFirstLib(searchDirs, "glib-2.0", ".dylib", ".so", ".dll");
            }
            if (glib != null) {
                System.setProperty("vipsffm.libpath.glib.override", glib.toAbsolutePath().toString());
            }
            Path gobject = findFirstLib(searchDirs, "libgobject-2.0", ".dylib", ".so", ".dll");
            if (gobject == null) {
                gobject = findFirstLib(searchDirs, "gobject-2.0", ".dylib", ".so", ".dll");
            }
            if (gobject != null) {
                System.setProperty("vipsffm.libpath.gobject.override", gobject.toAbsolutePath().toString());
            }
        }
    }

    private static Path findFirstLib(List<Path> dirs, String prefix, String ext1, String ext2, String ext3) {
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            Path found = findLib(dir, prefix, ext1, ext2, ext3);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Path findLib(Path dir, String prefix, String ext1, String ext2, String ext3) {
        Path p1 = dir.resolve(prefix + ext1);
        if (Files.exists(p1)) return p1;
        Path p2 = dir.resolve(prefix + ext2);
        if (Files.exists(p2)) return p2;
        Path p3 = dir.resolve(prefix + ext3);
        if (Files.exists(p3)) return p3;
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        if (name.startsWith("libvips-cpp") || name.startsWith("vips-cpp")) return false;
                        if (name.contains("vips-modules") || name.contains("vips-plugins")) return false;
                        return name.startsWith(prefix)
                                && (name.contains(ext1) || name.contains(ext2) || name.contains(ext3));
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException _) {
            return null;
        }
    }
}
