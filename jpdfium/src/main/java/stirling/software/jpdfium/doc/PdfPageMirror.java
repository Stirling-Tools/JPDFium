package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Mirror (flip) PDF page content horizontally or vertically.
 *
 * <p>Uses a transform matrix applied via {@code FPDFPage_TransFormWithClip()}.
 * Useful for iron-on transfers, fixing face-down scans, or booklet backs.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("input.pdf"))) {
 *     PdfPageMirror.mirrorHorizontalAll(doc);
 *     doc.save(Path.of("mirrored.pdf"));
 * }
 * }</pre>
 */
public final class PdfPageMirror {

    private PdfPageMirror() {}

    /**
     * Mirror a single page horizontally (left-right flip).
     */
    public static void mirrorHorizontal(PdfDocument doc, int pageIndex) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            float w = page.size().width();
            float h = page.size().height();

            try (Arena arena = Arena.ofConfined()) {
                // Horizontal flip: scale x by -1, translate by width
                MemorySegment matrix = arena.allocate(PageEditBindings.FS_MATRIX_LAYOUT);
                matrix.set(ValueLayout.JAVA_FLOAT, 0, -1f);   // a
                matrix.set(ValueLayout.JAVA_FLOAT, 4, 0f);    // b
                matrix.set(ValueLayout.JAVA_FLOAT, 8, 0f);    // c
                matrix.set(ValueLayout.JAVA_FLOAT, 12, 1f);   // d
                matrix.set(ValueLayout.JAVA_FLOAT, 16, w);    // e
                matrix.set(ValueLayout.JAVA_FLOAT, 20, 0f);   // f

                MemorySegment clip = arena.allocate(PageEditBindings.FS_RECTF_LAYOUT);
                clip.set(ValueLayout.JAVA_FLOAT, 0, 0f);
                clip.set(ValueLayout.JAVA_FLOAT, 4, 0f);
                clip.set(ValueLayout.JAVA_FLOAT, 8, w);
                clip.set(ValueLayout.JAVA_FLOAT, 12, h);

                try {
                    PageEditBindings.FPDFPage_TransFormWithClip.invokeExact(rawPage, matrix, clip);
                } catch (Throwable t) {
                    throw new RuntimeException("FPDFPage_TransFormWithClip failed", t);
                }
            }
        }
    }

    /**
     * Mirror a single page vertically (top-bottom flip).
     */
    public static void mirrorVertical(PdfDocument doc, int pageIndex) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            float w = page.size().width();
            float h = page.size().height();

            try (Arena arena = Arena.ofConfined()) {
                // Vertical flip: scale y by -1, translate by height
                MemorySegment matrix = arena.allocate(PageEditBindings.FS_MATRIX_LAYOUT);
                matrix.set(ValueLayout.JAVA_FLOAT, 0, 1f);    // a
                matrix.set(ValueLayout.JAVA_FLOAT, 4, 0f);    // b
                matrix.set(ValueLayout.JAVA_FLOAT, 8, 0f);    // c
                matrix.set(ValueLayout.JAVA_FLOAT, 12, -1f);  // d
                matrix.set(ValueLayout.JAVA_FLOAT, 16, 0f);   // e
                matrix.set(ValueLayout.JAVA_FLOAT, 20, h);    // f

                MemorySegment clip = arena.allocate(PageEditBindings.FS_RECTF_LAYOUT);
                clip.set(ValueLayout.JAVA_FLOAT, 0, 0f);
                clip.set(ValueLayout.JAVA_FLOAT, 4, 0f);
                clip.set(ValueLayout.JAVA_FLOAT, 8, w);
                clip.set(ValueLayout.JAVA_FLOAT, 12, h);

                try {
                    PageEditBindings.FPDFPage_TransFormWithClip.invokeExact(rawPage, matrix, clip);
                } catch (Throwable t) {
                    throw new RuntimeException("FPDFPage_TransFormWithClip failed", t);
                }
            }
        }
    }

    /** Mirror all pages horizontally. */
    public static int mirrorHorizontalAll(PdfDocument doc) {
        int n = doc.pageCount();
        for (int i = 0; i < n; i++) mirrorHorizontal(doc, i);
        return n;
    }

    /** Mirror all pages vertically. */
    public static int mirrorVerticalAll(PdfDocument doc) {
        int n = doc.pageCount();
        for (int i = 0; i < n; i++) mirrorVertical(doc, i);
        return n;
    }
}
