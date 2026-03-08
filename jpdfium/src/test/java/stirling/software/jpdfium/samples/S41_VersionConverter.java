package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfVersionConverter;
import stirling.software.jpdfium.model.PdfVersion;

import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 41 - Version Converter.
 *
 * <p>Demonstrates PdfVersionConverter: reading the PDF version and converting
 * (saving) the document with a different version number using FPDF_SaveWithVersion.
 */
public class S41_VersionConverter {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        Path outDir = SampleBase.out("S41_version-convert");

        System.out.printf("S41_VersionConverter  |  %d PDF(s)%n", inputs.size());

        Path input = inputs.get(0);
        String stem = SampleBase.stem(input);

        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfVersion original = PdfVersionConverter.getVersion(doc.rawHandle());
            System.out.printf("  Original version: %s%n", original);

            // Convert to PDF 1.4 (widely compatible)
            PdfVersion target14 = PdfVersion.V1_4;
            Path out14 = outDir.resolve(stem + "-v1.4.pdf");
            PdfVersionConverter.saveWithVersion(doc.rawHandle(), target14, out14);
            System.out.printf("  Saved as %s: %s%n", target14, out14.getFileName());

            // Verify
            try (PdfDocument v14Doc = PdfDocument.open(out14)) {
                PdfVersion readBack = PdfVersionConverter.getVersion(v14Doc.rawHandle());
                System.out.printf("  Verified: %s%n", readBack);
            }

            // Convert to PDF 2.0
            PdfVersion target20 = PdfVersion.V2_0;
            Path out20 = outDir.resolve(stem + "-v2.0.pdf");
            PdfVersionConverter.saveWithVersion(doc.rawHandle(), target20, out20);
            System.out.printf("  Saved as %s: %s%n", target20, out20.getFileName());

            SampleBase.done("S41_VersionConverter", out14, out20);
        }
    }
}
