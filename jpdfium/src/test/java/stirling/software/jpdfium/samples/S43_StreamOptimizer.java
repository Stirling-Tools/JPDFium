package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.doc.PdfStreamOptimizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 43 - Stream Optimizer.
 *
 * <p>Demonstrates PdfStreamOptimizer: optimizing PDF streams using object stream
 * compression via qpdf, and comparing file sizes before/after.
 */
public class S43_StreamOptimizer {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        Path outDir = SampleBase.out("S43_stream-optimize");

        System.out.printf("S43_StreamOptimizer  |  %d PDF(s)  qpdf=%b%n",
                inputs.size(), PdfStreamOptimizer.isSupported());

        if (PdfStreamOptimizer.isSupported()) {
            Path input = inputs.getFirst();
            String stem = SampleBase.stem(input);
            long originalSize = Files.size(input);
            System.out.printf("  Original: %s (%d bytes)%n", input.getFileName(), originalSize);

            // Full optimization (object streams + xref streams)
            Path optimized = outDir.resolve(stem + "-optimized.pdf");
            PdfStreamOptimizer.optimize(input, optimized);
            long optimizedSize = Files.size(optimized);
            double pct = (1.0 - (double) optimizedSize / originalSize) * 100;
            System.out.printf("  Optimized: %s (%d bytes, %.1f%% %s)%n",
                    optimized.getFileName(), optimizedSize,
                    Math.abs(pct), pct >= 0 ? "smaller" : "larger");

            // Compact (remove unreferenced objects)
            Path compacted = outDir.resolve(stem + "-compacted.pdf");
            PdfStreamOptimizer.compact(input, compacted);
            long compactedSize = Files.size(compacted);
            System.out.printf("  Compacted: %s (%d bytes)%n",
                    compacted.getFileName(), compactedSize);

            SampleBase.done("S43_StreamOptimizer", optimized, compacted);
        } else {
            Path input = inputs.getFirst();
            String stem = SampleBase.stem(input);
            System.out.println("  (qpdf not found - copying original as fallback)");
            Path fallback = outDir.resolve(stem + "-original.pdf");
            Files.copy(input, fallback, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.printf("  Copied: %s (%d bytes)%n", fallback.getFileName(), Files.size(fallback));
            SampleBase.done("S43_StreamOptimizer", fallback);
        }
    }
}
