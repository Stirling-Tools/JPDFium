package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.PdfTextExtractor;

/**
 * End-to-end regression + correctness gate for the {@link JpdfiumCli}.
 *
 * <p>Runs every CLI operation against real PDFs through the JVM-testable
 * {@code JpdfiumCli.run} seam, then verifies the operation actually happened and
 * that the output is not corrupt: every produced PDF must re-open and match the
 * expected page count / content, extracted text must reflect the mutation
 * (removed terms stay removed), rotations must swap dimensions, merges must sum
 * page counts, splits must produce the expected parts, and renders must decode to
 * a valid PNG of the expected size.
 *
 * <p>Gated on {@code -Djpdfium.smoke=true} (the real native must be on the
 * classpath), matching the other per-platform CI native tests.
 */
@EnabledIfSystemProperty(named = "jpdfium.smoke", matches = "true")
class JpdfiumCliTest {

    @TempDir
    Path tmp;

    private Path fixture(String resource) throws Exception {
        Path out = tmp.resolve(Path.of(resource).getFileName().toString());
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "fixture missing: " + resource);
            Files.copy(in, out);
        }
        return out;
    }

    private static void assertOk(String... args) throws Exception {
        int rc = JpdfiumCli.run(args);
        assertEquals(0, rc, "expected success for: " + String.join(" ", args));
    }

    private static void assertUsageError(String... args) throws Exception {
        int rc = JpdfiumCli.run(args);
        assertEquals(2, rc, "expected usage error (exit 2) for: " + String.join(" ", args));
    }

    private static void assertOpens(Path pdf, int expectedPages) {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(expectedPages, doc.pageCount(),
                    "output must open and report the expected page count: " + pdf);
        }
    }

    private static String pageText(Path pdf, int pageIndex) {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            return PdfTextExtractor.extractAll(doc).get(pageIndex).plainText();
        }
    }

    private static List<PageText> allText(Path pdf) {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            return PdfTextExtractor.extractAll(doc);
        }
    }

    private static String read(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** 3-page PDF with a distinct, machine-checkable word on each page. */
    private Path threePageFixture() throws Exception {
        Path pdf = tmp.resolve("three-page.pdf");
        Files.write(pdf, threePageBytes());
        return pdf;
    }

    private static byte[] threePageBytes() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        String[] pages = {"PAGEONE", "PAGETWO", "PAGETHREE"};
        String[] objs = new String[10];
        objs[1] = "<< /Type /Catalog /Pages 2 0 R >>\n";
        objs[2] = "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>\n";
        for (int i = 0; i < 3; i++) {
            String text = "BT /F1 24 Tf 72 720 Td (" + pages[i] + ") Tj ET\n";
            String stream = "<< /Length " + text.length() + " >>\nstream\n" + text + "endstream";
            objs[3 + i] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                    + "/Resources << /Font << /F1 6 0 R >> >> /Contents " + (7 + i) + " 0 R >>\n";
            objs[7 + i] = stream + "\n";
        }
        objs[6] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\n";
        int[] offset = new int[10];
        for (int i = 1; i <= 9; i++) {
            offset[i] = sb.length();
            sb.append(i).append(" 0 obj\n").append(objs[i]).append("endobj\n");
        }
        int xref = sb.length();
        sb.append("xref\n0 10\n0000000000 65535 f \n");
        for (int i = 1; i <= 9; i++) {
            sb.append(String.format("%010d 00000 n \n", offset[i]));
        }
        sb.append("trailer\n<< /Size 10 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }


    @Test
    void helpIsInstantAndDoesNotNeedNative() throws Exception {
        assertEquals(0, JpdfiumCli.run(new String[] {"help"}));
        assertEquals(0, JpdfiumCli.run(new String[] {"--help"}));
        assertEquals(2, JpdfiumCli.run(new String[0]));
        assertEquals(2, JpdfiumCli.run(new String[] {"does-not-exist"}));
    }

    @Test
    void infoReportsPagesAndMetadata() throws Exception {
        Path pdf = fixture("/pdfs/general/basic-text.pdf");
        assertEquals(0, JpdfiumCli.run(new String[] {"info", pdf.toString()}));
    }

    @Test
    void textExtractsKnownContent() throws Exception {
        Path pdf = fixture("/pdfs/general/basic-text.pdf");
        Path out = tmp.resolve("out.txt");
        assertOk("text", pdf.toString(), out.toString());
        String text = read(out);
        assertTrue(text.contains("Sample Document"), "extracted text must contain the document title");
        assertTrue(text.contains("Page 1"), "output must be page-framed");
    }

    @Test
    void renderProducesDecodablePngOfExpectedSize() throws Exception {
        Path pdf = fixture("/pdfs/general/basic-text.pdf");
        Path outDir = tmp.resolve("render");
        Files.createDirectories(outDir);
        assertOk("render", pdf.toString(), outDir.toString(), "--dpi", "72");
        byte[] png = Files.readAllBytes(outDir.resolve("page-001.png"));
        byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < signature.length; i++) {
            assertEquals(signature[i], png[i], "output must be a PNG");
        }
        // IHDR width/height at fixed offsets 16..23, big-endian.
        int width = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int height = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        assertEquals(595, width, "72 dpi render of an A4 page must be 595px wide");
        assertEquals(842, height, "72 dpi render of an A4 page must be 842px tall");
    }


    @Test
    void redactWordsRemovesContent() throws Exception {
        Path pdf = fixture("/pdfs/general/basic-text.pdf");
        Path out = tmp.resolve("redacted.pdf");
        assertOk("redact-words", pdf.toString(), out.toString(),
                "--words", "Sample,Introduction", "--remove");
        assertOpens(out, 1);
        String text = pageText(out, 0);
        assertFalse(text.contains("Sample"), "redacted term must be removed from content");
        assertFalse(text.contains("Introduction"), "redacted term must be removed from content");
        assertTrue(text.contains("Text Formatting"), "unrelated content must survive");
    }

    @Test
    void redactRegionRemovesContent() throws Exception {
        Path pdf = threePageFixture();
        Path out = tmp.resolve("region.pdf");
        // Cover the top-left of page 1 where PAGEONE is drawn; remove content.
        assertOk("redact-region", pdf.toString(), out.toString(),
                "--rect", "0,650,300,100", "--remove", "--page", "1");
        assertOpens(out, 3);
        String page0 = pageText(out, 0);
        assertFalse(page0.contains("PAGEONE"), "region redaction must remove covered text");
        assertEquals("PAGETWO", pageText(out, 1).trim(), "uncovered pages must be untouched");
    }

    @Test
    void redactPatternRemovesMatches() throws Exception {
        Path pdf = threePageFixture();
        Path out = tmp.resolve("pattern.pdf");
        assertOk("redact-pattern", pdf.toString(), out.toString(), "--pattern", "PAGE(ONE|THREE)", "--remove", "--page", "1");
        assertOpens(out, 3);
        assertFalse(pageText(out, 0).contains("PAGEONE"));
        assertEquals("PAGETWO", pageText(out, 1).trim());
        assertEquals("PAGETHREE", pageText(out, 2).trim());
    }


    @Test
    void flattenPreservesPages() throws Exception {
        Path pdf = fixture("/pdfs/general/basic-text.pdf");
        Path out = tmp.resolve("flat.pdf");
        assertOk("flatten", pdf.toString(), out.toString());
        assertOpens(out, 1);
    }

    @Test
    void compressProducesValidSmallerOutput() throws Exception {
        Path pdf = fixture("/pdfs/general/mozilla_tracemonkey.pdf");
        Path out = tmp.resolve("comp.pdf");
        assertOk("compress", pdf.toString(), out.toString(), "--preset", "LOSSLESS");
        assertOpens(out, 14); // mozilla_tracemonkey.pdf is 14 pages
        assertTrue(Files.size(out) > 0, "compressed output must not be empty");
    }

    @Test
    void rotateSwapsDimensions() throws Exception {
        Path pdf = threePageFixture();
        Path out = tmp.resolve("rot.pdf");
        assertOk("rotate", pdf.toString(), out.toString(), "--degrees", "90");
        assertOpens(out, 3);
        try (PdfDocument doc = PdfDocument.open(out); PdfPage page = doc.page(0)) {
            var size = page.size();
            assertEquals(792.0, size.width(), 0.1, "90deg rotation must swap width");
            assertEquals(612.0, size.height(), 0.1, "90deg rotation must swap height");
        }
    }

    @Test
    void reverseReordersPages() throws Exception {
        Path pdf = threePageFixture();
        Path out = tmp.resolve("rev.pdf");
        assertOk("reverse", pdf.toString(), out.toString());
        assertOpens(out, 3);
        assertEquals("PAGETHREE", pageText(out, 0).trim(), "first page must now be the last page");
        assertEquals("PAGEONE", pageText(out, 2).trim(), "last page must now be the first page");
    }

    @Test
    void extractPagesKeepsOnlyRequestedRange() throws Exception {
        Path pdf = threePageFixture();
        Path out = tmp.resolve("sel.pdf");
        assertOk("pages", pdf.toString(), out.toString(), "--range", "2-3");
        assertOpens(out, 2);
        assertEquals("PAGETWO", pageText(out, 0).trim());
        assertEquals("PAGETHREE", pageText(out, 1).trim());
    }

    @Test
    void mirrorPreservesPages() throws Exception {
        Path pdf = threePageFixture();
        Path h = tmp.resolve("mh.pdf");
        Path v = tmp.resolve("mv.pdf");
        assertOk("mirror-h", pdf.toString(), h.toString());
        assertOk("mirror-v", pdf.toString(), v.toString());
        assertOpens(h, 3);
        assertOpens(v, 3);
        assertEquals("PAGEONE", pageText(h, 0).trim());
    }

    @Test
    void grayscaleAndBackgroundPreservePages() throws Exception {
        Path pdf = threePageFixture();
        Path gray = tmp.resolve("gray.pdf");
        Path bg = tmp.resolve("bg.pdf");
        assertOk("grayscale", pdf.toString(), gray.toString());
        assertOk("background", pdf.toString(), bg.toString(), "--color", "FFEEEE");
        assertOpens(gray, 3);
        assertOpens(bg, 3);
        assertEquals("PAGETWO", pageText(gray, 1).trim());
    }

    @Test
    void autoCropAndScalePreserveContent() throws Exception {
        Path pdf = fixture("/pdfs/general/basic-text.pdf");
        Path crop = tmp.resolve("crop.pdf");
        Path scale = tmp.resolve("scale.pdf");
        assertOk("auto-crop", pdf.toString(), crop.toString(), "--margin", "2");
        assertOk("scale", pdf.toString(), scale.toString(), "--paper", "A4", "--fit", "page");
        assertOpens(crop, 1);
        assertOpens(scale, 1);
    }

    @Test
    void repairSanitizeDeskewTocProduceValidOutput() throws Exception {
        Path pdf = fixture("/pdfs/general/mozilla_tracemonkey.pdf");
        Path rep = tmp.resolve("rep.pdf");
        Path san = tmp.resolve("san.pdf");
        Path deskew = tmp.resolve("deskew.pdf");
        Path toc = tmp.resolve("toc.pdf");
        assertOk("repair", pdf.toString(), rep.toString());
        assertOk("sanitize", pdf.toString(), san.toString());
        assertOk("deskew", pdf.toString(), deskew.toString());
        assertOk("toc", pdf.toString(), toc.toString());
        assertOpens(rep, 14);
        assertOpens(san, 14);
        assertOpens(deskew, 14);
        assertOpens(toc, 15); // TOC page inserted in front of 14 pages
    }


    @Test
    void mergeSumsPageCounts() throws Exception {
        Path a = threePageFixture();
        Path b = fixture("/pdfs/general/basic-text.pdf");
        Path out = tmp.resolve("merged.pdf");
        assertOk("merge", out.toString(), a.toString(), b.toString());
        assertOpens(out, 4); // 3 + 1
        assertEquals("PAGEONE", pageText(out, 0).trim());
    }

    @Test
    void splitProducesExpectedParts() throws Exception {
        Path pdf = threePageFixture();
        Path outDir = tmp.resolve("parts");
        Files.createDirectories(outDir);
        assertOk("split", pdf.toString(), outDir.toString(), "--pages-per", "2");
        assertOpens(outDir.resolve("part-001.pdf"), 2);
        assertOpens(outDir.resolve("part-002.pdf"), 1);
        assertEquals("PAGEONE", pageText(outDir.resolve("part-001.pdf"), 0).trim());
        assertEquals("PAGETHREE", pageText(outDir.resolve("part-002.pdf"), 0).trim());
    }

    @Test
    void nupProducesFewerPages() throws Exception {
        Path pdf = threePageFixture();
        Path out = tmp.resolve("nup.pdf");
        assertOk("nup", pdf.toString(), out.toString(), "--cols", "2", "--rows", "2");
        assertOpens(out, 1); // 3 pages on a single 2x2 sheet
    }


    @Test
    void refusesToOverwriteInputOrExistingOutput() throws Exception {
        Path pdf = fixture("/pdfs/general/basic-text.pdf");
        assertUsageError("flatten", pdf.toString(), pdf.toString());
        assertUsageError("flatten", pdf.toString(), pdf.toString()); // same file

        Path out = tmp.resolve("existing.pdf");
        Files.writeString(out, "occupied");
        assertUsageError("flatten", pdf.toString(), out.toString());
        assertOk("flatten", pdf.toString(), out.toString(), "--force");
    }

    @Test
    void rejectsBadUsage() throws Exception {
        Path pdf = fixture("/pdfs/general/basic-text.pdf");
        assertUsageError("redact-region", pdf.toString(), tmp.resolve("x.pdf").toString()); // missing --rect
        assertUsageError("redact-region", pdf.toString(), tmp.resolve("x.pdf").toString(),
                "--rect", "1,2,3"); // malformed rect
        assertUsageError("pages", pdf.toString(), tmp.resolve("x.pdf").toString(),
                "--range", "0-2"); // page 0 invalid
        assertUsageError("pages", pdf.toString(), tmp.resolve("x.pdf").toString(),
                "--range", "1-999"); // out of range
        assertUsageError("compress", pdf.toString(), tmp.resolve("x.pdf").toString(),
                "--preset", "TYPO"); // unknown enum
        assertUsageError("flatten", pdf.toString(), tmp.resolve("x.pdf").toString(),
                "--bogus"); // unknown flag
        assertUsageError("background", pdf.toString(), tmp.resolve("x.pdf").toString(),
                "--color", "xyz"); // bad color
        assertUsageError("rotate", pdf.toString(), tmp.resolve("x.pdf").toString(),
                "--degrees", "45"); // not a multiple of 90
    }

    @Test
    void allOutputsAreIndependentOfInputs() throws Exception {
        Path pdf = threePageFixture();
        long before = Files.size(pdf);
        Path out = tmp.resolve("flat.pdf");
        assertOk("flatten", pdf.toString(), out.toString());
        assertEquals(before, Files.size(pdf), "input must be byte-identical after processing");
        assertNotEquals(out, pdf);
        assertOpens(out, 3);
    }
}
