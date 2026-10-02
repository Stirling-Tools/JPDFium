package stirling.software.jpdfium.doc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.QpdfLib;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;

/**
 * PDF stream optimization (object streams, cross-reference streams).
 *
 * <p>Two levels: full optimization (object streams + xref streams) and basic
 * compaction (removes unreferenced objects). Uses the bundled qpdf library
 * in-process (FFM), no external qpdf binary.
 */
public final class PdfStreamOptimizer {

    private PdfStreamOptimizer() {}

    /**
     * Full optimization: generates object streams and cross-reference streams.
     * Typically reduces PDF file size significantly.
     *
     * @param input  path to the input PDF
     * @param output path for the optimized output PDF
     * @throws JPDFiumException if optimization fails
     */
    public static void optimize(Path input, Path output) {
        try {
            if (QpdfLib.optimizeFile(input, output, 0, PdfOptimizer.OBJECT_STREAMS_GENERATE,
                    PdfOptimizer.DEFAULT, PdfOptimizer.DEFAULT)) {
                return;
            }
            byte[] out = PdfOptimizer.optimize(
                    Files.readAllBytes(input),
                    0,
                    PdfOptimizer.DEFAULT,
                    PdfOptimizer.OBJECT_STREAMS_GENERATE,
                    PdfOptimizer.DEFAULT,
                    PdfOptimizer.DEFAULT);
            if (out == null) {
                throw new JPDFiumException("qpdf optimization produced no output");
            }
            Path staging = output.toAbsolutePath().getParent() != null
                    ? Files.createTempFile(output.toAbsolutePath().getParent(), ".jpdfium-opt-", ".pdf")
                    : Files.createTempFile("jpdfium-opt-", ".pdf");
            try {
                Files.write(staging, out);
                try {
                    Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(staging, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(staging);
            }
        } catch (IOException e) {
            throw new JPDFiumException("qpdf optimization failed", e);
        }
    }

    /**
     * Basic compaction: removes unreferenced objects and normalizes the file.
     *
     * @param input  path to the input PDF
     * @param output path for the compacted output PDF
     * @throws JPDFiumException if compaction fails
     */
    public static void compact(Path input, Path output) {
        try {
            if (QpdfLib.optimizeFile(input, output, 0, PdfOptimizer.DEFAULT,
                    PdfOptimizer.DEFAULT, PdfOptimizer.DEFAULT)) {
                return;
            }
            byte[] out = PdfOptimizer.optimize(
                    Files.readAllBytes(input),
                    0,
                    PdfOptimizer.DEFAULT,
                    PdfOptimizer.DEFAULT,
                    PdfOptimizer.DEFAULT,
                    PdfOptimizer.DEFAULT);
            if (out == null) {
                throw new JPDFiumException("qpdf compaction produced no output");
            }
            Path staging = output.toAbsolutePath().getParent() != null
                    ? Files.createTempFile(output.toAbsolutePath().getParent(), ".jpdfium-compact-", ".pdf")
                    : Files.createTempFile("jpdfium-compact-", ".pdf");
            try {
                Files.write(staging, out);
                try {
                    Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(staging, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(staging);
            }
        } catch (IOException e) {
            throw new JPDFiumException("qpdf compaction failed", e);
        }
    }

    /** Check if the in-process qpdf library is available in the current environment. */
    public static boolean isSupported() {
        return QpdfLib.isSupported();
    }
}
