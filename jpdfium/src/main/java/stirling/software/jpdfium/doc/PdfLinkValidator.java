package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enumerate all hyperlinks in a PDF and validate each URL via HTTP HEAD requests.
 *
 * <p>Produces a report of broken, redirected, and valid links with page numbers.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("doc.pdf"))) {
 *     LinkReport report = PdfLinkValidator.validate(doc);
 *     report.links().forEach(s ->
 *         System.out.printf("  [%s] %s (page %d)%n", s.status(), s.url(), s.pageIndex()));
 * }
 * }</pre>
 */
public final class PdfLinkValidator {

    private PdfLinkValidator() {}

    /**
     * Status of a validated link.
     */
    public enum LinkStatus {
        VALID,
        REDIRECT,
        BROKEN,
        TIMEOUT,
        INVALID_URL,
        ERROR
    }

    /**
     * A validated link with its status.
     */
    public record LinkResult(
            int pageIndex,
            String url,
            LinkStatus status,
            int httpStatusCode,
            String redirectTarget,
            long responseTimeMs
    ) {}

    /**
     * Complete report of all links in a document.
     */
    public record LinkReport(List<LinkResult> links) {
        public long valid() { return links.stream().filter(l -> l.status() == LinkStatus.VALID).count(); }
        public long broken() { return links.stream().filter(l -> l.status() == LinkStatus.BROKEN).count(); }
        public long redirected() { return links.stream().filter(l -> l.status() == LinkStatus.REDIRECT).count(); }
        public long timeout() { return links.stream().filter(l -> l.status() == LinkStatus.TIMEOUT).count(); }
        public long errors() { return links.stream().filter(l -> l.status() == LinkStatus.ERROR || l.status() == LinkStatus.INVALID_URL).count(); }

        public String summary() {
            return String.format("%d links: %d valid, %d broken, %d redirected, %d timeout, %d errors",
                    links.size(), valid(), broken(), redirected(), timeout(), errors());
        }
    }

    /**
     * Validate all links using default settings.
     */
    public static LinkReport validate(PdfDocument doc) {
        return validate(doc, Duration.ofSeconds(5), 10, true);
    }

    /**
     * Validate all links with options.
     *
     * @param doc             open PDF document
     * @param timeout         timeout per request
     * @param parallelism     max concurrent HTTP requests
     * @param followRedirects if true, follow redirects to determine final status
     * @return complete link report
     */
    public static LinkReport validate(PdfDocument doc, Duration timeout,
                                       int parallelism, boolean followRedirects) {
        // Collect all web links from all pages
        List<PageLink> pageLinks = new ArrayList<>();
        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                List<WebLink> webLinks = PdfWebLinks.extract(page.rawHandle(), p);
                for (WebLink wl : webLinks) {
                    pageLinks.add(new PageLink(p, wl.url()));
                }
            }
        }

        if (pageLinks.isEmpty()) {
            return new LinkReport(Collections.emptyList());
        }

        // Validate links in parallel
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(parallelism, pageLinks.size()));

        HttpClient.Redirect redirectPolicy = followRedirects
                ? HttpClient.Redirect.ALWAYS
                : HttpClient.Redirect.NEVER;

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(redirectPolicy)
                .executor(executor)
                .build()) {

            List<CompletableFuture<LinkResult>> futures = new ArrayList<>(pageLinks.size());

            for (PageLink pl : pageLinks) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> validateLink(client, pl.pageIndex, pl.url, timeout), executor));
            }

            List<LinkResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            return new LinkReport(List.copyOf(results));
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Enumerate all URLs without validating them (no network access).
     */
    public static List<LinkResult> enumerate(PdfDocument doc) {
        List<LinkResult> results = new ArrayList<>();
        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                List<WebLink> webLinks = PdfWebLinks.extract(page.rawHandle(), p);
                for (WebLink wl : webLinks) {
                    results.add(new LinkResult(p, wl.url(), LinkStatus.VALID, 0, null, 0));
                }
            }
        }
        return Collections.unmodifiableList(results);
    }

    private static LinkResult validateLink(HttpClient client, int pageIndex,
                                            String url, Duration timeout) {
        long start = System.currentTimeMillis();

        // Validate URL format
        URI uri;
        try {
            uri = URI.create(url);
            if (uri.getScheme() == null || (!uri.getScheme().equals("http") && !uri.getScheme().equals("https"))) {
                return new LinkResult(pageIndex, url, LinkStatus.INVALID_URL, 0, null,
                        System.currentTimeMillis() - start);
            }
        } catch (IllegalArgumentException e) {
            return new LinkResult(pageIndex, url, LinkStatus.INVALID_URL, 0, null,
                    System.currentTimeMillis() - start);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(timeout)
                    .header("User-Agent", "JPDFium-LinkValidator/1.0")
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            long elapsed = System.currentTimeMillis() - start;
            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                return new LinkResult(pageIndex, url, LinkStatus.VALID, status, null, elapsed);
            } else if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                return new LinkResult(pageIndex, url, LinkStatus.REDIRECT, status, location, elapsed);
            } else {
                return new LinkResult(pageIndex, url, LinkStatus.BROKEN, status, null, elapsed);
            }
        } catch (java.net.http.HttpTimeoutException e) {
            return new LinkResult(pageIndex, url, LinkStatus.TIMEOUT, 0, null,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new LinkResult(pageIndex, url, LinkStatus.ERROR, 0, null,
                    System.currentTimeMillis() - start);
        }
    }

    private record PageLink(int pageIndex, String url) {}
}
