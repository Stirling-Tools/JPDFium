package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Malformed PDF regression inputs from qpdf, OpenPreserve, and the Evil PDF library. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class MalformedPdfFixturesTest {

    @Test
    void emptyAndBrokenXrefInputsFailCleanly() throws Exception {
        assertThrows(JPDFiumException.class,
                () -> PdfDocument.open(fixture("qpdf/bad1.pdf")));
        try (PdfDocument doc = PdfDocument.open(fixture("pdfjs/xref_command_missing.pdf"))) {
            assertEquals(1, doc.pageCount());
        }
        try (PdfDocument doc = PdfDocument.open(fixture("cabinet/corruptionOneByteMissing.pdf"))) {
            assertEquals(1, doc.pageCount());
        }
    }

    @Test
    void recoverableQpdfXrefDamageStillOpens() throws Exception {
        try (PdfDocument doc = PdfDocument.open(fixture("qpdf/bad-xref.pdf"))) {
            assertEquals(1, doc.pageCount());
        }
        try (PdfDocument doc = PdfDocument.open(fixture("qpdf/bad-token-startxref.pdf"))) {
            assertEquals(1, doc.pageCount());
        }
        try (PdfDocument doc = PdfDocument.open(fixture("qpdf/append-xref-loop.pdf"))) {
            assertEquals(30, doc.pageCount());
        }
    }

    @Test
    void maliciousCorpusIsPresentButNeverParsedByDefault() throws Exception {
        for (String name : new String[]{"bug_325_a.pdf", "bug_544880.pdf", "bug_782596.pdf"}) {
            Path file = fixture("evil/" + name);
            assertTrue(Files.size(file) > 0, name);
        }
    }

    @Disabled("Evil PDF corpus is potentially malicious; parse only in an isolated sandbox")
    @Test
    void maliciousCorpusRequiresSandbox() {
        // Deliberately does not open the files. See README for the sandbox requirement.
    }

    private static Path fixture(String name) throws Exception {
        URL url = MalformedPdfFixturesTest.class.getResource("/pdfs/external/" + name);
        assertNotNull(url, name + " test resource missing");
        return Path.of(url.toURI());
    }
}
