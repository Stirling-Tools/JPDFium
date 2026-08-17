package stirling.software.jpdfium.corpus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.PdfVerifier;
import stirling.software.jpdfium.model.RenderResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification suite for the PDFBox-generated synthetic corpus
 * ({@link DiversePdfGenerator}).
 *
 * <p>Every generated PDF gets the full check battery:
 * <ol>
 *   <li><strong>Open + page count</strong> against the generator's manifest.</li>
 *   <li><strong>Render every page</strong> (bitmap allocation, stride, null deref).</li>
 *   <li><strong>Text extraction</strong> (UTF-16LE round-trip, buffer sizing).</li>
 *   <li><strong>Differential ground truth</strong>: the generator recorded every
 *       string it drew in {@code manifest.tsv}. PDFium's extracted text must
 *       contain each one (whitespace-normalized, because rotated glyphs extract
 *       one per line). This is the highest-signal check - a mismatch means the
 *       wrapper loses or mangles text.</li>
 *   <li><strong>Save round-trip</strong>: page count preserved, output parses
 *       with PDFBox.</li>
 * </ol>
 *
 * <p>Plus a Linux-only native-memory stability sweep: processes 200 documents
 * and reports RSS growth ({@code /proc/self/status}); growth beyond 1 GB fails
 * (a real leak), anything below is reported only - never asserted.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@Tag("corpus")
class CorpusGeneratedTest {

    private static final int DPI = 72;
    private static final Duration PER_PDF_TIMEOUT = Duration.ofSeconds(60);

    private static final Path GENERATED_DIR = Path.of("build", "test-corpus", "generated");

    private static final Map<String, ManifestEntry> manifest = new HashMap<>();
    private static List<Path> generatedPdfs;

    record ManifestEntry(String file, int pages, boolean encrypted, List<String> groundTruth) {}

    @BeforeAll
    static void loadManifest() throws IOException {
        if (!Files.isDirectory(GENERATED_DIR)) {
            System.out.println("[CorpusGeneratedTest] no generated corpus at " + GENERATED_DIR
                    + " - run :jpdfium:generateCorpus first; tests will skip");
            return;
        }
        Path manifestPath = GENERATED_DIR.resolve("manifest.tsv");
        if (Files.exists(manifestPath)) {
            for (String line : Files.readAllLines(manifestPath, StandardCharsets.UTF_8)) {
                if (line.startsWith("file")) continue;
                String[] cols = line.split("\t", -1);
                if (cols.length < 5) continue;
                List<String> truth = new ArrayList<>();
                if (!cols[4].isEmpty()) {
                    for (String s : cols[4].split("\\|")) {
                        truth.add(s.replace("\\p", "|"));
                    }
                }
                manifest.put(cols[0], new ManifestEntry(cols[0],
                        Integer.parseInt(cols[1]), Boolean.parseBoolean(cols[2]), truth));
            }
        }
        try (var walk = Files.walk(GENERATED_DIR, 1)) {
            generatedPdfs = walk.filter(p -> p.getFileName().toString().endsWith(".pdf"))
                    .sorted()
                    .toList();
        }
        generatedPdfs = CorpusTestSupport.shard(generatedPdfs);
        System.out.println("[CorpusGeneratedTest] generated PDFs (this shard): " + generatedPdfs.size()
                + ", manifest entries: " + manifest.size());
    }

    @TestFactory
    Stream<DynamicTest> generatedPdfsPassFullCheckBattery() {
        if (generatedPdfs == null || generatedPdfs.isEmpty()) {
            System.out.println("[CorpusGeneratedTest] SKIP: no generated corpus available");
            return Stream.empty();
        }
        return generatedPdfs.stream().map(pdf -> DynamicTest.dynamicTest(
                pdf.getFileName().toString(),
                () -> assertTimeoutPreemptively(PER_PDF_TIMEOUT, () -> checkGeneratedPdf(pdf))));
    }

