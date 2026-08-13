package stirling.software.jpdfium.vips;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfImageConverter;
import stirling.software.jpdfium.model.ImageToPdfOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * libvips-backed image→PDF embedding: decode any format libvips supports
 * (PNG, JPEG, HEIC, HEIF, AVIF, JXL, WebP, TIFF, ...) via {@link VipsDecoder}
 * and embed each as a page through the JPDFium bridge's shared format=3 path
 * ({@link PdfImageConverter#embedRgbaImages}). This is the decode counterpart
 * to {@link VipsEncoder}, and the libvips alternative to
 * {@code PdfImageConverter.imagesToPdf} (which is limited to ImageIO's
 * JPG/PNG/GIF/BMP).
 *
 * <p>Requires the optional {@code jpdfium-vips} module + a libvips native
 * (bundled via {@code jpdfium-natives-vips-*}, or a system libvips).
 */
public final class VipsImageToPdf {

    private VipsImageToPdf() {}

    /** Decode and embed a single image as a one-page PDF. */
    public static PdfDocument fromImage(Path imagePath, ImageToPdfOptions options) throws IOException {
        return fromImages(List.of(imagePath), options);
    }

    /** Decode and embed each image file as a page in a new PDF. */
    public static PdfDocument fromImages(List<Path> imagePaths, ImageToPdfOptions options) throws IOException {
        if (imagePaths == null || imagePaths.isEmpty()) {
            throw new IllegalArgumentException("At least one image is required");
        }
        List<byte[]> images = new ArrayList<>(imagePaths.size());
        for (Path p : imagePaths) {
            images.add(Files.readAllBytes(p));
        }
        return fromImageBytes(images, options);
    }

    /**
     * Decode and embed each in-memory image as a page. Opt-in for callers that
     * already hold image bytes (e.g. downloaded or in-memory HEIC/JXL).
     */
    public static PdfDocument fromImageBytes(List<byte[]> images, ImageToPdfOptions options) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("At least one image is required");
        }
        List<byte[]> frames = new ArrayList<>(images.size());
        for (byte[] bytes : images) {
            frames.add(VipsDecoder.decodeToRgba(bytes));
        }
        return PdfImageConverter.embedRgbaImages(frames, options);
    }
}
