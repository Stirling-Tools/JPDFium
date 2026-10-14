package stirling.software.jpdfium.spi;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import stirling.software.jpdfium.panama.JpdfiumLib;

/**
 * Service provider that teaches {@link ImageIO} to read SVG through JPDFium's
 * native rasterizer.
 */
public final class SvgImageReaderSpi extends ImageReaderSpi {

    private static final int SNIFF_BYTES = 4096;

    public SvgImageReaderSpi() {
        super(
                "JPDFium",
                "1.0",
                new String[] {"svg"},
                new String[] {"svg"},
                new String[] {"image/svg+xml"},
                SvgImageReader.class.getName(),
                new Class<?>[] {ImageInputStream.class, byte[].class, InputStream.class},
                null,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null);
    }

    @Override
    public String getDescription(Locale locale) {
        return "JPDFium SVG reader (native resvg rasterizer)";
    }

    @Override
    public boolean canDecodeInput(Object input) throws IOException {
        if (!hasRasterizer(input)) {
            return false;
        }
        byte[] prefix = sniff(input);
        return prefix != null && SvgImageFormat.looksLikeSvg(prefix);
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new SvgImageReader(this);
    }

    /** True only when the native rasterizer can actually run, so we never claim SVG blindly. */
    private static boolean hasRasterizer(Object input) {
        try {
            return JpdfiumLib.isSvgRasterizerAvailable();
        } catch (RuntimeException | LinkageError e) {
            // Natives missing or the bridge failed to initialise: stay out of
            // the way rather than failing ImageIO's reader scan.
            return false;
        }
    }

    private static byte[] sniff(Object input) throws IOException {
        if (input instanceof byte[] array) {
            return array.length <= SNIFF_BYTES ? array : java.util.Arrays.copyOf(array, SNIFF_BYTES);
        }
        if (input instanceof ImageInputStream stream) {
            stream.mark();
            try {
                byte[] prefix = new byte[SNIFF_BYTES];
                int total = 0;
                int read;
                while (total < SNIFF_BYTES
                        && (read = stream.read(prefix, total, SNIFF_BYTES - total)) > 0) {
                    total += read;
                }
                return java.util.Arrays.copyOf(prefix, Math.max(total, 0));
            } finally {
                stream.reset();
            }
        }
        if (input instanceof InputStream stream) {
            if (!stream.markSupported()) {
                stream = new java.io.BufferedInputStream(stream, SNIFF_BYTES);
            }
            stream.mark(SNIFF_BYTES);
            try {
                byte[] prefix = new byte[SNIFF_BYTES];
                int total = 0;
                int read;
                while (total < SNIFF_BYTES
                        && (read = stream.read(prefix, total, SNIFF_BYTES - total)) > 0) {
                    total += read;
                }
                return java.util.Arrays.copyOf(prefix, Math.max(total, 0));
            } finally {
                stream.reset();
            }
        }
        return null;
    }
}
