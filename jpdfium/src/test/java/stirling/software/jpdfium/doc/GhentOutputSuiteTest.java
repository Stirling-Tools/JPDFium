package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfRenderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ghent PDF Output Suite 5.0 visual regression.
 *
 * <p>Run with {@code GHENT_DIR} pointing at the extracted
 * {@code Ghent_PDF_Output_Suite_V50_Testpages} directory:
 *
 * <pre>
 * GHENT_DIR=/path/to/Ghent_PDF_Output_Suite_V50_Testpages \
 *   ./gradlew :jpdfium:integrationTest --tests '*GhentOutputSuiteTest'
 * </pre>
 *
 * <p>The suite's test-page PDFs are already PDF/X-4 CMYK/spot/ICC content.
 * Converting them to DeviceCMYK must preserve DeviceCMYK and special colours,
 * so the rendered pages must remain visually identical. The reference patch
 * atlas is rendered as another input/output pair rather than as a page-for-page
 * expected image. Explanatory RGB PDFs are included as a looser visual sanity
 * check because profile-free RGB-to-CMYK conversion intentionally shifts them.
 * This is a regression gate, not a print-conformance certification.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@EnabledIfEnvironmentVariable(named = "GHENT_DIR", matches = ".+")
class GhentOutputSuiteTest {

    private static final float DPI = 100.0f;
    /** Strict bound for the suite's conformance test pages. */
    private static final double MAX_TEST_PAGE_MAE = 1.0d;
    /**
     * Explanatory/brochure PDFs are RGB artwork, so a profile-free CMYK
     * conversion intentionally changes their colours. Keep them in the gate as
     * a visual sanity check, with a looser bound.
     */
    private static final double MAX_AUXILIARY_MAE = 4.0d;

    @TestFactory
    Stream<DynamicTest> everyGhentFixturePreservesEveryPage() throws Exception {
        assertTrue(PdfColorConverter.supportsColorSpace(PdfColorConverter.ColorSpace.CMYK),
                "Ghent gate requires a PDFium build with vector CMYK and the colour-space probe");
        assertTrue(PdfColorConverter.supportsFormConversion(),
                "Ghent gate requires form XObject write-back support");

        Path dir = fixtureDir();
        List<Path> fixtures;
        try (Stream<Path> paths = Files.list(dir)) {
            // The suite ships both test-page PDFs and explanatory PDFs. Every
            // PDF is a conversion target; the render check is the oracle, so a
            // ReadMe or conformance brochure is just as useful as the atlas.
            fixtures = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".pdf"))
                    .sorted()
                    .toList();
        }
        assertTrue(!fixtures.isEmpty(), "no Ghent test PDFs found under " + dir);
        System.out.printf("Ghent fixtures: %d files under %s%n", fixtures.size(), dir);

        return fixtures.stream().map(input -> DynamicTest.dynamicTest(
                input.getFileName().toString(), () -> assertFixturePreserved(input)));
    }

    private static void assertFixturePreserved(Path input) throws Exception {
        String outputDir = System.getenv("GHENT_OUTPUT_DIR");
        Path out = (outputDir == null || outputDir.isBlank()
                        ? Path.of(System.getProperty("java.io.tmpdir"))
                        : Path.of(outputDir))
                .resolve("jpdfium-ghent-" + input.getFileName());
        int converted;
        try (PdfDocument doc = PdfDocument.open(input)) {
            converted = PdfColorConverter.toCmyk(doc);
            doc.save(out);
        }
        assertTrue(converted >= 0, "conversion count must not be negative");

        double threshold = input.getFileName().toString()
                        .startsWith("Ghent_PDF-Output-Test-")
                ? MAX_TEST_PAGE_MAE : MAX_AUXILIARY_MAE;
        try (PdfDocument before = PdfDocument.open(input);
                PdfDocument after = PdfDocument.open(out)) {
            assertEquals(before.pageCount(), after.pageCount(), "page count changed");
            PdfRenderer a = new PdfRenderer(before);
            PdfRenderer b = new PdfRenderer(after);

            for (int page = 0; page < before.pageCount(); page++) {
                BufferedImage expected = a.renderImageWithDPI(page, DPI);
                BufferedImage actual = b.renderImageWithDPI(page, DPI);
                assertEquals(expected.getWidth(), actual.getWidth(), "page width changed");
                assertEquals(expected.getHeight(), actual.getHeight(), "page height changed");

                double mae = meanAbsoluteError(expected, actual);
                System.out.printf("%s page %d: %s (MAE %.4f, max %.1f)%n",
                        input.getFileName(), page + 1,
                        mae <= threshold ? "PASS" : "FAIL", mae, threshold);
                assertTrue(mae <= threshold,
                        input.getFileName() + " page " + (page + 1)
                                + " changed after CMYK conversion: MAE " + mae);
            }
        }
    }

    private static Path fixtureDir() {
        Path dir = Path.of(System.getenv("GHENT_DIR"));
        if (Files.isRegularFile(dir.resolve("Ghent_PDF-Output-Test-V50_ALL_X4.pdf"))) {
            return dir;
        }
        Path nested = dir.resolve("Ghent_PDF_Output_Suite_V50_Testpages");
        if (Files.isDirectory(nested)) return nested;
        return dir;
    }

    private static double meanAbsoluteError(BufferedImage a, BufferedImage b) {
        long sum = 0;
        long count = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                sum += Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
                sum += Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF));
                sum += Math.abs((pa & 0xFF) - (pb & 0xFF));
                count += 3;
            }
        }
        return count == 0 ? 0 : (double) sum / count;
    }
}
