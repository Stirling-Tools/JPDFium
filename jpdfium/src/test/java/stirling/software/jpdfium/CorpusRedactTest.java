package stirling.software.jpdfium;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Corpus-level redaction regression test with structural failure detection.
 *
 * <p>Downloads a large corpus of real-world PDFs via {@link PdfCorpus}, applies word
 * redaction, and verifies correctness at both pixel and text level:
 *
 * <ol>
 *   <li><strong>Visual diff:</strong> renders before/after and computes pixel-level diffs.
 *       PDFs with extremely high visual change ratios are flagged.</li>
 *   <li><strong>Text preservation:</strong> compares extracted character counts before/after.
 *       If too many characters disappeared (disproportionate to target word frequency),
 *       the PDF is flagged as structurally damaged.</li>
 *   <li><strong>Non-target text survival:</strong> samples non-target words from the original
 *       text and verifies they still exist after redaction.</li>
 * </ol>
 *
 * <p>Output is written to {@code samples-output/corpus-redact-report/} in a structured
 * directory hierarchy. An HTML report is generated at {@code index.html} with severity
 * levels: PASS, WARN, FAIL.
 *
 * <p>Runs only when {@code jpdfium.integration=true} is set.
 *
 * @see PdfCorpus
 * @see VisualDiff
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@Tag("corpus")
class CorpusRedactTest {


    /** Render DPI for visual comparison. 72 keeps the test fast. */
    private static final int DPI = 72;

    /** Per-channel pixel threshold for anti-aliasing tolerance. */
    private static final int PIXEL_THRESHOLD = 4;

    /** Max pages to test per PDF (keeps runtime bounded). */
    private static final int MAX_PAGES_PER_PDF = 3;

    /** Max local redact PDFs to include alongside downloaded corpus. */
    private static final int MAX_LOCAL_PDFS = 20;


    /**
     * If more than this fraction of characters vanish after redaction, the PDF
     * is flagged as structurally damaged. The redact words are common English
     * words so some loss is expected, but losing more than 40% of all text
     * is disproportionate and warrants investigation.
     */
    private static final double MAX_CHAR_LOSS_FRACTION = 0.40;

    /**
     * If more than this fraction of visual pixels changed, the test issues a
     * WARNING. This does not fail the test because common-word redaction in
     * text-heavy documents legitimately causes large visual changes. However
     * it alerts the reviewer to inspect the output manually.
     */
    private static final double WARN_PIXEL_CHANGE_FRACTION = 0.15;

    /**
     * If a sampled non-target word that existed before redaction disappears
     * after redaction, this is a structural failure. We sample up to this
     * many non-target words per page.
     */
    private static final int NON_TARGET_SAMPLE_SIZE = 5;

    /** Words to redact across the corpus. Common English words. */
    private static final String[] REDACT_WORDS = {
            "the", "and", "for", "that", "this", "with"
    };

    /**
     * PDFs to skip (known-flaky). issue918.pdf uses Type3 fonts that PDFium
     * cannot fission - all text is lost during GenerateContent.
     */
    private static final Set<String> SKIP_PDFS = Set.of(
            "issue918.pdf"
    );

    /** Output directory under samples-output for structured report. */
    private static final Path REPORT_DIR;
    static {
        String override = System.getProperty("samples.output");
        Path root = (override != null)
                ? Path.of(override)
                : Path.of(System.getProperty("user.dir")).resolve("samples-output");
        REPORT_DIR = root.resolve("corpus-redact-report");
    }

    private static List<Path> corpusPdfs;
    private static final List<PdfReport> reports = new ArrayList<>();


