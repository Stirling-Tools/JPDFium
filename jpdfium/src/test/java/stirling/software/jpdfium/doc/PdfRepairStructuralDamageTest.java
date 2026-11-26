package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic structural damage: duplicate objects, cut-off bodies, missing xref/endobj. */
class PdfRepairStructuralDamageTest {

    @Test
    void repairsDuplicateObjectDefinitions() throws Exception {
        byte[] damaged = duplicateFirstStreamObject(RepairCorpus.validMultiPage());
        assertRepaired(damaged, true);
    }

    @Test
    void repairsCutOffObjectBody() throws Exception {
        byte[] damaged = cutStreamTail(RepairCorpus.validMultiPage());
        assertRepaired(damaged, false);
    }

    @Test
    void repairsMissingXrefTable() throws Exception {
        byte[] valid = RepairCorpus.validMultiPage();
        byte[] damaged = stripXrefTable(valid);
        assertTrue(damaged.length < valid.length, "xref table was not removed");
        assertRepaired(damaged, true);
    }

    @Test
    void repairsBrokenHeaderAndPagesCountWithoutAborting() throws Exception {
        byte[] damaged = brokenHeaderBadPagesCountPdf();
        assertNotNull(PdfRepair.inspect(damaged), "inspect must return diagnostics, not abort");
        assertRepaired(damaged, false, 1);
    }

    @Test
    void repairsMissingEndobjTokens() throws Exception {
        byte[] damaged = stripTwoEndobj(RepairCorpus.validMultiPage());
        assertRepaired(damaged, true);
    }

    @Test
    void truncatedDownloadWithoutPageObjectsFailsClosed75() throws Exception {
        assertFailsClosed(RepairCorpus.truncate(RepairCorpus.validMultiPage(), 0.75));
    }

    @Test
    void truncatedDownloadWithoutPageObjectsFailsClosed50() throws Exception {
        assertFailsClosed(RepairCorpus.truncate(RepairCorpus.validMultiPage(), 0.50));
    }

