package stirling.software.jpdfium.vips;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.internal.PixelFormat;
import stirling.software.jpdfium.internal.RenderedPageView;
import stirling.software.jpdfium.panama.NativeLoader;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeProbeTest {

    private static final int MIN_BYTES = 100;
    private static final int MIN_WEBP_BYTES = 10;

    @Test
    void probe() {
        System.out.println("os.name=" + System.getProperty("os.name"));
        System.out.println("os.arch=" + System.getProperty("os.arch"));
        System.out.println("platform=" + NativeLoader.detectPlatform());

        String arch = System.getProperty("os.arch").toLowerCase();
        boolean isArm64 = arch.equals("aarch64") || arch.equals("arm64");
        String platform = NativeLoader.detectPlatform();
        if (isArm64 && platform.equals("darwin-x64")) {
            System.out.println("NOTE: Intel-mode under Rosetta");
        }

        try {
            NativeLoader.ensureLoaded();
            System.out.println("JPDFium native loaded OK for " + platform);
        } catch (Throwable t) {
            System.out.println("JPDFium native NOT loaded: " + t.getMessage());
        }

        VipsAvailability.State state = VipsAvailability.probe();
        System.out.println("libvips available=" + state.available()
                + " version=" + state.version()
                + " platform=" + state.platform());
        System.out.println("  heifsave=" + state.heifsave()
                + " jxlsave=" + state.jxlsave()
                + " webpsave=" + state.webpsave()
                + " pngsave=" + state.pngsave()
                + " jpegsave=" + state.jpegsave());
        if (state.error() != null) {
            System.out.println("  error: " + state.error().getMessage());
        }

        if (!state.available()) {
            System.out.println("SKIP: libvips not available - " + VipsAvailability.installMessage(state));
            return;
        }

        try (var view = createTestView()) {
            byte[] png = VipsEncoder.encodeToBytes(view, VipsEncodeOptions.defaults(VipsFormat.PNG));
            System.out.println("PNG encode OK: " + png.length + " bytes");
            assertTrue(png.length > MIN_BYTES);

            byte[] jpeg = VipsEncoder.encodeToBytes(view, VipsEncodeOptions.defaults(VipsFormat.JPEG));
            System.out.println("JPEG encode OK: " + jpeg.length + " bytes");
            assertTrue(jpeg.length > MIN_BYTES);

            byte[] webp = VipsEncoder.encodeToBytes(view, VipsEncodeOptions.defaults(VipsFormat.WEBP));
            System.out.println("WebP encode OK: " + webp.length + " bytes");
            assertTrue(webp.length > MIN_WEBP_BYTES);
        }
    }

    private static RenderedPageView createTestView() {
        int w = 64;
        int h = 64;
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < rgba.length; i += 4) {
            rgba[i] = (byte) 200;
            rgba[i + 1] = (byte) 100;
            rgba[i + 2] = (byte) 50;
            rgba[i + 3] = (byte) 255;
        }
        MemorySegment seg = MemorySegment.ofArray(rgba);
        MemorySegment owned = Arena.ofAuto().allocate(rgba.length);
        MemorySegment.copy(seg, 0, owned, 0, rgba.length);
        return new RenderedPageView(
                w, h, w * 4, 4,
                PixelFormat.RGBA_STRAIGHT,
                owned.reinterpret(rgba.length), () -> {});
    }
}
