package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.doc.PdfAConverter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 69 - PDF/A Conversion.
 *
 * <p>Demonstrates PdfAConverter: converting a PDF to PDF/A-1b, PDF/A-2b, or PDF/A-3b
 * compliance using Ghostscript.
 *
 * <p>Requires Ghostscript ({@code gs}) and an sRGB ICC profile on the system.
 */
public class S69_PdfAConversion {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S69_PdfAConversion  |  %d PDF(s)%n", inputs.size());

        if (!PdfAConverter.isAvailable()) {
            System.out.println("  Ghostscript not available; skipping PDF/A conversion.");
            System.out.println("  Install: apt install ghostscript / brew install ghostscript");
            SampleBase.done("S69_PdfAConversion");
            return;
        }

        Path outDir = SampleBase.out("S69_pdfa-conversion");

        // Convert first PDF only (conversion is slow)
        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);

        // Save to temp file first (Ghostscript works on files, not in-memory)
        Path tempInput = Files.createTempFile("pdfa-input-", ".pdf");
        try {
            Files.copy(input, tempInput, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // PDF/A-2b conversion
            Path outPath = outDir.resolve(stem + "-pdfa2b.pdf");
            SampleBase.section(stem + " -> PDF/A-2b");

            PdfAConverter.ConversionResult result =
                    PdfAConverter.convert(tempInput, outPath, PdfAConverter.PdfALevel.PDFA_2B);

            System.out.printf("  Input:  %d bytes%n", result.inputSize());
            System.out.printf("  Output: %d bytes%n", result.outputSize());
            System.out.printf("  Level:  %s%n", result.level());
            System.out.printf("  ICC:    %s%n", result.iccProfileUsed());
            produced.add(outPath);

        } finally {
            Files.deleteIfExists(tempInput);
        }

        SampleBase.done("S69_PdfAConversion", produced.toArray(Path[]::new));
    }
}