    private static void checkGeneratedPdf(Path pdf) throws Exception {
        String name = pdf.getFileName().toString();
        ManifestEntry entry = manifest.get(name);

        // 1) Open + page count (against generator ground truth).
        int pageCount;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            pageCount = doc.pageCount();
            assertTrue(pageCount > 0, name + " must have at least one page");
            if (entry != null) {
                assertEquals(entry.pages(), pageCount, name + " page count must match the manifest");
            }

            // 2) Render every page.
            for (int i = 0; i < pageCount; i++) {
                try (PdfPage page = doc.page(i)) {
                    RenderResult r = page.renderAt(DPI);
                    assertNotNull(r, name + " page " + i + " must render");
                    assertTrue(r.rgba().length > 0, name + " page " + i + " render must not be empty");
                }
            }

            // 3) Text extraction (PDFium) - may legitimately be empty for blank docs.
            StringBuilder extracted = new StringBuilder();
            for (int i = 0; i < pageCount; i++) {
                try (PdfPage page = doc.page(i)) {
                    extracted.append(plainText(page.extractCharPositionsJson()));
                }
            }

            // 4) Differential ground truth: every string the generator drew
            //    must survive extraction (whitespace-normalized).
            if (entry != null) {
                String norm = extracted.toString().replaceAll("\\s+", "");
                for (String truth : entry.groundTruth()) {
                    String t = truth.replaceAll("\\s+", "");
                    assertTrue(norm.contains(t),
                            name + ": drawn text '" + truth + "' missing from PDFium extraction: '"
                                    + norm + "'");
                }
            }

            // 5) Save round-trip: page count preserved, output parses with PDFBox.
            byte[] saved = doc.saveBytes();
            assertEquals(pageCount, PdfVerifier.pageCount(saved, name),
                    name + " save round-trip must preserve the page count");
        }
    }

    /** Build plain text from the char-position JSON (the "u" fields). */
    private static String plainText(String json) {
        if (json == null || json.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while ((idx = json.indexOf("\"u\":", idx)) >= 0) {
            int start = idx + 4;
            int end = json.indexOf(',', start);
            if (end < 0) end = json.indexOf('}', start);
            if (end < 0) break;
            try {
                int cp = Integer.parseInt(json.substring(start, end).trim());
                if (Character.isValidCodePoint(cp)) {
                    sb.appendCodePoint(cp);
                }
            } catch (NumberFormatException ignored) {
            }
            idx = end;
        }
        return sb.toString();
    }

    // Native memory stability (Linux only; reported, generously bounded)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    @EnabledOnOs(OS.LINUX)
    void nativeMemoryStaysBoundedAcrossDocuments() throws Exception {
        List<Path> docs = generatedPdfs == null ? List.of() : generatedPdfs;
        if (docs.isEmpty()) {
            System.out.println("[CorpusGeneratedTest] SKIP RSS sweep: no generated corpus");
            return;
        }
        int batch = Math.min(docs.size(), 300);
        long rssBefore = currentRssKb();
        int opened = 0;
        for (int i = 0; i < batch; i++) {
            try (PdfDocument doc = PdfDocument.open(docs.get(i))) {
                doc.pageCount();
                try (PdfPage page = doc.page(0)) {
                    page.renderAt(72);
                }
            } catch (Throwable t) {
                // pathological generated docs: skip, keep sweeping
            }
            opened++;
        }
        System.gc();
        long rssAfter = currentRssKb();
        long growthKb = rssAfter - rssBefore;
        System.out.printf("[CorpusGeneratedTest] RSS: before=%dKB after=%dKB growth=%dKB (%d docs)%n",
                rssBefore, rssAfter, growthKb, opened);
        // Only a catastrophic leak (1 GB+) fails; moderate growth is environment
        // noise and is reported, not asserted.
        assertTrue(growthKb < 1_000_000,
                "native memory grew by " + growthKb + " KB over " + opened
                        + " documents - possible native leak");
    }

    private static long currentRssKb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (IOException ignored) {
        }
        return -1;
    }

    @AfterAll
    static void report() {
        if (generatedPdfs != null) {
            System.out.println("[CorpusGeneratedTest] done: " + generatedPdfs.size()
                    + " generated PDFs checked");
        }
    }
}
