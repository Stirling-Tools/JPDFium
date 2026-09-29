package stirling.software.jpdfium.redact;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Multi-page book redaction benchmark with decomposed stage profiling.
 * Separates cold and warmed runs, recording setup, text acq, prefilter, and mutation.
 */
class MultiPageBookRedactionBenchmarkTest {

    private static byte[] book50Pages;
    private static byte[] book100Pages;

    @BeforeAll
    static void setUpCorpus() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "Book redaction requires real PDFium native library");
        book50Pages = createBookPdf(50);
        book100Pages = createBookPdf(100);

        // Explicit JIT warmup pass to eliminate classloading and C2 compilation noise.
        List<String> warmupDict = List.of("ConfidentialAlpha", "TopSecretBeta", "UnusedWordWarmup");
        RedactOptions warmupOptions = RedactOptions.builder()
                .addWords(warmupDict)
                .wholeWord(true)
                .caseSensitive(true)
                .build();
        for (int w = 0; w < 3; w++) {
            try (PdfDocument doc = PdfDocument.open(book50Pages)) {
                PdfRedactor.redact(doc, warmupOptions);
            }
        }
    }

    @Test
    void testBookRedactionWith5000WordsAcross50PagesWarmed() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "Book redaction requires real PDFium native library");
        int pageCount = 50;
        List<String> dictionary = buildDictionary(5000);

        RedactOptions options = RedactOptions.builder()
                .addWords(dictionary)
                .wholeWord(true)
                .caseSensitive(true)
                .build();

        RedactionPipelineProfiler profiler = new RedactionPipelineProfiler();
        RedactResult result;
        try (PdfDocument doc = PdfDocument.open(book50Pages)) {
            result = PdfRedactor.redact(doc, options, profiler);
        }

        int totalMatches = countMatches(result);
        assertEquals(6, totalMatches);

        System.out.println("\n[WARMED RUN] 5,000 words across 50 pages:");
        System.out.println(profiler.generateReport());
    }

    @Test
    void testBookRedactionWith10000WordsAcross100PagesWarmed() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "Book redaction requires real PDFium native library");
        int pageCount = 100;
        List<String> dictionary = buildDictionary(10000);

        RedactOptions options = RedactOptions.builder()
                .addWords(dictionary)
                .wholeWord(true)
                .caseSensitive(true)
                .build();

        RedactionPipelineProfiler profiler = new RedactionPipelineProfiler();
        RedactResult result;
        try (PdfDocument doc = PdfDocument.open(book100Pages)) {
            result = PdfRedactor.redact(doc, options, profiler);
        }

        int totalMatches = countMatches(result);
        assertEquals(6, totalMatches);

        System.out.println("\n[WARMED RUN] 10,000 words across 100 pages:");
        System.out.println(profiler.generateReport());
    }

    @Test
    void testRedactionSessionWith5000WordsAcross50Pages() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "Book redaction requires real PDFium native library");
        int pageCount = 50;
        String[] dictionary = new String[5000];
        for (int i = 0; i < 4998; i++) {
            dictionary[i] = "UnusedWord" + i;
        }
        dictionary[4998] = "ConfidentialAlpha";
        dictionary[4999] = "TopSecretBeta";

        int totalMarked;
        RedactionSession.CommitResult commitResult;
        try (RedactionSession session = RedactionSession.open(book50Pages)) {
            totalMarked = session.markWords(dictionary, 0xFF000000, 1.0f, true, false, true);
            assertEquals(6, totalMarked);
            assertEquals(6, session.dirtyPageIndices().size());
            commitResult = session.commitAll();
        }

        System.out.println("\n[ISOLATED SESSION] 5,000 words mark and commit across 50 pages");
        assertEquals(6, commitResult.pageCommits().size());
    }

    private static List<String> buildDictionary(int totalWords) {
        List<String> dictionary = new ArrayList<>(totalWords);
        for (int i = 0; i < totalWords - 2; i++) {
            dictionary.add("UnusedWord" + i);
        }
        dictionary.add("ConfidentialAlpha");
        dictionary.add("TopSecretBeta");
        return dictionary;
    }

    private static int countMatches(RedactResult result) {
        int total = 0;
        for (RedactResult.PageResult pr : result.pageResults()) {
            if (pr.matchesFound() > 0) {
                total += pr.matchesFound();
            }
        }
        return total;
    }

    private static byte[] createBookPdf(int pageCount) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("This is page number " + i + " of the book.");
                    cs.newLineAtOffset(0, -20);
                    cs.showText("Standard paragraph content with ordinary text and information.");

                    if (i == 5 || i == 15 || i == 25) {
                        cs.newLineAtOffset(0, -20);
                        cs.showText("Contains ConfidentialAlpha secret clause.");
                    } else if (i == 10 || i == 20 || i == 30) {
                        cs.newLineAtOffset(0, -20);
                        cs.showText("Contains TopSecretBeta special classification.");
                    }
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
