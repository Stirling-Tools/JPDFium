package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.ThumbnailBindings;

import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * Extract embedded page thumbnails from a PDF.
 *
 * <p>Many PDFs include pre-rendered thumbnail images for each page.
 * This class provides access to both decoded (bitmap) and raw
 * (compressed) thumbnail data.
 *
 * <pre>{@code
 * try (var doc = PdfDocument.open(path);
 *      var page = doc.openPage(0)) {
 *     MemorySegment rawPage = JpdfiumLib.pageRawHandle(page.nativeHandle());
 *     Optional<byte[]> thumbnail = PdfThumbnails.getDecoded(rawPage);
 *     thumbnail.ifPresent(data ->
 *         System.out.printf("Thumbnail: %d bytes%n", data.length));
 * }
 * }</pre>
 */
public final class PdfThumbnails {

    private PdfThumbnails() {}

    /**
     * Get the decoded (uncompressed) thumbnail data for a page.
     *
     * @param page raw FPDF_PAGE segment
     * @return the decoded image data, or empty if no thumbnail exists
     */
    public static Optional<byte[]> getDecoded(MemorySegment page) {
        return getThumbnailData(page, ThumbnailBindings.FPDFPage_GetDecodedThumbnailData);
    }

    /**
     * Get the raw (compressed) thumbnail data for a page.
     *
     * @param page raw FPDF_PAGE segment
     * @return the raw image data, or empty if no thumbnail exists
     */
    public static Optional<byte[]> getRaw(MemorySegment page) {
        return getThumbnailData(page, ThumbnailBindings.FPDFPage_GetRawThumbnailData);
    }

    /**
     * Get the thumbnail for a page as a {@link BufferedImage}.
     *
     * <p>Uses {@code FPDFPage_GetThumbnailAsBitmap} to obtain the bitmap with known
     * dimensions, then converts from PDFium's BGRA memory layout to a Java
     * {@code BufferedImage} (TYPE_INT_ARGB).
     *
     * @param page raw FPDF_PAGE segment
     * @return the thumbnail image, or empty if no thumbnail exists
     */
    // FPDFBitmap format constants
    private static final int FMT_GRAY = 1;
    private static final int FMT_BGR  = 2;
    private static final int FMT_BGRx = 3;
    private static final int FMT_BGRA = 4;

    public static Optional<BufferedImage> getAsImage(MemorySegment page) {
        MemorySegment bitmap;
        try {
            bitmap = (MemorySegment) ThumbnailBindings.FPDFPage_GetThumbnailAsBitmap.invokeExact(page);
        } catch (Throwable t) { throw new RuntimeException("FPDFPage_GetThumbnailAsBitmap failed", t); }

        if (bitmap.equals(MemorySegment.NULL)) return Optional.empty();

        try {
            int w, h, stride, fmt;
            MemorySegment buf;
            try {
                w      = (int)           PageEditBindings.FPDFBitmap_GetWidth.invokeExact(bitmap);
                h      = (int)           PageEditBindings.FPDFBitmap_GetHeight.invokeExact(bitmap);
                stride = (int)           PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap);
                fmt    = (int)           PageEditBindings.FPDFBitmap_GetFormat.invokeExact(bitmap);
                buf    = (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap);
            } catch (Throwable t) { throw new RuntimeException("FPDFBitmap_Get* failed", t); }

            if (w <= 0 || h <= 0 || buf.equals(MemorySegment.NULL)) return Optional.empty();

            // Copy raw buffer into a Java byte array to avoid MemorySegment bounds issues
            byte[] raw = buf.reinterpret((long) stride * h).toArray(ValueLayout.JAVA_BYTE);

            int bpp = switch (fmt) {
                case FMT_GRAY -> 1;
                case FMT_BGR  -> 3;
                default       -> 4; // BGRx or BGRA
            };

            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int off = y * stride + x * bpp;
                    if (off + bpp - 1 >= raw.length) continue;
                    int r, g, b, a;
                    switch (fmt) {
                        case FMT_GRAY -> { r = g = b = raw[off] & 0xFF; a = 255; }
                        case FMT_BGR  -> { b = raw[off] & 0xFF; g = raw[off+1] & 0xFF;
                                           r = raw[off+2] & 0xFF; a = 255; }
                        case FMT_BGRA -> { b = raw[off] & 0xFF; g = raw[off+1] & 0xFF;
                                           r = raw[off+2] & 0xFF; a = raw[off+3] & 0xFF; }
                        default       -> { b = raw[off] & 0xFF; g = raw[off+1] & 0xFF;
                                           r = raw[off+2] & 0xFF; a = 255; } // BGRx: ignore padding byte
                    }
                    img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            return Optional.of(img);
        } finally {
            try { PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap); }
            catch (Throwable ignored) {}
        }
    }

    private static Optional<byte[]> getThumbnailData(MemorySegment page,
                                                      MethodHandle getter) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) getter.invokeExact(page, MemorySegment.NULL, 0L);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (needed <= 0) return Optional.empty();

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) getter.invokeExact(page, buf, needed);
            } catch (Throwable t) { throw new RuntimeException(t); }
            return Optional.of(buf.asSlice(0, needed).toArray(ValueLayout.JAVA_BYTE));
        }
    }
}
