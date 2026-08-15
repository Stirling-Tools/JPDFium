package stirling.software.jpdfium.crop;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Input validation for the crop entry points.
 *
 * <p>NaN/Inf coordinates make every geometry comparison silently false, so a
 * non-finite crop rect could remove all content (or nothing) depending on how
 * each comparison falls out. The Java layer rejects degenerate rects before
 * any native call; these tests pass {@code null} for the document handle to
 * prove the validation runs FIRST (no document access, no native downcall),
 * so they run on every build without a native library.
 */
class CropInvalidInputTest {

    @Test
    void nanCoordinatesAreRejectedBeforeAnyNativeCall() {
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.cropAndRemoveContent(null, 0,
                        new Rect(Float.NaN, 0, 10, 10)));
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.cropAndRemoveContent(null, 0,
                        new Rect(0, Float.NaN, 10, 10)));
    }

    @Test
    void infiniteCoordinatesAreRejectedBeforeAnyNativeCall() {
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.cropAndRemoveContent(null, 0,
                        new Rect(0, 0, Float.POSITIVE_INFINITY, 10)));
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.cropAndRemoveContent(null, 0,
                        new Rect(0, 0, 10, Float.NEGATIVE_INFINITY)));
    }

    @Test
    void nonPositiveSizesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.cropAndRemoveContent(null, 0, new Rect(0, 0, 0, 10)));
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.cropAndRemoveContent(null, 0, new Rect(0, 0, -10, 10)));
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.cropAndRemoveContent(null, 0, new Rect(0, 0, 10, 0)));
    }

    @Test
    void cropAllValidatesUpFront() {
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.cropAllAndRemoveContent(null,
                        new Rect(Float.NaN, 0, 10, 10)));
        assertThrows(IllegalArgumentException.class,
                () -> PdfPageGeometry.cropAllAndRemoveContent(null, new Rect(0, 0, 0, 792)));
    }
}
