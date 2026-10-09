package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.ThreadMXBean;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.doc.ActionType;
import stirling.software.jpdfium.doc.Bookmark;
import stirling.software.jpdfium.doc.CompressOptions;
import stirling.software.jpdfium.doc.CompressPreset;
import stirling.software.jpdfium.doc.NUpLayout;
import stirling.software.jpdfium.doc.PdfBookmarkEditor;
import stirling.software.jpdfium.doc.PdfCompressor;
import stirling.software.jpdfium.doc.PdfLinearizer;
import stirling.software.jpdfium.doc.PdfRepair;
import stirling.software.jpdfium.doc.PdfSanitizer;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.QpdfLib;

/**
 * Concurrent multi-user workload simulation across merge, split, extract, render, compress and
 * more. Reports ops/s and heap bytes per op. Requires real natives.
 */
final class LargeOrgSimulationTest {

    private static final int KIND_MERGE = 0;
    private static final int KIND_SPLIT = 1;
    private static final int KIND_EXTRACT = 2;
    private static final int KIND_RENDER = 3;
    private static final int KIND_TEXT = 4;
    private static final int KIND_BOOKMARKS = 5;
    private static final int KIND_COMPRESS = 6;
    private static final int KIND_NUP = 7;
    private static final int KIND_LINEARIZE = 8;
    private static final int KIND_SANITIZE = 9;
    private static final int KIND_REPAIR = 10;
    private static final int KIND_BOOKMARK_EDIT = 11;

