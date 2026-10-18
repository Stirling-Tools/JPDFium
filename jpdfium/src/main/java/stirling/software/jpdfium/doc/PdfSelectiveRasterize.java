package stirling.software.jpdfium.doc;

import java.util.Locale;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.text.PdfTextExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Selectively rasterize specific pages while keeping others as vector content.
 *
 * <p>Useful for flattening complex pages (e.g., pages with problematic
 * transparency or complex vector art) into bitmap-on-page while preserving
 * other pages as native PDF.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("mixed.pdf"))) {
 *     // Rasterize pages 0 and 2 at 150 DPI
 *     PdfSelectiveRasterize.rasterize(doc, List.of(0, 2), 150);
 *     doc.save(Path.of("rasterized.pdf"));
 * }
 * }</pre>
 */
public final class PdfSelectiveRasterize {

    private PdfSelectiveRasterize() {}

    /**
     * Rasterize specific pages at the given DPI, replacing their content
     * with a single full-page image.
     *
     * @param doc         open PDF document
     * @param pageIndices pages to rasterize (0-based)
     * @param dpi         rendering resolution
     * @return number of pages rasterized
     */
    public static int rasterize(PdfDocument doc, List<Integer> pageIndices, int dpi) {
        int count = 0;
        for (int pageIndex : pageIndices) {
            if (pageIndex < 0 || pageIndex >= doc.pageCount()) continue;
            rasterizePage(doc, pageIndex, dpi);
            count++;
        }
        return count;
    }

    /**
     * Rasterize pages matching a text search pattern.
     *
     * @param doc     open PDF document
     * @param keyword pages containing this text will be rasterized
     * @param dpi     rendering resolution
     * @return number of pages rasterized
     */
    public static int rasterizeByContent(PdfDocument doc, String keyword, int dpi) {
        List<Integer> pagesToRasterize = new ArrayList<>();
        for (int i = 0; i < doc.pageCount(); i++) {
            String text = PdfTextExtractor.extractPage(doc, i).plainText();
            if (text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                pagesToRasterize.add(i);
            }
        }
        return rasterize(doc, pagesToRasterize, dpi);
    }

    /**
     * Rasterize a range of pages.
     *
     * @param doc       open PDF document
     * @param fromPage  start page (inclusive, 0-based)
     * @param toPage    end page (inclusive, 0-based)
     * @param dpi       rendering resolution
     * @return number of pages rasterized
     */
    public static int rasterizeRange(PdfDocument doc, int fromPage, int toPage, int dpi) {
        List<Integer> indices = new ArrayList<>();
        for (int i = fromPage; i <= Math.min(toPage, doc.pageCount() - 1); i++) {
            indices.add(i);
        }
        return rasterize(doc, indices, dpi);
    }

    private static void rasterizePage(PdfDocument doc, int pageIndex, int dpi) {
        doc.convertPageToImage(pageIndex, dpi);
    }
}
