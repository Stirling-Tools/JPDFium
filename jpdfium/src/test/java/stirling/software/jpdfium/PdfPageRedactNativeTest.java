package stirling.software.jpdfium;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.PdfAnnotationBuilder;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfPageRedactNativeTest {

    @Test
    void testDirectNativeRedactInRect() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "Direct native text redaction requires real PDFium native library");
        byte[] pdfBytes = SyntheticPdfFactory.createDiverse(1);
        byte[] savedBytes;
        try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
            try (PdfPage page = doc.page(0)) {
                String initialText = page.extractText();
                assertTrue(initialText.contains("Synthetic corpus page 1"));

                // Redact the region containing "Synthetic corpus page 1"
                // The text is placed at (72, 700) with font size 24
                Rect redactArea = new Rect(70, 690, 350, 40);
                boolean ok = page.redactInRect(redactArea);
                assertTrue(ok);

                // Text should now be permanently removed in PDFium
                String afterRedact = page.extractText();
                assertFalse(afterRedact.contains("Synthetic corpus page 1"));
            }
            savedBytes = doc.saveBytes();
        }

        // Verify with independent PDFBox parser:
        // 1. Structure is a valid PDF
        assertNotNull(savedBytes);
        assertEquals(1, PdfVerifier.pageCount(savedBytes, "redacted doc"));
        // 2. The redacted text is completely gone from the underlying content streams
        String pdfBoxText = PdfVerifier.pageText(savedBytes, 0, "redacted doc");
        assertFalse(pdfBoxText.contains("Synthetic corpus page 1"));
    }

    @Test
    void testApplyAnnotationRedactions() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "Applying annotation redactions requires real PDFium native library");
        byte[] pdfBytes = SyntheticPdfFactory.createDiverse(1);
        byte[] savedBytes;
        try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
            try (PdfPage page = doc.page(0)) {
                // Add a REDACT annotation over the rotated marker
                PdfAnnotationBuilder.on(page.rawHandle())
                        .type(AnnotationType.REDACT)
                        .rect(new Rect(50, 380, 200, 50))
                        .overlayText("REDACTED")
                        .build();

                // Verify annotation exists
                var annots = page.annotations();
                assertFalse(annots.isEmpty());
                boolean hasRedact = annots.stream().anyMatch(a -> a.type() == AnnotationType.REDACT);
                assertTrue(hasRedact);

                // Apply all redactions natively
                boolean applied = page.applyRedactions();
                assertTrue(applied);

                // The REDACT annotation itself is consumed and removed
                var remaining = page.annotations();
                boolean stillHasRedact = remaining.stream().anyMatch(a -> a.type() == AnnotationType.REDACT);
                assertFalse(stillHasRedact);
            }
            savedBytes = doc.saveBytes();
        }

        // Verify valid PDF produced via PDFBox
        assertNotNull(savedBytes);
        assertEquals(1, PdfVerifier.pageCount(savedBytes, "applied redactions doc"));
    }

    @Test
    void testStubModeGracefulDegradation() throws Exception {
        assumeTrue(NativeRuntime.isStub(), "Stub mode specific verification");
        byte[] pdfBytes = SyntheticPdfFactory.createDiverse(1);
        try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
            try (PdfPage page = doc.page(0)) {
                assertThrows(UnsupportedOperationException.class, () -> page.redactInRect(new Rect(10, 10, 100, 100)));
                assertThrows(UnsupportedOperationException.class, page::applyRedactions);
            }
        }
    }

    /**
     * A Type0 font whose ToUnicode CMap puts TWO bfchar entries on ONE line
     * (spec-nonconforming but common in the wild). Redacting one drawn code
     * must rebuild the CMap from the surviving entry: the old line-index
     * parser silently dropped the second one-line entry, so the surviving
     * glyph lost its Unicode mapping during redaction.
     */
    @Test
    void redactionKeepsToUnicodeEntriesSharingALine() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "redaction ToUnicode rebuild requires real PDFium native library");
        byte[] pdf = type0PdfWithTwoBfcharsOnOneLine();
        byte[] saved;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            // The opt-in sanitize stage is what rebuilds /ToUnicode from the
            // parsed entries; without it the original stream passes through.
            doc.setSanitizeOnSave(true);
            try (PdfPage page = doc.page(0)) {
                String before = page.extractText();
                assertTrue(before.contains("A") && before.contains("B"),
                        "both glyphs must map through ToUnicode: " + before);
                // Redact only the left glyph ("A" at x=72); "B" at x=200 survives.
                assertTrue(page.redactInRect(new Rect(60, 680, 100, 40)));
                String after = page.extractText();
                assertFalse(after.contains("A"), "redacted glyph must be gone: " + after);
                assertTrue(after.contains("B"),
                        "the surviving glyph must keep its rebuilt ToUnicode mapping: " + after);
            }
            saved = doc.saveBytes();
        }
        assertNotNull(saved);
        String pdfBoxText = PdfVerifier.pageText(saved, 0, "partial redact doc");
        assertFalse(pdfBoxText.contains("A"));
        assertTrue(pdfBoxText.contains("B"),
                "saved document must keep the surviving glyph's text mapping");
    }

    /** Builds a single page drawing two Identity-H CIDs (1 -> 'A' at x=72,
     *  2 -> 'B' at x=200) whose ToUnicode CMap declares both bfchar entries on
     *  one shared line, as broken producers in the wild do. */
    private static byte[] type0PdfWithTwoBfcharsOnOneLine() throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            pdf.addPage(page);
            org.apache.pdfbox.cos.COSDictionary descendant = new org.apache.pdfbox.cos.COSDictionary();
            descendant.setItem(org.apache.pdfbox.cos.COSName.TYPE,
                    org.apache.pdfbox.cos.COSName.getPDFName("Font"));
            descendant.setItem(org.apache.pdfbox.cos.COSName.SUBTYPE,
                    org.apache.pdfbox.cos.COSName.getPDFName("CIDFontType2"));
            descendant.setItem(org.apache.pdfbox.cos.COSName.BASE_FONT,
                    org.apache.pdfbox.cos.COSName.getPDFName("LiberationSans"));
            org.apache.pdfbox.cos.COSDictionary sysInfo = new org.apache.pdfbox.cos.COSDictionary();
            sysInfo.setItem(org.apache.pdfbox.cos.COSName.getPDFName("Registry"),
                    org.apache.pdfbox.cos.COSName.getPDFName("Adobe"));
            sysInfo.setItem(org.apache.pdfbox.cos.COSName.getPDFName("Ordering"),
                    org.apache.pdfbox.cos.COSName.getPDFName("Identity"));
            sysInfo.setItem(org.apache.pdfbox.cos.COSName.getPDFName("Supplement"),
                    org.apache.pdfbox.cos.COSInteger.get(0));
            descendant.setItem(org.apache.pdfbox.cos.COSName.getPDFName("CIDSystemInfo"), sysInfo);
            descendant.setItem(org.apache.pdfbox.cos.COSName.getPDFName("CIDToGIDMap"),
                    org.apache.pdfbox.cos.COSName.getPDFName("Identity"));
            org.apache.pdfbox.cos.COSArray descendants = new org.apache.pdfbox.cos.COSArray();
            descendants.add(descendant);
            org.apache.pdfbox.cos.COSDictionary font = new org.apache.pdfbox.cos.COSDictionary();
            font.setItem(org.apache.pdfbox.cos.COSName.TYPE,
                    org.apache.pdfbox.cos.COSName.getPDFName("Font"));
            font.setItem(org.apache.pdfbox.cos.COSName.SUBTYPE,
                    org.apache.pdfbox.cos.COSName.getPDFName("Type0"));
            font.setItem(org.apache.pdfbox.cos.COSName.BASE_FONT,
                    org.apache.pdfbox.cos.COSName.getPDFName("LiberationSans"));
            font.setItem(org.apache.pdfbox.cos.COSName.getPDFName("Encoding"),
                    org.apache.pdfbox.cos.COSName.getPDFName("Identity-H"));
            font.setItem(org.apache.pdfbox.cos.COSName.DESCENDANT_FONTS, descendants);
            org.apache.pdfbox.cos.COSStream toUnicode = new org.apache.pdfbox.cos.COSStream();
            try (java.io.OutputStream out = toUnicode.createOutputStream()) {
                out.write((
                        "/CIDInit /ProcSet findresource begin\n"
                        + "12 dict begin\n"
                        + "begincmap\n"
                        + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                        + "/CMapName /Adobe-Identity-UCS def\n"
                        + "/CMapType 2 def\n"
                        + "1 begincodespacerange\n"
                        + "<0000> <FFFF>\n"
                        + "endcodespacerange\n"
                        + "2 beginbfchar\n"
                        // Both entries on ONE line: the old line-index parser
                        // kept only the first pair.
                        + "<0001> <0041> <0002> <0042>\n"
                        + "endbfchar\n"
                        + "endcmap\n"
                        + "CMapName currentdict /CMap defineresource pop\n"
                        + "end\n"
                        + "end\n").getBytes(StandardCharsets.US_ASCII));
            }
            font.setItem(org.apache.pdfbox.cos.COSName.TO_UNICODE, toUnicode);
            org.apache.pdfbox.cos.COSDictionary fonts = new org.apache.pdfbox.cos.COSDictionary();
            fonts.setItem("F1", font);
            org.apache.pdfbox.cos.COSDictionary resources = new org.apache.pdfbox.cos.COSDictionary();
            resources.setItem(org.apache.pdfbox.cos.COSName.FONT, fonts);
            page.setResources(new org.apache.pdfbox.pdmodel.PDResources(resources));
            page.setContents(new org.apache.pdfbox.pdmodel.common.PDStream(pdf,
                    new java.io.ByteArrayInputStream((
                            "BT /F1 24 Tf 72 700 Td <0001> Tj ET\n"
                            + "BT /F1 24 Tf 200 700 Td <0002> Tj ET\n")
                            .getBytes(StandardCharsets.US_ASCII))));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }
}
