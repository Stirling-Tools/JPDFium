package stirling.software.jpdfium.doc;

import java.nio.file.Path;

/**
 * PDF Stream Optimization (object stream compression, cross-reference streams).
 *
 * <p>Provides two levels of optimization:
 * <ul>
 *   <li>{@link #optimize(Path, Path)} - full optimization via qpdf
 *       (object streams, cross-reference streams, removes unreferenced objects)</li>
 *   <li>{@link #compact(Path, Path)} - basic compaction via qpdf
 *       (removes unreferenced objects, normalizes streams)</li>
 * </ul>
 *
 * <p>Requires qpdf to be installed on the system: {@code apt install qpdf},
 * {@code brew install qpdf}, or {@code choco install qpdf}.
 */
public final class PdfStreamOptimizer {

    private PdfStreamOptimizer() {}

    /**
     * Full optimization: generates object streams and cross-reference streams.
     * Typically reduces PDF file size significantly.
     *
     * @param input  path to the input PDF
     * @param output path for the optimized output PDF
     * @throws RuntimeException if qpdf is not available or optimization fails
     */
    public static void optimize(Path input, Path output) {
        QpdfHelper.run("--object-streams=generate",
                input.toAbsolutePath().toString(),
                output.toAbsolutePath().toString());
    }

    /**
     * Basic compaction: removes unreferenced objects and normalizes the file.
     *
     * @param input  path to the input PDF
     * @param output path for the compacted output PDF
     * @throws RuntimeException if qpdf is not available or compaction fails
     */
    public static void compact(Path input, Path output) {
        QpdfHelper.run(input.toAbsolutePath().toString(),
                output.toAbsolutePath().toString());
    }

    /**
     * Check if stream optimization (qpdf) is available.
     *
     * @return true if qpdf is found on the system PATH
     */
    public static boolean isSupported() {
        return QpdfHelper.isAvailable();
    }
}
