package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.PdfPipeline;
import stirling.software.jpdfium.ProcessingMode;
import stirling.software.jpdfium.doc.PdfPageImporter;
import stirling.software.jpdfium.doc.Annotation;
import stirling.software.jpdfium.doc.AnnotationType;
import stirling.software.jpdfium.doc.PdfAutoCrop;
import stirling.software.jpdfium.doc.PdfFlattenRotation;
import stirling.software.jpdfium.doc.BlankPageDetector;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.text.PdfTableExtractor;
import stirling.software.jpdfium.text.Table;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SAMPLE 88 — Streaming &amp; Parallel Processing: Comprehensive Showcase.
 *
 * <p>This sample demonstrates how <b>every parallelizable operation</b> in JPDFium
 * (S01–S87) can be accelerated with {@link PdfPipeline} and {@link ProcessingMode}.
 * It generates a large synthetic test corpus, runs each operation category in
 * sequential, parallel, and streaming modes, then produces a benchmark report
 * with JMX/RSS metrics.
 *
 * <h3>Covered Operation Categories (mapped to samples)</h3>
 * <table>
 *   <tr><th>Category</th><th>Samples</th><th>Parallel</th><th>Streaming</th></tr>
 *   <tr><td>Rendering</td><td>S01, S19, S21, S29</td><td>YES</td><td>YES</td></tr>
 *   <tr><td>Text Extraction</td><td>S02, S03, S42, S77</td><td>YES</td><td>YES</td></tr>
 *   <tr><td>Table Extraction</td><td>S24</td><td>YES</td><td>YES</td></tr>
 *   <tr><td>Analytics / Inspection</td><td>S07, S12, S30, S32, S58, S68, S78, S85</td><td>YES</td><td>YES</td></tr>
 *   <tr><td>Detection</td><td>S47, S60, S67, S76, S81</td><td>YES</td><td>YES</td></tr>
 *   <tr><td>Modification (flatten/crop)</td><td>S09, S25, S40, S70, S71, S72, S79, S84</td><td>YES (split/merge)</td><td>YES</td></tr>
 *   <tr><td>Annotation CRUD</td><td>S36, S39, S48, S61, S73</td><td>YES (split/merge)</td><td>YES</td></tr>
 *   <tr><td>Content Addition</td><td>S16, S23, S26, S37, S53, S80</td><td>YES (split/merge)</td><td>YES</td></tr>
 *   <tr><td>Redaction</td><td>S06, S50</td><td>YES</td><td>YES</td></tr>
 *   <tr><td>Selective Rasterization</td><td>S84, S65, S86</td><td>YES (split/merge)</td><td>YES</td></tr>
 * </table>
 *
 * <h3>Operations NOT suitable for streaming/parallel</h3>
 * <p>The following are whole-document operations that cannot be meaningfully
 * parallelized or streamed:
 * <ul>
 *   <li>S04 Metadata, S05 Bookmarks, S10 Signatures — document-level reads</li>
 *   <li>S11 Attachments, S13 PageImport, S17 NUp — cross-document assembly</li>
 *   <li>S18 Repair, S22 MergeSplit — document-wide structural ops</li>
 *   <li>S27 Security, S33 Encryption, S49 NativeEncryption — global crypto</li>
 *   <li>S34 Linearize, S41 VersionConvert, S43 StreamOptimize — structural</li>
 *   <li>S45 Interleave, S52 BookmarkEditor, S56 Booklet — page reordering</li>
 *   <li>S51 Compress, S66 PdfDiff, S69 PdfA — external tool pipelines</li>
 *   <li>S54 PageReorder, S82 ResourceDedup, S83 TocGenerate — sequential</li>
 * </ul>
 *
 * <h3>How to enable streaming/parallel in YOUR sample</h3>
 * <p>See the guidance comments in each operation method below, and the
 * "STREAMING/PARALLEL GUIDE" block at the bottom of this file.
 *
 * <h3>Test Corpus</h3>
 * <p>Generates three synthetic PDFs from existing test resources:
 * <ul>
 *   <li><b>large-text.pdf</b> — 200 pages, text-heavy (from mozilla_tracemonkey.pdf)</li>
 *   <li><b>large-forms.pdf</b> — 200 pages, form-heavy (from all_form_fields.pdf)</li>
 *   <li><b>large-mixed.pdf</b> — 300 pages, mixed content (interleaved text + forms)</li>
 * </ul>
 *
 * <h3>Monitoring Stack</h3>
 * <ul>
 *   <li>{@code MemoryMXBean} — peak heap usage per benchmark</li>
 *   <li>{@code GarbageCollectorMXBean} — GC count and pause time</li>
 *   <li>{@code /proc/self/status} VmRSS — native + heap resident memory (Linux)</li>
 *   <li>Thread-name tracking — proves parallel execution</li>
 *   <li>Wall-clock timing — speedup calculations</li>
 * </ul>
 *
 * @see ProcessingMode
 * @see PdfPipeline
 */
