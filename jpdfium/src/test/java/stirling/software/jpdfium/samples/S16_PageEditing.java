package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfPageEditor;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 16 - Page editing: add text, rectangles, and paths.
 *
 * <p>Demonstrates creating page objects (text, rectangles, paths),
 * setting colors and transforms, inserting them into a page, and
 * generating page content.
 *
 * <h3>Streaming &amp; Parallel Guidance (MEDIUM benefit)</h3>
 * <p>Page editing modifies pages individually. Use split-merge for parallel:
 * <pre>{@code
 * PdfPipeline.processAndSave(input, output,
 *     ProcessingMode.parallel(4),
 *     (doc, pageIndex) -> {
 *         try (PdfPage page = doc.page(pageIndex)) {
 *             PdfPageEditor.addText(page, "STAMP", 72, 72, 12);
 *         }
 *     });
 * }</pre>
 * <p>See {@link S88_StreamingParallel} for benchmarks.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S16_PageEditing {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S16_PageEditing  |  %d PDF(s)%n", inputs.size());

        Path outDir = SampleBase.out("S16_page-editing");

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S16_PageEditing", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                if (doc.pageCount() == 0) {
                    System.out.println("  (no pages, skipping)");
                    continue;
                }
                MemorySegment rawDoc = doc.rawHandle();
                MemorySegment rawPage;
                try (var page = doc.page(0)) {
                    rawPage = page.rawHandle();

                    // Count existing objects
                    int before = PdfPageEditor.countObjects(rawPage);
                    System.out.printf("  Objects before: %d%n", before);

                    // Add a red rectangle
                    MemorySegment rect = PdfPageEditor.createRect(50, 50, 150, 40);
                    PdfPageEditor.setFillColor(rect, 220, 50, 50, 180);
                    PdfPageEditor.setDrawMode(rect, PdfPageEditor.FillMode.ALTERNATE, false);
                    PdfPageEditor.insertObject(rawPage, rect);

                    // Add a blue outlined rectangle
                    MemorySegment rect2 = PdfPageEditor.createRect(250, 600, 100, 100);
                    PdfPageEditor.setStrokeColor(rect2, 0, 0, 200, 255);
                    PdfPageEditor.setDrawMode(rect2, PdfPageEditor.FillMode.NONE, true);
                    PdfPageEditor.insertObject(rawPage, rect2);

                    // Add text
                    MemorySegment textObj = PdfPageEditor.createTextObject(rawDoc, "Helvetica", 14f);
                    PdfPageEditor.setText(textObj, "JPDFium Page Editing Sample");
                    PdfPageEditor.setFillColor(textObj, 0, 0, 0, 255);
                    PdfPageEditor.transform(textObj, 1, 0, 0, 1, 60, 65);
                    PdfPageEditor.insertObject(rawPage, textObj);

                    // Add a triangle path
                    MemorySegment path = PdfPageEditor.createPath(400, 100);
                    PdfPageEditor.pathLineTo(path, 450, 200);
                    PdfPageEditor.pathLineTo(path, 350, 200);
                    PdfPageEditor.pathClose(path);
                    PdfPageEditor.setFillColor(path, 50, 180, 50, 200);
                    PdfPageEditor.setDrawMode(path, PdfPageEditor.FillMode.ALTERNATE, false);
                    PdfPageEditor.insertObject(rawPage, path);

                    // Generate content
                    boolean ok = PdfPageEditor.generateContent(rawPage);
                    int after = PdfPageEditor.countObjects(rawPage);
                    System.out.printf("  +rect +rect2 +text +triangle -> %d objects (%s)%n",
                            after, ok ? "OK" : "FAILED");
                }

                Path outFile = outDir.resolve(SampleBase.stem(input) + "-edited.pdf");
                doc.save(outFile);
                produced.add(outFile);
                System.out.printf("  Saved: %s%n", outFile.getFileName());
            }
        }

        SampleBase.done("S16_PageEditing", produced.toArray(Path[]::new));
    }
}
