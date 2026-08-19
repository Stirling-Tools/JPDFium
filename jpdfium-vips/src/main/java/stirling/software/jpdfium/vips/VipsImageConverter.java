package stirling.software.jpdfium.vips;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.internal.RenderedPageView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional, vips-backed imaging facade for {@code jpdfium}.
 *
 * <p>This is the vips replacement for the core module's {@code ImageIO} image
 * codec path. A page is rendered straight into a native {@link RenderedPageView}
 * (a {@code MemorySegment} owned by PDFium) and handed to vips via
 * {@link VImage#newFromMemory} — that single native-to-vips copy is the only
 * allocation in the render→encode path. Encoding supports every format libvips
 * ships a saver for (PNG, JPEG, WEBP, HEIC, HEIF, AVIF, JXL, TIFF), and decoding
 * flows {@code image bytes -> VipsDecoder -> the bridge's raw-RGBA (format=3)
 * embed}. No {@code BufferedImage}, no {@code ImageIO}, no AWT raster.
 *
 * <p>Requires the optional {@code jpdfium-vips} module plus a libvips native
 * (bundled via the {@code jpdfium-natives-vips-*} jars, resolved by
 * {@link VipsNatives}, or a system libvips as fallback).
 */
public final class VipsImageConverter {

    private VipsImageConverter() {}

    /** @return raw bytes of page {@code pageIndex} rendered at {@code dpi} and encoded to {@code format}. */
    public static byte[] pageToBytes(PdfDocument doc, int pageIndex, int dpi, VipsFormat format) {
        return pageToBytes(doc, pageIndex, dpi, format, 75);
    }

    /** @return encoded page bytes; {@code quality} is the codec quality (1-100). */
    public static byte[] pageToBytes(PdfDocument doc, int pageIndex, int dpi,
                                     VipsFormat format, int quality) {
        try (PdfPage page = doc.page(pageIndex);
             RenderedPageView view = JpdfiumLib.renderPageView(page.nativeHandle(), dpi)) {
            VipsEncodeOptions encodeOptions = VipsEncodeOptions.builder(format).quality(quality).build();
            return VipsEncoder.encodeToBytes(view, encodeOptions);
        }
    }

    /** @return one encoded byte buffer per page, in page order. */
    public static List<byte[]> pdfToBytes(PdfDocument doc, int dpi, VipsFormat format) {
        int pageCount = doc.pageCount();
        List<byte[]> outputBytesList = new ArrayList<>(pageCount);
        for (int i = 0; i < pageCount; i++) {
            outputBytesList.add(pageToBytes(doc, i, dpi, format));
        }
        return outputBytesList;
    }

    /**
     * Render every page to an image file under {@code dir}, named
     * {@code <stem>-p<N>.<ext>}.
     *
     * @return the list of written paths, in page order
     */
    public static List<Path> pdfToImages(PdfDocument doc, int dpi, VipsFormat format, Path dir)
            throws IOException {
        Files.createDirectories(dir);
        List<Path> writtenFilePaths = new ArrayList<>();
        int pageCount = doc.pageCount();
        for (int i = 0; i < pageCount; i++) {
            VipsEncodeOptions encodeOptions = VipsEncodeOptions.builder(format).build();
            try (PdfPage page = doc.page(i);
                 RenderedPageView view = JpdfiumLib.renderPageView(page.nativeHandle(), dpi)) {
                Path file = dir.resolve(filename(i, format));
                VipsEncoder.encodeToFile(view, file, encodeOptions);
                writtenFilePaths.add(file);
            }
        }
        return writtenFilePaths;
    }

    /**
     * Thumbnail the page to roughly {@code maxDimPx} on its long edge, encoded
     * to {@code format}. Renders directly at the scaled dpi so no extra vips
     * resize allocation is needed.
     */
    public static byte[] thumbnail(PdfDocument doc, int pageIndex, int maxDimPx, VipsFormat format) {
        PageSize size = doc.page(pageIndex).size();
        int longer = (int) Math.max(size.width(), size.height());
        int dpi = (int) Math.max(72.0, Math.round(72.0 * maxDimPx / longer));
        try (PdfPage page = doc.page(pageIndex);
             RenderedPageView view = JpdfiumLib.renderPageView(page.nativeHandle(), dpi)) {
            return VipsEncoder.encodeToBytes(view, VipsEncodeOptions.defaults(format));
        }
    }

    /**
     * Decode image files (PNG/JPEG/HEIC/HEIF/AVIF/JXL/WEBP/TIFF) into PDF pages.
     * The decode is vips-backed ({@link VipsDecoder}); the embed uses the
     * bridge's zero-allocation raw-RGBA path ({@code format=3}).
     */
    public static PdfDocument imagesToPdf(List<Path> images, ImageToPdfOptions options)
            throws IOException {
        return VipsImageToPdf.fromImages(images, options);
    }

    private static String filename(int index, VipsFormat format) {
        String stem = "page";
        String ext = switch (format) {
            case PNG -> "png";
            case JPEG -> "jpg";
            case WEBP -> "webp";
            case HEIC, HEIF -> "heic";
            case AVIF -> "avif";
            case JXL -> "jxl";
            case TIFF -> "tiff";
        };
        return stem + "-p" + index + "." + ext;
    }
}