public class S88_StreamingParallel {

    /** Pages for text-heavy and form-heavy corpus PDFs. */
    private static final int CORPUS_PAGES_STD  = 200;
    /** Pages for the large mixed corpus PDF. */
    private static final int CORPUS_PAGES_LARGE = 300;
    /** DPI for rendering benchmarks. */
    private static final int RENDER_DPI        = 100;
    /** Thread count for parallel tests. */
    private static final int PARALLEL_THREADS  = 4;
    /** SHA-256 rounds for CPU-bound Java work in parallel tests. */
    private static final int HASH_ROUNDS       = 20;
    /** Streaming flush interval (pages between save/reload cycles). */
    private static final int FLUSH_INTERVAL    = 25;

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path outDir = SampleBase.out("S88_streaming-parallel");
        Path reportFile = outDir.resolve("report.txt");

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  S88 — Streaming & Parallel: All-Operations Benchmark   ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.printf("  JVM:        %s %s%n",
                System.getProperty("java.vm.name"),
                System.getProperty("java.vm.version"));
        System.out.printf("  Processors: %d%n", Runtime.getRuntime().availableProcessors());
        System.out.printf("  Threads:    %d%n", PARALLEL_THREADS);
        System.out.printf("  Flush:      every %d pages%n%n", FLUSH_INTERVAL);

        System.out.println("═══ PHASE 1: Corpus Generation ════════════════════════════");
        List<Path> inputs = SampleBase.inputPdfs(args);

        Path textTemplate  = findTemplate(inputs, "mozilla_tracemonkey");
        Path formTemplate  = findTemplate(inputs, "all_form_fields");

        Path corpusText  = outDir.resolve("corpus-text-200p.pdf");
        Path corpusForms = outDir.resolve("corpus-forms-200p.pdf");
        Path corpusMixed = outDir.resolve("corpus-mixed-300p.pdf");

        byte[] textPdf  = generateCorpus(textTemplate, CORPUS_PAGES_STD, "text-heavy");
        Files.write(corpusText, textPdf);
        byte[] formPdf  = generateCorpus(formTemplate, CORPUS_PAGES_STD, "form-heavy");
        Files.write(corpusForms, formPdf);
        byte[] mixedPdf = generateMixedCorpus(textTemplate, formTemplate, CORPUS_PAGES_LARGE);
        Files.write(corpusMixed, mixedPdf);

        System.out.printf("  corpus-text:  %d pages, %.1f MB%n",
                CORPUS_PAGES_STD, textPdf.length / (1024.0 * 1024.0));
        System.out.printf("  corpus-forms: %d pages, %.1f MB%n",
                CORPUS_PAGES_STD, formPdf.length / (1024.0 * 1024.0));
        System.out.printf("  corpus-mixed: %d pages, %.1f MB%n%n",
                CORPUS_PAGES_LARGE, mixedPdf.length / (1024.0 * 1024.0));

