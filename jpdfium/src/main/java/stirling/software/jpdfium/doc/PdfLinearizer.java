package stirling.software.jpdfium.doc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.QpdfLib;

/**
 * PDF linearization (fast web view).
 *
 * <p>Linearized PDFs let the first page render before the whole file downloads.
 * Uses the bundled qpdf library in-process (FFM), no external qpdf binary.
 */
public final class PdfLinearizer {

    private PdfLinearizer() {}

    /**
     * Linearize a PDF file for fast web view.
     *
     * @param input  path to the input PDF
     * @param output path for the linearized output PDF
     * @throws JPDFiumException if linearization fails
     */
    public static void linearize(Path input, Path output) {
        try {
            byte[] out = PdfOptimizer.optimize(
                    Files.readAllBytes(input),
                    PdfOptimizer.LINEARIZE,
                    PdfOptimizer.DEFAULT,
                    PdfOptimizer.OBJECT_STREAMS_GENERATE,
                    PdfOptimizer.DEFAULT,
                    PdfOptimizer.DEFAULT);
            if (out == null) {
                throw new JPDFiumException("qpdf linearization produced no output");
            }
            Files.write(output, out);
        } catch (IOException e) {
            throw new JPDFiumException("qpdf linearization failed", e);
        }
    }

    /** Check if the in-process qpdf library is available in the current environment. */
    public static boolean isSupported() {
        return QpdfLib.isSupported();
    }

    /**
     * Check if a PDF is linearized by examining the first bytes.
     *
     * @param pdfBytes the PDF file bytes
     * @return true if the PDF appears to be linearized
     */
    public static boolean isLinearized(byte[] pdfBytes) {
        String header = new String(pdfBytes, 0, Math.min(pdfBytes.length, 1024),
                StandardCharsets.US_ASCII);
        return header.contains("/Linearized");
    }
}