    @Test
    @Timeout(900)
    @EnabledIfSystemProperty(named = "jpdfium.sim", matches = "true")
    void largeOrgMixedWorkload() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "large-org simulation needs real PDFium natives");
        assumeTrue(QpdfLib.isSupported(), "requires the bundled qpdf");

        int users = Integer.getInteger("jpdfium.sim.users", 32);
        int seconds = Integer.getInteger("jpdfium.sim.seconds", 20);
        int docs = Integer.getInteger("jpdfium.sim.docs", 24);

        Path work = Files.createTempDirectory("jpdfium-sim");
        try {
            List<Path> corpus = buildCorpus(work, docs);
            List<Integer> ops = supportedOps();
            execute(ops.get(0), corpus.get(0), corpus.get(0), work, new Random(1));
            run("large-org", users, seconds, corpus, ops, work);
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    @Timeout(1800)
    @EnabledIfSystemProperty(named = "jpdfium.sim.sweep", matches = "true")
    void throughputScalesWithUsers() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "large-org simulation needs real PDFium natives");
        assumeTrue(QpdfLib.isSupported(), "requires the bundled qpdf");

        int seconds = Integer.getInteger("jpdfium.sim.seconds", 8);
        Path work = Files.createTempDirectory("jpdfium-sweep");
        try {
            List<Path> corpus = buildCorpus(work, Integer.getInteger("jpdfium.sim.docs", 24));
            List<Integer> ops = supportedOps();
            execute(ops.get(0), corpus.get(0), corpus.get(0), work, new Random(1));
            for (int users : new int[] {1, 2, 4, 8, 16, 32, 64}) {
                run("sweep-" + users, users, seconds, corpus, ops, work);
            }
        } finally {
            deleteRecursively(work);
        }
    }

    private void run(
            String label, int users, int seconds, List<Path> docs, List<Integer> ops, Path work)
            throws Exception {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        ExecutorService pool = Executors.newFixedThreadPool(users);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        LongAdder opCount = new LongAdder();
        LongAdder errorCount = new LongAdder();
        LongAdder allocated = new LongAdder();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);

        List<Future<?>> futures = new ArrayList<>(users);
        for (int u = 0; u < users; u++) {
            final long seed = 0x5EEDL + u;
            futures.add(
                    pool.submit(
                            () -> {
                                Random rnd = new Random(seed);
                                long id = Thread.currentThread().threadId();
                                boolean measure = bean.isThreadAllocatedMemoryEnabled();
                                long before = measure ? bean.getThreadAllocatedBytes(id) : -1;
                                try {
                                    start.await();
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                while (System.nanoTime() < deadline && firstError.get() == null) {
                                    Path a = docs.get(rnd.nextInt(docs.size()));
                                    Path b = docs.get(rnd.nextInt(docs.size()));
                                    int kind = ops.get(rnd.nextInt(ops.size()));
                                    long t0 = System.nanoTime();
                                    try {
                                        execute(kind, a, b, work, rnd);
                                        opCount.increment();
                                        latencies.add(System.nanoTime() - t0);
                                    } catch (Throwable t) {
                                        errorCount.increment();
                                        firstError.compareAndSet(null, t);
                                    }
                                }
                                long after = measure ? bean.getThreadAllocatedBytes(id) : -1;
                                if (before >= 0 && after >= 0) {
                                    allocated.add(after - before);
                                }
                            }));
        }

        long wallStart = System.nanoTime();
        start.countDown();
        pool.shutdown();
        boolean finished = pool.awaitTermination(seconds + 180L, TimeUnit.SECONDS);
        long wallNanos = System.nanoTime() - wallStart;
        if (!finished) {
            pool.shutdownNow();
            // The interrupted workers may still be inside native PDFium/qpdf calls; let
            // them actually stop before the caller deletes the corpus directory.
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Comparator.naturalOrder());
        long totalOps = opCount.sum();
        double opsPerSec = totalOps / (wallNanos / 1e9);
        double meanMs =
                sorted.isEmpty()
                        ? 0
                        : sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double mbPerOp = totalOps == 0 ? 0 : allocated.sum() / (1024.0 * 1024.0) / totalOps;
        System.out.printf(
                Locale.ROOT,
                "SIM %-12s users=%d ops=%d errors=%d wall=%.1fs %.1f ops/s"
                        + " mean=%.2fms p50=%.2fms p95=%.2fms p99=%.2fms heap=%.3f MB/op%n",
                label,
                users,
                totalOps,
                errorCount.sum(),
                wallNanos / 1e9,
                opsPerSec,
                meanMs,
                percentile(sorted, 50),
                percentile(sorted, 95),
                percentile(sorted, 99),
                mbPerOp);

        if (!finished) {
            throw new AssertionError(label + ": workers did not finish within the timeout");
        }
        long failures = errorCount.sum();
        if (failures != 0) {
            throw new AssertionError(
                    label + ": " + failures + " operation(s) failed; first=" + firstError.get(),
                    firstError.get());
        }
        assertTrue(totalOps > 0, label + ": no operations completed");
    }

    private void execute(int kind, Path a, Path b, Path work, Random rnd) throws Exception {
        switch (kind) {
            case KIND_MERGE -> {
                try (PdfDocument merged = PdfMerge.mergeFiles(List.of(a, b))) {
                    merged.pageCount();
                }
            }
            case KIND_SPLIT -> {
                try (PdfDocument doc = PdfDocument.open(a)) {
                    List<PdfDocument> parts =
                            PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(2));
                    try {
                        for (PdfDocument part : parts) {
                            part.pageCount();
                        }
                    } finally {
                        for (PdfDocument part : parts) {
                            part.close();
                        }
                    }
                }
            }
            case KIND_EXTRACT -> {
                try (PdfDocument doc = PdfDocument.open(a);
                        PdfDocument page =
                                PdfSplit.extractPageRange(doc, 0, Math.min(1, doc.pageCount() - 1))) {
                    page.pageCount();
                }
            }
            case KIND_RENDER -> {
                try (PdfDocument doc = PdfDocument.open(a);
                        PdfPage page = doc.page(0)) {
                    page.renderImage(96);
                }
            }
            case KIND_TEXT -> {
                try (PdfDocument doc = PdfDocument.open(a);
                        PdfPage page = doc.page(0)) {
                    page.extractText();
                }
            }
            case KIND_BOOKMARKS -> {
                try (PdfDocument doc = PdfDocument.open(a)) {
                    doc.bookmarks().size();
                }
            }
            case KIND_COMPRESS -> {
                try (PdfDocument doc = PdfDocument.open(a)) {
                    PdfCompressor.compress(
                            doc, CompressOptions.builder().preset(CompressPreset.WEB).build());
                }
            }
            case KIND_NUP -> {
                try (PdfDocument doc = PdfDocument.open(a)) {
                    NUpLayout.from(doc).grid(2, 2).build().toBytes();
                }
            }
            case KIND_LINEARIZE -> {
                Path out = Files.createTempFile(work, "lin-", ".pdf");
                try {
                    PdfLinearizer.linearize(a, out);
                } finally {
                    Files.deleteIfExists(out);
                }
            }
            case KIND_SANITIZE -> {
                Path out = Files.createTempFile(work, "san-", ".pdf");
                try {
                    PdfSanitizer.sanitize(a, out, PdfSanitizer.METADATA | PdfSanitizer.INFO);
                } finally {
                    Files.deleteIfExists(out);
                }
            }
            case KIND_REPAIR -> PdfRepair.builder().input(a).build().execute();
            case KIND_BOOKMARK_EDIT -> {
                try (PdfDocument doc = PdfDocument.open(a)) {
                    PdfBookmarkEditor.setBookmarks(
                            doc,
                            List.of(
                                    new Bookmark(
                                            "Section 1",
                                            0,
                                            List.of(),
                                            ActionType.GOTO,
                                            Optional.empty(),
                                            Optional.empty())));
                }
            }
            default -> throw new IllegalStateException("unknown operation " + kind);
        }
    }

    private static List<Integer> supportedOps() {
        List<Integer> ops = new ArrayList<>();
        ops.add(KIND_MERGE);
        ops.add(KIND_SPLIT);
        ops.add(KIND_EXTRACT);
        ops.add(KIND_RENDER);
        ops.add(KIND_TEXT);
        ops.add(KIND_BOOKMARKS);
        ops.add(KIND_COMPRESS);
        ops.add(KIND_NUP);
        ops.add(KIND_BOOKMARK_EDIT);
        if (PdfLinearizer.isSupported()) {
            ops.add(KIND_LINEARIZE);
        }
        if (PdfSanitizer.isSupported()) {
            ops.add(KIND_SANITIZE);
        }
        ops.add(KIND_REPAIR);
        return ops;
    }

    private List<Path> buildCorpus(Path work, int docCount) throws Exception {
        List<Path> docs = new ArrayList<>(docCount);
        Random rnd = new Random(0xC0FFEE);
        for (int i = 0; i < docCount; i++) {
            Path p = work.resolve("doc-" + i + ".pdf");
            switch (i % 3) {
                case 0 -> copyResource("/pdfs/general/basic-text.pdf", p);
                case 1 -> copyResource("/pdfs/general/minimal.pdf", p);
                default -> {
                    int pages = 1 + rnd.nextInt(6);
                    List<BufferedImage> images = new ArrayList<>(pages);
                    for (int pg = 0; pg < pages; pg++) {
                        images.add(noiseImage(rnd));
                    }
                    try (PdfDocument doc = PdfDocument.fromImages(images)) {
                        doc.save(p);
                    }
                }
            }
            docs.add(p);
        }
        return docs;
    }

    private static void copyResource(String resource, Path dest) throws Exception {
        try (var in = LargeOrgSimulationTest.class.getResourceAsStream(resource)) {
            Objects.requireNonNull(in, "missing test resource: " + resource);
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static BufferedImage noiseImage(Random rnd) {
        int w = 200 + rnd.nextInt(200);
        int h = 200 + rnd.nextInt(200);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rnd.nextInt(0x1000000));
            }
        }
        return img;
    }

    private static double percentile(List<Long> sortedNanos, int p) {
        if (sortedNanos.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sortedNanos.size()) - 1;
        idx = Math.max(0, Math.min(sortedNanos.size() - 1, idx));
        return sortedNanos.get(idx) / 1e6;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignored) {
                                    // best-effort cleanup
                                }
                            });
        }
    }
}
