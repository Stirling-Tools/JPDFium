package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.doc.PdfRepair;
import stirling.software.jpdfium.doc.RepairResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 18 - PDF Repair Pipeline.
 *
 * <p>
 * Demonstrates the full repair cascade:
 * Brotli pre-transcoding → core qpdf repair → PDFio fallback →
 * ICC/JPX post-validation.
 *
 * <p>
 * <strong>VM Options:</strong> {@code --enable-native-access=ALL-UNNAMED}
 */
public class S18_Repair {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputRepairPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S18_Repair  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S18_Repair", input, fi + 1, inputs.size());
            Path outDir = SampleBase.out("S18_repair", input);
            String stem = SampleBase.stem(input);

            byte[] pdfBytes = Files.readAllBytes(input);

            // Inspect (non-destructive)
            SampleBase.section("Inspect");
            String diagnostics = PdfRepair.inspect(pdfBytes);
            System.out.printf("  Diagnostics: %s%n", diagnostics);

            Path diagPath = outDir.resolve(stem + "-diagnostics.json");
            Files.writeString(diagPath, diagnostics);
            produced.add(diagPath);

            // Full pipeline: Brotli pre-transcoding → core → PDFio fallback
            SampleBase.section("Repair (full pipeline)");
            RepairResult result = PdfRepair.builder()
                    .input(pdfBytes)
                    .all()
                    .writeDiagnostics(true)   // toggle to false to skip JSON sidecar
                    .build()
                    .execute();

            System.out.printf("  Status: %s | Usable: %s%n",
                    result.status(), result.isUsable());

            // Save the repaired PDF if bytes were produced (even for PARTIAL status)
            byte[] repairedBytes = result.repairedPdf();
            if (repairedBytes != null) {
                Path repairedPath = outDir.resolve(stem + "-repaired.pdf");
                Files.write(repairedPath, repairedBytes);
                produced.add(repairedPath);
                System.out.printf("  Output: %s (%,d bytes)%n",
                        repairedPath.getFileName(), repairedBytes.length);
            } else {
                System.out.println("  Output: (no PDF bytes produced - repair failed)");
            }

            if (result.diagnosticJson() != null) {
                System.out.printf("  Diagnostics: %s%n", result.diagnosticJson());
            } else {
                System.out.println("  Diagnostics: (disabled)");
            }
        }

        SampleBase.done("S18_Repair", produced.toArray(Path[]::new));
    }
}
