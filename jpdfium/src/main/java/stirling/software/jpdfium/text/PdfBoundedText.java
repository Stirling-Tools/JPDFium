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
            int written = (int) TextPageBindings.FPDFText_GetBoundedText.invokeExact(
                    textPage, left, top, right, bottom, scratch.buffer(), capChars);
            if (written <= 0) return "";
            if (written > capChars) {
                MemorySegment largeBuf = scratch.ensureNativeBuffer((long) (written + 1) * 2);
                int actual = (int) TextPageBindings.FPDFText_GetBoundedText.invokeExact(
                        textPage, left, top, right, bottom, largeBuf, written + 1);
                if (actual <= 0) return "";
                return scratch.decodeWideChars(largeBuf, actual);
            }
            return scratch.decodeWideChars(scratch.buffer(), written);
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
            return scratch.decodeWideChars(buf, written + 1);
        } finally {
            try { TextPageBindings.FPDFText_ClosePage.invokeExact(textPage); }
            catch (Throwable t) { NativeRuntime.rethrowFatal(t); }
        }
    }
}
