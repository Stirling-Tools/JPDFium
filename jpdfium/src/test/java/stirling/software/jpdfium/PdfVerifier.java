package stirling.software.jpdfium;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDFBox-based structural verification helpers for integration tests.
 *
 * <p>Every produced PDF is re-parsed by PDFBox (an independent parser) and
 * optionally text-checked, so a test asserting on {@link PdfDocument} output
 * never trusts the same library that produced the bytes.
 */
public final class PdfVerifier {

    private PdfVerifier() {}

    /** Page count of the PDF bytes, counted by PDFBox (independent parser). */
    public static int pageCount(byte[] pdf, String what) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        } catch (Exception e) {
            throw new AssertionError("PDFBox failed to parse " + what, e);
        }
    }

    /** Full text of a single page (0-based), extracted by PDFBox. */
    public static String pageText(byte[] pdf, int pageIndex, String what) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            return stripper.getText(doc);
        } catch (Exception e) {
            throw new AssertionError("PDFBox failed to extract text from " + what, e);
        }
    }

    /** Assert that a page carries non-whitespace text. */
    public static void assertNonEmptyText(byte[] pdf, int pageIndex, String what) {
        String text = pageText(pdf, pageIndex, what);
        assertFalse(text == null || text.isBlank(),
                "expected non-empty text on page " + pageIndex + " of " + what);
    }

    /** Assert that a page contains the given substring. */
    public static void assertContainsText(byte[] pdf, int pageIndex, String needle, String what) {
        String text = pageText(pdf, pageIndex, what);
        assertTrue(text.contains(needle),
                "expected '" + needle + "' on page " + pageIndex + " of " + what
                        + " but extracted: '" + text + "'");
    }
}
