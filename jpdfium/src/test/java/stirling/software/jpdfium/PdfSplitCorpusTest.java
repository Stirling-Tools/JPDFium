package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corpus-driven split correctness: extracted documents must be self-contained
 * (usable after the source closes) and structurally valid per PDFBox.
 *
 * <p>Integration-gated: requires the real PDFium native.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfSplitCorpusTest {

    private static Path resource(String name) throws Exception {
        URL url = PdfSplitCorpusTest.class.getResource("/pdfs/general/" + name);
        assertNotNull(url, name + " test resource missing");
        return Path.of(url.toURI());
    }

    @Test
    void extractPageRangeSurvivesSourceClosedBeforeUse(@TempDir Path tmp) throws Exception {
        PdfDocument extracted;
        int sourcePages;
        try (PdfDocument doc = PdfDocument.open(resource("irs_w9.pdf"))) {
            sourcePages = doc.pageCount();
            extracted = PdfSplit.extractPageRange(doc, 0, sourcePages - 1);
        }

        try {
            assertEquals(sourcePages, extracted.pageCount());
            byte[] bytes = extracted.saveBytes();
            assertEquals(sourcePages, PdfVerifier.pageCount(bytes, "extracted page range"));
            PdfVerifier.assertNonEmptyText(bytes, 0, "extracted page range");
            Path out = tmp.resolve("extracted-range.pdf");
            extracted.save(out);
            assertTrue(Files.size(out) > 0);
        } finally {
            extracted.close();
        }
    }

    @Test
    void extractPagesByIndexKeepsOnlyRequestedPages(@TempDir Path tmp) throws Exception {
        PdfDocument extracted;
        try (PdfDocument doc = PdfDocument.open(resource("minimal.pdf"))) {
            assertEquals(3, doc.pageCount(), "minimal.pdf fixture must have 3 pages");
            extracted = PdfSplit.extractPages(doc, Set.of(0, 2));
        }

        try {
            assertEquals(2, extracted.pageCount());
            byte[] bytes = extracted.saveBytes();
            assertEquals(2, PdfVerifier.pageCount(bytes, "extracted pages {0,2}"));
            Path out = tmp.resolve("extracted-pages.pdf");
            extracted.save(out);
            assertTrue(Files.size(out) > 0);
        } finally {
            extracted.close();
        }
    }

    @Test
    void splitSinglePagesProducesStandaloneOnePageDocuments(@TempDir Path tmp) throws Exception {
        List<PdfDocument> parts;
        int sourcePages;
        try (PdfDocument doc = PdfDocument.open(resource("minimal.pdf"))) {
            sourcePages = doc.pageCount();
            parts = PdfSplit.split(doc, PdfSplit.SplitStrategy.singlePages());
        }

        try {
            assertEquals(sourcePages, parts.size());
            for (int i = 0; i < parts.size(); i++) {
                try (PdfDocument part = parts.get(i)) {
                    assertEquals(1, part.pageCount(), "part " + i);
                    byte[] bytes = part.saveBytes();
                    assertEquals(1, PdfVerifier.pageCount(bytes, "single-page split part " + i));
                    Path out = tmp.resolve("split-page-" + i + ".pdf");
                    part.save(out);
                    assertTrue(Files.size(out) > 0);
                }
            }
        } finally {
            for (PdfDocument p : parts) p.close();
        }
    }

    @Test
    void splitEveryNPagesRoundTripsFullPageCount(@TempDir Path tmp) throws Exception {
        List<PdfDocument> parts;
        int sourcePages;
        try (PdfDocument doc = PdfDocument.open(resource("minimal.pdf"))) {
            sourcePages = doc.pageCount();
            parts = PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(2));
        }

        try {
            assertEquals((sourcePages + 1) / 2, parts.size());
            int total = 0;
            for (PdfDocument part : parts) {
                total += part.pageCount();
            }
            assertEquals(sourcePages, total);
            for (int i = 0; i < parts.size(); i++) {
                try (PdfDocument part = parts.get(i)) {
                    Path out = tmp.resolve("split-chunk-" + i + ".pdf");
                    part.save(out);
                    assertTrue(Files.size(out) > 0);
                }
            }
        } finally {
            for (PdfDocument p : parts) p.close();
        }
    }
}
