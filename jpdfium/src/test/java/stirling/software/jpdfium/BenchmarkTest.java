package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import stirling.software.jpdfium.doc.PdfToSvg;

/**
 * Wall-clock, CPU and allocation benchmark for the SVG/PDF entry points. Run with
 * {@code -Djpdfium.bench=true}; results only mean anything in a release build.
 */
@EnabledIfSystemProperty(named = "jpdfium.bench", matches = "true")
class BenchmarkTest {

    private static final int WARMUP = 5;
    private static final int ITERS = 30;

    private static final String SMALL_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" width="200" height="120" viewBox="0 0 200 120">
              <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="0">
                <stop offset="0" stop-color="#2040ff"/><stop offset="1" stop-color="#ffcc00"/>
              </linearGradient></defs>
              <rect x="10" y="10" width="80" height="50" fill="url(#g)"/>
              <circle cx="150" cy="60" r="30" fill="#e02020"/>
              <text x="20" y="100" font-size="16">Vector text</text>
            </svg>
            """;

    private static String heavySvg(int shapes) {
        StringBuilder svg = new StringBuilder(64 * 1024);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"600\""
                + " viewBox=\"0 0 800 600\"><defs><linearGradient id=\"g\" x1=\"0\" y1=\"0\""
                + " x2=\"1\" y2=\"1\"><stop offset=\"0\" stop-color=\"#1020a0\"/>"
                + "<stop offset=\"1\" stop-color=\"#f0d000\"/></linearGradient></defs>\n");
        for (int i = 0; i < shapes; i++) {
            int x = (i * 37) % 780;
            int y = (i * 53) % 580;
            svg.append("<path d=\"M").append(x).append(' ').append(y)
                    .append(" C").append(x + 40).append(' ').append(y - 30)
                    .append(' ').append(x + 60).append(' ').append(y + 30)
                    .append(' ').append(x + 90).append(' ').append(y)
                    .append("\" fill=\"url(#g)\" stroke=\"#123\"/>\n");
        }
        svg.append("<text x=\"40\" y=\"560\" font-size=\"24\">Heavy vector text</text></svg>\n");
        return svg.toString();
    }

    /** One timed sample: wall clock, CPU time and allocated bytes. */
    private static final class Sample {

        final long wallNanos;
        final long cpuNanos;
        final long allocated;

        Sample(long wallNanos, long cpuNanos, long allocated) {
            this.wallNanos = wallNanos;
            this.cpuNanos = cpuNanos;
            this.allocated = allocated;
        }
    }

    private static Sample time(Runnable body) {
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long alloc0 = bean.getThreadAllocatedBytes(tid);
        long cpu0 = bean.getThreadCpuTime(tid);
        long wall0 = System.nanoTime();
        body.run();
        long wall1 = System.nanoTime();
        long cpu1 = bean.getThreadCpuTime(tid);
        long alloc1 = bean.getThreadAllocatedBytes(tid);
        return new Sample(wall1 - wall0, cpu1 - cpu0, alloc1 - alloc0);
    }

    private static void run(String name, Runnable body) {
        for (int i = 0; i < WARMUP; i++) {
            body.run();
        }
        List<Sample> samples = new ArrayList<>(ITERS);
        for (int i = 0; i < ITERS; i++) {
            samples.add(time(body));
        }
        long[] wall = new long[ITERS];
        long[] cpu = new long[ITERS];
        long[] alloc = new long[ITERS];
        for (int i = 0; i < ITERS; i++) {
            wall[i] = samples.get(i).wallNanos;
            cpu[i] = samples.get(i).cpuNanos;
            alloc[i] = samples.get(i).allocated;
        }
        Arrays.sort(wall);
        Arrays.sort(cpu);
        Arrays.sort(alloc);
        System.out.printf("%-28s wall med %.2f p95 %.2f ms | cpu med %.2f ms | alloc med %.1f KB"
                        + " | min %.2f ms%n",
                name, wall[ITERS / 2] / 1e6, wall[(int) (ITERS * 0.95)] / 1e6,
                cpu[ITERS / 2] / 1e6, alloc[ITERS / 2] / 1024.0, wall[0] / 1e6);
        System.out.printf("%-28s wall spread max %.2f ms (%.2fx median)%n", "",
                wall[ITERS - 1] / 1e6, (double) wall[ITERS - 1] / Math.max(1, wall[ITERS / 2]));
    }

    private static byte[] fixture(String relative) throws Exception {
        Path path = Path.of("src/test/resources/pdfs", relative);
        return Files.exists(path) ? Files.readAllBytes(path) : null;
    }

    @Test
    void svgEntryPoints() {
        byte[] small = SMALL_SVG.getBytes(StandardCharsets.UTF_8);
        byte[] heavy = heavySvg(300).getBytes(StandardCharsets.UTF_8);
        System.out.printf("%n== SVG entry points (small=%d B, heavy=%d B) ==%n",
                small.length, heavy.length);

        run("svg_to_pdf small", () -> {
            try (PdfDocument doc = SvgConverter.toVectorPdf(small)) {
                assertTrue(doc.pageCount() >= 1);
            }
        });
        run("svg_to_pdf heavy", () -> {
            try (PdfDocument doc = SvgConverter.toVectorPdf(heavy)) {
                assertTrue(doc.pageCount() >= 1);
            }
        });
        run("svg_to_rgba small natural", () -> SvgConverter.toRgba(small));
        run("svg_to_rgba heavy 1600x1200", () -> SvgConverter.toRgba(heavy, 1600, 1200));
    }

    @Test
    void pdfToSvg() throws Exception {
        byte[] heavy = fixture("repair/pdfjs-issue6961.pdf");
        assumeTrue(heavy != null, "benchmark fixtures missing");
        System.out.printf("%n== PDF -> SVG (%d B) ==%n", heavy.length);

        composition("issue6961 p0", heavy, 0);
        run("toSvg issue6961 p0 (gradients)", () -> PdfToSvg.toSvg(heavy, 0));
        run("toSvgExact issue6961 p0 (hayro)", () -> PdfToSvg.toSvgExact(heavy, 0));

        byte[] narrative =
                fixture("redact/23_Narrative of the Life of Frederick Douglass, An Am.pdf");
        if (narrative != null) {
            try (PdfDocument doc = PdfDocument.open(narrative)) {
                if (doc.pageCount() > 0) {
                    run("toSvg Narrative p0", () -> PdfToSvg.toSvg(narrative, 0));
                }
            } catch (Throwable t) {
                System.out.println("toSvg Narrative skipped: " + t);
            }
        }
    }

    /** File-backed routes: one document parse per export instead of one per page. */
    @Test
    void fileBackedRoutes() throws Exception {
        byte[] many = fixture("redact/redact-test-100pages.pdf");
        assumeTrue(many != null, "benchmark fixtures missing");
        Path manyFile = Files.createTempFile("jpdfium-100p", ".pdf");
        Files.write(manyFile, many);
        int manyPages;
        try (PdfDocument doc = PdfDocument.open(manyFile)) {
            manyPages = doc.pageCount();
        }
        int[] selection = java.util.stream.IntStream.range(0, manyPages).toArray();
        System.out.printf("%n== file-backed (%d pages, %d B) ==%n", manyPages, many.length);
        run("toSvgDocument bytes all", () -> PdfToSvg.toSvgDocument(many, selection));
        run("toSvgDocument file all", () -> PdfToSvg.toSvgDocument(manyFile, selection));

        byte[] large = fixture("redact/redact-test-tj-deviation.pdf");
        if (large != null) {
            Path largeFile = Files.createTempFile("jpdfium-large", ".pdf");
            Files.write(largeFile, large);
            int largePages;
            try (PdfDocument doc = PdfDocument.open(largeFile)) {
                largePages = doc.pageCount();
            }
            System.out.printf("%n== file-backed large (%d pages, %d B) ==%n", largePages,
                    large.length);
            run("toSvg bytes p0 15MB", () -> PdfToSvg.toSvg(large, 0));
            run("toSvg file p0 15MB", () -> PdfToSvg.toSvg(largeFile, 0));
            Files.deleteIfExists(largeFile);
        }
        byte[] gradients = fixture("repair/pdfjs-issue6961.pdf");
        if (gradients != null) {
            Path gradientFile = Files.createTempFile("jpdfium-grad", ".pdf");
            Files.write(gradientFile, gradients);
            run("toSvg bytes 6961 p0", () -> PdfToSvg.toSvg(gradients, 0));
            run("toSvg file 6961 p0", () -> PdfToSvg.toSvg(gradientFile, 0));
            Files.deleteIfExists(gradientFile);
        }
        Files.deleteIfExists(manyFile);
    }

    /** Decompose an export into vector markup versus repeated raster payload. */
    private static void composition(String name, byte[] pdf, int pageIndex) {
        String svg = PdfToSvg.toSvg(pdf, pageIndex);
        String needle = "data:image/png;base64,";
        int count = 0;
        long payload = 0;
        java.util.Set<Integer> distinct = new java.util.HashSet<>();
        for (int i = svg.indexOf(needle); i >= 0; i = svg.indexOf(needle, i + 1)) {
            int start = i + needle.length();
            int end = svg.indexOf('"', start);
            if (end < 0) {
                break;
            }
            String data = svg.substring(start, end);
            payload += data.length();
            distinct.add(data.hashCode());
            count++;
        }
        System.out.printf(
                "[composition] %s: svg %.2f MB, image count %d, payload %.2f MB, distinct %d%n",
                name, svg.length() / 1048576.0, count, payload / 1048576.0, distinct.size());
    }
}
