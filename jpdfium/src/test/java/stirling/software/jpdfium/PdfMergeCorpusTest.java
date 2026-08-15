package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corpus-driven merge correctness: merges a diverse set of PDFs (text-only,
 * form fields, annotations, fonts, images) and verifies the output with an
 * independent parser (PDFBox) plus a native render of the merged result.
 *
 * <p>Integration-gated: requires the real PDFium native (the stub performs no
 * actual page import).
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfMergeCorpusTest {

    private static Path resource(String name) throws Exception {
        URL url = PdfMergeCorpusTest.class.getResource("/pdfs/general/" + name);
        assertNotNull(url, name + " test resource missing");
        return Path.of(url.toURI());
    }

    /** Diverse corpus: text, forms, annotations, exotic fonts, CJK, images. */
    private static List<Path> corpus() throws Exception {
        return List.of(
                resource("minimal.pdf"),
                resource("basic-text.pdf"),
                resource("all_form_fields.pdf"),
                resource("irs_w9.pdf"),
                resource("mozilla_tracemonkey.pdf"),
                resource("pdfjs_ZapfDingbats.pdf"),
                resource("pdfjs_annotation-underline.pdf"),
                resource("text_form.pdf"),
                resource("combobox_form.pdf"),
                resource("pdfjs_SimFang-variant.pdf"));
    }

    @Test
    void mergeFilesAcrossDiverseCorpusProducesValidStandalonePdf(@TempDir Path tmp) throws Exception {
        List<Path> sources = corpus();
        int expectedPages = 0;
        List<String> firstPageTexts = new ArrayList<>();
        for (Path p : sources) {
            try (PdfDocument d = PdfDocument.open(p)) {
                expectedPages += d.pageCount();
            }
        }
        firstPageTexts.add(PdfVerifier.pageText(Files.readAllBytes(sources.get(1)), 0, "basic-text.pdf"));

        byte[] merged;
        try (PdfDocument m = PdfMerge.mergeFiles(sources)) {
            merged = m.saveBytes();
        }

        // Independent parser sees the right page count and content.
        assertEquals(expectedPages, PdfVerifier.pageCount(merged, "merged corpus output"));
        String basicText = firstPageTexts.get(0).strip();
        assertFalse(basicText.isEmpty(), "sanity: basic-text.pdf should carry text");
        PdfVerifier.assertContainsText(merged, 3, basicText.substring(0, Math.min(40, basicText.length())),
                "merged corpus output");

        // The merged document is standalone: usable after every source closed.
        try (PdfDocument reopened = PdfDocument.open(merged)) {
            assertEquals(expectedPages, reopened.pageCount());
            Path out = tmp.resolve("merged-corpus.pdf");
            reopened.save(out);
            assertTrue(Files.size(out) > 0);
        }
    }

    @Test
    void mergeFilesIncludesSyntheticImageAndRotatedTextPdf(@TempDir Path tmp) throws Exception {
        byte[] synthetic = SyntheticPdfFactory.createDiverse(2);
        Path synthPath = tmp.resolve("synthetic-diverse.pdf");
        Files.write(synthPath, synthetic);

        List<Path> sources = List.of(
                resource("minimal.pdf"),
                synthPath,
                resource("all_form_fields.pdf"));

        int expectedPages = 0;
        for (Path p : sources) {
            try (PdfDocument d = PdfDocument.open(p)) {
                expectedPages += d.pageCount();
            }
        }

        byte[] merged;
        try (PdfDocument m = PdfMerge.mergeFiles(sources)) {
            merged = m.saveBytes();
        }

        assertEquals(expectedPages, PdfVerifier.pageCount(merged, "synthetic corpus merge"));
        PdfVerifier.assertContainsText(merged, 3, "Synthetic corpus page 1", "synthetic corpus merge");
        // Rotated glyphs extract one per line; normalize whitespace before matching.
        String pageText = PdfVerifier.pageText(merged, 3, "synthetic corpus merge").replaceAll("\\s+", "");
        assertTrue(pageText.contains("rotatedmarker1"),
                "expected rotated text in merged output but extracted: '" + pageText + "'");
    }

    @Test
    void mergeOpenDocumentsSurvivesSourcesClosedBeforeUse(@TempDir Path tmp) throws Exception {
        List<Path> corpus = corpus();
        PdfDocument merged;
        int expectedPages = 0;
        try (PdfDocument a = PdfDocument.open(corpus.get(0));
             PdfDocument b = PdfDocument.open(corpus.get(1));
             PdfDocument c = PdfDocument.open(corpus.get(2))) {
            expectedPages = a.pageCount() + b.pageCount() + c.pageCount();
            merged = PdfMerge.merge(List.of(a, b, c));
        }
        try {
            assertEquals(expectedPages, merged.pageCount());
            byte[] bytes = merged.saveBytes();
            assertEquals(expectedPages, PdfVerifier.pageCount(bytes, "merged docs after source close"));
            Path out = tmp.resolve("merged-docs.pdf");
            merged.save(out);
            assertTrue(Files.size(out) > 0);
        } finally {
            merged.close();
        }
    }

    @Test
    void mergedOutputRendersNativelyWithoutCrash(@TempDir Path tmp) throws Exception {
        List<Path> sources = List.of(
                resource("minimal.pdf"),
                resource("all_form_fields.pdf"),
                resource("irs_w9.pdf"));
        try (PdfDocument m = PdfMerge.mergeFiles(sources);
             PdfDocument reopened = PdfDocument.open(m.saveBytes())) {
            try (PdfPage page = reopened.page(0)) {
                var result = page.renderAt(96);
                assertNotNull(result);
                assertTrue(result.rgba().length > 0, "rendered merged page must not be empty");
            }
        }
    }
}