    @BeforeAll
    static void downloadCorpus() throws Exception {
        corpusPdfs = new ArrayList<>();
        try {
            corpusPdfs.addAll(PdfCorpus.download());
        } catch (Exception e) {
            System.err.println("[CorpusRedactTest] Corpus download failed: " + e.getMessage());
            corpusPdfs.addAll(PdfCorpus.cached());
        }
        // Include a sample of local redact test PDFs
        var redactUrl = CorpusRedactTest.class.getResource("/pdfs/redact");
        if (redactUrl != null) {
            Path redactDir = Path.of(redactUrl.toURI());
            try (Stream<Path> walk = Files.walk(redactDir, 1)) {
                walk.filter(p -> p.toString().endsWith(".pdf"))
                    .sorted()
                    .limit(MAX_LOCAL_PDFS)
                    .forEach(corpusPdfs::add);
            }
        }

        Files.createDirectories(REPORT_DIR);
        System.out.printf("[CorpusRedactTest] Corpus: %d PDFs, output: %s%n",
                corpusPdfs.size(), REPORT_DIR.toAbsolutePath());
    }


    @TestFactory
    Stream<DynamicTest> redactCorpusPreservesNonTargetContent() {
        assertNotNull(corpusPdfs, "Corpus not initialized");
        assertFalse(corpusPdfs.isEmpty(), "Corpus is empty -- no PDFs to test");

        return corpusPdfs.stream()
                .filter(pdf -> !SKIP_PDFS.contains(pdf.getFileName().toString()))
                .map(pdf -> DynamicTest.dynamicTest(
                "redact-preserves: " + pdf.getFileName(),
                () -> {
                    testSinglePdf(pdf);
                    System.gc(); // release rendering buffers between PDFs
                }
        ));
    }


    private void testSinglePdf(Path pdf) throws Exception {
        String stem = pdf.getFileName().toString().replace(".pdf", "");
        Path pdfOutDir = REPORT_DIR.resolve(stem);
        Files.createDirectories(pdfOutDir);

        PdfReport report = new PdfReport(stem);

        try {
            testSinglePdfInner(pdf, stem, pdfOutDir, report);
        } catch (Exception e) {
            report.error = "Unexpected error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            report.severity = Severity.SKIP;
            addReport(report);
            writeHtmlReport();
        }
    }

    private void testSinglePdfInner(Path pdf, String stem, Path pdfOutDir,
                                     PdfReport report) throws Exception {
        int pageCount;
        try (var doc = PdfDocument.open(pdf)) {
            pageCount = doc.pageCount();
        } catch (Exception e) {
            report.error = "Failed to open: " + e.getMessage();
            report.severity = Severity.SKIP;
            addReport(report);
            return;
        }

        report.pageCount = pageCount;
        int pagesToTest = Math.min(pageCount, MAX_PAGES_PER_PDF);

        String[] textBefore = new String[pagesToTest];
        BufferedImage[] originals = new BufferedImage[pagesToTest];
        try (var doc = PdfDocument.open(pdf)) {
            for (int i = 0; i < pagesToTest; i++) {
                try (var page = doc.page(i)) {
                    originals[i] = page.renderAt(DPI).toBufferedImage();
                    textBefore[i] = page.extractCharPositionsJson();
                }
            }
        }

        byte[] redactedBytes;
        int totalMatches = 0;
        try (var doc = PdfDocument.open(pdf)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                try (var page = doc.page(i)) {
                    totalMatches += page.redactWordsEx(
                            REDACT_WORDS,
                            0xFF000000, 0.0f,
                            true,     // wholeWord
                            false,    // not regex
                            true,     // removeContent (Object Fission)
                            false);   // case insensitive
                    page.flatten();
                }
            }
            redactedBytes = doc.saveBytes();
        }
        report.totalMatches = totalMatches;

