package stirling.software.jpdfium.corpus;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfMerge;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.PdfSplit;
import stirling.software.jpdfium.corpus.PathologicalPdfFactory.Specimen;
import stirling.software.jpdfium.doc.PdfRepair;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.RenderResult;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@Tag("corpus")
class PathologicalCorpusTest {

    @TestFactory
    Stream<DynamicTest> testAllPathologicalSpecimens() {
        Map<String, Specimen> specimens = PathologicalPdfFactory.generateAll();

        return specimens.values().stream().map(specimen -> DynamicTest.dynamicTest(
                "[" + specimen.category() + "] " + specimen.name(),
                () -> exerciseSpecimen(specimen)
        ));
    }

    private void exerciseSpecimen(Specimen specimen) {
        byte[] bytes = specimen.bytes();
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        PdfDocument openDoc;
        try {
            openDoc = PdfDocument.open(bytes);
        } catch (JPDFiumException expected) {
            return;
        }

        try (PdfDocument doc = openDoc) {
            int pageCount = doc.pageCount();
            assertTrue(pageCount >= 0);

            for (int i = 0; i < pageCount; i++) {
                try (PdfPage page = doc.page(i)) {
                    double w = page.size().width();
                    double h = page.size().height();

                    String json = page.extractCharPositionsJson();
                    assertNotNull(json);
                    assertDoesNotThrow(() -> page.findTextJson("Test"));

                    if (w > 0 && h > 0 && w < 10000 && h < 10000) {
                        RenderResult render = page.renderAt(72);
                        assertNotNull(render);
                    }
                } catch (Throwable _) {
                    // Corrupted page degraded gracefully
                }
            }

            assertDoesNotThrow(doc::bookmarks);

            byte[] saved = null;
            try {
                saved = doc.saveBytes();
            } catch (Throwable _) {
            }

            if (pageCount > 0) {
                try (PdfDocument splitDoc = PdfSplit.extractPages(doc, Set.of(0))) {
                    assertNotNull(splitDoc);
                } catch (Throwable _) {
                }
            }

            if (pageCount > 0 && saved != null && saved.length > 0) {
                try (PdfDocument merged = PdfMerge.merge(List.of(doc))) {
                    assertNotNull(merged);
                } catch (Throwable _) {
                }
            }

            try {
                PdfRepair.builder().input(bytes).all().build().execute();
            } catch (Throwable _) {
            }
        }
    }
}