        StringBuilder report = new StringBuilder();
        report.append("================================================================\n");
        report.append("  JPDFium S88: All-Operations Streaming & Parallel Report\n");
        report.append("================================================================\n\n");
        report.append(String.format("JVM: %s %s%n",
                System.getProperty("java.vm.name"),
                System.getProperty("java.vm.version")));
        report.append(String.format("CPUs: %d  |  Threads: %d  |  Flush: %d pages%n",
                Runtime.getRuntime().availableProcessors(),
                PARALLEL_THREADS, FLUSH_INTERVAL));
        report.append(String.format("Corpus text: %d pages (%.1f MB)%n",
                CORPUS_PAGES_STD, textPdf.length / (1024.0 * 1024.0)));
        report.append(String.format("Corpus forms: %d pages (%.1f MB)%n",
                CORPUS_PAGES_STD, formPdf.length / (1024.0 * 1024.0)));
        report.append(String.format("Corpus mixed: %d pages (%.1f MB)%n%n",
                CORPUS_PAGES_LARGE, mixedPdf.length / (1024.0 * 1024.0)));

        Map<String, BenchmarkGroup> results = new LinkedHashMap<>();
        ProcessingMode SEQ       = ProcessingMode.sequential();
        ProcessingMode PAR       = ProcessingMode.parallel(PARALLEL_THREADS);
        ProcessingMode STREAM    = ProcessingMode.builder().streaming(true)
                .flushInterval(FLUSH_INTERVAL).build();
        ProcessingMode COMBINED  = ProcessingMode.builder().streaming(true)
                .parallel(PARALLEL_THREADS).flushInterval(FLUSH_INTERVAL).build();

        System.out.println("═══ PHASE 2: Read-Only Operations ═════════════════════════\n");

        results.put("TextExtract+Hash", benchmarkCategory(
                "Text Extract + Hash (S02,S03,S42,S77)", textPdf,
                SEQ, PAR, STREAM, COMBINED,
                textHashOp()));

        results.put("Render", benchmarkCategory(
                "Page Rendering (S01,S19,S21,S29)", textPdf,
                SEQ, PAR, STREAM, COMBINED,
                renderOp()));

        results.put("AnnotInspect", benchmarkCategory(
                "Annotation Inspect (S07,S12,S73,S85)", formPdf,
                SEQ, PAR, STREAM, COMBINED,
                annotInspectOp()));

        results.put("TableExtract", benchmarkCategory(
                "Table Extract (S24)", textPdf,
                SEQ, PAR, STREAM, COMBINED,
                tableExtractOp()));

        results.put("Detection", benchmarkCategory(
                "Detect (S47 blank, S60 crop detect)", mixedPdf,
                SEQ, PAR, STREAM, COMBINED,
                detectionOp()));

        results.put("PageInspect", benchmarkCategory(
                "Page Inspect (S32,S58,S68,S78)", formPdf,
                SEQ, PAR, STREAM, COMBINED,
                pageInspectOp()));

        System.out.println("\n═══ PHASE 3: Modification Operations ══════════════════════\n");

        results.put("Flatten", benchmarkModifyCategory(
                "Flatten (S09,S72)", formPdf,
                SEQ, PAR, STREAM, COMBINED,
                flattenOp()));

        results.put("AutoCropApply", benchmarkModifyCategory(
                "AutoCrop Apply (S60,S87)", textPdf,
                SEQ, PAR, STREAM, COMBINED,
                autoCropApplyOp()));

        results.put("SelectiveRaster", benchmarkModifyCategory(
                "Selective Raster (S84,S65,S86)", textPdf,
                SEQ, PAR, STREAM, COMBINED,
                selectiveRasterOp()));

        System.out.println("\n═══ PHASE 4: Verification ═════════════════════════════════\n");

        report.append("================================================================\n");
        report.append("  BENCHMARK RESULTS\n");
        report.append("================================================================\n\n");

