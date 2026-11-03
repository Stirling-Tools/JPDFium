package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.SyntheticPdfFactory;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers the qpdf operations Stirling-PDF used to invoke through the qpdf
 * executable: QDF repair, PDF/A content normalization and resource cleanup,
 * level-controlled compression, and qpdf's image optimization pass.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class QpdfParityTest {

    @BeforeAll
    static void requireFullNative() {
        assumeTrue(NativeRuntime.isFull(), "requires a full native build");
    }

    private static String document(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static void assertReadable(byte[] bytes, int expectedPages) {
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        try (PdfDocument doc = PdfDocument.open(bytes)) {
            assertEquals(expectedPages, doc.pageCount());
        }
    }

    /** Adds a font to page 0 /Resources that no content stream refers to. */
    private static byte[] withUnreferencedFont(byte[] input) throws Exception {
        try (PDDocument doc = Loader.loadPDF(input)) {
            PDResources resources = doc.getPage(0).getResources();
            resources.put(COSName.getPDFName("JpdfUnusedFont"),
                    new PDType1Font(Standard14Fonts.FontName.COURIER));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static boolean hasFontResource(byte[] pdf, COSName name) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                if (page.getResources() == null) continue;
                for (COSName font : page.getResources().getFontNames()) {
                    if (font.equals(name)) return true;
                }
            }
        }
        return false;
    }

    /** Repetitive content whose Flate payload is measurably smaller at level 9 than level 1. */
    private static byte[] compressionFixture() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
                cs.newLineAtOffset(36, 750);
                for (int i = 0; i < 1200; i++) {
                    cs.showText("JPDFium compression fixture line " + (i % 40)
                            + " - repeated content compresses differently by level.");
                    cs.newLineAtOffset(0, -9);
                }
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void qdfRepairWritesReadableQdfWithoutObjectStreams(@TempDir Path tempDir) throws Exception {
        byte[] input = SyntheticPdfFactory.createDiverse(3);
        assumeTrue(PdfOptimizer.isSupported(), "native qpdf optimizer unavailable");

        byte[] repaired = PdfOptimizer.repair(input);
        assertReadable(repaired, 3);
        String document = document(repaired);
        assertTrue(document.contains("%QDF-1.0"),
                "qpdf QDF mode must be visible in the output header");
        assertFalse(document.contains("/ObjStm"),
                "QDF repair must disable object streams");

        Path in = tempDir.resolve("repair-in.pdf");
        Path out = tempDir.resolve("repair-out.pdf");
        Files.write(in, input);
        PdfOptimizer.repair(in, out);
        assertTrue(Files.size(out) > 0);
        assertReadable(Files.readAllBytes(out), 3);
    }

    @Test
    void pdfaNormalizationAndResourceCleanupPreservePages(@TempDir Path tempDir) throws Exception {
        byte[] input = withUnreferencedFont(SyntheticPdfFactory.createDiverse(2));
        assumeTrue(PdfOptimizer.isSupported(), "native qpdf optimizer unavailable");
        COSName unusedFont = COSName.getPDFName("JpdfUnusedFont");
        assertTrue(hasFontResource(input, unusedFont), "fixture must carry an unreferenced font");

        byte[] cleanedBytes = PdfOptimizer.removeUnreferencedResources(input);
        assertReadable(cleanedBytes, 2);
        assertFalse(hasFontResource(cleanedBytes, unusedFont),
                "resource cleanup must remove the unreferenced font");
        assertReadable(PdfOptimizer.normalizeContent(input), 2);
        assertReadable(PdfOptimizer.normalizeContentAndCleanResources(input), 2);

        Path in = tempDir.resolve("normalize-in.pdf");
        Files.write(in, input);
        Path normalized = tempDir.resolve("normalized.pdf");
        Path cleaned = tempDir.resolve("cleaned.pdf");
        Path combined = tempDir.resolve("combined.pdf");
        PdfOptimizer.normalizeContent(in, normalized);
        PdfOptimizer.removeUnreferencedResources(in, cleaned);
        PdfOptimizer.normalizeContentAndCleanResources(in, combined);
        assertReadable(Files.readAllBytes(normalized), 2);
        assertReadable(Files.readAllBytes(cleaned), 2);
        assertReadable(Files.readAllBytes(combined), 2);
    }

    @Test
    void compressionLevelsAndImagesProduceValidOutput(@TempDir Path tempDir) throws Exception {
        byte[] input = SyntheticPdfFactory.createDiverse(2);
        assumeTrue(PdfOptimizer.isSupported(), "native qpdf optimizer unavailable");

        byte[] levelInput = compressionFixture();
        byte[] level1 = PdfOptimizer.compress(levelInput, 1, false, false);
        byte[] level9 = PdfOptimizer.compress(levelInput, 9, false, false);
        assertReadable(level1, 1);
        assertReadable(level9, 1);
        assertTrue(level9.length < level1.length,
                "compression level must affect output size: level 1=" + level1.length
                        + " B, level 9=" + level9.length + " B");

        if (PdfOptimizer.isImageOptimizationSupported()) {
            byte[] structural = PdfOptimizer.compress(input, 9, false, false);
            byte[] optimized = PdfOptimizer.compress(input, 9, false, false,
                    true, true, 40);
            assertReadable(optimized, 2);
            assertFalse(Arrays.equals(structural, optimized),
                    "the qpdf image pass must change the output");

            Path in = tempDir.resolve("compress-in.pdf");
            Path out = tempDir.resolve("compress-out.pdf");
            Files.write(in, input);
            PdfOptimizer.compress(in, out, 9, false, false, true, true, 40);
            assertTrue(Files.size(out) > 0);
            assertReadable(Files.readAllBytes(out), 2);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for compression start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for compression start", e);
        }
    }

    @Test
    void concurrentLevelsDoNotCorruptEachOther() throws Exception {
        byte[] input = compressionFixture();
        assumeTrue(PdfOptimizer.isSupported(), "native qpdf optimizer unavailable");

        byte[] sequentialLow = PdfOptimizer.compress(input, 1, false, false);
        byte[] sequentialHigh = PdfOptimizer.compress(input, 9, false, false);
        assertTrue(sequentialHigh.length < sequentialLow.length,
                "compression level must affect output size: level 1=" + sequentialLow.length
                        + " B, level 9=" + sequentialHigh.length + " B");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<byte[]> low = CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            await(start);
            return PdfOptimizer.compress(input, 1, false, false);
        });
        CompletableFuture<byte[]> high = CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            await(start);
            return PdfOptimizer.compress(input, 9, false, false);
        });

        assertTrue(ready.await(60, TimeUnit.SECONDS), "compression workers did not become ready");
        start.countDown();
        byte[] concurrentLow = low.get(60, TimeUnit.SECONDS);
        byte[] concurrentHigh = high.get(60, TimeUnit.SECONDS);
        assertReadable(concurrentLow, 1);
        assertReadable(concurrentHigh, 1);
        assertEquals(sequentialLow.length, concurrentLow.length,
                "level-1 concurrent output must match the sequential level-1 size");
        assertEquals(sequentialHigh.length, concurrentHigh.length,
                "level-9 concurrent output must match the sequential level-9 size");
    }
}
