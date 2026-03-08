package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.doc.PdfLinearizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 34 - Linearization (fast web view).
 *
 * <p>Demonstrates PdfLinearizer: checking if a PDF is linearized and actually
 * linearizing it using qpdf.
 */
public class S34_Linearizer {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        Path outDir = SampleBase.out("S34_linearize");

        System.out.printf("S34_Linearizer  |  %d PDF(s)  qpdf=%b%n",
                inputs.size(), PdfLinearizer.isSupported());

        Path input = inputs.get(0);
        String stem = SampleBase.stem(input);

        // 1. Check if already linearized
        byte[] pdfBytes = Files.readAllBytes(input);
        boolean before = PdfLinearizer.isLinearized(pdfBytes);
        System.out.printf("  Before: linearized=%b  size=%d bytes%n", before, pdfBytes.length);

        // 2. Linearize
        if (PdfLinearizer.isSupported()) {
            Path linearized = outDir.resolve(stem + "-linearized.pdf");
            PdfLinearizer.linearize(input, linearized);

            byte[] outBytes = Files.readAllBytes(linearized);
            boolean after = PdfLinearizer.isLinearized(outBytes);
            System.out.printf("  After:  linearized=%b  size=%d bytes%n", after, outBytes.length);

            SampleBase.done("S34_Linearizer", linearized);
        } else {
            System.out.println("  (qpdf not found - copying original as fallback)");
            Path fallback = outDir.resolve(stem + "-original.pdf");
            Files.copy(input, fallback, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.printf("  Copied: %s (%d bytes)%n", fallback.getFileName(), Files.size(fallback));
            SampleBase.done("S34_Linearizer", fallback);
        }
    }
}
