package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.RenderBindings;
import stirling.software.jpdfium.panama.TextPageBindings;
import stirling.software.jpdfium.panama.FfmHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compare two PDF documents and produce a text diff and/or visual diff report.
 *
 * <p>Two-layer approach:
 * <ul>
 *   <li><b>Text diff:</b> Extracts text from both documents and runs a line-level
 *       diff to find insertions, deletions, and changes.</li>
 *   <li><b>Visual diff:</b> Renders each page pair at matching DPI, diffs the pixel
 *       buffers, and finds bounding boxes of changed regions.</li>
 * </ul>
 *
 * <pre>{@code
 * try (PdfDocument doc1 = PdfDocument.open(Path.of("v1.pdf"));
 *      PdfDocument doc2 = PdfDocument.open(Path.of("v2.pdf"))) {
 *     DiffResult diff = PdfDiff.compare(doc1, doc2);
 *     diff.textChanges().forEach(c ->
 *         System.out.printf("Page %d: %s '%s' -> '%s'%n",
 *             c.pageIndex(), c.type(), c.oldText(), c.newText()));
 * }
 * }</pre>
 */
public final class PdfDiff {

    private PdfDiff() {}

    /**
     * Type of text change.
     */
    public enum ChangeType {
        INSERTION,
        DELETION,
        MODIFICATION,
        PAGE_ADDED,
        PAGE_REMOVED
    }

    /**
     * A single text change between two documents.
     */
    public record TextChange(
            int pageIndex,
            ChangeType type,
            String oldText,
            String newText
    ) {}

    /**
     * A region of visual difference.
     */
    public record VisualChange(
            int pageIndex,
            Rect boundingBox,
            float diffPercentage
    ) {}

    /**
     * Complete diff result.
     */
    public record DiffResult(
            List<TextChange> textChanges,
            List<VisualChange> visualChanges,
            int pagesCompared
    ) {
        public String summary() {
            return String.format("%d pages compared, %d text changes, %d visual changes",
                    pagesCompared, textChanges.size(), visualChanges.size());
        }
    }

    /**
     * Compare two documents using text diff only.
     */
    public static DiffResult compare(PdfDocument doc1, PdfDocument doc2) {
        return compare(doc1, doc2, true, false, 150, 30);
    }

    /**
     * Compare two documents with options.
     *
     * @param doc1            first document
     * @param doc2            second document
     * @param textDiff        perform text diff
     * @param visualDiff      perform visual (pixel) diff
     * @param visualDpi       DPI for visual comparison
     * @param visualThreshold pixel difference threshold (0-255)
     * @return diff result
     */
    public static DiffResult compare(PdfDocument doc1, PdfDocument doc2,
                                      boolean textDiff, boolean visualDiff,
                                      int visualDpi, int visualThreshold) {
        List<TextChange> textChanges = new ArrayList<>();
        List<VisualChange> visualChanges = new ArrayList<>();

        int maxPages = Math.max(doc1.pageCount(), doc2.pageCount());
        int commonPages = Math.min(doc1.pageCount(), doc2.pageCount());

        // Compare common pages
        for (int i = 0; i < commonPages; i++) {
            if (textDiff) {
                textChanges.addAll(compareTextPage(doc1, doc2, i));
            }
            if (visualDiff) {
                VisualChange vc = compareVisualPage(doc1, doc2, i, visualDpi, visualThreshold);
                if (vc != null) {
                    visualChanges.add(vc);
                }
            }
        }

        // Report extra pages
        for (int i = commonPages; i < doc1.pageCount(); i++) {
            textChanges.add(new TextChange(i, ChangeType.PAGE_REMOVED,
                    extractPageText(doc1, i), ""));
        }
        for (int i = commonPages; i < doc2.pageCount(); i++) {
            textChanges.add(new TextChange(i, ChangeType.PAGE_ADDED,
                    "", extractPageText(doc2, i)));
        }

        return new DiffResult(
                Collections.unmodifiableList(textChanges),
                Collections.unmodifiableList(visualChanges),
                maxPages);
    }

