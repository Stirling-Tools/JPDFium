package stirling.software.jpdfium.text;

import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.TextPageBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Extract text within a bounded rectangle on a PDF page.
 *
 * <p>Uses FPDFText_GetBoundedText to retrieve text that falls within
 * a specified rectangular region of the page.
 */
public final class PdfBoundedText {

    private PdfBoundedText() {}

    /**
     * Extract text within the given rectangle.
     *
     * @param rawPage raw FPDF_PAGE segment
     * @param left    left edge in page coordinates
     * @param top     top edge in page coordinates
     * @param right   right edge in page coordinates
     * @param bottom  bottom edge in page coordinates
     * @return the text within the bounded region
     */
    public static String extract(MemorySegment rawPage, double left, double top,
                                  double right, double bottom) {
        MemorySegment textPage;
        try {
            textPage = (MemorySegment) TextPageBindings.FPDFText_LoadPage.invokeExact(rawPage);
        } catch (Throwable t) { return ""; }
        if (textPage.equals(MemorySegment.NULL)) return "";

        try {
            return extractFromTextPage(textPage, left, top, right, bottom);
        } finally {
            try { TextPageBindings.FPDFText_ClosePage.invokeExact(textPage); }
            catch (Throwable ignored) {}
        }
    }

    /**
     * Extract text from an already-loaded text page.
     */
    public static String extractFromTextPage(MemorySegment textPage, double left, double top,
                                              double right, double bottom) {
        try (Arena arena = Arena.ofConfined()) {
            // First call: get required buffer size
            int charCount = (int) TextPageBindings.FPDFText_GetBoundedText.invokeExact(
                    textPage, left, top, right, bottom, MemorySegment.NULL, 0);
            if (charCount <= 0) return "";

            // Allocate buffer (charCount includes null terminator, each char is 2 bytes)
            MemorySegment buf = arena.allocate((long) (charCount + 1) * 2);
            int written = (int) TextPageBindings.FPDFText_GetBoundedText.invokeExact(
                    textPage, left, top, right, bottom, buf, charCount + 1);
            if (written <= 0) return "";
            return FfmHelper.fromWideString(buf, (long) written * 2);
        } catch (Throwable t) { return ""; }
    }

    /**
     * Extract all text from the page using the full page bounds.
     */
    public static String extractAll(MemorySegment rawPage) {
        MemorySegment textPage;
        try {
            textPage = (MemorySegment) TextPageBindings.FPDFText_LoadPage.invokeExact(rawPage);
        } catch (Throwable t) { return ""; }
        if (textPage.equals(MemorySegment.NULL)) return "";

        try (Arena arena = Arena.ofConfined()) {
            int charCount;
            try {
                charCount = (int) TextPageBindings.FPDFText_CountChars.invokeExact(textPage);
            } catch (Throwable t) { return ""; }
            if (charCount <= 0) return "";

            MemorySegment buf = arena.allocate((long) (charCount + 1) * 2);
            int written;
            try {
                written = (int) TextPageBindings.FPDFText_GetText.invokeExact(
                        textPage, 0, charCount, buf);
            } catch (Throwable t) { return ""; }
            if (written <= 0) return "";
            return FfmHelper.fromWideString(buf, (long) written * 2);
        } finally {
            try { TextPageBindings.FPDFText_ClosePage.invokeExact(textPage); }
            catch (Throwable ignored) {}
        }
    }
}
