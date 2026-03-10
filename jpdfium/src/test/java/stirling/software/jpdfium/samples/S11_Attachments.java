package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.Attachment;
import stirling.software.jpdfium.doc.PdfAttachments;
import stirling.software.jpdfium.panama.JpdfiumLib;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 11 - Full CRUD for embedded file attachments.
 *
 * <p>Demonstrates the complete lifecycle: list, extract, add, update (delete + add),
 * and delete attachments.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S11_Attachments {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S11_attachments");
        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);

        System.out.printf("S11_Attachments  |  input: %s%n", input.getFileName());

        // 1. List existing attachments
        SampleBase.section("List existing attachments");
        try (PdfDocument doc = PdfDocument.open(input)) {
            List<Attachment> atts = doc.attachments();
            if (atts.isEmpty()) {
                System.out.println("  (no attachments found)");
            } else {
                for (Attachment att : atts) {
                    System.out.printf("  [%d] \"%s\" (%d bytes)%n",
                            att.index(), att.name(), att.data().length);
                }
            }
        }

        // 2. Extract existing attachments to disk
        SampleBase.section("Extract attachments");
        try (PdfDocument doc = PdfDocument.open(input)) {
            List<Attachment> atts = doc.attachments();
            for (Attachment att : atts) {
                if (att.hasData()) {
                    Path outFile = outDir.resolve(stem + "-att-" + att.index()
                            + "-" + sanitize(att.name()));
                    Files.write(outFile, att.data());
                    produced.add(outFile);
                    System.out.printf("  Extracted: %s (%d bytes)%n",
                            outFile.getFileName(), att.data().length);
                }
            }
            if (atts.isEmpty()) System.out.println("  (nothing to extract)");
        }

        // 3. Add new attachments
        SampleBase.section("Add attachments");
        try (PdfDocument doc = PdfDocument.open(input)) {
            MemorySegment rawDoc = JpdfiumLib.docRawHandle(doc.nativeHandle());

            byte[] textData = "Hello from JPDFium attachment!".getBytes(StandardCharsets.UTF_8);
            boolean added = PdfAttachments.add(rawDoc, "greeting.txt", textData);
            System.out.printf("  Added greeting.txt: %s (%d bytes)%n", added ? "OK" : "FAILED", textData.length);

            byte[] csvData = "name,value\nfoo,42\nbar,99\n".getBytes(StandardCharsets.UTF_8);
            added = PdfAttachments.add(rawDoc, "data.csv", csvData);
            System.out.printf("  Added data.csv: %s (%d bytes)%n", added ? "OK" : "FAILED", csvData.length);

            int count = PdfAttachments.count(rawDoc);
            System.out.printf("  Total attachments now: %d%n", count);

            Path withAdded = outDir.resolve(stem + "-with-attachments.pdf");
            doc.save(withAdded);
            produced.add(withAdded);
        }

        // 4. Update attachment (delete old + add replacement)
        SampleBase.section("Update attachment (delete + re-add)");
        Path withAdded = outDir.resolve(stem + "-with-attachments.pdf");
        try (PdfDocument doc = PdfDocument.open(withAdded)) {
            MemorySegment rawDoc = JpdfiumLib.docRawHandle(doc.nativeHandle());

            // Find and replace "greeting.txt"
            List<Attachment> atts = PdfAttachments.list(rawDoc);
            for (Attachment att : atts) {
                if ("greeting.txt".equals(att.name())) {
                    PdfAttachments.delete(rawDoc, att.index());
                    System.out.printf("  Deleted old greeting.txt (index %d)%n", att.index());

                    byte[] updated = "Updated greeting from JPDFium!".getBytes(StandardCharsets.UTF_8);
                    PdfAttachments.add(rawDoc, "greeting.txt", updated);
                    System.out.printf("  Re-added greeting.txt with new content (%d bytes)%n", updated.length);
                    break;
                }
            }

            Path updatedFile = outDir.resolve(stem + "-updated-attachment.pdf");
            doc.save(updatedFile);
            produced.add(updatedFile);
        }

        // 5. Delete all attachments
        SampleBase.section("Delete all attachments");
        try (PdfDocument doc = PdfDocument.open(withAdded)) {
            MemorySegment rawDoc = JpdfiumLib.docRawHandle(doc.nativeHandle());

            int count = PdfAttachments.count(rawDoc);
            System.out.printf("  Attachments before: %d%n", count);
            // Delete from last to first to avoid index shifting
            for (int i = count - 1; i >= 0; i--) {
                Attachment att = PdfAttachments.get(rawDoc, i);
                PdfAttachments.delete(rawDoc, i);
                System.out.printf("  Deleted [%d] \"%s\"%n", i, att.name());
            }
            System.out.printf("  Attachments after: %d%n", PdfAttachments.count(rawDoc));

            Path cleaned = outDir.resolve(stem + "-no-attachments.pdf");
            doc.save(cleaned);
            produced.add(cleaned);
        }

        SampleBase.done("S11_Attachments", produced.toArray(Path[]::new));
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