    private static List<TextChange> compareTextPage(PdfDocument doc1, PdfDocument doc2,
                                                     int pageIndex) {
        String text1 = extractPageText(doc1, pageIndex);
        String text2 = extractPageText(doc2, pageIndex);

        if (text1.equals(text2)) return List.of();

        // Line-level diff
        String[] lines1 = text1.split("\n", -1);
        String[] lines2 = text2.split("\n", -1);

        // Simple LCS-based diff
        int[][] lcs = computeLCS(lines1, lines2);
        List<TextChange> lineChanges = extractDiffs(lcs, lines1, lines2, pageIndex);
        List<TextChange> changes = new ArrayList<>(lineChanges);

        return changes;
    }

    private static VisualChange compareVisualPage(PdfDocument doc1, PdfDocument doc2,
                                                    int pageIndex, int dpi, int threshold) {
        float pageWidth1, pageHeight1, pageWidth2, pageHeight2;
        try (PdfPage p1 = doc1.page(pageIndex)) {
            pageWidth1 = p1.size().width();
            pageHeight1 = p1.size().height();
        }
        try (PdfPage p2 = doc2.page(pageIndex)) {
            pageWidth2 = p2.size().width();
            pageHeight2 = p2.size().height();
        }

        float pageWidth = Math.max(pageWidth1, pageWidth2);
        float pageHeight = Math.max(pageHeight1, pageHeight2);
        int bmpW = (int) (pageWidth * dpi / 72.0f);
        int bmpH = (int) (pageHeight * dpi / 72.0f);
        if (bmpW <= 0 || bmpH <= 0) return null;

        byte[] pixels1 = renderPage(doc1, pageIndex, bmpW, bmpH);
        byte[] pixels2 = renderPage(doc2, pageIndex, bmpW, bmpH);

        if (pixels1 == null || pixels2 == null) return null;

        // Find bounding box of different pixels
        int minX = bmpW, minY = bmpH, maxX = -1, maxY = -1;
        int diffCount = 0;
        int totalPixels = bmpW * bmpH;

        for (int y = 0; y < bmpH; y++) {
            for (int x = 0; x < bmpW; x++) {
                int idx = (y * bmpW + x) * 4;
                if (idx + 2 >= pixels1.length || idx + 2 >= pixels2.length) continue;

                int db = Math.abs(Byte.toUnsignedInt(pixels1[idx]) - Byte.toUnsignedInt(pixels2[idx]));
                int dg = Math.abs(Byte.toUnsignedInt(pixels1[idx + 1]) - Byte.toUnsignedInt(pixels2[idx + 1]));
                int dr = Math.abs(Byte.toUnsignedInt(pixels1[idx + 2]) - Byte.toUnsignedInt(pixels2[idx + 2]));

                if (db > threshold || dg > threshold || dr > threshold) {
                    diffCount++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX < 0) return null; // identical

        float ptsPerPixel = 72.0f / dpi;
        Rect bounds = new Rect(
                minX * ptsPerPixel,
                (bmpH - 1 - maxY) * ptsPerPixel,
                (maxX - minX + 1) * ptsPerPixel,
                (maxY - minY + 1) * ptsPerPixel
        );
        float pct = (float) diffCount / totalPixels * 100f;

        return new VisualChange(pageIndex, bounds, pct);
    }

    private static byte[] renderPage(PdfDocument doc, int pageIndex, int bmpW, int bmpH) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            MemorySegment bitmap;
            try {
                bitmap = (MemorySegment) RenderBindings.FPDFBitmap_Create.invokeExact(bmpW, bmpH, 0);
            } catch (Throwable t) { return null; }
            if (bitmap.equals(MemorySegment.NULL)) return null;

            try {
                try { RenderBindings.FPDFBitmap_FillRect.invokeExact(bitmap, 0, 0, bmpW, bmpH, 0xFFFFFFFFL); }
                catch (Throwable t) { return null; }

                int flags = RenderBindings.FPDF_PRINTING;
                try { RenderBindings.FPDF_RenderPageBitmap.invokeExact(bitmap, rawPage, 0, 0, bmpW, bmpH, 0, flags); }
                catch (Throwable t) { return null; }

                MemorySegment bufferPtr;
                try { bufferPtr = (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap); }
                catch (Throwable t) { return null; }

                int stride;
                try { stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap); }
                catch (Throwable t) { return null; }

                MemorySegment buffer = bufferPtr.reinterpret((long) stride * bmpH);

                // Copy to byte array (stride may differ from bmpW*4)
                byte[] pixels = new byte[bmpW * bmpH * 4];
                for (int y = 0; y < bmpH; y++) {
                    MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, (long) y * stride,
                            pixels, y * bmpW * 4, bmpW * 4);
                }
                return pixels;
            } finally {
                try { PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap); }
                catch (Throwable ignored) {}
            }
        }
    }

    static String extractPageText(PdfDocument doc, int pageIndex) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            MemorySegment textPage;
            try {
                textPage = (MemorySegment) TextPageBindings.FPDFText_LoadPage.invokeExact(rawPage);
            } catch (Throwable t) { return ""; }
            if (textPage.equals(MemorySegment.NULL)) return "";

            try {
                int charCount;
                try {
                    charCount = (int) TextPageBindings.FPDFText_CountChars.invokeExact(textPage);
                } catch (Throwable t) { return ""; }
                if (charCount <= 0) return "";

                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment buf = arena.allocate((long) (charCount + 1) * 2);
                    int actual;
                    try {
                        actual = (int) TextPageBindings.FPDFText_GetText.invokeExact(
                                textPage, 0, charCount, buf);
                    } catch (Throwable t) { return ""; }
                    return FfmHelper.fromWideString(buf, (long) actual * 2);
                }
            } finally {
                try { TextPageBindings.FPDFText_ClosePage.invokeExact(textPage); }
                catch (Throwable ignored) {}
            }
        }
    }

    // Simple LCS (Longest Common Subsequence) implementation
    private static int[][] computeLCS(String[] a, String[] b) {
        int m = a.length, n = b.length;
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a[i - 1].equals(b[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp;
    }

    private static List<TextChange> extractDiffs(int[][] dp, String[] a, String[] b, int pageIndex) {
        List<TextChange> changes = new ArrayList<>();
        int i = a.length, j = b.length;

        // Backtrack through LCS matrix
        List<Object[]> ops = new ArrayList<>(); // {type, oldLine, newLine}
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a[i - 1].equals(b[j - 1])) {
                i--; j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                ops.add(new Object[]{ChangeType.INSERTION, "", b[j - 1]});
                j--;
            } else if (i > 0) {
                ops.add(new Object[]{ChangeType.DELETION, a[i - 1], ""});
                i--;
            }
        }

        // Reverse and merge adjacent deletions+insertions into modifications
        Collections.reverse(ops);
        for (int k = 0; k < ops.size(); k++) {
            ChangeType type = (ChangeType) ops.get(k)[0];
            String oldText = (String) ops.get(k)[1];
            String newText = (String) ops.get(k)[2];

            // Try to merge deletion followed by insertion into modification
            if (type == ChangeType.DELETION && k + 1 < ops.size()
                    && ops.get(k + 1)[0] == ChangeType.INSERTION) {
                changes.add(new TextChange(pageIndex, ChangeType.MODIFICATION,
                        oldText, (String) ops.get(k + 1)[2]));
                k++; // skip the insertion
            } else {
                changes.add(new TextChange(pageIndex, type, oldText, newText));
            }
        }

        return changes;
    }
}
