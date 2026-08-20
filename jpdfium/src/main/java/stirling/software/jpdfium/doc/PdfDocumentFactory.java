package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfImageConverter;
import stirling.software.jpdfium.PdfMerge;
import stirling.software.jpdfium.PdfSplit;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.panama.QpdfLib;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

/**
 * Factory for creating, loading, and saving {@link PdfDocument} instances.
 *
 * <p>Provides unified load and save operations across memory buffers, streams,
 * files, and image sources for web applications and pipeline services.
 */
public final class PdfDocumentFactory {

    private PdfDocumentFactory() {}

    /**
     * Load a PDF document from a byte array.
     */
    public static PdfDocument load(byte[] bytes) {
        return PdfDocument.open(bytes);
    }

    /**
     * Load a password-protected PDF document from a byte array.
     */
    public static PdfDocument load(byte[] bytes, String password) {
        return PdfDocument.open(bytes, password);
    }

    /**
     * Load a PDF document from an {@link InputStream}.
     */
    public static PdfDocument load(InputStream inputStream) throws IOException {
        return PdfDocument.open(inputStream);
    }

    /**
     * Load a password-protected PDF document from an {@link InputStream}.
     */
    public static PdfDocument load(InputStream inputStream, String password) throws IOException {
        return PdfDocument.open(inputStream, password);
    }

    /**
     * Load a PDF document from a file {@link Path}.
     */
    public static PdfDocument load(Path path) {
        return PdfDocument.open(path);
    }

    /**
     * Load a password-protected PDF document from a file {@link Path}.
     */
    public static PdfDocument load(Path path, String password) {
        return PdfDocument.open(path, password);
    }

    /**
     * Load a PDF document from a {@link File}.
     */
    public static PdfDocument load(File file) {
        return PdfDocument.open(file);
    }

    /**
     * Load a password-protected PDF document from a {@link File}.
     */
    public static PdfDocument load(File file, String password) {
        return PdfDocument.open(file, password);
    }

    /**
     * Load a PDF document from a {@link ByteBuffer}.
     */
    public static PdfDocument load(ByteBuffer byteBuffer) {
        return PdfDocument.open(byteBuffer);
    }

    /**
     * Load a password-protected PDF document from a {@link ByteBuffer}.
     */
    public static PdfDocument load(ByteBuffer byteBuffer, String password) {
        return PdfDocument.open(byteBuffer, password);
    }

    /**
     * Create a new empty PDF document.
     */
    public static PdfDocument createNew() {
        return PdfDocument.createEmpty();
    }

    /**
     * Create a new PDF document from a list of {@link BufferedImage} instances.
     */
    public static PdfDocument createFromImages(List<BufferedImage> images) {
        return PdfDocument.fromImages(images);
    }

    /**
     * Create a new PDF document from images with custom layout options.
     */
    public static PdfDocument createFromImages(List<BufferedImage> images, ImageToPdfOptions options) {
        return PdfDocument.fromImages(images, options);
    }

    /**
     * Create a new PDF document from image file paths.
     */
    public static PdfDocument createFromImagePaths(List<Path> imagePaths, ImageToPdfOptions options) throws IOException {
        return PdfImageConverter.imagesToPdf(imagePaths, options);
    }

    /**
     * Save a document to a byte array.
     */
    public static byte[] saveToBytes(PdfDocument doc) {
        if (doc == null) throw new IllegalArgumentException("doc must not be null");
        return doc.saveBytes();
    }

    /**
     * Save a document to an {@link OutputStream}.
     */
    public static void saveToStream(PdfDocument doc, OutputStream out) throws IOException {
        if (doc == null) throw new IllegalArgumentException("doc must not be null");
        if (out == null) throw new IllegalArgumentException("out must not be null");
        doc.save(out);
    }

    /**
     * Save a document to a file {@link Path}.
     */
    public static void saveToFile(PdfDocument doc, Path path) throws IOException {
        if (doc == null) throw new IllegalArgumentException("doc must not be null");
        if (path == null) throw new IllegalArgumentException("path must not be null");
        doc.save(path);
    }

    /**
     * Save a document to a {@link File}.
     */
    public static void saveToFile(PdfDocument doc, File file) throws IOException {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        saveToFile(doc, file.toPath());
    }

    /**
     * Merge multiple open PDF documents into a single new document.
     */
    public static PdfDocument merge(List<PdfDocument> documents) {
        return PdfMerge.merge(documents);
    }

    /**
     * Merge multiple PDF byte arrays into a single merged byte array.
     */
    public static byte[] mergeBytes(List<byte[]> inputs) {
        return PdfMerger.mergeBytes(inputs);
    }

    /**
     * Merge multiple PDF files into a single destination file.
     */
    public static void mergeFiles(List<Path> paths, Path outputPath) throws IOException {
        PdfMerger.merge(paths, outputPath);
    }

    /**
     * Extract specific pages from a document by zero-based indices.
     */
    public static PdfDocument extractPages(PdfDocument doc, java.util.Set<Integer> indices) {
        return PdfSplit.extractPages(doc, indices);
    }

    /**
     * Extract specific pages from a PDF byte array by zero-based indices.
     */
    public static byte[] extractPages(byte[] pdfBytes, int[] indices) {
        return QpdfLib.extractPages(pdfBytes, indices);
    }

    /**
     * Split a document according to a split strategy.
     */
    public static List<PdfDocument> split(PdfDocument doc, PdfSplit.SplitStrategy strategy) {
        return PdfSplit.split(doc, strategy);
    }
}
