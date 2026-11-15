package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.SyntheticPdfFactory;
import stirling.software.jpdfium.exception.PdfPasswordException;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfMergerAndSecurityTest {

    @Test
    void testPdfMergerBasic(@TempDir Path tempDir) throws IOException {
        byte[] pdf1 = SyntheticPdfFactory.singlePageWithText("Doc 1");
        byte[] pdf2 = SyntheticPdfFactory.singlePageWithText("Doc 2");

        if (PdfMerger.isSupported()) {
            byte[] merged = PdfMerger.mergeBytes(List.of(pdf1, pdf2));
            assertNotNull(merged);
            assertTrue(merged.length > 0);

            Path p1 = tempDir.resolve("doc1.pdf");
            Path p2 = tempDir.resolve("doc2.pdf");
            Path out = tempDir.resolve("merged.pdf");
            Files.write(p1, pdf1);
            Files.write(p2, pdf2);

            PdfMerger.merge(List.of(p1, p2), out);
            assertTrue(Files.exists(out));
            assertTrue(Files.size(out) > 0);
        }
    }

    @Test
    void testPdfSecurityEncryptAndDecrypt(@TempDir Path tempDir) throws IOException {
        byte[] pdf = SyntheticPdfFactory.singlePageWithText("Sensitive Document");

        if (PdfSecurity.isSupported()) {
            byte[] encrypted = PdfSecurity.encryptBytes(pdf, "userPass", "ownerPass", PdfSecurity.PERM_ALL);
            assertNotNull(encrypted);
            assertTrue(encrypted.length > 0);
            // The stub bridge passes bytes through instead of encrypting, so it
            // cannot model the protection half of the contract. Real natives
            // can, and there the whole point is checked.
            boolean canEncrypt = NativeRuntime.isFull();
            if (canEncrypt) {
                assertThrows(PdfPasswordException.class, () -> {
                    try (PdfDocument d = PdfDocument.open(encrypted)) {
                        d.pageCount();
                    }
                }, "the encrypted fixture must not open without a password");
            }

            // Length alone proves nothing: qpdf's writer preserves the source
            // encryption unless it is told not to, so a "decrypted" result can
            // still be a valid, fully encrypted PDF. The only check that proves
            // the contract is opening the output with no password at all.
            byte[] decrypted = PdfSecurity.decryptBytes(encrypted, "ownerPass");
            assertNotNull(decrypted);
            assertTrue(decrypted.length > 0);
            if (canEncrypt) {
                try (PdfDocument d = PdfDocument.open(decrypted)) {
                    assertTrue(d.pageCount() > 0,
                            "decrypted bytes must open without a password");
                }
            }

            Path inPath = tempDir.resolve("plain.pdf");
            Path encPath = tempDir.resolve("enc.pdf");
            Path decPath = tempDir.resolve("dec.pdf");
            Files.write(inPath, pdf);

            PdfSecurity.encrypt(inPath, encPath, "userPass", "ownerPass", PdfSecurity.PERM_ALL);
            assertTrue(Files.exists(encPath));

            PdfSecurity.decrypt(encPath, decPath, "ownerPass");
            assertTrue(Files.exists(decPath));
            if (canEncrypt) {
                try (PdfDocument d = PdfDocument.open(decPath)) {
                    assertTrue(d.pageCount() > 0,
                            "the decrypted file must open without a password");
                }
            }
        }
    }

    @Test
    void testPdfDocumentMergeConvenience() {
        byte[] pdf1 = SyntheticPdfFactory.singlePageWithText("Page 1");
        byte[] pdf2 = SyntheticPdfFactory.singlePageWithText("Page 2");

        if (PdfMerger.isSupported()) {
            byte[] merged = PdfDocument.mergeBytes(List.of(pdf1, pdf2));
            assertNotNull(merged);
            assertTrue(merged.length > 0);
            if (NativeRuntime.isFull()) {
                try (PdfDocument doc = PdfDocument.open(merged)) {
                    assertEquals(2, doc.pageCount());
                }
            }
        }
    }

    @Test
    void testMergeBytesRejectsPartialImportFailure() {
        if (!PdfMerger.isSupported() || !NativeRuntime.isFull()) {
            return;
        }
        byte[] valid = SyntheticPdfFactory.singlePageWithText("Valid Page");
        byte[] corrupt = "not a valid pdf payload at all".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertNull(PdfMerger.mergeBytes(List.of(valid, corrupt)),
                "mergeBytes must fail rather than returning a partial document when an input cannot be merged");
    }

    @Test
    void testNativeRuntimeRethrowFatalPropagatesErrorsOnly() {
        assertThrows(OutOfMemoryError.class,
                () -> NativeRuntime.rethrowFatal(new OutOfMemoryError("synthetic oom")));
        NativeRuntime.rethrowFatal(new RuntimeException("non-fatal runtime exception"));
    }

    @Test
    void mergeDisambiguatesCollidingSubsetFontTags() throws IOException {
        if (!PdfMerger.isSupported() || !NativeRuntime.isFull()) {
            return;
        }
        byte[] pdf1 = subsetFontPdfWithFixedTag("AAAAAA+LiberationSans", "Alpha");
        byte[] pdf2 = subsetFontPdfWithFixedTag("AAAAAA+LiberationSans", "Zeta");

        byte[] merged = PdfMerger.mergeBytes(List.of(pdf1, pdf2));
        assertNotNull(merged);
        try (PDDocument loaded = Loader.loadPDF(merged)) {
            assertEquals(2, loaded.getNumberOfPages());
            String text = new PDFTextStripper().getText(loaded);
            assertTrue(text.contains("Alpha"), "page 1 text missing: " + text);
            assertTrue(text.contains("Zeta"), "page 2 text missing: " + text);

            Set<String> baseFonts = new HashSet<>();
            for (COSObject obj : loaded.getDocument().getObjectsByType(COSName.FONT)) {
                if (obj.getObject() instanceof COSDictionary font
                        && COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE))) {
                    COSName bf = font.getCOSName(COSName.BASE_FONT);
                    if (bf != null) {
                        baseFonts.add(bf.getName());
                    }
                }
            }
            assertEquals(2, baseFonts.size(),
                    "colliding subset tags must be disambiguated: " + baseFonts);
        }
    }

    @Test
    void removeUnreferencedResourcesDropsUnusedPageFont() throws IOException {
        if (!NativeRuntime.isFull()) {
            return;
        }
        byte[] src;
        try (InputStream fontIn = PdfMergerAndSecurityTest.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
                PDDocument doc = new PDDocument()) {
            assertNotNull(fontIn);
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType0Font typeface = PDType0Font.load(doc, fontIn, true);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(typeface, 14);
                cs.newLineAtOffset(72, 700);
                cs.showText("UsedText");
                cs.endText();
            }
            COSDictionary unusedFont = new COSDictionary();
            unusedFont.setItem(COSName.TYPE, COSName.FONT);
            unusedFont.setItem(COSName.SUBTYPE, COSName.TYPE1);
            unusedFont.setName(COSName.BASE_FONT, "Courier");
            COSDictionary resFonts = page.getResources().getCOSObject().getCOSDictionary(COSName.FONT);
            resFonts.setItem(COSName.getPDFName("FUnused"), unusedFont);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            src = out.toByteArray();
        }

        byte[] cleaned = PdfOptimizer.removeUnreferencedResources(src);
        assertNotNull(cleaned);
        try (PDDocument loaded = Loader.loadPDF(cleaned)) {
            COSDictionary resFonts = loaded.getPage(0).getResources()
                    .getCOSObject().getCOSDictionary(COSName.FONT);
            assertNotNull(resFonts);
            assertFalse(resFonts.containsKey(COSName.getPDFName("FUnused")),
                    "unreferenced font /FUnused must be pruned");
            String text = new PDFTextStripper().getText(loaded).trim();
            assertEquals("UsedText", text);
        }
    }

    private static byte[] subsetFontPdfWithFixedTag(String tagName, String text) throws IOException {
        byte[] raw;
        try (InputStream fontIn = PdfMergerAndSecurityTest.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
                PDDocument doc = new PDDocument()) {
            assertNotNull(fontIn);
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType0Font typeface = PDType0Font.load(doc, fontIn, true);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(typeface, 14);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream first = new ByteArrayOutputStream();
            doc.save(first);
            raw = first.toByteArray();
        }
        try (PDDocument doc = Loader.loadPDF(raw)) {
            COSName tagged = COSName.getPDFName(tagName);
            for (COSObject obj : doc.getDocument().getObjectsByType(COSName.FONT)) {
                if (!(obj.getObject() instanceof COSDictionary font)) {
                    continue;
                }
                font.setItem(COSName.BASE_FONT, tagged);
                COSDictionary desc = font.getCOSDictionary(COSName.FONT_DESC);
                if (desc != null) {
                    desc.setItem(COSName.FONT_NAME, tagged);
                }
            }
            // Also rewrite /BaseFont in any stream content not needed; save clean copy.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
