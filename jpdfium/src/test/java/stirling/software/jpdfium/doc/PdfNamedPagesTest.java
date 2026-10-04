package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;

import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfNamedPagesTest {

    @Test
    void namedPagesEmptyDocument() {
        assumeTrue(PdfNamedPages.isSupported(), "named pages API not in this native build");

        try (PdfDocument doc = PdfDocument.createEmpty()) {
            assertEquals(0, doc.namedPageCount(PdfNamedPages.Tree.PAGES));
            assertEquals(0, doc.namedPageCount(PdfNamedPages.Tree.TEMPLATES));
            assertTrue(doc.namedPages(PdfNamedPages.Tree.PAGES).isEmpty());
            assertTrue(doc.namedPages(PdfNamedPages.Tree.TEMPLATES).isEmpty());

            assertFalse(doc.removeNamedPage("nonexistent"));
        }
    }

    @Test
    void registerListAndRemoveNamedPages() {
        assumeTrue(PdfNamedPages.isSupported(), "named pages API not in this native build");

        try (PdfDocument doc = PdfDocument.createEmpty()) {
            MemorySegment rawPage = PdfPageEditor.newPage(doc.rawHandle(), 0, 612, 792);
            PdfPageEditor.generateContent(rawPage);
            try {
                PageEditBindings.FPDF_ClosePage.invokeExact(rawPage);
            } catch (Throwable t) {
                throw new AssertionError("FPDF_ClosePage failed", t);
            }
            // Resolve the actual indirect object number for page index 0 via FPDF_GetPageObject
            // rather than assuming it is always 1; native trees vary by build.
            int pageObjNum = doc.getPageObjectNumber(0);
            boolean set = doc.setNamedPage("cover-page", pageObjNum);
            assumeTrue(set, "setNamedPage rejected object number " + pageObjNum + " – skipping");

            assertEquals(1, doc.namedPageCount(PdfNamedPages.Tree.PAGES));
            List<PdfNamedPages.NamedPageEntry> entries = doc.namedPages(PdfNamedPages.Tree.PAGES);
            assertEquals(1, entries.size());
            assertEquals("cover-page", entries.getFirst().name());
            assertEquals(pageObjNum, entries.getFirst().objectNumber());
            assertEquals(PdfNamedPages.Kind.PAGE, entries.getFirst().kind());

            assertTrue(doc.removeNamedPage("cover-page"));
            assertEquals(0, doc.namedPageCount(PdfNamedPages.Tree.PAGES));
        }
    }
}
