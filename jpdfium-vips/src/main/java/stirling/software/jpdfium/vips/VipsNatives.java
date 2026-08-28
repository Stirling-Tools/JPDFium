package stirling.software.jpdfium.vips;

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
            for (String lib : libs) {
                Path out = extract(base + lib, dir);
                if (out == null) {
                    continue;
                }
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
