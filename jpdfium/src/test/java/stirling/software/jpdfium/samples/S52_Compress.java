package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.CompressOptions;
import stirling.software.jpdfium.doc.CompressPreset;
import stirling.software.jpdfium.doc.PdfCompressor;
import stirling.software.jpdfium.doc.PdfCompressor.CompressResultWithBytes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 52 - PDF Compression (Complete Guide).
 *
 * <p>Demonstrates the complete compression pipeline with ALL features:
 * <ul>
 *   <li>Ghostscript (image resampling, font subsetting)</li>
 *   <li>qpdf (structural optimization, object streams)</li>
 *   <li>Metadata stripping</li>
 *   <li>Rust/zopfli FlateDecode enhancement (10-25% better than standard DEFLATE)</li>
 * </ul>
 *
 * <h3>Compression Presets</h3>
 * <ul>
 *   <li>{@link CompressPreset#LOSSLESS} - Structural optimization only, no quality loss</li>
 *   <li>{@link CompressPreset#WEB} - Ghostscript ebook + qpdf (balanced for web)</li>
 *   <li>{@link CompressPreset#SCREEN} - Aggressive compression (96 DPI, max quality 40)</li>
 *   <li>{@link CompressPreset#MAXIMUM} - Maximum compression (all optimizations)</li>
 * </ul>
 *
 * <h3>Rust/zopfli Enhancement</h3>
 * <p>The optional Rust/zopfli pass improves FlateDecode stream compression by 10-25%
 * over standard DEFLATE. It uses lopdf (to reload and decompress all streams) and
 * zopfli (to recompress with superior DEFLATE iteration).
 *
 * <p>The Rust path is transparent: if the native library was compiled without
 * {@code -DJPDFIUM_USE_RUST=ON}, the {@code useZopfliDeflate} option is silently
 * ignored. No exception is thrown.
 *
 * <h3>Build with Rust support (optional)</h3>
 * <pre>
 * bash native/build-rust.sh        # compile Rust static library
 * cmake -B native/build-real -S native \
 *       -DJPDFIUM_USE_PDFIUM=ON -DPDFIUM_DIR=native/pdfium \
 *       -DJPDFIUM_USE_RUST=ON
 * cmake --build native/build-real --parallel
 * </pre>
 *
 * <h3>Streaming &amp; Parallel Guidance (LOW / not parallelizable)</h3>
 * <p>Compression operates on the <b>entire document</b> (cross-reference table,
 * object streams, font subsetting). It cannot be split per-page.
 * <p>However, if you need to compress <b>multiple PDFs</b>, you can parallelize
 * at the file level:
 * <pre>{@code
 * List<Path> inputs = ...;
 * try (var pool = Executors.newFixedThreadPool(4)) {
 *     inputs.forEach(input -> pool.submit(() -> {
 *         synchronized (PdfPipeline.PDFIUM_LOCK) {
 *             try (PdfDocument doc = PdfDocument.open(input)) {
 *                 PdfCompressor.compress(doc, CompressPreset.BALANCED);
 *                 doc.save(outputPath);
 *             }
 *         }
 *     }));
 * }
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for the full streaming/parallel guide.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S52_Compress {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        Path outDir = SampleBase.out("S52_compress");
        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);
        List<Path> produced = new ArrayList<>();

        long inputSize = Files.size(input);
        System.out.printf("S52_Compress  |  input: %s (%,d bytes)%n",
                input.getFileName(), inputSize);

        // ====================================================================
        // SECTION 1: Standard Compression (no Rust)
        // ====================================================================

        // 1. Lossless (structural optimization only, no quality loss)
        SampleBase.section("Lossless compression (qpdf only, no quality loss)");
        byte[] baseline;
        try (PdfDocument doc = PdfDocument.open(input)) {
            CompressResultWithBytes result = PdfCompressor.compress(doc,
                    CompressOptions.builder().preset(CompressPreset.LOSSLESS).build());
            baseline = result.bytes();
            Path outFile = outDir.resolve(stem + "-lossless.pdf");
            Files.write(outFile, baseline);
            produced.add(outFile);
            System.out.println(result.summary());
            System.out.printf("  Actions: %s%n", result.toJson());
        }

        // 2. Web preset (Ghostscript ebook + qpdf)
        SampleBase.section("Web preset (GS ebook + qpdf, balanced for web)");
        try (PdfDocument doc = PdfDocument.open(input)) {
            CompressResultWithBytes result = PdfCompressor.compress(doc,
                    CompressOptions.builder().preset(CompressPreset.WEB).build());
            Path outFile = outDir.resolve(stem + "-web.pdf");
            Files.write(outFile, result.bytes());
            produced.add(outFile);
            System.out.println(result.summary());
        }

        // 3. Screen preset (maximum compression, 96 DPI)
        SampleBase.section("Screen preset (aggressive, 96 DPI)");
        try (PdfDocument doc = PdfDocument.open(input)) {
            CompressResultWithBytes result = PdfCompressor.compress(doc,
                    CompressOptions.builder().preset(CompressPreset.SCREEN).build());
            Path outFile = outDir.resolve(stem + "-screen.pdf");
            Files.write(outFile, result.bytes());
            produced.add(outFile);
            System.out.println(result.summary());
        }

        // 4. Maximum compression (all optimizations)
        SampleBase.section("Maximum compression (all optimizations)");
        try (PdfDocument doc = PdfDocument.open(input)) {
            byte[] compressed = PdfCompressor.maximum(doc);
            Path outFile = outDir.resolve(stem + "-maximum.pdf");
            Files.write(outFile, compressed);
            produced.add(outFile);
            System.out.printf("  %d to %d bytes (%.1f%% reduction)%n",
                    inputSize, compressed.length,
                    100.0 * (inputSize - compressed.length) / inputSize);
        }

        // 5. Custom options (explicit quality + DPI)
        SampleBase.section("Custom options (quality=60, DPI=120, strip metadata)");
        try (PdfDocument doc = PdfDocument.open(input)) {
            CompressResultWithBytes result = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .imageQuality(60)
                            .maxImageDpi(120)
                            .removeMetadata(true)
                            .optimizeStreams(true)
                            .removeUnusedObjects(true)
                            .build());
            Path outFile = outDir.resolve(stem + "-custom.pdf");
            Files.write(outFile, result.bytes());
            produced.add(outFile);
            System.out.println(result.summary());
            System.out.println("  JSON: " + result.toJson());
        }

        // ====================================================================
        // SECTION 2: Rust/zopfli Enhancement
        // ====================================================================

        // 6. Rust/zopfli pass with default iteration count (15)
        SampleBase.section("Rust/zopfli: lossless + zopfli (15 iterations, default)");
        try (PdfDocument doc = PdfDocument.open(input)) {
            CompressResultWithBytes result = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .preset(CompressPreset.LOSSLESS)
                            .useZopfliDeflate(true)
                            .zopfliIterations(15)
                            .build());
            byte[] compressed = result.bytes();
            Path outFile = outDir.resolve(stem + "-zopfli-15.pdf");
            Files.write(outFile, compressed);
            produced.add(outFile);
            System.out.println(result.summary());
            System.out.printf("  Actions: %s%n", result.toJson());
            if (compressed.length < baseline.length) {
                double extra = 100.0 * (baseline.length - compressed.length) / baseline.length;
                System.out.printf("  Zopfli saved %.1f%% more than qpdf-only baseline%n", extra);
            } else if (result.toJson().contains("zopfli")) {
                System.out.println("  Note: zopfli ran but did not produce smaller output for this PDF.");
            } else {
                System.out.println("  Note: Rust not compiled in - zopfli pass skipped.");
                System.out.println("        Build with -DJPDFIUM_USE_RUST=ON to enable.");
            }
        }

        // 7. Rust/zopfli with fast iteration count (5) - quick mode
        SampleBase.section("Rust/zopfli: lossless + zopfli (5 iterations, fast)");
        try (PdfDocument doc = PdfDocument.open(input)) {
            CompressResultWithBytes result = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .preset(CompressPreset.LOSSLESS)
                            .useZopfliDeflate(true)
                            .zopfliIterations(5)
                            .build());
            Path outFile = outDir.resolve(stem + "-zopfli-5.pdf");
            Files.write(outFile, result.bytes());
            produced.add(outFile);
            System.out.println(result.summary());
        }

        // 8. Full pipeline: Ghostscript + qpdf + zopfli (maximum compression)
        SampleBase.section("Full pipeline: GS + qpdf + Rust/zopfli (maximum)");
        try (PdfDocument doc = PdfDocument.open(input)) {
            CompressResultWithBytes result = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .preset(CompressPreset.MAXIMUM)
                            .useZopfliDeflate(true)
                            .zopfliIterations(15)
                            .build());
            byte[] compressed = result.bytes();
            Path outFile = outDir.resolve(stem + "-maximum-zopfli.pdf");
            Files.write(outFile, compressed);
            produced.add(outFile);
            System.out.println(result.summary());
            System.out.printf("  Total reduction vs. original: %.1f%%%n",
                    100.0 * (inputSize - compressed.length) / inputSize);
        }

        // 9. Web + zopfli (balanced compression with enhanced FlateDecode)
        SampleBase.section("Web + Rust/zopfli (balanced + enhanced DEFLATE)");
        try (PdfDocument doc = PdfDocument.open(input)) {
            CompressResultWithBytes result = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .preset(CompressPreset.WEB)
                            .useZopfliDeflate(true)
                            .zopfliIterations(15)
                            .build());
            Path outFile = outDir.resolve(stem + "-web-zopfli.pdf");
            Files.write(outFile, result.bytes());
            produced.add(outFile);
            System.out.println(result.summary());
        }

        SampleBase.done("S52_Compress", produced.toArray(Path[]::new));
    }
}
