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
 * SAMPLE 51 - PDF Compression.
 *
 * <p>Demonstrates the full compression pipeline: Ghostscript (image resampling,
 * font subsetting) - qpdf (structural optimization) - metadata stripping.
 * Includes all presets and custom options.
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
public class S51_Compress {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        Path outDir = SampleBase.out("S51_compress");
        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S51_Compress  |  input: %s (%d bytes)%n",
                input.getFileName(), Files.size(input));

        // 1. Lossless (structural optimization only, no quality loss)
        SampleBase.section("Lossless compression (qpdf only)");
        try (PdfDocument doc = PdfDocument.open(input)) {
            byte[] compressed = PdfCompressor.lossless(doc);
            Path outFile = outDir.resolve(stem + "-lossless.pdf");
            Files.write(outFile, compressed);
            produced.add(outFile);
            System.out.printf("  %d to %d bytes (%.1f%% reduction)%n",
                    Files.size(input), compressed.length,
                    100.0 * (Files.size(input) - compressed.length) / Files.size(input));
        }

        // 2. Web preset (Ghostscript ebook + qpdf)
        SampleBase.section("Web preset (GS + qpdf)");
        try (PdfDocument doc = PdfDocument.open(input)) {
            CompressResultWithBytes result = PdfCompressor.compress(doc,
                    CompressOptions.builder().preset(CompressPreset.WEB).build());
            Path outFile = outDir.resolve(stem + "-web.pdf");
            Files.write(outFile, result.bytes());
            produced.add(outFile);
            System.out.println(result.summary());
        }

        // 3. Screen preset (maximum compression, 96 DPI)
        SampleBase.section("Screen preset (aggressive)");
        try (PdfDocument doc = PdfDocument.open(input)) {
            CompressResultWithBytes result = PdfCompressor.compress(doc,
                    CompressOptions.builder().preset(CompressPreset.SCREEN).build());
            Path outFile = outDir.resolve(stem + "-screen.pdf");
            Files.write(outFile, result.bytes());
            produced.add(outFile);
            System.out.println(result.summary());
        }

        // 4. Maximum compression
        SampleBase.section("Maximum compression");
        try (PdfDocument doc = PdfDocument.open(input)) {
            byte[] compressed = PdfCompressor.maximum(doc);
            Path outFile = outDir.resolve(stem + "-maximum.pdf");
            Files.write(outFile, compressed);
            produced.add(outFile);
            System.out.printf("  %d to %d bytes%n", Files.size(input), compressed.length);
        }

        // 5. Custom options (explicit quality + DPI)
        SampleBase.section("Custom (quality=60, DPI=120, strip metadata)");
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

        SampleBase.done("S51_Compress", produced.toArray(Path[]::new));
    }
}
