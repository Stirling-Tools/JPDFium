package stirling.software.jpdfium.corpus;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfColorConverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the CMYK vector+image pipeline over a broad slice of the real PDF
 * corpus. This is deliberately a smoke test: malformed inputs that cannot be
 * opened are skipped, but any file that opens must survive conversion, save
 * and reopen with the same page count.
 *
 * <p>The default limit keeps the corpus job bounded; set
 * {@code -Djpdfium.pdfx.corpus.limit=0} to run every corpus file.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@Tag("corpus")
class PdfXCmykCorpusTest {

    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

    @TestFactory
    Stream<DynamicTest> cmykConversionRoundTripsCorpus() throws IOException {
        assumeTrue(PdfColorConverter.supportsColorSpace(PdfColorConverter.ColorSpace.CMYK),
                "CMYK pipeline unavailable");

        int limit = Integer.getInteger("jpdfium.pdfx.corpus.limit", 200);
        List<Path> files = CorpusTestSupport.gatherCorpus().stream()
                .filter(PdfXCmykCorpusTest::isCandidate)
                .limit(limit <= 0 ? Long.MAX_VALUE : limit)
                .toList();
        System.out.printf("[pdfx-corpus] testing %d files (limit=%d)%n", files.size(), limit);
        return files.stream().map(path -> DynamicTest.dynamicTest(
                path.getFileName().toString(), () -> testOne(path)));
    }

    private static boolean isCandidate(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0
                    && Files.size(path) <= MAX_FILE_BYTES;
        } catch (IOException _) {
            return false;
        }
    }

    private static void testOne(Path input) throws Exception {
        PdfDocument opened;
        try {
            opened = PdfDocument.open(input);
        } catch (Exception e) {
            assumeTrue(false, "corpus file cannot be opened: " + e.getMessage());
            return;
        }
        try (PdfDocument doc = opened) {
            int pages = doc.pageCount();
            if (pages <= 0) {
                assumeTrue(false, "corpus file has no pages");
                return;
            }
            // Some real-world files advertise a page count but cannot open
            // every page. The corpus is allowed to contain those; skip them
            // rather than treating a malformed input as a conversion failure.
            try {
                for (int i = 0; i < pages; i++) {
                    doc.page(i).close();
                }
            } catch (Exception e) {
                assumeTrue(false, "corpus file has an unopenable page: " + e.getMessage());
                return;
            }
            PdfColorConverter.toCmyk(doc);
            byte[] output = doc.saveBytes();
            assertTrue(output.length > 0, "conversion produced no bytes");
            try (PdfDocument reopened = PdfDocument.open(output)) {
                assertEquals(pages, reopened.pageCount(),
                        "page count changed after CMYK conversion");
            }
        }
    }
}
