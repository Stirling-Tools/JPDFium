package stirling.software.jpdfium.corpus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfRenderer;
import stirling.software.jpdfium.VisualDiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PDF/A and ISO 32000 corpus smoke test: open, render, save and reopen.
 *
 * <p>Set {@code VERAPDF_DIR} to an extracted veraPDF corpus. The corpus is
 * downloaded by CI and is not redistributed with JPDFium.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@EnabledIfEnvironmentVariable(named = "VERAPDF_DIR", matches = ".+")
class VeraPdfCorpusTest {

    private static final int LIMIT = Integer.getInteger("jpdfium.verapdf.limit", 200);
    private static final float DPI = 36.0f;
    private static final double REOPEN_MAE = 8.0d;
    private static final AtomicInteger PROBED = new AtomicInteger();

    @TestFactory
    Stream<DynamicTest> openRenderAndReopen() throws Exception {
        Path dir = Path.of(System.getenv("VERAPDF_DIR"));
        List<Path> files;
        try (Stream<Path> paths = Files.walk(dir)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".pdf"))
                    .sorted()
                    .limit(LIMIT)
                    .toList();
        }
        assertTrue(!files.isEmpty(), "no veraPDF files found under " + dir);
        return files.stream().map(path -> DynamicTest.dynamicTest(
                path.getFileName().toString(), () -> probe(path)));
    }

    @AfterAll
    static void atLeastOneProbed() {
        assertTrue(PROBED.get() > 0, "no veraPDF fixture could be opened and rendered");
    }

    private static void probe(Path input) throws Exception {
        assumeTrue(Files.size(input) < 64L * 1024 * 1024, "oversized fixture");
        PdfDocument doc;
        try {
            doc = PdfDocument.open(input);
        } catch (Exception e) {
            assumeTrue(false, "veraPDF fixture cannot be opened: " + e.getMessage());
            return;
        }
        try (PdfDocument source = doc) {
            int pages = source.pageCount();
            assumeTrue(pages > 0, "no pages");
            BufferedImage image = new PdfRenderer(source).renderImageWithDPI(0, DPI);
            assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
            byte[] output = source.saveBytes();
            assertTrue(output.length > 0, "empty save");
            try (PdfDocument reopened = PdfDocument.open(output)) {
                assertEquals(pages, reopened.pageCount(), "page count changed");
                BufferedImage reopenedImage = new PdfRenderer(reopened).renderImageWithDPI(0, DPI);
                assertEquals(image.getWidth(), reopenedImage.getWidth(),
                        "reopened page width changed");
                assertEquals(image.getHeight(), reopenedImage.getHeight(),
                        "reopened page height changed");
                double mae = VisualDiff.meanAbsoluteError(image, reopenedImage);
                assertTrue(mae <= REOPEN_MAE,
                        "reopened page changed: MAE " + mae);
            }
            PROBED.incrementAndGet();
        }
    }
}
