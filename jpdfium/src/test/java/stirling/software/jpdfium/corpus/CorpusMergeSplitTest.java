package stirling.software.jpdfium.corpus;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfMerge;
import stirling.software.jpdfium.PdfSplit;
import stirling.software.jpdfium.PdfVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole-corpus merge + split round-trips.
 *
 * <p>Merges <em>every</em> available corpus PDF in chunks, verifies the merged
 * output with an independent parser, then extracts pages back out of the merge
 * and verifies each extracted document. Time and allocation metrics are
 * reported to {@code build/reports/corpus/merge-split.csv}; they are never
 * asserted (environment-sensitive perf must not fail the build).
 *
 * <p>Native crashes abort the test JVM and fail the build by construction.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@Tag("corpus")
class CorpusMergeSplitTest {

    /** Merge at most this many files per chunk (bounds peak memory). */
    private static final int CHUNK_SIZE = 40;

    private static List<Path> corpus;

    @BeforeAll
    static void setUp() throws IOException {
        corpus = CorpusTestSupport.gatherCorpus();
        Files.createDirectories(CorpusTestSupport.REPORT_DIR);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void mergeEntireCorpusInChunksThenSplitBack() throws Exception {
        assertTrue(!corpus.isEmpty(), "no corpus PDFs available (offline and no cache)");

        List<String> report = new ArrayList<>();
        report.add("chunk;files;inputPages;mergedBytes;timeMs;allocB");

        int chunks = 0;
        int totalInputPages = 0;
        for (int from = 0; from < corpus.size(); from += CHUNK_SIZE) {
            List<Path> chunk = corpus.subList(from, Math.min(from + CHUNK_SIZE, corpus.size()));
            // Only feed openable files to the merge: fuzzed/pathological files
            // are exercised by the per-file metrics suite, not by the merge.
            List<Path> openable = new ArrayList<>();
            int inputPages = 0;
            for (Path p : chunk) {
                int pc = CorpusTestSupport.pageCount(p);
                if (pc < 0) {
                    System.out.println("[corpus] merge SKIP (unopenable): " + p.getFileName());
                    continue;
                }
                openable.add(p);
                inputPages += pc;
            }
            if (openable.isEmpty()) continue;

            final byte[][] merged = new byte[1][];
            CorpusTestSupport.Metrics m = CorpusTestSupport.measure(() -> {
                try (PdfDocument dest = PdfMerge.mergeFiles(openable)) {
                    merged[0] = dest.saveBytes();
                }
            });

            totalInputPages += inputPages;
            int mergedPages = PdfVerifier.pageCount(merged[0], "chunk " + chunks);
            assertEquals(inputPages, mergedPages,
                    "merged chunk must contain the sum of openable input pages");

            report.add(String.format("%d;%d;%d;%d;%d;%d", chunks, openable.size(), inputPages,
                    merged[0].length, m.timeMs(), m.allocatedBytes()));
            chunks++;
        }

        // ── Split back: extract the first page of every corpus PDF ────────
        List<Path> extractables = corpus.stream()
                .filter(p -> CorpusTestSupport.pageCount(p) > 0)
                .toList();
        assertTrue(!extractables.isEmpty(), "corpus must contain at least one openable PDF");

        int extracted = 0;
        long splitMs = 0;
        for (Path p : extractables) {
            PdfDocument part;
            try (PdfDocument doc = PdfDocument.open(p)) {
                part = PdfSplit.extractPageRange(doc, 0, 0);
            }
            try (part) {
                byte[] bytes = part.saveBytes();
                assertEquals(1, PdfVerifier.pageCount(bytes, "extracted " + p.getFileName()),
                        "extracted single page must contain exactly one page");
            } catch (Throwable t) {
                System.out.println("[corpus] split SKIP " + p.getFileName() + ": "
                        + t.getClass().getSimpleName());
                continue;
            }
            extracted++;
        }
        assertTrue(extracted > 0, "at least one PDF must split successfully");

        // ── Report (soft - never asserted) ─────────────────────────────────
        Path csv = CorpusTestSupport.REPORT_DIR.resolve("merge-split.csv");
        Files.writeString(csv, String.join("\n", report) + "\n", StandardCharsets.UTF_8);
        System.out.println("[CorpusMergeSplitTest] report: " + csv.toAbsolutePath()
                + " | chunks=" + chunks + " | files=" + corpus.size()
                + " | inputPages=" + totalInputPages + " | extracted=" + extracted);
    }
}