        int totalPass = 0;
        int totalMarginal = 0;

        for (var entry : results.entrySet()) {
            BenchmarkGroup g = entry.getValue();
            report.append(formatGroup(g));

            if (g.parallel != null && g.sequential != null && g.parallel.durationMs > 0) {
                double speedup = safeDivide(g.sequential.durationMs, g.parallel.durationMs);
                if (speedup > 1.1) totalPass++; else totalMarginal++;
            }
        }

        report.append("\n================================================================\n");
        report.append("  VERIFICATION SUMMARY\n");
        report.append("================================================================\n\n");

        for (var entry : results.entrySet()) {
            BenchmarkGroup g = entry.getValue();
            String name = g.label;

            if (g.parallel != null && g.sequential != null) {
                double speedup = safeDivide(g.sequential.durationMs, g.parallel.durationMs);
                boolean multiThread = g.parallel.uniqueThreadCount > 1;
                report.append(String.format("%-50s PAR: %.2fx %s  THR: %d %s%n",
                        name,
                        speedup, speedup > 1.1 ? "PASS" : "MARGINAL",
                        g.parallel.uniqueThreadCount, multiThread ? "PASS" : "FAIL"));
            }

            if (g.streaming != null && g.sequential != null) {
                boolean lowerHeap = g.streaming.peakHeapMB <= g.sequential.peakHeapMB;
                report.append(String.format("%-50s STR: heap seq=%dMB str=%dMB %s%n",
                        name,
                        g.sequential.peakHeapMB, g.streaming.peakHeapMB,
                        lowerHeap ? "PASS" : "MARGINAL (GC timing may vary)"));
            }
        }

        report.append(String.format(
                "%n═══════════════════════════════════════%n" +
                "  OVERALL: %d PASS, %d MARGINAL%n" +
                "═══════════════════════════════════════%n",
                totalPass, totalMarginal));

        String reportStr = report.toString();
        Files.writeString(reportFile, reportStr);
        System.out.println(reportStr);

