package stirling.software.jpdfium.corpus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.PdfVerifier;
import stirling.software.jpdfium.model.RenderResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generates diverse PDFs with PDFBox, runs the standard corpus checks over
 * them, and then <strong>deletes</strong> the generated files - asserting the
 * deletion so the workspace stays self-cleaning.
 *
 * <p>This exercises content JPDFium never generated itself (bookmarks, form
 * fields, annotations, images, rotated text) and proves the corpus pipeline
 * works end-to-end without leaving artifacts.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@Tag("corpus")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CorpusSyntheticPdfBoxTest {

    private static final int PER_KIND = 3;

    private static final List<Path> generated = new ArrayList<>();

    @BeforeAll
    static void generate(@TempDir Path tmp) throws Exception {
        List<SyntheticCorpusPdfFactory.Variant> variants =
                SyntheticCorpusPdfFactory.generateAll(PER_KIND);
        for (SyntheticCorpusPdfFactory.Variant v : variants) {
            generated.add(SyntheticCorpusPdfFactory.write(tmp, v));
        }
        System.out.println("[CorpusSyntheticPdfBoxTest] generated " + generated.size() + " PDFs");
    }

    @Test
    @Order(1)
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void generatedPdfsPassStandardCorpusChecks() throws Exception {
        assertFalse(generated.isEmpty(), "generated files must exist");

        int checked = 0;
        for (Path pdf : generated) {
            int pages;
            try (PdfDocument doc = PdfDocument.open(pdf)) {
                pages = doc.pageCount();
                assertTrue(pages > 0, pdf.getFileName() + " must have pages");
                try (PdfPage page = doc.page(0)) {
                    RenderResult r = page.renderAt(72);
                    assertTrue(r != null && r.rgba().length > 0,
                            pdf.getFileName() + " must render");
                }
                byte[] saved = doc.saveBytes();
                assertEquals(pages, PdfVerifier.pageCount(saved, pdf.getFileName().toString()),
                        pdf.getFileName() + " save round-trip must keep pages");
                assertTrue(saved.length > 0);
            }
            checked++;
        }
        assertEquals(generated.size(), checked, "every generated file must be checked");
    }

    @Test
    @Order(2)
    void generatedPdfsAreDeletedAfterVerification() throws Exception {
        for (Path pdf : generated) {
            Files.deleteIfExists(pdf);
            assertTrue(Files.notExists(pdf),
                    "generated file must be deleted: " + pdf.getFileName());
        }
        generated.clear();
    }

    @AfterAll
    static void selfClean() {
        for (Path pdf : generated) {
            try {
                Files.deleteIfExists(pdf);
            } catch (IOException ignored) {
            }
        }
    }
}
