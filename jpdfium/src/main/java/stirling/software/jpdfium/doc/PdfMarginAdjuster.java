package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Add whitespace margins (padding) around existing page content.
 *
 * <p>The inverse of auto-crop: expands the page and shifts content inward to
 * create margins for binding, annotations, or print bleed.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("input.pdf"))) {
 *     // Add 1 inch (72pt) on all sides
 *     PdfMarginAdjuster.addMargins(doc, 72, 72, 72, 72);
 *     doc.save(Path.of("padded.pdf"));
 * }
 * }</pre>
 */
public final class PdfMarginAdjuster {

    private PdfMarginAdjuster() {}

    /**
     * Add margins to a single page by shifting content and expanding the page.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param left      left margin in PDF points
     * @param bottom    bottom margin in PDF points
     * @param right     right margin in PDF points
     * @param top       top margin in PDF points
     */
    public static void addMargins(PdfDocument doc, int pageIndex,
                                   float left, float bottom, float right, float top) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            float srcW = page.size().width();
            float srcH = page.size().height();

            float newW = srcW + left + right;
            float newH = srcH + top + bottom;

            try (Arena arena = Arena.ofConfined()) {
                // Identity scale, translate by (left, bottom) 
                MemorySegment matrix = arena.allocate(PageEditBindings.FS_MATRIX_LAYOUT);
                matrix.set(ValueLayout.JAVA_FLOAT, 0, 1f);        // a
                matrix.set(ValueLayout.JAVA_FLOAT, 4, 0f);        // b
                matrix.set(ValueLayout.JAVA_FLOAT, 8, 0f);        // c
                matrix.set(ValueLayout.JAVA_FLOAT, 12, 1f);       // d
                matrix.set(ValueLayout.JAVA_FLOAT, 16, left);     // e
                matrix.set(ValueLayout.JAVA_FLOAT, 20, bottom);   // f

                MemorySegment clip = arena.allocate(PageEditBindings.FS_RECTF_LAYOUT);
                clip.set(ValueLayout.JAVA_FLOAT, 0, 0f);
                clip.set(ValueLayout.JAVA_FLOAT, 4, 0f);
                clip.set(ValueLayout.JAVA_FLOAT, 8, newW);
                clip.set(ValueLayout.JAVA_FLOAT, 12, newH);

                try {
                    int ok = (int) PageEditBindings.FPDFPage_TransFormWithClip.invokeExact(
                            rawPage, matrix, clip);
                    if (ok == 0) throw new RuntimeException("TransFormWithClip failed");
                } catch (RuntimeException re) { throw re; }
                catch (Throwable t) { throw new RuntimeException("TransFormWithClip failed", t); }
            }

            try {
                PageEditBindings.FPDFPage_SetMediaBox.invokeExact(rawPage, 0f, 0f, newW, newH);
                PageEditBindings.FPDFPage_SetCropBox.invokeExact(rawPage, 0f, 0f, newW, newH);
            } catch (Throwable t) {
                throw new RuntimeException("Failed to set page boxes", t);
            }
        }
    }

    /** Add equal margins on all sides of a single page. */
    public static void addMargins(PdfDocument doc, int pageIndex, float margin) {
        addMargins(doc, pageIndex, margin, margin, margin, margin);
    }

    /** Add margins to all pages. Returns number of pages modified. */
    public static int addMargins(PdfDocument doc, float left, float bottom,
                                  float right, float top) {
        int count = doc.pageCount();
        for (int i = 0; i < count; i++) {
            addMargins(doc, i, left, bottom, right, top);
        }
        return count;
    }

    /** Add equal margins to all pages. */
    public static int addMargins(PdfDocument doc, float margin) {
        return addMargins(doc, margin, margin, margin, margin);
    }

    /** Add a binding margin (extra space on the left side for hole-punch). */
    public static int addBindingMargin(PdfDocument doc, float bindingMarginPt) {
        return addMargins(doc, bindingMarginPt, 0f, 0f, 0f);
    }
}
