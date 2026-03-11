package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Scale PDF page content to fit a target paper size.
 *
 * <p>Unlike simply changing the MediaBox (which clips), this transforms all page
 * objects with a scale matrix via {@code FPDFPage_TransFormWithClip()} and then
 * updates the MediaBox to the target dimensions.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("a4-doc.pdf"))) {
 *     PdfPageScaler.scaleAll(doc, PaperSize.LETTER, FitMode.FIT_PAGE);
 *     doc.save(Path.of("letter-doc.pdf"));
 * }
 * }</pre>
 */
public final class PdfPageScaler {

    private PdfPageScaler() {}

    /** How to handle aspect ratio differences. */
    public enum FitMode {
        /** Scale uniformly to fit within target width. Bottom may have extra space. */
        FIT_WIDTH,
        /** Scale uniformly to fit within target height. Right may have extra space. */
        FIT_HEIGHT,
        /** Scale uniformly to fit entirely within target (no clipping). Preserves aspect ratio. */
        FIT_PAGE,
        /** Stretch non-uniformly to fill the entire target. May distort. */
        STRETCH
    }

    /** Scale a single page to the target size. */
    public static void scale(PdfDocument doc, int pageIndex,
                             PdfPosterizer.PaperSize target, FitMode mode) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            float srcW = page.size().width();
            float srcH = page.size().height();
            float tgtW = target.widthPt();
            float tgtH = target.heightPt();

            float sx, sy;
            switch (mode) {
                case FIT_WIDTH -> {
                    sx = tgtW / srcW;
                    sy = sx;
                }
                case FIT_HEIGHT -> {
                    sy = tgtH / srcH;
                    sx = sy;
                }
                case FIT_PAGE -> {
                    sx = Math.min(tgtW / srcW, tgtH / srcH);
                    sy = sx;
                }
                case STRETCH -> {
                    sx = tgtW / srcW;
                    sy = tgtH / srcH;
                }
                default -> throw new IllegalArgumentException("Unknown mode: " + mode);
            }

            // Center content within the target page
            float scaledW = srcW * sx;
            float scaledH = srcH * sy;
            float offsetX = (tgtW - scaledW) / 2f;
            float offsetY = (tgtH - scaledH) / 2f;

            try (Arena arena = Arena.ofConfined()) {
                // Build FS_MATRIX: [a=sx, b=0, c=0, d=sy, e=offsetX, f=offsetY]
                MemorySegment matrix = arena.allocate(PageEditBindings.FS_MATRIX_LAYOUT);
                matrix.set(ValueLayout.JAVA_FLOAT, 0, sx);        // a
                matrix.set(ValueLayout.JAVA_FLOAT, 4, 0f);        // b
                matrix.set(ValueLayout.JAVA_FLOAT, 8, 0f);        // c
                matrix.set(ValueLayout.JAVA_FLOAT, 12, sy);       // d
                matrix.set(ValueLayout.JAVA_FLOAT, 16, offsetX);  // e
                matrix.set(ValueLayout.JAVA_FLOAT, 20, offsetY);  // f

                // Build clip rect: full target area
                MemorySegment clip = arena.allocate(PageEditBindings.FS_RECTF_LAYOUT);
                clip.set(ValueLayout.JAVA_FLOAT, 0, 0f);          // left
                clip.set(ValueLayout.JAVA_FLOAT, 4, 0f);          // bottom
                clip.set(ValueLayout.JAVA_FLOAT, 8, tgtW);        // right
                clip.set(ValueLayout.JAVA_FLOAT, 12, tgtH);       // top

                int ok;
                try {
                    ok = (int) PageEditBindings.FPDFPage_TransFormWithClip.invokeExact(
                            rawPage, matrix, clip);
                } catch (Throwable t) {
                    throw new RuntimeException("FPDFPage_TransFormWithClip failed", t);
                }
                if (ok == 0) {
                    throw new RuntimeException("FPDFPage_TransFormWithClip returned 0");
                }
            }

            // Update page boxes to target dimensions
            try {
                PageEditBindings.FPDFPage_SetMediaBox.invokeExact(rawPage, 0f, 0f, tgtW, tgtH);
                PageEditBindings.FPDFPage_SetCropBox.invokeExact(rawPage, 0f, 0f, tgtW, tgtH);
            } catch (Throwable t) {
                throw new RuntimeException("Failed to set page boxes", t);
            }
        }
    }

    /** Scale all pages to the target paper size. */
    public static int scaleAll(PdfDocument doc, PdfPosterizer.PaperSize target, FitMode mode) {
        int count = doc.pageCount();
        for (int i = 0; i < count; i++) {
            scale(doc, i, target, mode);
        }
        return count;
    }
}
