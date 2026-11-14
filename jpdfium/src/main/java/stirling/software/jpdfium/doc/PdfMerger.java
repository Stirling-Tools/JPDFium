package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.OutputTransaction;
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
     * <p>File-backed first: the native merge streams each input, so the common
     * case never puts the inputs on the Java heap. The in-memory fallback only
     * materializes them when the native path is unavailable or fails.
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

        if (QpdfLib.mergeFiles(inputPaths, outputPath)) {
            return;
        }

        // Native file merge unavailable or failed: only now pay for the bytes.
        List<byte[]> inputBytes = new ArrayList<>(inputPaths.size());
        for (Path p : inputPaths) {
            inputBytes.add(Files.readAllBytes(p));
        }

        byte[] merged = mergeBytes(inputBytes);
        if (merged == null) {
            throw new JPDFiumException("PDF merge failed or produced empty output");
        }

        try (OutputTransaction tx = OutputTransaction.begin(outputPath)) {
            Files.write(tx.staging(), merged);
            tx.publish(null);
        }
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
        byte[] result = QpdfLib.merge(inputs);
        if (hasPages(result)) {
            return result;
        }
        List<byte[]> repaired = new ArrayList<>(inputs.size());
        for (byte[] in : inputs) {
            if (in == null) continue;
            try {
                byte[] rep = PdfRepair.builder().input(in).build().execute().repairedPdf();
                repaired.add(rep != null && rep.length > 0 ? rep : in);
            } catch (RuntimeException e) {
                repaired.add(in);
            }
        }
        byte[] retried = repaired.isEmpty() ? null : QpdfLib.merge(repaired);
        if (hasPages(retried)) {
            return retried;
        }
        try (PdfDocument dest = PdfDocument.createEmpty()) {
            for (byte[] in : repaired) {
                try (PdfDocument src = PdfDocument.open(in)) {
                    PdfPageImporter.importPages(
                            dest.rawHandle(), src.rawHandle(), null, dest.pageCount());
                }
            }
            return dest.pageCount() > 0 ? dest.saveBytes() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasPages(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            return false;
        }
        if (pdf.length >= 2048) {
            return true;
        }
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            return doc.pageCount() > 0;
        } catch (RuntimeException e) {
            return false;
        }
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
