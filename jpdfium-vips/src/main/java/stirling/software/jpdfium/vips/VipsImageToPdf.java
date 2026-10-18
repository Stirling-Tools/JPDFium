package stirling.software.jpdfium.vips;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfImageConverter;
import stirling.software.jpdfium.model.ImageToPdfOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * libvips-backed image to PDF embedding.
 */
public final class VipsImageToPdf {

    private VipsImageToPdf() {}

    /** Decode and embed a single image as a one-page PDF. */
    public static PdfDocument fromImage(Path imagePath, ImageToPdfOptions options) throws IOException {
        return fromImages(List.of(imagePath), options);
    }

    /** Decode and embed each image file as a page in a new PDF (supports multi-page TIFF/GIF). */
    public static PdfDocument fromImages(List<Path> imagePaths, ImageToPdfOptions options) throws IOException {
        if (imagePaths == null || imagePaths.isEmpty()) {
            throw new IllegalArgumentException("At least one image is required");
        }
        List<byte[]> frames = new ArrayList<>();
        for (Path p : imagePaths) {
            frames.addAll(VipsDecoder.decodeAllFrames(p));
        }
        return PdfImageConverter.embedRgbaImages(frames, options);
    }

    /**
     * Decode and embed each in-memory image as a page (supports multi-page TIFF/GIF).
     */
    public static PdfDocument fromImageBytes(List<byte[]> images, ImageToPdfOptions options) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("At least one image is required");
        }
        List<byte[]> frames = new ArrayList<>();
        for (byte[] bytes : images) {
            frames.addAll(VipsDecoder.decodeAllFrames(bytes));
        }
        return PdfImageConverter.embedRgbaImages(frames, options);
    }
}
