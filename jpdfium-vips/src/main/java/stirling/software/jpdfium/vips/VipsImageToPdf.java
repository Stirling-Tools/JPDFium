package stirling.software.jpdfium.vips;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfImageConverter;
import stirling.software.jpdfium.model.ImageToPdfOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

/**
 * libvips-backed image to PDF embedding.
 *
 * <p>Decoding goes through {@link VipsDecoder} directly, so libvips failures
 * (for example a corrupt image) propagate as their native runtime exception
 * instead of being swallowed by the generic codec fallback. Images are decoded
 * one at a time and streamed into the PDF builder; only the current image's
 * frames are held in memory.
 */
public final class VipsImageToPdf {

    private VipsImageToPdf() {}

    /** Decode and embed a single image as a one-page PDF. */
    public static PdfDocument fromImage(Path imagePath, ImageToPdfOptions options) throws IOException {
        if (imagePath == null) {
            throw new IllegalArgumentException("imagePath must not be null");
        }
        return PdfImageConverter.embedRgbaImages(VipsDecoder.decodeAllFrames(imagePath), options);
    }

    /** Decode and embed each image file as a page in a new PDF (supports multi-page TIFF/GIF). */
    public static PdfDocument fromImages(List<Path> imagePaths, ImageToPdfOptions options)
            throws IOException {
        if (imagePaths == null || imagePaths.isEmpty()) {
            throw new IllegalArgumentException("At least one image is required");
        }
        return PdfImageConverter.embedRgbaFrameBatches(lazyPathFrames(imagePaths), options);
    }

    /**
     * Decode and embed each in-memory image as a page (supports multi-page TIFF/GIF).
     *
     * <p>Corrupt input surfaces as the libvips decode exception; it is not
     * wrapped or retried through ImageIO.
     */
    public static PdfDocument fromImageBytes(List<byte[]> images, ImageToPdfOptions options) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("At least one image is required");
        }
        return PdfImageConverter.embedRgbaFrameBatches(lazyByteFrames(images), options);
    }

    private static Iterable<List<byte[]>> lazyPathFrames(List<Path> paths) {
        return () -> new Iterator<>() {
            private final Iterator<Path> source = paths.iterator();

            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public List<byte[]> next() {
                return VipsDecoder.decodeAllFrames(source.next());
            }
        };
    }

    private static Iterable<List<byte[]>> lazyByteFrames(List<byte[]> images) {
        return () -> new Iterator<>() {
            private final Iterator<byte[]> source = images.iterator();

            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public List<byte[]> next() {
                return VipsDecoder.decodeAllFrames(source.next());
            }
        };
    }
}
