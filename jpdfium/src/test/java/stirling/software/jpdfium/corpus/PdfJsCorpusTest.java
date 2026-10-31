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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfRenderer;
import stirling.software.jpdfium.doc.PdfAnnotations;
import stirling.software.jpdfium.model.FlattenMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Broad PDF.js rendering, save/reopen and annotation-flatten smoke test.
 *
 * <p>Set {@code PDFJS_DIR} to an extracted {@code test/pdfs} directory. The
 * corpus is downloaded by CI and is not redistributed with JPDFium.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@EnabledIfEnvironmentVariable(named = "PDFJS_DIR", matches = ".+")
class PdfJsCorpusTest {

    private static final int LIMIT = Integer.getInteger("jpdfium.pdfjs.limit", 200);
    private static final Set<String> KNOWN_PATHOLOGICAL = Set.of(
            "tiling-pattern-tiny-steps-large.pdf",
            "tiling-pattern-tiny-steps.pdf",
            "poppler-85140-0.pdf",
            "poppler-937-0-fuzzed.pdf");
    private static final float DPI = 36.0f;
    private static final AtomicInteger PROBED = new AtomicInteger();

    @TestFactory
    Stream<DynamicTest> openRenderAndReopen() throws Exception {
        Path dir = Path.of(System.getenv("PDFJS_DIR"));
        List<Path> files;
        try (Stream<Path> paths = Files.list(dir)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".pdf"))
                    .sorted()
                    .limit(LIMIT)
                    .toList();
        }
        assertTrue(!files.isEmpty(), "no PDF.js files found under " + dir);
        return files.stream().map(path -> DynamicTest.dynamicTest(
                path.getFileName().toString(), () -> probe(path)));
    }

    @AfterAll
    static void atLeastOneProbed() {
        assertTrue(PROBED.get() > 0, "no PDF.js fixture could be opened and rendered");
    }

    private static void probe(Path input) throws Exception {
        assumeTrue(!KNOWN_PATHOLOGICAL.contains(input.getFileName().toString()),
                "known pathological corpus fixture");
        assumeTrue(Files.size(input) < 64L * 1024 * 1024, "oversized fixture");
        PdfDocument doc;
        try {
            doc = PdfDocument.open(input);
        } catch (Exception e) {
            assumeTrue(false, "PDF.js fixture cannot be opened: " + e.getMessage());
            return;
        }
        try (PdfDocument source = doc) {
            int pages = source.pageCount();
            assumeTrue(pages > 0, "no pages");
            BufferedImage image = new PdfRenderer(source).renderImageWithDPI(0, DPI);
            assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
            boolean flattenAnnotations =
                    input.getFileName().toString().matches(".*(annotation|form|widget).*");
            int annotations = flattenAnnotations ? annotationCount(source) : 0;
            if (flattenAnnotations) {
                for (int i = 0; i < pages; i++) {
                    try (var page = source.page(i)) {
                        page.flatten(FlattenMode.ANNOTATIONS);
                    }
                }
            }
            byte[] output = source.saveBytes();
            assertTrue(output.length > 0, "empty save");
            try (PdfDocument reopened = PdfDocument.open(output)) {
                assertEquals(pages, reopened.pageCount(), "page count changed");
                if (annotations > 0) {
                    assertEquals(0, annotationCount(reopened),
                            "annotation flattening left interactive annotations behind");
                }
                if (reopened.pageCount() > 0) {
                    new PdfRenderer(reopened).renderImageWithDPI(0, DPI);
                }
            }
            PROBED.incrementAndGet();
        }
    }

    private static int annotationCount(PdfDocument doc) {
        int total = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            try (var page = doc.page(i)) {
                total += PdfAnnotations.count(page.rawHandleForRead());
            }
        }
        return total;
    }
}