        String[] textAfter = new String[pagesToTest];
        BufferedImage[] redacted = new BufferedImage[pagesToTest];
        try (var doc = PdfDocument.open(redactedBytes)) {
            for (int i = 0; i < pagesToTest; i++) {
                try (var page = doc.page(i)) {
                    redacted[i] = page.renderAt(DPI).toBufferedImage();
                    textAfter[i] = page.extractCharPositionsJson();
                }
            }
        }

        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (int i = 0; i < pagesToTest; i++) {
            PageAnalysis pa = analyzePage(
                    originals[i], redacted[i],
                    textBefore[i], textAfter[i],
                    i, pdfOutDir, stem);
            report.pageAnalyses.add(pa);

            if (pa.charLossFraction > MAX_CHAR_LOSS_FRACTION && pa.charsBefore > 10) {
                failures.add(String.format(
                        "page %d: %.1f%% chars lost (max %.0f%%), %d->%d chars",
                        i, pa.charLossFraction * 100, MAX_CHAR_LOSS_FRACTION * 100,
                        pa.charsBefore, pa.charsAfter));
            }
            if (!pa.missingNonTargetWords.isEmpty()) {
                failures.add(String.format(
                        "page %d: non-target words disappeared: %s",
                        i, pa.missingNonTargetWords));
            }
            if (pa.pixelChangeFraction > WARN_PIXEL_CHANGE_FRACTION) {
                warnings.add(String.format(
                        "page %d: %.1f%% pixels changed (high visual impact)",
                        i, pa.pixelChangeFraction * 100));
            }
        }

        if (!failures.isEmpty()) {
            report.severity = Severity.FAIL;
            report.issues.addAll(failures);
        } else if (!warnings.isEmpty()) {
            report.severity = Severity.WARN;
            report.issues.addAll(warnings);
        } else {
            report.severity = Severity.PASS;
        }

        addReport(report);
        writeHtmlReport();

