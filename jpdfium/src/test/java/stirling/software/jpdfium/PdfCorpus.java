package stirling.software.jpdfium;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads and caches a large set of publicly available PDF files for corpus-level testing.
 *
 * PDFs are cached under {@code build/test-corpus/} so the network is only hit on the first
 * run. Subsequent runs are fully offline. Tests that rely on this class should be tagged
 * {@code @Tag("corpus")} and guarded with {@code @EnabledIfSystemProperty} so they are skipped
 * in offline CI environments.
 *
 * Source: Mozilla pdf.js test suite -- stable, public domain, no authentication required.
 */
public final class PdfCorpus {

    /** Cache directory, relative to the Gradle project working directory. */
    public static final Path CACHE_DIR = Path.of("build/test-corpus");

    // Short timeout so tests fail fast on network unavailability rather than hanging.
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String BASE =
            "https://raw.githubusercontent.com/mozilla/pdf.js/master/test/pdfs/";

    private static final List<Entry> ENTRIES = List.of(
        entry("basicapi.pdf"),
        entry("font_ascent_descent.pdf"),
        entry("annotation-button-widget.pdf"),
        entry("tracemonkey.pdf"),
        entry("calrgb.pdf"),

        entry("bug1020858.pdf"),
        entry("bug946506.pdf"),
        entry("bug894572.pdf"),
        entry("bug1028735.pdf"),
        entry("bug1068432.pdf"),
        entry("bug1132849.pdf"),
        entry("bug1252420.pdf"),
        entry("bug1851498.pdf"),
        entry("bug1865341.pdf"),

        entry("issue2840.pdf"),
        entry("issue16221.pdf"),
        entry("issue18117.pdf"),
        entry("issue1155r.pdf"),
        entry("issue2391-1.pdf"),
        entry("issue4061.pdf"),
        entry("issue6068.pdf"),
        entry("issue6961.pdf"),
        entry("issue7544.pdf"),
        entry("issue7665.pdf"),
        entry("issue9278.pdf"),
        entry("issue13003.pdf"),
        entry("issue13372.pdf"),
        entry("issue14307.pdf"),
        entry("issue925.pdf"),
        entry("issue918.pdf"),
        entry("issue5334.pdf"),
        entry("issue6108.pdf"),
        entry("issue6127.pdf"),

        entry("annotation-text-widget.pdf"),
        entry("annotation-line.pdf"),
        entry("annotation-fileattachment.pdf"),
        entry("annotation-stamp.pdf"),
        entry("annotation-underline.pdf"),
        entry("annotation-strikeout.pdf"),
        entry("annotation-highlight.pdf"),
        entry("annotation-squiggly.pdf"),
        entry("annotation-freetext.pdf"),
        entry("annotation-link-text-popup.pdf"),
        entry("annotation-border-styles.pdf"),

        entry("vertical.pdf"),
        entry("copy_paste_ligatures.pdf")
    );

    private PdfCorpus() {}

    /** A single corpus entry pairing a local cache name with its download URL. */
    public record Entry(String name, String url) {}

    /** Convenience factory: builds an Entry from just the filename, using the standard base URL. */
    private static Entry entry(String filename) {
        return new Entry(filename, BASE + filename);
    }

    /** Returns the full list of corpus entries (for inspection or selective download). */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /**
     * Download all corpus PDFs, skipping entries that are already cached.
     *
     * @return paths to all available corpus PDFs (cached or freshly downloaded)
     * @throws IOException          if the cache directory cannot be created or a download fails
     * @throws InterruptedException if the thread is interrupted during a download
     */
    public static List<Path> download() throws IOException, InterruptedException {
        Files.createDirectories(CACHE_DIR);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        List<Path> result = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (Entry entry : ENTRIES) {
            Path dest = CACHE_DIR.resolve(entry.name());

            if (Files.exists(dest) && Files.size(dest) > 0) {
                result.add(dest);
                continue;
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(entry.url()))
                    .timeout(TIMEOUT)
                    .build();

            try {
                HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(dest));
                if (resp.statusCode() != 200) {
                    Files.deleteIfExists(dest);
                    failures.add(entry.name() + " (HTTP " + resp.statusCode() + ")");
                    continue;
                }
                result.add(dest);
            } catch (IOException e) {
                Files.deleteIfExists(dest);
                failures.add(entry.name() + " (" + e.getMessage() + ")");
            }
        }

        if (!failures.isEmpty()) {
            System.err.println("[PdfCorpus] WARNING: failed to download " + failures.size()
                    + " PDFs: " + failures);
        }

        return result;
    }

    /**
     * Return all already-cached corpus PDFs without attempting any network access.
     * Useful for asserting corpus coverage in offline-only CI.
     */
    public static List<Path> cached() throws IOException {
        if (!Files.exists(CACHE_DIR)) return List.of();
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(CACHE_DIR)) {
            stream.filter(p -> p.toString().endsWith(".pdf"))
                  .sorted()
                  .forEach(result::add);
        }
        return result;
    }

    /**
     * Programmatic analysis of the corpus: opens each PDF and prints page count, file size,
     * and basic stats. Useful for verifying corpus health after download.
     *
     * @param pdfs list of PDF paths to analyze
     */
    public static void analyze(List<Path> pdfs) {
        System.out.printf("%n=== PdfCorpus Analysis: %d PDFs ===%n", pdfs.size());
        long totalBytes = 0;
        int totalPages = 0;

        for (Path pdf : pdfs) {
            try {
                long size = Files.size(pdf);
                totalBytes += size;
                try (PdfDocument doc = PdfDocument.open(pdf)) {
                    int pages = doc.pageCount();
                    totalPages += pages;
                    System.out.printf("  %-40s  %6d bytes  %3d page(s)%n",
                            pdf.getFileName(), size, pages);
                }
            } catch (Exception e) {
                System.out.printf("  %-40s  ERROR: %s%n", pdf.getFileName(), e.getMessage());
            }
        }

        System.out.printf("  --- TOTALS: %d PDFs, %d pages, %.1f KB ---%n%n",
                pdfs.size(), totalPages, totalBytes / 1024.0);
    }
}
