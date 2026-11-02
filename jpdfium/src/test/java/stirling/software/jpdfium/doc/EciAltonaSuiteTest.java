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
import stirling.software.jpdfium.VisualDiff;
import stirling.software.jpdfium.PdfRenderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ECI Altona Test Suite 1.2 and 2.0 technical-page visual regression for the CMYK conversion path.
 *
 * <p>Set {@code ECI_DIR} to the directory containing the three Altona PDFs.
 * The files are downloaded by CI and are not redistributed with JPDFium.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@EnabledIfEnvironmentVariable(named = "ECI_DIR", matches = ".+")
class EciAltonaSuiteTest {

    private static final float DPI = 100.0f;
    private static final double MAX_MAE = 1.0d;

    @TestFactory
    Stream<DynamicTest> everyAltonaPagePreservesRendering() throws Exception {
        assertTrue(PdfColorConverter.supportsColorSpace(PdfColorConverter.ColorSpace.CMYK),
                "ECI gate requires vector CMYK support");
        Path dir = Path.of(System.getenv("ECI_DIR"));
        List<Path> fixtures;
        try (Stream<Path> paths = Files.list(dir)) {
            fixtures = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".pdf"))
                    .sorted()
                    .toList();
        }
        assertTrue(!fixtures.isEmpty(), "no ECI PDFs found under " + dir);
        return fixtures.stream().map(input -> DynamicTest.dynamicTest(
                input.getFileName().toString(), () -> assertPreserved(input)));
    }

    private static void assertPreserved(Path input) throws Exception {
        Path out = Files.createTempFile("jpdfium-eci-", ".pdf");
        try {
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfColorConverter.toCmyk(doc);
                doc.save(out);
            }
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
                    double mae = VisualDiff.meanAbsoluteError(expected, actual);
                    System.out.printf("%s page %d: %s (MAE %.4f)%n",
                            input.getFileName(), page + 1, mae <= MAX_MAE ? "PASS" : "FAIL", mae);
                    assertTrue(mae <= MAX_MAE,
                            input.getFileName() + " page " + (page + 1) + " changed: MAE " + mae);
                }
            }
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
