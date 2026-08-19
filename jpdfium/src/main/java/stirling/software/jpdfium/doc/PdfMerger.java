package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.QpdfLib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * In-process PDF document merging backed by QPDF.
 *
 * <p>Merges page object graphs directly, preserving bookmarks, forms,
 * and structure trees while deduplicating shared resources.
 */
public final class PdfMerger {

    private PdfMerger() {}

    /**
     * Check if in-process QPDF merging is available.
     */
    public static boolean isSupported() {
        return QpdfLib.isMergeSupported();
    }

    /**
     * Merge multiple PDF files into a single output file.
     *
     * @param inputPaths list of input PDF file paths
     * @param outputPath destination PDF file path
     * @throws IOException on I/O error
     */
    public static void merge(List<Path> inputPaths, Path outputPath) throws IOException {
        if (inputPaths == null || inputPaths.isEmpty()) {
            throw new IllegalArgumentException("inputPaths must not be empty");
        }
        if (outputPath == null) {
            throw new IllegalArgumentException("outputPath must not be null");
        }

        List<byte[]> inputBytes = new ArrayList<>(inputPaths.size());
        for (Path p : inputPaths) {
            inputBytes.add(Files.readAllBytes(p));
        }

        byte[] merged = mergeBytes(inputBytes);
        if (merged == null) {
            throw new JPDFiumException("PDF merge failed or produced empty output");
        }

        Files.write(outputPath, merged);
    }

    /**
     * Merge multiple PDF byte arrays into a single merged PDF byte array.
     *
     * @param inputs list of PDF byte arrays
     * @return merged PDF byte array, or {@code null} on failure
     */
    public static byte[] mergeBytes(List<byte[]> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return null;
        }
        return QpdfLib.merge(inputs);
    }

    /**
     * Merge open PdfDocument instances into a new merged byte array.
     *
     * @param docs open documents to merge
     * @return merged PDF byte array
     */
    public static byte[] mergeDocuments(PdfDocument... docs) {
        if (docs == null || docs.length == 0) {
            return null;
        }
        List<byte[]> bytes = new ArrayList<>(docs.length);
        for (PdfDocument doc : docs) {
            if (doc != null) {
                bytes.add(doc.saveBytes());
            }
        }
        return mergeBytes(bytes);
    }
}