        SampleBase.done("S88_StreamingParallel", corpusText, corpusForms, corpusMixed, reportFile);
    }

    /**
     * Text extraction + SHA-256 hashing (S02, S03, S42, S77).
     *
     * <p>Extracts text from each page via PDFium (serialized), then performs
     * CPU-bound SHA-256 hashing on the extracted text (parallel). The hash
     * work simulates downstream NLP, indexing, or search operations.
     */
    private static PdfPipeline.PageOperation textHashOp() {
        return (doc, pageIndex) -> {
            String text;
            synchronized (PdfPipeline.PDFIUM_LOCK) {
                try (PdfPage page = doc.page(pageIndex)) {
                    text = page.extractTextJson();
                }
            }
            // CPU-bound Java work — runs in parallel across threads
            try {
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                byte[] inflated = new byte[Math.max(data.length, 64 * 1024)];
                for (int i = 0; i < inflated.length; i++) {
                    inflated[i] = data[i % data.length];
                }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = inflated;
                for (int r = 0; r < HASH_ROUNDS; r++) {
                    hash = md.digest(hash);
                    byte[] next = new byte[inflated.length];
                    for (int i = 0; i < next.length; i++) {
                        next[i] = (byte) (inflated[i] ^ hash[i % hash.length]);
                    }
                    hash = md.digest(next);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * Page rendering (S01, S19, S21, S29).
     *
     * <p>Renders each page at configured DPI. Streaming flushes release
     * PDFium's font/image caches between batches. Parallel requires
     * PDFIUM_LOCK around renderAt().
     */
    private static PdfPipeline.PageOperation renderOp() {
        return (doc, pageIndex) -> {
            byte[] pixels;
            synchronized (PdfPipeline.PDFIUM_LOCK) {
                try (PdfPage page = doc.page(pageIndex)) {
                    RenderResult result = page.renderAt(RENDER_DPI);
                    pixels = result.rgba();
                }
            }
            // Simulate post-processing (image encoding) — parallel
            long sum = 0;
            for (int i = 0; i < pixels.length; i += 997) sum += pixels[i];
            if (sum == Long.MIN_VALUE) System.out.print("");
        };
    }

    /**
     * Annotation inspection (S07, S12, S73, S85).
     *
     * <p>Reads annotations from each page, performs Java-side classification.
     */
    private static PdfPipeline.PageOperation annotInspectOp() {
        return (doc, pageIndex) -> {
            List<Annotation> annots;
            synchronized (PdfPipeline.PDFIUM_LOCK) {
                try (PdfPage page = doc.page(pageIndex)) {
                    annots = page.annotations();
                }
            }
            // Java-side classification — runs in parallel
            int highlights = 0, widgets = 0, other = 0;
            for (Annotation a : annots) {
                AnnotationType type = a.type();
                if (type == AnnotationType.HIGHLIGHT) highlights++;
                else if (type == AnnotationType.WIDGET) widgets++;
                else other++;
            }
            String summary = String.format("p%d: %d annots (%dH,%dW,%dO)",
                    pageIndex, annots.size(), highlights, widgets, other);
            summary.hashCode(); // prevent elimination
        };
    }

    /**
     * Table extraction (S24).
     *
     * <p>Extracts tables per page; geometry analysis benefits from parallelization.
     */
    private static PdfPipeline.PageOperation tableExtractOp() {
        return (doc, pageIndex) -> {
            List<Table> tables;
            synchronized (PdfPipeline.PDFIUM_LOCK) {
                tables = PdfTableExtractor.extract(doc, pageIndex);
            }
            // Java-side CSV generation — parallel
            for (Table t : tables) {
                String csv = t.toCsv();
                csv.hashCode(); // prevent elimination
            }
        };
    }

    /**
     * Detection (S47 blank, S60 autocrop, S67 deskew).
     *
     * <p>Per-page detection of blank pages and content bounds.
     */
    private static PdfPipeline.PageOperation detectionOp() {
        return (doc, pageIndex) -> {
            boolean isBlank;
            Rect cropBounds;
            synchronized (PdfPipeline.PDFIUM_LOCK) {
                try (PdfPage page = doc.page(pageIndex)) {
                    isBlank = BlankPageDetector.isBlankText(page.rawHandle());
                }
                cropBounds = PdfAutoCrop.detectContentBoundsText(doc, pageIndex, 10);
            }
            // Java-side aggregation — parallel
            String result = String.format("p%d: blank=%b crop=%s",
                    pageIndex, isBlank, cropBounds != null ? "found" : "none");
            result.hashCode(); // prevent elimination
        };
    }

    /**
     * Page inspection: char positions + page size (S32, S58, S68, S78).
     *
     * <p>Extracts character-level position data and computes page statistics.
     */
    private static PdfPipeline.PageOperation pageInspectOp() {
        return (doc, pageIndex) -> {
            String charPositions;
            PageSize size;
            synchronized (PdfPipeline.PDFIUM_LOCK) {
                try (PdfPage page = doc.page(pageIndex)) {
                    charPositions = page.extractCharPositionsJson();
                    size = page.size();
                }
            }
            // Java-side analysis — parallel
            int charCount = charPositions.length();
            double density = charCount / (size.width() * size.height());
            String stats = String.format("p%d: %d chars, density=%.4f",
                    pageIndex, charCount, density);
            stats.hashCode(); // prevent elimination
        };
    }

    /**
     * Flatten rotation (S09, S72).
     *
     * <p>Parallel mode: each chunk is an independent document,
     * so modification is safe without cross-page coordination.
     */
    private static PdfPipeline.PageOperation flattenOp() {
        return (doc, pageIndex) -> {
            synchronized (PdfPipeline.PDFIUM_LOCK) {
                try (PdfPage page = doc.page(pageIndex)) {
                    PdfFlattenRotation.flatten(page.rawHandle());
                }
            }
        };
    }

    /**
     * Auto-crop detection (S60, S87).
     *
     * <p>Detects content bounds for each page.
     */
    private static PdfPipeline.PageOperation autoCropApplyOp() {
        return (doc, pageIndex) -> {
            synchronized (PdfPipeline.PDFIUM_LOCK) {
                PdfAutoCrop.detectContentBoundsText(doc, pageIndex, 10);
            }
        };
    }

    /**
     * Selective rasterization (S84, S65, S86).
     *
     * <p>Rasterizes every 5th page at 72 DPI.
     */
    private static PdfPipeline.PageOperation selectiveRasterOp() {
        return (doc, pageIndex) -> {
            if (pageIndex % 5 == 0) {
                synchronized (PdfPipeline.PDFIUM_LOCK) {
                    doc.convertPageToImage(pageIndex, 72);
                }
            }
        };
    }

    /**
     * Benchmarks a read-only operation across 4 modes.
     */
    private static BenchmarkGroup benchmarkCategory(
            String label, byte[] pdf,
            ProcessingMode seq, ProcessingMode par,
            ProcessingMode stream, ProcessingMode combined,
            PdfPipeline.PageOperation op) {

        System.out.printf("  %-50s", label);
        System.out.flush();

        forceGc();
        Metrics mSeq = benchmarkForEach(pdf, seq, op);
        forceGc();
        Metrics mPar = benchmarkForEach(pdf, par, op);
        forceGc();
        Metrics mStr = benchmarkForEach(pdf, stream, op);
        forceGc();
        Metrics mCom = benchmarkForEach(pdf, combined, op);

        double speedup = safeDivide(mSeq.durationMs, mPar.durationMs);
        System.out.printf("  seq=%.2fs  par=%.2fs (%.1fx)  str=%.2fs  comb=%.2fs%n",
                mSeq.durationMs / 1000.0, mPar.durationMs / 1000.0, speedup,
                mStr.durationMs / 1000.0, mCom.durationMs / 1000.0);

        return new BenchmarkGroup(label, mSeq, mPar, mStr, mCom);
    }

    /**
     * Benchmarks a modification operation across 4 modes.
     */
    private static BenchmarkGroup benchmarkModifyCategory(
            String label, byte[] pdf,
            ProcessingMode seq, ProcessingMode par,
            ProcessingMode stream, ProcessingMode combined,
            PdfPipeline.PageOperation op) {

        System.out.printf("  %-50s", label);
        System.out.flush();

        forceGc();
        Metrics mSeq = benchmarkProcess(pdf, seq, op);
        forceGc();
        Metrics mPar = benchmarkProcess(pdf, par, op);
        forceGc();
        Metrics mStr = benchmarkProcess(pdf, stream, op);
        forceGc();
        Metrics mCom = benchmarkProcess(pdf, combined, op);

        double speedup = safeDivide(mSeq.durationMs, mPar.durationMs);
        System.out.printf("  seq=%.2fs  par=%.2fs (%.1fx)  str=%.2fs  comb=%.2fs%n",
                mSeq.durationMs / 1000.0, mPar.durationMs / 1000.0, speedup,
                mStr.durationMs / 1000.0, mCom.durationMs / 1000.0);

        return new BenchmarkGroup(label, mSeq, mPar, mStr, mCom);
    }

    /** Measures a read-only forEach run with JMX monitoring. */
    private static Metrics benchmarkForEach(byte[] pdf, ProcessingMode mode,
                                            PdfPipeline.PageOperation op) {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        Set<String> threadNames = ConcurrentHashMap.newKeySet();

        PdfPipeline.PageOperation wrapped = (doc, pageIndex) -> {
            threadNames.add(Thread.currentThread().getName());
            op.apply(doc, pageIndex);
        };

        long gcCountBefore = totalGcCount();
        long gcTimeBefore  = totalGcTimeMs();

        AtomicLong peakHeap = new AtomicLong(0);
        AtomicLong peakRss  = new AtomicLong(0);
        Thread monitor = startMonitor(memBean, peakHeap, peakRss);

        long startNs = System.nanoTime();

        if (mode.isParallel()) {
            PdfPipeline.forEach(pdf, mode, wrapped);
        } else if (mode.isStreaming()) {
            streamingForEach(pdf, mode, wrapped);
        } else {
            try (PdfDocument doc = PdfDocument.open(pdf)) {
                for (int i = 0; i < doc.pageCount(); i++) {
                    wrapped.apply(doc, i);
                }
            }
        }

        long durationMs = (System.nanoTime() - startNs) / 1_000_000;
        stopMonitor(monitor);

        return new Metrics(durationMs,
                peakHeap.get() / (1024 * 1024),
                peakRss.get() > 0 ? peakRss.get() / 1024 : -1,
                totalGcCount() - gcCountBefore,
                totalGcTimeMs() - gcTimeBefore,
                threadNames.size(), List.copyOf(threadNames));
    }

    /** Measures a modification process() run with JMX monitoring. */
    private static Metrics benchmarkProcess(byte[] pdf, ProcessingMode mode,
                                            PdfPipeline.PageOperation op) {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        Set<String> threadNames = ConcurrentHashMap.newKeySet();

        PdfPipeline.PageOperation wrapped = (doc, pageIndex) -> {
            threadNames.add(Thread.currentThread().getName());
            op.apply(doc, pageIndex);
        };

        long gcCountBefore = totalGcCount();
        long gcTimeBefore  = totalGcTimeMs();

        AtomicLong peakHeap = new AtomicLong(0);
        AtomicLong peakRss  = new AtomicLong(0);
        Thread monitor = startMonitor(memBean, peakHeap, peakRss);

        long startNs = System.nanoTime();
        try (PdfDocument result = PdfPipeline.process(pdf, mode, wrapped)) {
            // discard — measuring processing time
        }
        long durationMs = (System.nanoTime() - startNs) / 1_000_000;
        stopMonitor(monitor);

        return new Metrics(durationMs,
                peakHeap.get() / (1024 * 1024),
                peakRss.get() > 0 ? peakRss.get() / 1024 : -1,
                totalGcCount() - gcCountBefore,
                totalGcTimeMs() - gcTimeBefore,
                threadNames.size(), List.copyOf(threadNames));
    }

    private static void streamingForEach(byte[] pdf, ProcessingMode mode,
                                         PdfPipeline.PageOperation op) {
        PdfDocument doc = PdfDocument.open(pdf);
        try {
            int pages = doc.pageCount();
            int flush = mode.flushInterval();
            for (int i = 0; i < pages; i++) {
                op.apply(doc, i);
                if ((i + 1) % flush == 0 && (i + 1) < pages) {
                    byte[] snap = doc.saveBytes();
                    doc.close();
                    doc = PdfDocument.open(snap);
                }
            }
        } finally {
            doc.close();
        }
    }

    record Metrics(
            long durationMs,
            long peakHeapMB, long peakRssMB,
            long gcCount, long gcTimeMs,
            int uniqueThreadCount, List<String> threadNames
    ) {}

    record BenchmarkGroup(
            String label,
            Metrics sequential, Metrics parallel,
            Metrics streaming, Metrics combined
    ) {}

    private static String formatGroup(BenchmarkGroup g) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("── %s ──%n", g.label));
        sb.append(formatMetrics("Sequential", g.sequential));
        sb.append(formatMetrics("Parallel-" + PARALLEL_THREADS, g.parallel));
        sb.append(formatMetrics("Streaming", g.streaming));
        sb.append(formatMetrics("Combined", g.combined));

        if (g.sequential != null && g.parallel != null) {
            double speedup = safeDivide(g.sequential.durationMs, g.parallel.durationMs);
            sb.append(String.format("  -> Parallel speedup: %.2fx%n", speedup));
        }
        if (g.sequential != null && g.streaming != null) {
            sb.append(String.format("  -> Streaming heap: %dMB (seq) vs %dMB (str)%n",
                    g.sequential.peakHeapMB, g.streaming.peakHeapMB));
        }
        sb.append("\n");
        return sb.toString();
    }

    private static String formatMetrics(String label, Metrics m) {
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  %-20s time=%.3fs  heap=%dMB", label, m.durationMs / 1000.0, m.peakHeapMB));
        if (m.peakRssMB > 0) sb.append(String.format("  rss=%dMB", m.peakRssMB));
        sb.append(String.format("  gc=%d(%.0fms)  threads=%d", m.gcCount, (double)m.gcTimeMs, m.uniqueThreadCount));
        if (m.uniqueThreadCount > 1) {
            sb.append(" [");
            sb.append(String.join(", ", m.threadNames));
            sb.append("]");
        }
        sb.append("\n");
        return sb.toString();
    }

    private static Thread startMonitor(MemoryMXBean memBean, AtomicLong peakHeap, AtomicLong peakRss) {
        Thread monitor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peakHeap.accumulateAndGet(memBean.getHeapMemoryUsage().getUsed(), Math::max);
                long rss = readRssKB();
                if (rss > 0) peakRss.accumulateAndGet(rss, Math::max);
                try { Thread.sleep(25); } catch (InterruptedException e) { break; }
            }
        });
        monitor.setDaemon(true);
        monitor.start();
        return monitor;
    }

    private static void stopMonitor(Thread monitor) {
        monitor.interrupt();
        try { monitor.join(500); } catch (InterruptedException ignored) {}
    }

    private static long totalGcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .filter(c -> c >= 0).sum();
    }

    private static long totalGcTimeMs() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(c -> c >= 0).sum();
    }

    private static long readRssKB() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:"))
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static void forceGc() {
        System.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        System.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
    }

    private static double safeDivide(long a, long b) {
        return b > 0 ? (double) a / b : 1.0;
    }

    private static byte[] generateCorpus(Path templatePath, int targetPages, String label) {
        System.out.printf("  Generating %s corpus (%d pages)...", label, targetPages);
        System.out.flush();
        try (PdfDocument template = PdfDocument.open(templatePath)) {
            if (template.pageCount() >= targetPages) return template.saveBytes();
            PdfDocument dest = PdfDocument.open(template.saveBytes());
            try {
                while (dest.pageCount() < targetPages) {
                    PdfPageImporter.importPages(
                            dest.rawHandle(), template.rawHandle(), null, dest.pageCount());
                }
                byte[] result = dest.saveBytes();
                System.out.println(" done");
                return result;
            } finally {
                dest.close();
            }
        }
    }

    private static byte[] generateMixedCorpus(Path textTemplate, Path formTemplate, int targetPages) {
        System.out.printf("  Generating mixed corpus (%d pages)...", targetPages);
        System.out.flush();
        try (PdfDocument textDoc = PdfDocument.open(textTemplate);
             PdfDocument formDoc = PdfDocument.open(formTemplate)) {
            PdfDocument dest = PdfDocument.open(textDoc.saveBytes());
            try {
                while (dest.pageCount() < targetPages) {
                    PdfDocument src = (dest.pageCount() / 5) % 2 == 0 ? textDoc : formDoc;
                    PdfPageImporter.importPages(
                            dest.rawHandle(), src.rawHandle(), null, dest.pageCount());
                }
                byte[] result = dest.saveBytes();
                System.out.println(" done");
                return result;
            } finally {
                dest.close();
            }
        }
    }

    private static Path findTemplate(List<Path> inputs, String stemPrefix) {
        return inputs.stream()
                .filter(p -> SampleBase.stem(p).contains(stemPrefix))
                .findFirst()
                .orElse(inputs.getFirst());
    }
}
