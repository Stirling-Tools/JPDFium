package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.SyntheticPdfFactory;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
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

    private static String ascii(byte[] bytes) {
        return new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
    }

    private static void assertReadable(byte[] bytes, int expectedPages) {
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        try (PdfDocument doc = PdfDocument.open(bytes)) {
            assertEquals(expectedPages, doc.pageCount());
        }
    }

    @Test
    void qdfRepairWritesReadableQdfWithoutObjectStreams(@TempDir Path tempDir) throws Exception {
        byte[] input = SyntheticPdfFactory.createDiverse(3);
        assumeTrue(PdfOptimizer.isSupported(), "native qpdf optimizer unavailable");

        byte[] repaired = PdfOptimizer.repair(input);
        assertReadable(repaired, 3);
        String header = ascii(repaired);
        assertTrue(header.contains("%QDF-1.0"),
                "qpdf QDF mode must be visible in the output header");
        assertFalse(header.contains("/ObjStm"),
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
        byte[] input = SyntheticPdfFactory.createDiverse(2);
        assumeTrue(PdfOptimizer.isSupported(), "native qpdf optimizer unavailable");

        assertReadable(PdfOptimizer.normalizeContent(input), 2);
        assertReadable(PdfOptimizer.removeUnreferencedResources(input), 2);
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

        byte[] level1 = PdfOptimizer.compress(input, 1, false, false);
        byte[] level9 = PdfOptimizer.compress(input, 9, false, false);
        assertReadable(level1, 2);
        assertReadable(level9, 2);
        assertTrue(level9.length <= level1.length,
                "level 9 must not produce a larger structural result than level 1");

        if (PdfOptimizer.isImageOptimizationSupported()) {
            byte[] optimized = PdfOptimizer.compress(input, 9, false, false,
                    true, true, 40);
            assertReadable(optimized, 2);

            Path in = tempDir.resolve("compress-in.pdf");
            Path out = tempDir.resolve("compress-out.pdf");
            Files.write(in, input);
            PdfOptimizer.compress(in, out, 9, false, false, true, true, 40);
            assertTrue(Files.size(out) > 0);
            assertReadable(Files.readAllBytes(out), 2);
        }
    }

    @Test
    void concurrentLevelsDoNotCorruptEachOther() throws Exception {
        byte[] input = SyntheticPdfFactory.createDiverse(2);
        assumeTrue(PdfOptimizer.isSupported(), "native qpdf optimizer unavailable");

        byte[] sequentialLow = PdfOptimizer.compress(input, 1, false, false);
        byte[] sequentialHigh = PdfOptimizer.compress(input, 9, false, false);

        CompletableFuture<byte[]> low = CompletableFuture.supplyAsync(
                () -> PdfOptimizer.compress(input, 1, false, false));
        CompletableFuture<byte[]> high = CompletableFuture.supplyAsync(
                () -> PdfOptimizer.compress(input, 9, false, false));

        byte[] concurrentLow = low.get(60, TimeUnit.SECONDS);
        byte[] concurrentHigh = high.get(60, TimeUnit.SECONDS);
        assertReadable(concurrentLow, 2);
        assertReadable(concurrentHigh, 2);
        assertEquals(sequentialLow.length, concurrentLow.length,
                "level-1 concurrent output must match the sequential level-1 size");
        assertEquals(sequentialHigh.length, concurrentHigh.length,
                "level-9 concurrent output must match the sequential level-9 size");
    }
}