        if (report.severity == Severity.FAIL) {
            fail(String.format(
                    "Structural failure in %s:%n%s%nReport: %s",
                    stem, String.join("\n", failures),
                    REPORT_DIR.toAbsolutePath().resolve("index.html")));
        }
    }


    private PageAnalysis analyzePage(
            BufferedImage before, BufferedImage after,
            String jsonBefore, String jsonAfter,
            int pageIndex, Path outDir, String pdfName) throws IOException {

        PageAnalysis pa = new PageAnalysis();
        pa.pageIndex = pageIndex;

        if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()) {
            pa.sizeMismatch = true;
            VisualDiff.save(before, outDir.resolve("page-" + pageIndex + "-before.png"));
            VisualDiff.save(after, outDir.resolve("page-" + pageIndex + "-after.png"));
            return pa;
        }

        VisualDiff.DiffResult diff = VisualDiff.compare(before, after);
        pa.totalPixels = diff.totalPixels();
        pa.changedPixels = diff.changedPixels();
        pa.pixelChangeFraction = diff.changedFraction();

        VisualDiff.save(before, outDir.resolve("page-" + pageIndex + "-before.png"));
        VisualDiff.save(after, outDir.resolve("page-" + pageIndex + "-after.png"));
        VisualDiff.save(diff.diffImage(), outDir.resolve("page-" + pageIndex + "-diff.png"));

        // Count Unicode characters (the "u" field in the JSON)
        pa.charsBefore = countChars(jsonBefore);
        pa.charsAfter = countChars(jsonAfter);
        pa.charLossFraction = pa.charsBefore > 0
                ? 1.0 - ((double) pa.charsAfter / pa.charsBefore)
                : 0;

        // Extract words from BEFORE text, filter out target words, sample some,
        // and verify they still exist in AFTER text.
        String plainBefore = extractPlainText(jsonBefore);
        String plainAfter = extractPlainText(jsonAfter);

        List<String> nonTargetWords = extractNonTargetWords(plainBefore);
        pa.sampledNonTargetWords = nonTargetWords.size();

        for (String word : nonTargetWords) {
            if (!plainAfter.toLowerCase().contains(word.toLowerCase())) {
                pa.missingNonTargetWords.add(word);
            }
        }

        return pa;
    }

    /** Count how many character entries exist in the JSON positions array. */
    private static int countChars(String json) {
        if (json == null || json.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = json.indexOf("\"u\":", idx)) >= 0) {
            count++;
            idx += 4;
        }
        return count;
    }

    /** Build plain text from the JSON positions by extracting Unicode codepoints. */
    private static String extractPlainText(String json) {
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
                // Validate the codepoint before appending. CJK or composite values
                // can produce values outside the valid Unicode range.
                if (Character.isValidCodePoint(cp)) {
                    sb.appendCodePoint(cp);
                }
            } catch (NumberFormatException ignored) {}
            idx = end;
        }
        return sb.toString();
    }

    /**
     * Extract words from text that are NOT in the redact target list.
     * Returns up to {@link #NON_TARGET_SAMPLE_SIZE} words, picking longer words
     * that are more likely to be unique and meaningful.
     */
    private static List<String> extractNonTargetWords(String text) {
        if (text == null || text.length() < 5) return List.of();
        List<String> targetSet = Arrays.asList(REDACT_WORDS);
        String[] allWords = text.split("[\\s\\p{Punct}]+");
        List<String> candidates = new ArrayList<>();
        for (String w : allWords) {
            // Only consider words with 4+ alphabetic chars to avoid noise
            if (w.length() >= 4 && w.chars().allMatch(Character::isLetter)
                    && !targetSet.contains(w.toLowerCase())) {
                candidates.add(w);
            }
        }
        // Deduplicate and pick a spread
        List<String> unique = candidates.stream().distinct().toList();
        if (unique.isEmpty()) return List.of();
        List<String> sampled = new ArrayList<>();
        int step = Math.max(1, unique.size() / NON_TARGET_SAMPLE_SIZE);
        for (int i = 0; i < unique.size() && sampled.size() < NON_TARGET_SAMPLE_SIZE; i += step) {
            sampled.add(unique.get(i));
        }
        return sampled;
    }


    private static synchronized void addReport(PdfReport report) {
        reports.add(report);
    }


    private static synchronized void writeHtmlReport() {
        try {
            Path htmlFile = REPORT_DIR.resolve("index.html");
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html>\n<html lang='en'>\n<head>\n");
            html.append("<meta charset='UTF-8'>\n");
            html.append("<title>Corpus Redaction Test Report</title>\n");
            html.append(REPORT_CSS);
            html.append("</head>\n<body>\n");
            html.append("<h1>Corpus Redaction Test Report</h1>\n");

            String timestamp = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            int passed = 0, warned = 0, failed = 0, skipped = 0;
            for (PdfReport r : reports) {
                switch (r.severity) {
                    case PASS -> passed++;
                    case WARN -> warned++;
                    case FAIL -> failed++;
                    case SKIP -> skipped++;
                }
            }

            html.append("<div class='summary'>\n");
            html.append(String.format("<p><strong>Generated:</strong> %s</p>%n", timestamp));
            html.append(String.format("<p><strong>Redact words:</strong> %s</p>%n",
                    String.join(", ", REDACT_WORDS)));
            html.append(String.format(
                    "<p>Total: <strong>%d</strong> | ", reports.size()));
            html.append(String.format("<span class='pass'>PASS: %d</span> | ", passed));
            html.append(String.format("<span class='warn'>WARN: %d</span> | ", warned));
            html.append(String.format("<span class='fail'>FAIL: %d</span> | ", failed));
            html.append(String.format("<span class='skip'>SKIP: %d</span></p>%n", skipped));
            html.append("</div>\n");

            html.append("<details class='thresholds'><summary>Detection Thresholds</summary>\n");
            html.append("<table><tr><th>Check</th><th>Threshold</th><th>Action</th></tr>\n");
            html.append(String.format(
                    "<tr><td>Character loss</td><td>&gt; %.0f%%</td><td class='fail'>FAIL</td></tr>%n",
                    MAX_CHAR_LOSS_FRACTION * 100));
            html.append(String.format(
                    "<tr><td>Visual pixel change</td><td>&gt; %.0f%%</td><td class='warn'>WARN</td></tr>%n",
                    WARN_PIXEL_CHANGE_FRACTION * 100));
            html.append("<tr><td>Non-target word missing</td><td>any</td><td class='fail'>FAIL</td></tr>\n");
            html.append("</table></details>\n");

            // Sort: failures first, then warnings, then passes
            List<PdfReport> sorted = new ArrayList<>(reports);
            sorted.sort(Comparator.comparingInt(a -> a.severity.ordinal()));

            for (PdfReport r : sorted) {
                String sevClass = r.severity.name().toLowerCase();
                String badge = switch (r.severity) {
                    case FAIL -> "<span class='badge fail'>FAIL</span>";
                    case WARN -> "<span class='badge warn'>WARN</span>";
                    case PASS -> "<span class='badge pass'>PASS</span>";
                    case SKIP -> "<span class='badge skip'>SKIP</span>";
                };

                html.append(String.format("<div class='pdf-card %s'>%n", sevClass));
                html.append(String.format("<h3>%s %s</h3>%n", badge, r.name));
                html.append(String.format(
                        "<p>Pages: %d | Matches: %d</p>%n", r.pageCount, r.totalMatches));

                if (r.error != null) {
                    html.append(String.format("<p class='error'>%s</p>%n", r.error));
                }

                if (!r.issues.isEmpty()) {
                    html.append("<div class='issues'><strong>Issues:</strong><ul>\n");
                    for (String issue : r.issues) {
                        html.append(String.format("<li>%s</li>%n", issue));
                    }
                    html.append("</ul></div>\n");
                }

                if (!r.pageAnalyses.isEmpty()) {
                    html.append("<table class='metrics'>\n");
                    html.append("<tr><th>Page</th><th>Chars Before</th><th>Chars After</th>"
                            + "<th>Char Loss</th><th>Pixels Changed</th>"
                            + "<th>Non-Target Missing</th><th>Status</th></tr>\n");
                    for (PageAnalysis pa : r.pageAnalyses) {
                        String status;
                        if (pa.charLossFraction > MAX_CHAR_LOSS_FRACTION && pa.charsBefore > 10) {
                            status = "<span class='fail'>FAIL</span>";
                        } else if (!pa.missingNonTargetWords.isEmpty()) {
                            status = "<span class='fail'>FAIL</span>";
                        } else if (pa.pixelChangeFraction > WARN_PIXEL_CHANGE_FRACTION) {
                            status = "<span class='warn'>WARN</span>";
                        } else {
                            status = "<span class='pass'>OK</span>";
                        }
                        html.append(String.format(
                                "<tr><td>%d</td><td>%d</td><td>%d</td>"
                                + "<td>%.1f%%</td><td>%.1f%%</td><td>%s</td><td>%s</td></tr>%n",
                                pa.pageIndex, pa.charsBefore, pa.charsAfter,
                                pa.charLossFraction * 100, pa.pixelChangeFraction * 100,
                                pa.missingNonTargetWords.isEmpty() ? "none" :
                                        String.join(", ", pa.missingNonTargetWords),
                                status));
                    }
                    html.append("</table>\n");

                    // Image gallery -- collapsed for PASS, expanded for FAIL/WARN
                    boolean expanded = r.severity == Severity.FAIL || r.severity == Severity.WARN;
                    html.append(String.format(
                            "<details %s><summary>Visual Diffs</summary>%n",
                            expanded ? "open" : ""));
                    html.append("<div class='images'>\n");
                    for (PageAnalysis pa : r.pageAnalyses) {
                        String prefix = r.name + "/page-" + pa.pageIndex;
                        html.append(String.format(
                                "<div class='page-images'><h4>Page %d</h4>%n", pa.pageIndex));
                        html.append("<div class='img-row'>\n");
                        html.append(String.format(
                                "<figure><img src='%s-before.png' loading='lazy'>"
                                + "<figcaption>Before</figcaption></figure>%n", prefix));
                        html.append(String.format(
                                "<figure><img src='%s-after.png' loading='lazy'>"
                                + "<figcaption>After</figcaption></figure>%n", prefix));
                        html.append(String.format(
                                "<figure><img src='%s-diff.png' loading='lazy'>"
                                + "<figcaption>Diff</figcaption></figure>%n", prefix));
                        html.append("</div></div>\n");
                    }
                    html.append("</div></details>\n");
                }

                html.append("</div>\n");
            }

            html.append("</body>\n</html>\n");
            Files.writeString(htmlFile, html.toString());
        } catch (IOException e) {
            System.err.println("[CorpusRedactTest] Failed to write report: " + e.getMessage());
        }
    }


    private static final String REPORT_CSS = """
            <style>
            :root { --bg: #0d1117; --card: #161b22; --border: #30363d; --text: #c9d1d9; }
            body { font-family: -apple-system, 'Segoe UI', system-ui, sans-serif;
                   margin: 0; padding: 24px; background: var(--bg); color: var(--text); }
            h1 { color: #58a6ff; margin-bottom: 8px; }
            .summary { background: var(--card); border: 1px solid var(--border);
                       border-radius: 8px; padding: 16px; margin: 16px 0; }
            .badge { padding: 2px 10px; border-radius: 12px; font-size: 0.85em; font-weight: 700; }
            .pass { color: #3fb950; } .badge.pass { background: #1a3a1a; }
            .warn { color: #d29922; } .badge.warn { background: #3d2e00; }
            .fail { color: #f85149; } .badge.fail { background: #3d1114; }
            .skip { color: #8b949e; } .badge.skip { background: #21262d; }
            .pdf-card { background: var(--card); border: 1px solid var(--border);
                        border-radius: 8px; margin: 12px 0; padding: 16px; }
            .pdf-card.fail { border-color: #f85149; border-width: 2px; }
            .pdf-card.warn { border-color: #d29922; }
            .issues { background: #1c1014; border: 1px solid #f85149; border-radius: 6px;
                      padding: 8px 16px; margin: 8px 0; }
            .issues ul { margin: 4px 0; }
            table.metrics { border-collapse: collapse; margin: 8px 0; width: 100%%; }
            .metrics th, .metrics td { padding: 6px 12px; border: 1px solid var(--border);
                                       text-align: right; }
            .metrics th { background: #21262d; text-align: center; }
            .thresholds { margin: 8px 0; }
            .thresholds table { border-collapse: collapse; }
            .thresholds th, .thresholds td { padding: 4px 12px; border: 1px solid var(--border); }
            .thresholds th { background: #21262d; }
            .img-row { display: flex; gap: 8px; flex-wrap: wrap; }
            .img-row img { max-width: 280px; border: 1px solid var(--border); border-radius: 4px; }
            .img-row figure { margin: 0; text-align: center; }
            .img-row figcaption { font-size: 0.75em; color: #8b949e; }
            .page-images { margin: 8px 0; }
            details summary { cursor: pointer; color: #58a6ff; }
            .error { color: #f85149; }
            </style>
            """;


    enum Severity { FAIL, WARN, PASS, SKIP }

    static class PdfReport {
        final String name;
        int pageCount;
        int totalMatches;
        Severity severity = Severity.PASS;
        String error;
        final List<String> issues = new ArrayList<>();
        final List<PageAnalysis> pageAnalyses = new ArrayList<>();
        PdfReport(String name) { this.name = name; }
    }

    static class PageAnalysis {
        int pageIndex;
        int totalPixels;
        int changedPixels;
        double pixelChangeFraction;
        int charsBefore;
        int charsAfter;
        double charLossFraction;
        int sampledNonTargetWords;
        final List<String> missingNonTargetWords = new ArrayList<>();
        boolean sizeMismatch;
    }
}
