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
            if (!extracted.isEmpty()
                    && System.getProperty("os.name", "").toLowerCase().contains("win")) {
                addWindowsDllDirectory(dir);
                preloadWindows(extracted);
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
    private static boolean isVipsLib(String n) {
        return "vips.dll".equals(n) || n.startsWith("libvips.") || n.startsWith("libvips-42");
    }

    private static boolean isGlibLib(String n) {
        return n.startsWith("libglib-2.0") || n.startsWith("glib-2.0");
    }

    private static boolean isGobjectLib(String n) {
        return n.startsWith("libgobject-2.0") || n.startsWith("gobject-2.0");
    }
}
