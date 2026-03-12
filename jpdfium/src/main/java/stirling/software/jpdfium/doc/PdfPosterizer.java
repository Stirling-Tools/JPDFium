package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.transform.PdfPageBoxes;

import java.lang.foreign.MemorySegment;

/**
 * Posterize PDF pages by splitting them into a grid of tiles.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>Grid-based</b>: split into xFactor x yFactor tiles with optional overlap</li>
 *   <li><b>Target-size</b>: automatically compute the grid so each tile fits a target
 *       paper size (A4, A3, A5, Letter, Legal, or custom dimensions)</li>
 * </ul>
 *
 * <pre>{@code
 * // Grid-based: 2x2 tiles
 * PdfPosterizer.posterize(doc, 2, 2, 0);
 *
 * // Target-size: tiles that print on A3 sheets
 * PdfPosterizer.posterize(doc, PaperSize.A3, 18.0f);
 *
 * // Custom target: tiles that print on 20cm x 30cm sheets
 * PdfPosterizer.posterize(doc, PaperSize.ofCm(20, 30), 10.0f);
 * }</pre>
 */
public final class PdfPosterizer {

    private PdfPosterizer() {}

    /** Standard and custom paper sizes for target-size posterization. */
    public record PaperSize(float widthPt, float heightPt, String name) {
        /** A5: 148 x 210 mm */
        public static final PaperSize A5 = new PaperSize(419.53f, 595.28f, "A5");
        /** A4: 210 x 297 mm */
        public static final PaperSize A4 = new PaperSize(595.28f, 841.89f, "A4");
        /** A3: 297 x 420 mm */
        public static final PaperSize A3 = new PaperSize(841.89f, 1190.55f, "A3");
        /** A2: 420 x 594 mm */
        public static final PaperSize A2 = new PaperSize(1190.55f, 1683.78f, "A2");
        /** A1: 594 x 841 mm */
        public static final PaperSize A1 = new PaperSize(1683.78f, 2383.94f, "A1");
        /** A0: 841 x 1189 mm */
        public static final PaperSize A0 = new PaperSize(2383.94f, 3370.39f, "A0");
        /** US Letter: 8.5 x 11 in */
        public static final PaperSize LETTER = new PaperSize(612f, 792f, "Letter");
        /** US Legal: 8.5 x 14 in */
        public static final PaperSize LEGAL = new PaperSize(612f, 1008f, "Legal");
        /** US Tabloid: 11 x 17 in */
        public static final PaperSize TABLOID = new PaperSize(792f, 1224f, "Tabloid");

        /** Create a custom paper size from centimeters. */
        public static PaperSize ofCm(float widthCm, float heightCm) {
            return new PaperSize(widthCm * 72f / 2.54f, heightCm * 72f / 2.54f,
                    String.format("%.1fcm x %.1fcm", widthCm, heightCm));
        }

        /** Create a custom paper size from inches. */
        public static PaperSize ofInch(float widthIn, float heightIn) {
            return new PaperSize(widthIn * 72f, heightIn * 72f,
                    String.format("%.1fin x %.1fin", widthIn, heightIn));
        }
    }

    /**
     * Posterize all pages to fit a target paper size.
     *
     * <p>Automatically computes the number of columns and rows needed so that
     * each tile fits within the target paper dimensions.
     *
     * @param doc     open PDF document
     * @param target  target paper size for each tile
     * @param overlap overlap margin between tiles in PDF points
     */
    public static void posterize(PdfDocument doc, PaperSize target, float overlap) {
        if (doc.pageCount() == 0) return;

        // Use the first page to determine grid size
        float pageWidth, pageHeight;
        try (PdfPage page = doc.page(0)) {
            pageWidth = page.size().width();
            pageHeight = page.size().height();
        }

        int cols = Math.max(1, (int) Math.ceil(pageWidth / target.widthPt()));
        int rows = Math.max(1, (int) Math.ceil(pageHeight / target.heightPt()));

        posterize(doc, cols, rows, overlap);
    }

    /**
     * Posterize all pages into a grid of tiles.
     *
     * @param doc     open PDF document
     * @param xFactor number of horizontal tiles (columns)
     * @param yFactor number of vertical tiles (rows)
     * @param overlap overlap margin between tiles in PDF points
     */
    public static void posterize(PdfDocument doc, int xFactor, int yFactor, float overlap) {
        if (xFactor < 1 || yFactor < 1) {
            throw new IllegalArgumentException("xFactor and yFactor must be >= 1");
        }
        if (xFactor == 1 && yFactor == 1) return; // no-op

        int originalPageCount = doc.pageCount();
        MemorySegment rawDoc = doc.rawHandle();

        for (int p = 0; p < originalPageCount; p++) {
            float pageWidth, pageHeight;
            try (PdfPage page = doc.page(p)) {
                pageWidth = page.size().width();
                pageHeight = page.size().height();
            }

            // Calculate tile dimensions
            float tileWidth = (pageWidth + (xFactor - 1) * overlap) / xFactor;
            float tileHeight = (pageHeight + (yFactor - 1) * overlap) / yFactor;

            // Generate tiles: top-to-bottom, left-to-right
            for (int row = 0; row < yFactor; row++) {
                for (int col = 0; col < xFactor; col++) {
                    // Import the original page to the end of the document
                    boolean ok = PdfPageImporter.importPagesByIndex(rawDoc, rawDoc,
                            new int[]{p}, doc.pageCount());
                    if (!ok) throw new RuntimeException("Failed to import page " + p);

                    int newPageIndex = doc.pageCount() - 1;

                    // Calculate tile coordinates in PDF coordinate space
                    float left = col * (tileWidth - overlap);
                    float top = pageHeight - (row * (tileHeight - overlap));
                    float right = left + tileWidth;
                    float bottom = top - tileHeight;

                    // Clamp to page bounds
                    left = Math.max(0, left);
                    bottom = Math.max(0, bottom);
                    right = Math.min(pageWidth, right);
                    top = Math.min(pageHeight, top);

                    Rect tileRect = new Rect(left, bottom, right - left, top - bottom);

                    try (PdfPage newPage = doc.page(newPageIndex)) {
                        PdfPageBoxes.setCropBox(newPage.rawHandle(), tileRect);
                        PdfPageBoxes.setMediaBox(newPage.rawHandle(), tileRect);
                    }
                }
            }
        }

        // Remove the original pages from the start of the document
        for (int p = originalPageCount - 1; p >= 0; p--) {
            try {
                PageEditBindings.FPDFPage_Delete.invokeExact(rawDoc, p);
            } catch (Throwable t) { throw new RuntimeException("FPDFPage_Delete failed", t); }
        }
    }
}