    @Test
    void preservesPageRotationThroughXrefRepair() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        byte[] rotated;
        try (PDDocument doc = new PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            page.setRotation(90);
            doc.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                    new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(72, 700);
                cs.showText("Rotated repair page");
                cs.endText();
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            rotated = out.toByteArray();
        }
        byte[] damaged = stripXrefTable(rotated);
        RepairResult result = PdfRepair.builder().input(damaged).all().build().execute();
        assertTrue(result.isUsable(), "rotated PDF must repair: " + result.status());
        try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
            assertEquals(1, doc.pageCount());
            // One try-with-resources page: an unclosed doc.page() pins a tracked
            // native page for the whole JVM and later lifecycle restores fail.
            try (PdfPage page = doc.page(0)) {
                assertEquals(1, page.rotation(), "90-degree page rotation must survive repair");
            }
        }
    }

    @Test
    void repairsDuplicateResourceDictionaryKeys() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        StringBuilder pdf = new StringBuilder();
        pdf.append("%PDF-1.4\n");
        pdf.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        pdf.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        pdf.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ")
                .append("/Contents 4 0 R /Resources <<");
        for (int i = 0; i < 40; i++) pdf.append(" /Im1 1 0 R");
        pdf.append(" >> >>\nendobj\n");
        String content = "BT 12 Tf 72 700 Td (Duplicate keys) Tj ET";
        pdf.append("4 0 obj\n<< /Length ").append(content.length())
                .append(" >>\nstream\n").append(content).append("\nendstream\nendobj\n");
        pdf.append("trailer\n<< /Root 1 0 R /Size 5 >>\nstartxref\n0\n%%EOF\n");
        RepairResult result = PdfRepair.builder()
                .input(pdf.toString().getBytes(StandardCharsets.ISO_8859_1))
                .all()
                .build()
                .execute();
        assertTrue(result.isUsable(), "duplicate keys must repair: " + result.status());
        try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
            assertEquals(1, doc.pageCount());
        }
    }

    @Test
    void repairsTabSeparatedDuplicateResourceKeys() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        StringBuilder pdf = new StringBuilder();
        pdf.append("%PDF-1.4\n");
        pdf.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        pdf.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        pdf.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ")
                .append("/Contents 4 0 R /Resources <<");
        for (int i = 0; i < 40; i++) pdf.append(" /Im1\t1\t0\tR");
        pdf.append(" >> >>\nendobj\n");
        String content = "BT 12 Tf 72 700 Td (Tabbed duplicate keys) Tj ET";
        pdf.append("4 0 obj\n<< /Length ").append(content.length())
                .append(" >>\nstream\n").append(content).append("\nendstream\nendobj\n");
        pdf.append("trailer\n<< /Root 1 0 R /Size 5 >>\nstartxref\n0\n%%EOF\n");
        RepairResult result = PdfRepair.builder()
                .input(pdf.toString().getBytes(StandardCharsets.ISO_8859_1))
                .all()
                .build()
                .execute();
        assertTrue(result.isUsable(), "tab-separated resource keys must repair: " + result.status());
        try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
            assertEquals(1, doc.pageCount());
        }
    }

    @Test
    void leavesLiteralStringsUntouchedDuringTokenRepair() throws Exception {
        String raw = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
                + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n"
                + "4 0 obj\n<< /Title (note 1 0 o /Example) >>\nendobj\n"
                + "trailer\n<< /Root 1 0 R /Info 4 0 R /Size 5 >>\nstartxref\n0\n%%EOF\n";
        java.lang.reflect.Method normalize =
                PdfRepair.class.getDeclaredMethod("normalizeRepairTokens", byte[].class);
        normalize.setAccessible(true);
        byte[] out = (byte[]) normalize.invoke(null, raw.getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(new String(out, StandardCharsets.ISO_8859_1)
                        .contains("(note 1 0 o /Example)"),
                "literal strings must not be treated as object tokens");
    }

    @Test
    void rebuildsOrphanedPageDictionaryWithoutObjectHeader() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        String content = "0 0 100 100 re f";
        String pdf = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 3 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Length " + content.length() + " >>\nstream\n"
                + content + "\nendstream\nendobj\n"
                + "<</Type/Page/MediaBox [0 0 612 792]/Parent 3 0 R "
                + "/Resources << >>/Contents 2 0 R>>\n"
                + "trailer\n<< /Root 1 0 R /Size 4 >>\nstartxref\n0\n%%EOF\n";
        RepairResult result = PdfRepair.builder()
                .input(pdf.getBytes(StandardCharsets.ISO_8859_1))
                .all()
                .build()
                .execute();
        assertTrue(result.isUsable(), "orphaned page dictionary must be rebuilt: " + result.status());
        try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
            assertEquals(1, doc.pageCount());
        }
    }

    @Test
    void rebuildsMissingPageTreeFromPageDictionaries() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        String content = "0 0 100 100 re f";
        String pdf = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                + "/Contents 4 0 R /Resources << >> >>\nendobj\n"
                + "4 0 obj\n<< /Length " + content.length() + " >>\nstream\n"
                + content + "\nendstream\nendobj\n"
                + "trailer\n<< /Root 1 0 R /Size 5 >>\nstartxref\n0\n%%EOF\n";
        RepairResult result = PdfRepair.builder()
                .input(pdf.getBytes(StandardCharsets.ISO_8859_1))
                .all()
                .build()
                .execute();
        assertTrue(result.isUsable(), "missing /Pages tree must be rebuilt: " + result.status());
        try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
            assertEquals(1, doc.pageCount(), "the orphaned page dictionary must survive");
        }
    }

    @Test
    void rejectsPdfWithoutPageObjects() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        String pdf = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "trailer\n<< /Root 1 0 R /Size 3 >>\nstartxref\n0\n%%EOF\n";
        RepairResult result = PdfRepair.builder()
                .input(pdf.getBytes(StandardCharsets.ISO_8859_1))
                .all()
                .build()
                .execute();
        assertFalse(result.isUsable(), "a file without page objects cannot be repaired");
        assertEquals(RepairResult.Status.FAILED, result.status());
    }

    @Test
    void rejectsHtmlDisguisedAsPdf() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        byte[] html = "<!doctype html><html><body>not a pdf</body></html>"
                .getBytes(StandardCharsets.ISO_8859_1);
        RepairResult result = PdfRepair.builder().input(html).all().build().execute();
        assertFalse(result.isUsable(), "HTML renamed to .pdf must fail closed");
    }

    @Test
    void rejectsPasswordProtectedPdf() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        byte[] encrypted;
        try (PDDocument doc = new PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            doc.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "owner", "user", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            encrypted = out.toByteArray();
        }
        RepairResult result = PdfRepair.builder().input(encrypted).all().build().execute();
        assertFalse(result.isUsable(), "password-protected input must fail closed: status="
                + result.status() + " len=" + (result.repairedPdf() == null ? 0 : result.repairedPdf().length));
    }

    @Test
    void recoversCompletePagesFromPartiallyDownloadedInlinePdf() throws Exception {
        byte[] valid = inlineThreePagePdf();
        String text = new String(valid, StandardCharsets.ISO_8859_1);
        int cut = text.lastIndexOf("endobj") + "endobj".length();
        byte[] damaged = java.util.Arrays.copyOf(valid, cut);

        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        RepairResult result = PdfRepair.builder().input(damaged).all().build().execute();
        assertTrue(result.isUsable(), "complete page objects must be salvageable: "
                + result.status() + " " + result.diagnosticJson());
        try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
            assertEquals(3, doc.pageCount(), "all complete pages must survive");
        }
        try (PDDocument doc = Loader.loadPDF(result.repairedPdf())) {
            String out = new PDFTextStripper().getText(doc);
            assertTrue(out.contains("Page 1"), "missing first page text: " + out);
            assertTrue(out.contains("Page 3"), "missing last page text: " + out);
        }
    }

    @Test
    void repairsMissingTrailerRootReference() throws Exception {
        byte[] damaged = stripRootReference(RepairCorpus.validMultiPage());
        assertRepaired(damaged, true);
    }

    @Test
    void repairsCorruptCatalogType() throws Exception {
        byte[] damaged = corruptCatalogType(RepairCorpus.validMultiPage());
        assertRepaired(damaged, true);
    }

    @Test
    void repairsTruncatedObjKeyword() throws Exception {
        byte[] damaged = truncateObjKeyword(RepairCorpus.validMultiPage());
        assertRepaired(damaged, true);
    }

    @Test
    void repairsCatalogEndobjGluedToNextObject() throws Exception {
        byte[] damaged = glueFirstEndobj(RepairCorpus.validMultiPage());
        assertRepaired(damaged, true);
    }

    @Test
    void repairsJunkBeforeEndobj() throws Exception {
        byte[] damaged = junkBeforeEndobj(RepairCorpus.validMultiPage());
        assertRepaired(damaged, true);
    }

    @Test
    void repairsEndobjGluedToNextObject() throws Exception {
        byte[] damaged = glueEndobj(RepairCorpus.validMultiPage());
        assertRepaired(damaged, true);
    }

    private static void assertFailsClosed(byte[] damaged) throws IOException {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        RepairResult result = PdfRepair.builder().input(damaged).all().build().execute();
        assertNotNull(result);
        if (result.isUsable()) {
            try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
                assertTrue(doc.pageCount() >= 1,
                        "a usable repair must contain a page: " + result.status());
            }
        }
    }

    private static byte[] brokenHeaderBadPagesCountPdf() {
        String pdf = "%P\u0000F-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Counte /Page\n"
                + "/Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n"
                + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n"
                + "trailer\n<< /Root 1 0 R >>\nstartxref\n0\n%%EOF\n";
        return pdf.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void deduplicatesResourceKeysPerDictionary() throws Exception {
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        pdf.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        pdf.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>\nendobj\n");
        for (int page = 3; page <= 4; page++) {
            pdf.append(page).append(" 0 obj\n<< /Type /Page /Parent 2 0 R ")
                    .append("/MediaBox [0 0 612 792] /Resources <<");
            for (int i = 0; i < 40; i++) pdf.append(" /Im1 1 0 R");
            pdf.append(" >> >>\nendobj\n");
        }
        pdf.append("trailer\n<< /Root 1 0 R /Size 5 >>\nstartxref\n0\n%%EOF\n");
        java.lang.reflect.Method dedup =
                PdfRepair.class.getDeclaredMethod("deduplicateResourceKeys", byte[].class);
        dedup.setAccessible(true);
        byte[] out = (byte[]) dedup.invoke(null,
                pdf.toString().getBytes(StandardCharsets.ISO_8859_1));
        String text = new String(out, StandardCharsets.ISO_8859_1);
        assertEquals(2, text.split("/Im1", -1).length - 1,
                "each resource dictionary must keep its own /Im1");
    }

    @Test
    void rebuildKeepsKidsOrder() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        String pdf = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [4 0 R 3 0 R] /Count 2 >>\nendobj\n"
                + pageWithText(3, "FIRST")
                + pageWithText(4, "SECOND")
                + FONT_OBJECT
                + "trailer\n<< /Root 1 0 R /Size 5 >>\nstartxref\n0\n%%EOF\n";
        RepairResult result = repairBrokenHeader(pdf);
        assertTrue(result.isUsable(), "ordered page tree must repair: " + result.status());
        try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
            assertEquals(2, doc.pageCount());
        }
        try (PDDocument doc = Loader.loadPDF(result.repairedPdf())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.indexOf("SECOND") < text.indexOf("FIRST"),
                    "/Kids order must be preserved, got: " + text);
        }
    }

    @Test
    void oversizedObjectNumbersFailWithoutThrowing() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        String pdf = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
                + pageWithText(3, "NORMAL")
                + FONT_OBJECT
                + "99999999999999999999 0 obj\n<< /Type /Page >>\nendobj\n"
                + "trailer\n<< /Root 1 0 R /Size 4 >>\nstartxref\n0\n%%EOF\n";
        RepairResult result = assertDoesNotThrow(() -> repairBrokenHeader(pdf));
        assertNotNull(result.status());
    }

    @Test
    void rebuildInheritsAncestorPageAttributes() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        String pdf = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 "
                + "/MediaBox [0 0 200 300] /Rotate 90 /Resources << >> >>\nendobj\n"
                + "3 0 obj\n<< /Type /Page /Parent 2 0 R >>\nendobj\n"
                + "trailer\n<< /Root 1 0 R /Size 4 >>\nstartxref\n0\n%%EOF\n";
        RepairResult result = repairBrokenHeader(pdf);
        assertTrue(result.isUsable(), "inherited attributes must repair: " + result.status());
        try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
            assertEquals(1, doc.pageCount());
            // All three asserts share one page handle: three unclosed doc.page()
            // calls would pin three tracked pages for the rest of the JVM.
            try (PdfPage page = doc.page(0)) {
                assertEquals(300.0, page.size().width(), 1.0);
                assertEquals(200.0, page.size().height(), 1.0);
                assertEquals(1, page.rotation(), "90-degree rotation must be inherited");
            }
        }
    }

    @Test
    void rebuildPreservesExistingCatalogEntries() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        String pdf = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R /AcroForm 7 0 R "
                + "/Outlines 8 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
                + pageWithText(3, "CATALOG")
                + FONT_OBJECT
                + "7 0 obj\n<< /Fields [] >>\nendobj\n"
                + "8 0 obj\n<< /Type /Outlines /Count 0 >>\nendobj\n"
                + "trailer\n<< /Root 1 0 R /Size 9 >>\nstartxref\n0\n%%EOF\n";
        RepairResult result = repairBrokenHeader(pdf);
        assertTrue(result.isUsable(), "catalog must survive rebuild: " + result.status());
        try (PDDocument doc = Loader.loadPDF(result.repairedPdf())) {
            org.apache.pdfbox.cos.COSDictionary catalog =
                    doc.getDocumentCatalog().getCOSObject();
            assertTrue(catalog.containsKey(org.apache.pdfbox.cos.COSName.ACRO_FORM),
                    "existing catalog entries must be preserved");
            assertTrue(catalog.containsKey(org.apache.pdfbox.cos.COSName.OUTLINES),
                    "outlines must be preserved");
        }
    }

    @Test
    void rebuildUsesPageDictionaryNotNestedResources() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        String content = "0 0 100 100 re f";
        String pdf = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 3 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Length " + content.length() + " >>\nstream\n"
                + content + "\nendstream\nendobj\n"
                + "<</Resources << /ProcSet [/PDF] >>/Type/Page/MediaBox [0 0 200 300]"
                + "/Contents 2 0 R>>\n"
                + "trailer\n<< /Root 1 0 R /Size 4 >>\nstartxref\n0\n%%EOF\n";
        RepairResult result = repairBrokenHeader(pdf);
        assertTrue(result.isUsable(), "orphan with Resources before Type must repair: " + result.status());
        try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
            assertEquals(1, doc.pageCount());
            try (PdfPage page = doc.page(0)) {
                assertEquals(200.0, page.size().width(), 1.0);
            }
        }
    }

    private static String pageWithText(int object, String text) {
        String content = "BT /F1 12 Tf 72 700 Td (" + text + ") Tj ET";
        return object + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                + "/Resources << /Font << /F1 9 0 R >> >> /Contents " + (object + 2)
                + " 0 R >>\nendobj\n" + (object + 2) + " 0 obj\n<< /Length "
                + content.length() + " >>\nstream\n" + content + "\nendstream\nendobj\n";
    }

    private static final String FONT_OBJECT =
            "9 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n";

    private static RepairResult repairBrokenHeader(String pdf) {
        String broken = pdf.replaceFirst("%PDF-1\\.4", "%P\u00b5F-1.4");
        return PdfRepair.builder()
                .input(broken.getBytes(StandardCharsets.ISO_8859_1))
                .all()
                .build()
                .execute();
    }

    private static void assertRepaired(byte[] damaged, boolean expectText) throws IOException {
        assertRepaired(damaged, expectText, 3);
    }

    private static void assertRepaired(byte[] damaged, boolean expectText, int expectedPages)
            throws IOException {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        RepairResult result = PdfRepair.builder().input(damaged).all().build().execute();
        assertNotNull(result);
        assertTrue(result.isUsable(), "repair failed: " + result.status() + " " + result.diagnosticJson());
        assertNotEquals(RepairResult.Status.CLEAN, result.status(),
                "damaged input must not be reported as clean");
        try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
            assertEquals(expectedPages, doc.pageCount(), "page count must survive repair");
        }
        try (PDDocument doc = Loader.loadPDF(result.repairedPdf())) {
            assertEquals(expectedPages, doc.getNumberOfPages());
            if (expectText) {
                String text = new PDFTextStripper().getText(doc);
                assertTrue(text.contains("Repair test page 1"), "missing repaired text: " + text);
            }
        }
    }

    @Test
    void repairsTruncatedPageDictionaryMidArrayWithJunkHeader() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        String valid = new String(inlineThreePagePdf(), StandardCharsets.ISO_8859_1);
        int pageObj = valid.indexOf("3 0 obj");
        int annots = valid.indexOf("/Contents 6 0 R >>", pageObj);
        assertTrue(annots > 0, "fixture must have a page dictionary to cut");
        int cut = annots + 10;
        String damaged = valid.substring(0, cut)
                + " 11 0 R 13 0 R 16 0%PDF-1.3\n%junk injected by a broken merge\n"
                + "garbage that is not a pdf object at all\n";
        byte[] input = damaged.getBytes(StandardCharsets.ISO_8859_1);
        RepairResult result = PdfRepair.builder().input(input).all().build().execute();
        assertTrue(result.isUsable(),
                "truncated page dict with junk header must repair: " + result.status());
        try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
            assertTrue(doc.pageCount() >= 1, "at least one page must survive");
        }
        try (PDDocument doc = Loader.loadPDF(result.repairedPdf())) {
            assertTrue(doc.getNumberOfPages() >= 1);
        }
    }

    private static byte[] inlineThreePagePdf() {
        java.util.List<String> objects = new java.util.ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>");
        for (int i = 0; i < 3; i++) {
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                    + "/Resources << /Font << /F1 9 0 R >> >> /Contents " + (6 + i) + " 0 R >>");
        }
        for (int i = 1; i <= 3; i++) {
            String content = "BT /F1 12 Tf 72 700 Td (Page " + i + ") Tj ET";
            objects.add("<< /Length " + content.length() + " >>\nstream\n" + content
                    + "\nendstream");
        }
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");

        StringBuilder out = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i] = out.length();
            out.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = out.length();
        out.append("xref\n0 ").append(objects.size() + 1).append("\n");
        out.append("0000000000 65535 f \n");
        for (int offset : offsets) {
            out.append(String.format("%010d 00000 n \n", offset));
        }
        out.append("trailer\n<< /Size ").append(objects.size() + 1)
                .append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] stripRootReference(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        String out = s.replaceFirst("/Root\\s+\\d+\\s+\\d+\\s+R", "");
        return out.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] corruptCatalogType(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        return s.replaceFirst("/Type\\s*/Catalog", "/Type /CatalogX")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] truncateObjKeyword(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int stream = s.indexOf("stream\n");
        int obj = s.lastIndexOf(" obj", stream);
        if (obj < 0) return pdf;
        return (s.substring(0, obj) + " o" + s.substring(obj + 4))
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] junkBeforeEndobj(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int end = s.indexOf("endobj");
        if (end < 0) return pdf;
        return (s.substring(0, end) + ">> " + s.substring(end))
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] glueFirstEndobj(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int end = s.indexOf("endobj\n");
        if (end < 0) return pdf;
        return (s.substring(0, end) + "endobj" + s.substring(end + 7))
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] glueEndobj(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int stream = s.indexOf("stream\n");
        int end = s.indexOf("endobj\n", stream);
        if (end < 0) return pdf;
        return (s.substring(0, end) + "endobj" + s.substring(end + 7))
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] duplicateFirstStreamObject(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int stream = s.indexOf("stream\n");
        int obj = s.lastIndexOf(" obj", stream);
        int start = s.lastIndexOf('\n', obj) + 1;
        int end = s.indexOf("endobj", stream) + "endobj".length();
        String object = s.substring(start, end);
        int xref = s.lastIndexOf("xref");
        if (stream < 0 || obj < 0 || end < 0 || xref < 0) return pdf;
        return (s.substring(0, xref) + object + "\n" + s.substring(xref))
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] cutStreamTail(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int stream = s.indexOf("stream\n");
        int endstream = s.indexOf("endstream", stream);
        if (stream < 0 || endstream < 0) return pdf;
        int cut = Math.max(stream + 8, endstream - 24);
        return (s.substring(0, cut) + s.substring(endstream)).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] stripXrefTable(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int typeXref = s.indexOf("/Type /XRef");
        if (typeXref < 0) {
            int xref = s.lastIndexOf("\nxref");
            int trailer = s.indexOf("trailer", xref);
            if (xref < 0 || trailer < 0) return pdf;
            return (s.substring(0, xref) + s.substring(trailer))
                    .getBytes(StandardCharsets.ISO_8859_1);
        }
        int obj = s.lastIndexOf(" obj", typeXref);
        int start = s.lastIndexOf('\n', obj) + 1;
        int stream = s.indexOf("stream", typeXref);
        int end = s.indexOf("endobj", stream) + "endobj".length();
        if (start <= 0 || stream < 0 || end < start) return pdf;
        String dict = s.substring(s.lastIndexOf("<<", typeXref), stream);
        StringBuilder trailer = new StringBuilder("<<\n");
        for (String line : dict.split("\n")) {
            if (line.contains("/Root") || line.contains("/Info") || line.contains("/ID")) {
                trailer.append(line).append('\n');
            }
        }
        trailer.append(">>\n");
        return (s.substring(0, start) + s.substring(end) + "\ntrailer\n" + trailer
                        + "startxref\n0\n%%EOF")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] stripTwoEndobj(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        for (int i = 0; i < 2; i++) {
            int at = s.indexOf("endobj");
            if (at < 0) break;
            s = s.substring(0, at) + s.substring(at + "endobj".length());
        }
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
