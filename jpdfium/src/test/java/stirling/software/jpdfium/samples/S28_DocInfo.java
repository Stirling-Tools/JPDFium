package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.DocInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 28 - Document Information Audit.
 *
 * <p>Demonstrates DocInfo: one-call comprehensive audit of PDF properties
 * including version, tags, encryption, page count, JavaScript, signatures, etc.
 */
public class S28_DocInfo {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S28_DocInfo  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S28_DocInfo", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                long fileSize = Files.size(input);
                DocInfo info = DocInfo.analyze(doc, fileSize);

                System.out.println("  Summary: " + info.summary());
                System.out.println("  JSON:    " + info.toJson());
                System.out.printf("  Version: %s  Tagged: %b  Encrypted: %b%n",
                        info.pdfVersion(), info.isTagged(), info.isEncrypted());
                System.out.printf("  Pages: %d  Images: %d  FormFields: %d%n",
                        info.pageCount(), info.imageCount(), info.formFieldCount());
                if (!info.blankPages().isEmpty()) {
                    System.out.println("  Blank pages: " + info.blankPages());
                }
            }
        }

        SampleBase.done("S28_DocInfo");
    }
}
