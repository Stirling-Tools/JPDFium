package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.PdfRenderer;
import stirling.software.jpdfium.model.FlattenMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Visual corpus checks for annotation flattening and full-page rasterization.
 *
 * <p>Annotation flattening must remove the interactive objects without changing
 * their rendered appearance. Ghent fixtures are optional and exercise form,
 * transparency and spot-colour content through the full rasterization path.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class FlattenVisualCorpusTest {

    private static final float DPI = 100.0f;
    private static final double ANNOTATION_MAE = 1.0d;
    private static final double FORM_MAE = 2.0d;
    private static final double RASTER_MAE = 1.0d;
    private static final double ANNOTATION_TILE_MAE = 62.0d;
    private static final List<String> ANNOTATION_FIXTURES = List.of(
            "pdfjs_annotation-highlight.pdf",
            "pdfjs_annotation-ink-without-appearance.pdf",
            "pdfjs_annotation-underline.pdf",
            "pdfjs_annotation-strikeout.pdf",
            "pdfjs_annotation-stamp.pdf",
            "pdfjs_annotation-freetext.pdf",
            "pdfjs_annotation-line.pdf",
            "pdfjs_annotation-caret-ink.pdf",
            "pdfjs_annotation-fileattachment.pdf",
            "pdfjs_annotation-border-styles.pdf");
    private static final List<String> FULL_RASTER_FIXTURES = List.of(
            "pdfjs_annotation-highlight.pdf",
            "pdfjs_annotation-stamp.pdf",
            "all_form_fields.pdf");
    private static final List<String> FORM_FIXTURES = List.of(
            "text_form.pdf",
            "all_form_fields.pdf",
            "combobox_form.pdf",
            "click_form.pdf",
            "pdfjs_annotation-choice-widget.pdf",
            "pdfjs_annotation-button-widget.pdf");

    @TestFactory
    Stream<DynamicTest> annotationFlattenKeepsAppearance() {
        return ANNOTATION_FIXTURES.stream().map(name -> DynamicTest.dynamicTest(
                name, () -> flattenAndAssert("/pdfs/general/" + name, name, ANNOTATION_MAE)));
    }

    @TestFactory
    Stream<DynamicTest> formFlattenKeepsAppearance() {
        return FORM_FIXTURES.stream().map(name -> DynamicTest.dynamicTest(
                name, () -> flattenAndAssert("/pdfs/general/" + name, name, FORM_MAE)));
    }

    private static void flattenAndAssert(String resourcePath, String label, double threshold)
            throws Exception {
        Path input = resource(resourcePath);
        Path output = Files.createTempFile("jpdfium-flat-", ".pdf");
        try {
            int annotations;
            try (PdfDocument doc = PdfDocument.open(input)) {
                annotations = annotationCount(doc);
                assertTrue(annotations > 0, label + " has no annotations or widgets");
                doc.flatten(FlattenMode.ANNOTATIONS);
                doc.save(output);
            }
            try (PdfDocument after = PdfDocument.open(output)) {
                assertEquals(0, annotationCount(after),
                        label + " still contains annotations after flatten");
            }
            try (PDDocument pdfbox = Loader.loadPDF(output.toFile())) {
                PDAcroForm form = pdfbox.getDocumentCatalog().getAcroForm();
                assertTrue(form == null || form.getFields().isEmpty(),
                        label + " still exposes populated AcroForm fields after flatten");
            }
            assertRenderPreserved(input, output, threshold, label,
                    threshold == ANNOTATION_MAE);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void pageFullFlattenMarksDocumentModified() throws Exception {
        Path input = resource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input);
                PdfPage page = doc.page(0)) {
            page.flatten(FlattenMode.FULL, 100);
            assertTrue(doc.isContentModified(),
                    "page-level full raster flatten must mark the document modified");
            assertTrue(page.isClosed(), "full raster flatten must close the page handle");
        }
    }

    @TestFactory
    Stream<DynamicTest> fullRasterFlattenKeepsAnnotationAndFormAppearance() {
        return FULL_RASTER_FIXTURES.stream().map(name -> DynamicTest.dynamicTest(name, () -> {
            Path input = resource("/pdfs/general/" + name);
            Path output = Files.createTempFile("jpdfium-flat-full-", ".pdf");
            try {
                try (PdfDocument doc = PdfDocument.open(input)) {
                    doc.flatten(FlattenMode.FULL, 100);
                    assertTrue(doc.isContentModified(),
                            "full raster flatten must mark the document modified");
                    doc.save(output);
                }
                assertRenderPreserved(input, output, RASTER_MAE, name, false);
            } finally {
                Files.deleteIfExists(output);
            }
        }));
    }

    @TestFactory
    @EnabledIfEnvironmentVariable(named = "GHENT_DIR", matches = ".+")
    Stream<DynamicTest> fullRasterFlattenPreservesGhentPages() {
        Path dir = fixtureDir();
        List<Path> fixtures;
        try (Stream<Path> paths = Files.list(dir)) {
            fixtures = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("Ghent_PDF-Output-Test-"))
                    .filter(path -> path.getFileName().toString().endsWith(".pdf"))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("cannot list Ghent fixtures", e);
        }
        assumeTrue(!fixtures.isEmpty(), "no Ghent test pages found");
        Path finalDir = dir;
        return fixtures.stream().map(input -> DynamicTest.dynamicTest(
                input.getFileName().toString(), () -> {
                    Path output = Files.createTempFile("jpdfium-flat-ghent-", ".pdf");
                    try {
                        try (PdfDocument doc = PdfDocument.open(input)) {
                            doc.flatten(FlattenMode.FULL, 100);
                            doc.save(output);
                        }
                        assertRenderPreserved(input, output, RASTER_MAE,
                                finalDir.relativize(input).toString(), false);
                    } finally {
                        Files.deleteIfExists(output);
                    }
                }));
    }

    private static int annotationCount(PdfDocument doc) {
        int total = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            try (PdfPage page = doc.page(i)) {
                total += PdfAnnotations.count(page.rawHandleForRead());
            }
        }
        return total;
    }

    private static void assertRenderPreserved(Path input, Path output, double threshold,
                                              String label, boolean checkLocalized) throws Exception {
        try (PdfDocument before = PdfDocument.open(input);
                PdfDocument after = PdfDocument.open(output)) {
            assertEquals(before.pageCount(), after.pageCount(), label + " page count changed");
            PdfRenderer a = new PdfRenderer(before);
            PdfRenderer b = new PdfRenderer(after);
            for (int page = 0; page < before.pageCount(); page++) {
                BufferedImage expected = a.renderImageWithDPI(page, DPI);
                BufferedImage actual = b.renderImageWithDPI(page, DPI);
                assertEquals(expected.getWidth(), actual.getWidth(), label + " page width changed");
                assertEquals(expected.getHeight(), actual.getHeight(), label + " page height changed");
                double mae = meanAbsoluteError(expected, actual);
                System.out.printf("%s page %d: %s (MAE %.4f, max %.1f)%n",
                        label, page + 1, mae <= threshold ? "PASS" : "FAIL", mae, threshold);
                assertTrue(mae <= threshold,
                        label + " page " + (page + 1) + " changed: MAE " + mae);
                if (checkLocalized) {
                    double tileMae = maxTileMae(expected, actual, 16);
                    assertTrue(tileMae <= ANNOTATION_TILE_MAE,
                            label + " page " + (page + 1)
                                    + " lost a localized annotation region: tile MAE " + tileMae);
                }
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
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("GHENT_DIR is not a directory: " + dir);
        }
        return dir;
    }

    private static Path resource(String path) throws Exception {
        return Path.of(FlattenVisualCorpusTest.class.getResource(path).toURI());
    }

    private static double maxTileMae(BufferedImage a, BufferedImage b, int tile) {
        double max = 0;
        for (int y = 0; y + tile <= a.getHeight(); y += tile) {
            for (int x = 0; x + tile <= a.getWidth(); x += tile) {
                long sum = 0;
                for (int yy = y; yy < y + tile; yy++) {
                    for (int xx = x; xx < x + tile; xx++) {
                        int pa = a.getRGB(xx, yy);
                        int pb = b.getRGB(xx, yy);
                        sum += Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
                        sum += Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF));
                        sum += Math.abs((pa & 0xFF) - (pb & 0xFF));
                    }
                }
                max = Math.max(max, (double) sum / (tile * tile * 3));
            }
        }
        return max;
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
        return count == 0 ? 0.0d : (double) sum / count;
    }
}
