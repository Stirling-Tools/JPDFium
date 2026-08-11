package stirling.software.jpdfium.vips;

import stirling.software.jpdfium.panama.NativeLoader;

import java.lang.reflect.InvocationTargetException;

public final class VipsAvailability {

    private static volatile State cached;

    private VipsAvailability() {}

    public static State probe() {
        State s = cached;
        if (s != null) return s;
        synchronized (VipsAvailability.class) {
            if (cached != null) return cached;
            cached = doProbe();
            return cached;
        }
    }

    public static boolean isAvailable() {
        return probe().available;
    }

    public static boolean isFormatAvailable(VipsFormat format) {
        State s = probe();
        if (!s.available) return false;
        return switch (format) {
            case HEIC, HEIF, AVIF -> s.heifsave;
            case JXL -> s.jxlsave;
            case WEBP -> s.webpsave;
            case PNG -> s.pngsave;
            case JPEG -> s.jpegsave;
        };
    }

    public static void require(VipsFormat format) {
        State s = probe();
        if (!s.available) {
            throw new VipsUnavailableException(installMessage(s));
        }
        if (!isFormatAvailable(format)) {
            throw new VipsUnavailableException(
                    "libvips is available but operation '" + format.operation()
                    + "' is not available for format " + format
                    + ". Platform: " + s.platform + ". " + formatGuidance(format));
        }
    }

    private static State doProbe() {
        String platform = NativeLoader.detectPlatform();
        try {
            Class<?> vipsClass = Class.forName("app.photofox.vipsffm.Vips");
            vipsClass.getMethod("init").invoke(null);
            String version = "unknown";
            try {
                Object v = vipsClass.getMethod("version").invoke(null);
                if (v != null) version = v.toString();
            } catch (Exception _) {
                // Version method optional across libvips bindings
            }

            boolean heif = probeOperation("heifsave");
            boolean jxl = probeOperation("jxlsave");
            boolean webp = probeOperation("webpsave");

            return new State(true, platform, version, heif, jxl, webp, true, true, null);
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            return new State(false, platform, null, false, false, false, false, false, root);
        }
    }

    private static boolean probeOperation(String name) {
        try {
            Class<?> vipsClass = Class.forName("app.photofox.vipsffm.Vips");
            try {
                Object found = vipsClass.getMethod("typeFind", String.class, String.class)
                        .invoke(null, "VipsOperation", name);
                if (found instanceof Boolean b) return b;
                if (found instanceof Long l) return l != 0;
                return found != null;
            } catch (NoSuchMethodException e) {
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static Throwable unwrap(Throwable t) {
        while (t.getCause() != null
                && (t instanceof InvocationTargetException)) {
            t = t.getCause();
        }
        return t;
    }

    static String installMessage(State s) {
        String msg = "libvips not available on " + s.platform;
        if (s.error != null) msg += ": " + s.error.getMessage();
        msg += ". Install: " + installGuidance(s.platform);
        return msg;
    }

    static String installGuidance(String platform) {
        if (platform.contains("darwin")) return "brew install vips";
        if (platform.contains("linux")) return "apt install libvips-dev  or  dnf install vips-devel";
        if (platform.contains("windows")) return "download libvips Windows binaries from https://github.com/libvips/build-win64-mxe/releases";
        return "install libvips (https://www.libvips.org/install.html)";
    }

    static String formatGuidance(VipsFormat format) {
        return switch (format) {
            case HEIC, HEIF, AVIF -> "requires libheif with x265 (HEIC) or aom/rav1e (AVIF)";
            case JXL -> "requires libjxl";
            case WEBP -> "requires libwebp";
            default -> "requires libvips with " + format.operation() + " support";
        };
    }

    public record State(
            boolean available,
            String platform,
            String version,
            boolean heifsave,
            boolean jxlsave,
            boolean webpsave,
            boolean pngsave,
            boolean jpegsave,
            Throwable error) {}
}
