package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.panama.FfmHelper;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 16 - Page editing: add text, rectangles, paths, rotation, and create-from-scratch.
 *
 * <p>Section 1: For each input PDF, opens page 0 and inserts:
 * <ul>
 *   <li>A filled red rectangle ({@code FPDFPageObj_CreateNewRect})</li>
 *   <li>A stroked blue rectangle (outline only)</li>
 *   <li>A text object with the Helvetica built-in font ({@code FPDFPageObj_NewTextObj})</li>
 *   <li>A filled green triangle (path with {@code FPDFPath_LineTo})</li>
 *   <li>A purple cubic Bézier curve ({@code FPDFPath_BezierTo})</li>
 * </ul>
 * Also reads and rotates the page by 90° ({@code FPDFPage_GetRotation} /
 * {@code FPDFPage_SetRotation}) and prints bounding-box information for the
 * inserted rectangle ({@code FPDFPageObj_GetBounds}).
 *
 * <p>Section 2: Creates one new PDF entirely from scratch using
 * {@code FPDF_CreateNewDocument} + {@code FPDFPage_New}, paints coloured bars,
 * a Bézier sweep, and a white title, then saves via {@code FPDF_SaveAsCopy}
 * without going through the jpdfium C bridge at all.
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

        Path outDir = SampleBase.out("page-editing");

        // -------------------------------------------------------------------------
        // Section 1: Edit page 0 of every input PDF
        // -------------------------------------------------------------------------
        System.out.println("\n  -- Edit existing PDFs --");
        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S16_PageEditing", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                if (doc.pageCount() == 0) {
                    System.out.println("  (no pages, skipping)");
                    continue;
                }
                MemorySegment rawDoc = doc.rawHandle();
                try (var page = doc.page(0)) {
                    MemorySegment rawPage = page.rawHandle();

                    // Read and cycle page rotation (0°→90°→180°→270°→0°)
                    int rotBefore = PdfPageEditor.getRotation(rawPage);
                    int rotAfter  = (rotBefore + 1) % 4;
                    PdfPageEditor.setRotation(rawPage, rotAfter);

                    // Count existing objects
                    int before = PdfPageEditor.countObjects(rawPage);

                    // Filled red rectangle
                    MemorySegment rect = PdfPageEditor.createRect(50, 50, 150, 40);
                    PdfPageEditor.setFillColor(rect, 220, 50, 50, 180);
                    PdfPageEditor.setDrawMode(rect, PdfPageEditor.FillMode.ALTERNATE, false);
                    PdfPageEditor.insertObject(rawPage, rect);

                    // Report bounds of the rectangle
                    float[] bounds = PdfPageEditor.getBounds(rect);
                    String boundsStr = bounds != null
                            ? String.format("[%.0f,%.0f,%.0f,%.0f]", bounds[0], bounds[1], bounds[2], bounds[3])
                            : "n/a";

                    // Stroked blue rectangle (outline only)
                    MemorySegment rect2 = PdfPageEditor.createRect(250, 600, 100, 100);
                    PdfPageEditor.setStrokeColor(rect2, 0, 0, 200, 255);
                    PdfPageEditor.setDrawMode(rect2, PdfPageEditor.FillMode.NONE, true);
                    PdfPageEditor.insertObject(rawPage, rect2);

                    // Text object with built-in Helvetica font
                    MemorySegment textObj = PdfPageEditor.createTextObject(rawDoc, "Helvetica", 14f);
                    PdfPageEditor.setText(textObj, "JPDFium Page Editing Sample");
                    PdfPageEditor.setFillColor(textObj, 0, 0, 0, 255);
                    PdfPageEditor.transform(textObj, 1, 0, 0, 1, 60, 65);
                    PdfPageEditor.insertObject(rawPage, textObj);

                    // Filled green triangle (LineTo path)
                    MemorySegment triangle = PdfPageEditor.createPath(400, 100);
                    PdfPageEditor.pathLineTo(triangle, 450, 200);
                    PdfPageEditor.pathLineTo(triangle, 350, 200);
                    PdfPageEditor.pathClose(triangle);
                    PdfPageEditor.setFillColor(triangle, 50, 180, 50, 200);
                    PdfPageEditor.setDrawMode(triangle, PdfPageEditor.FillMode.ALTERNATE, false);
                    PdfPageEditor.insertObject(rawPage, triangle);

                    // Purple cubic Bézier curve (BezierTo path)
                    MemorySegment bezier = PdfPageEditor.createPath(100, 300);
                    PdfPageEditor.pathBezierTo(bezier, 150, 450, 300, 150, 400, 300);
                    PdfPageEditor.setStrokeColor(bezier, 128, 0, 200, 220);
                    PdfPageEditor.setDrawMode(bezier, PdfPageEditor.FillMode.NONE, true);
                    PdfPageEditor.insertObject(rawPage, bezier);

                    boolean ok = PdfPageEditor.generateContent(rawPage);
                    int after = PdfPageEditor.countObjects(rawPage);
                    System.out.printf("  rot %d°->%d° | objs %d->%d | rect%s | gen=%s%n",
                            rotBefore * 90, rotAfter * 90, before, after, boundsStr, ok ? "OK" : "FAILED");
                }

                Path outFile = outDir.resolve(SampleBase.stem(input) + "-edited.pdf");
                doc.save(outFile);
                produced.add(outFile);
            } catch (Exception e) {
                System.err.printf("  FAILED: %s%n", e.getMessage());
            }
        }

        // -------------------------------------------------------------------------
        // Section 2: Create a new PDF entirely from scratch via FFM
        //   FPDF_CreateNewDocument → FPDFPage_New → add objects → FPDF_SaveAsCopy
        //   No C bridge involved — pure Panama FFM round-trip.
        // -------------------------------------------------------------------------
        System.out.println("\n  -- Create from scratch (pure FFM, no bridge) --");
        MemorySegment rawDoc = PdfPageEditor.createDocument();
        try {
            // A4 landscape: 842 × 595 pt
            MemorySegment pg = PdfPageEditor.newPage(rawDoc, 0, 842, 595);
            try {
                // Five coloured bars — gradient-like effect
                int[] r = {220, 180, 100, 50,  20};
                int[] g = {60,  120, 180, 160, 80};
                int[] b = {60,  80,  100, 200, 220};
                for (int i = 0; i < 5; i++) {
                    MemorySegment bar = PdfPageEditor.createRect(40 + i * 154f, 40, 140, 515);
                    PdfPageEditor.setFillColor(bar, r[i], g[i], b[i], 210);
                    PdfPageEditor.setDrawMode(bar, PdfPageEditor.FillMode.WINDING, false);
                    PdfPageEditor.insertObject(pg, bar);
                }

                // Sweeping cubic Bézier across the page (orange)
                MemorySegment sweep = PdfPageEditor.createPath(40, 300);
                PdfPageEditor.pathBezierTo(sweep, 200, 80, 600, 520, 800, 300);
                PdfPageEditor.setStrokeColor(sweep, 255, 165, 0, 255);
                PdfPageEditor.setDrawMode(sweep, PdfPageEditor.FillMode.NONE, true);
                PdfPageEditor.insertObject(pg, sweep);

                // White title text
                MemorySegment title = PdfPageEditor.createTextObject(rawDoc, "Helvetica-Bold", 22f);
                PdfPageEditor.setText(title, "JPDFium \u2014 Created from scratch via Panama FFM");
                PdfPageEditor.setFillColor(title, 255, 255, 255, 255);
                PdfPageEditor.transform(title, 1, 0, 0, 1, 42, 548);
                PdfPageEditor.insertObject(pg, title);

                // Subtitle
                MemorySegment sub = PdfPageEditor.createTextObject(rawDoc, "Helvetica", 13f);
                PdfPageEditor.setText(sub, "FPDF_CreateNewDocument \u00b7 FPDFPage_New \u00b7 FPDFPath_BezierTo \u00b7 FPDF_SaveAsCopy");
                PdfPageEditor.setFillColor(sub, 240, 240, 240, 255);
                PdfPageEditor.transform(sub, 1, 0, 0, 1, 42, 528);
                PdfPageEditor.insertObject(pg, sub);

                boolean ok = PdfPageEditor.generateContent(pg);
                System.out.printf("  A4-landscape page: %d objects, generateContent=%s%n",
                        PdfPageEditor.countObjects(pg), ok ? "OK" : "FAILED");
            } finally {
                PdfPageEditor.closePage(pg);
            }

            byte[] pdfBytes = FfmHelper.saveRawDocument(rawDoc);
            Path scratchPath = outDir.resolve("scratch.pdf");
            Files.write(scratchPath, pdfBytes);
            produced.add(scratchPath);
            System.out.printf("  Saved: scratch.pdf (%,d bytes)%n", pdfBytes.length);
        } finally {
            PdfPageEditor.closeDocument(rawDoc);
        }

        SampleBase.done("S16_PageEditing", produced.toArray(Path[]::new));
    }
}
