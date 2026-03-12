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
 * SAMPLE 92 - Rust-enhanced PDF Compression (zopfli FlateDecode).
 *
 * <p>Demonstrates the optional Rust/zopfli post-processing pass that improves
 * FlateDecode stream compression by 10-25% over standard DEFLATE. The pass uses
 * lopdf (to reload and decompress all streams) and zopfli (to recompress them with
 * superior DEFLATE iteration).
 *
 * <p>The Rust path is fully transparent and gracefully unavailable: if the native
 * library was compiled without {@code -DJPDFIUM_USE_RUST=ON}, the
 * {@code useZopfliDeflate} option is silently ignored and the existing qpdf output
 * is returned unchanged. No exception is thrown.
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
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S92_RustCompress {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        Path outDir = SampleBase.out("S92_rust-compress");
        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);
        List<Path> produced = new ArrayList<>();

        long inputSize = Files.size(input);
        System.out.printf("S92_RustCompress  |  input: %s (%,d bytes)%n",
                input.getFileName(), inputSize);

        // 1. Baseline: standard lossless compression (qpdf only, no Rust)
        SampleBase.section("Baseline: lossless (qpdf only, no Rust)");
        byte[] baseline;
        try (PdfDocument doc = PdfDocument.open(input)) {
            CompressResultWithBytes result = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .preset(CompressPreset.LOSSLESS)
                            .build());
            baseline = result.bytes();
            Path outFile = outDir.resolve(stem + "-lossless.pdf");
            Files.write(outFile, baseline);
            produced.add(outFile);
            System.out.println(result.summary());
            System.out.printf("  Actions: %s%n", result.toJson());
        }

        // 2. Rust/zopfli pass with default iteration count (15)
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

        // 3. Rust/zopfli with fast iteration count (5) - quick mode
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

        // 4. Full pipeline: Ghostscript + qpdf + zopfli (maximum compression)
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

        SampleBase.done("S92_RustCompress", produced.toArray(Path[]::new));
    }
}
