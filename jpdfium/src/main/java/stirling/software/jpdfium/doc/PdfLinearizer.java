package stirling.software.jpdfium.doc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * PDF Linearization (web optimization / fast web view).
 *
 * <p>Linearized PDFs load faster in web browsers because the first page can be
 * displayed before the entire file is downloaded.
 *
 * <p>Linearization uses the qpdf CLI tool, which must be installed on the system.
 * Install via: {@code apt install qpdf}, {@code brew install qpdf}, or
 * {@code choco install qpdf}.
 */
public final class PdfLinearizer {

    private PdfLinearizer() {}

    /**
     * Linearize a PDF file for fast web view.
     *
     * @param input  path to the input PDF
     * @param output path for the linearized output PDF
     * @throws RuntimeException if qpdf is not available or linearization fails
     */
    public static void linearize(Path input, Path output) {
        QpdfHelper.run("--linearize", input.toAbsolutePath().toString(),
                output.toAbsolutePath().toString());
    }

    /**
     * Check if qpdf (required for linearization) is available.
     *
     * @return true if qpdf is found on the system PATH
     */
    public static boolean isSupported() {
        return QpdfHelper.isAvailable();
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
