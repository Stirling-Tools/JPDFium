package stirling.software.jpdfium.text;

import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.FfmHelper.Scratch;
import stirling.software.jpdfium.panama.TextPageBindings;

import java.lang.foreign.MemorySegment;

/**
 * Extract text within a bounded rectangle on a PDF page.
 *
 * <p>Uses FPDFText_GetBoundedText to retrieve text that falls within
 * a specified rectangular region of the page.
 */
public final class PdfBoundedText {

    /** Upper bound for growing the bounded-text buffer, in UTF-16 code units. */
    private static final int MAX_BOUNDED_TEXT_CHARS = 1 << 20;

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
            catch (Throwable t) { NativeRuntime.rethrowFatal(t); }
        }
    }

    /**
     * Extract text from an already-loaded text page.
     */
    public static String extractFromTextPage(MemorySegment textPage, double left, double top,
                                              double right, double bottom) {
        Scratch scratch = FfmHelper.scratch();
        int capChars = (int) (scratch.bufferSize() / 2);
        try {
            MemorySegment buf = scratch.buffer();
            int written = (int) TextPageBindings.FPDFText_GetBoundedText.invokeExact(
                    textPage, left, top, right, bottom, buf, capChars);
            if (written <= 0) return "";
            // With a buffer, GetBoundedText truncates to the buffer size, so a
            // filled buffer means the text may be incomplete: grow and re-query.
            while (written == capChars && capChars < MAX_BOUNDED_TEXT_CHARS) {
                capChars = (int) Math.min((long) capChars * 2, MAX_BOUNDED_TEXT_CHARS);
                buf = scratch.ensureNativeBuffer((long) capChars * 2);
                written = (int) TextPageBindings.FPDFText_GetBoundedText.invokeExact(
                        textPage, left, top, right, bottom, buf, capChars);
                if (written <= 0) return "";
            }
            return scratch.decodeWideChars(buf, written);
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

        try {
            int charCount;
            try {
                charCount = (int) TextPageBindings.FPDFText_CountChars.invokeExact(textPage);
            } catch (Throwable t) { return ""; }
            if (charCount <= 0) return "";

            Scratch scratch = FfmHelper.scratch();
            long neededBytes = (long) (charCount + 1) * 2;
            MemorySegment buf = scratch.ensureNativeBuffer(neededBytes);
            int written;
            try {
                written = (int) TextPageBindings.FPDFText_GetText.invokeExact(
                        textPage, 0, charCount, buf);
            } catch (Throwable t) { return ""; }
            if (written <= 0) return "";
            // GetText returns the count including the trailing NUL, which
            // decodeWideChars already strips.
            return scratch.decodeWideChars(buf, written);
        } finally {
            try { TextPageBindings.FPDFText_ClosePage.invokeExact(textPage); }
            catch (Throwable t) { NativeRuntime.rethrowFatal(t); }
        }
    }
}
