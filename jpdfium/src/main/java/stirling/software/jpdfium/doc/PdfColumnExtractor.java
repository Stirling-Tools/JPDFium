package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.TextPageBindings;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.TextChar;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Multi-column text extraction.
 *
 * <p>Detects vertical gaps (gutters) in text layout and extracts text
 * column-by-column from left to right, preserving reading order within
 * each column.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("two-column.pdf"))) {
 *     var cols = PdfColumnExtractor.extract(doc, 0);
 *     for (int c = 0; c < cols.size(); c++) {
 *         System.out.printf("Column %d:%n%s%n", c, cols.get(c));
 *     }
 * }
 * }</pre>
 */
public final class PdfColumnExtractor {

    private PdfColumnExtractor() {}

    /** A single detected column with its bounds and text. */
    public record Column(float left, float right, String text) {}

    /**
     * Extract columns from a single page.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param gutterThreshold minimum horizontal gap (in pts) to be considered a column separator
     * @return ordered list of columns from left to right
     */
    public static List<Column> extract(PdfDocument doc, int pageIndex, float gutterThreshold) {
        PageText pageText = PdfTextExtractor.extractPage(doc, pageIndex);
        List<TextChar> chars = pageText.chars();
        if (chars.isEmpty()) return List.of();

        // Sort characters by x position to find gutters
        float pageWidth;
        try (PdfPage page = doc.page(pageIndex)) {
            pageWidth = page.size().width();
        }

        // Create histogram of character x-center positions using bins
        int binCount = Math.max(1, (int) (pageWidth / 2));
        int[] histogram = new int[binCount];
        for (TextChar ch : chars) {
            if (ch.unicode() <= 32) continue; // skip whitespace
            int bin = Math.min(binCount - 1, Math.max(0, (int) ((ch.x() + ch.width() / 2) / pageWidth * binCount)));
            histogram[bin]++;
        }

        // Detect gutters: consecutive empty bins wider than threshold
        List<float[]> gutters = new ArrayList<>();
        float binWidth = pageWidth / binCount;
        int gapStart = -1;
        for (int i = 0; i < binCount; i++) {
            if (histogram[i] == 0) {
                if (gapStart < 0) gapStart = i;
            } else {
                if (gapStart >= 0) {
                    float gapWidth = (i - gapStart) * binWidth;
                    float gapCenter = (gapStart + i) / 2.0f * binWidth;
                    if (gapWidth >= gutterThreshold && gapCenter > gutterThreshold && gapCenter < pageWidth - gutterThreshold) {
                        gutters.add(new float[]{gapCenter});
                    }
                    gapStart = -1;
                }
            }
        }

        // Build column boundaries
        List<float[]> colBounds = new ArrayList<>();
        float prevRight = 0f;
        for (float[] gutter : gutters) {
            colBounds.add(new float[]{prevRight, gutter[0]});
            prevRight = gutter[0];
        }
        colBounds.add(new float[]{prevRight, pageWidth});

        if (colBounds.size() <= 1) {
            // Single column - just return full text
            return List.of(new Column(0f, pageWidth, pageText.plainText()));
        }

        // Extract text for each column using bounded text extraction
        List<Column> columns = new ArrayList<>();
        float pageHeight;
        try (PdfPage page = doc.page(pageIndex)) {
            pageHeight = page.size().height();
        }

        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            MemorySegment textPage;
            try {
                textPage = (MemorySegment) TextPageBindings.FPDFText_LoadPage.invokeExact(rawPage);
            } catch (Throwable t) { return List.of(); }

            try {
                for (float[] bounds : colBounds) {
                    String text = getBoundedText(textPage, bounds[0], 0, bounds[1], pageHeight);
                    if (!text.isBlank()) {
                        columns.add(new Column(bounds[0], bounds[1], text.strip()));
                    }
                }
            } finally {
                try { TextPageBindings.FPDFText_ClosePage.invokeExact(textPage); }
                catch (Throwable ignored) {}
            }
        }

        return Collections.unmodifiableList(columns);
    }

    /** Extract with default 20pt gutter threshold. */
    public static List<Column> extract(PdfDocument doc, int pageIndex) {
        return extract(doc, pageIndex, 20f);
    }

    /** Extract columns from all pages. */
    public static List<List<Column>> extractAll(PdfDocument doc, float gutterThreshold) {
        List<List<Column>> result = new ArrayList<>();
        for (int i = 0; i < doc.pageCount(); i++) {
            result.add(extract(doc, i, gutterThreshold));
        }
        return Collections.unmodifiableList(result);
    }

    private static String getBoundedText(MemorySegment textPage,
                                         double left, double bottom, double right, double top) {
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            int charCount;
            try {
                charCount = (int) TextPageBindings.FPDFText_GetBoundedText.invokeExact(
                        textPage, left, top, right, bottom,
                        MemorySegment.NULL, 0);
            } catch (Throwable t) { return ""; }

            if (charCount <= 0) return "";

            var buf = arena.allocate(java.lang.foreign.ValueLayout.JAVA_SHORT, charCount + 1);
            try {
                TextPageBindings.FPDFText_GetBoundedText.invokeExact(
                        textPage, left, top, right, bottom,
                        buf, charCount + 1);
            } catch (Throwable t) { return ""; }

            char[] chars = new char[charCount];
            for (int i = 0; i < charCount; i++) {
                chars[i] = (char) buf.getAtIndex(java.lang.foreign.ValueLayout.JAVA_SHORT, i);
            }
            return new String(chars).replace("\0", "");
        }
    }
}
