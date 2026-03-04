package stirling.software.jpdfium;

import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.model.RenderResult;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Path;

/**
 * Getting started with JPDFium - end-to-end examples of core functionality.
 *
 * <p>This sample covers:
 * <ul>
 *   <li>Opening PDFs (from file path, bytes, password-protected)</li>
 *   <li>Page info and size queries</li>
 *   <li>Rendering pages to images</li>
 *   <li>Text extraction (raw JSON)</li>
 *   <li>Text search</li>
 *   <li>Region-based redaction</li>
 *   <li>Pattern (regex) redaction</li>
 *   <li>Word-list redaction</li>
 *   <li>Flattening annotations</li>
 *   <li>Convert page to image</li>
 *   <li>Saving results</li>
 * </ul>
 *
 * <p><b>Run:</b>
 * <pre>
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp jpdfium-document.jar:jpdfium-bindings.jar:jpdfium-core.jar:jpdfium-natives-linux-x64.jar \
 *        stirling.software.jpdfium.GettingStartedExample /path/to/input.pdf
 * </pre>
 *
 * <p><b>Note:</b> Requires a native library on the classpath (e.g., jpdfium-natives-linux-x64).
 *
 * @see PdfDocument
 * @see PdfPage
 */
public class GettingStartedExample {

    public static void main(String[] args) throws Exception {
        Path input = args.length > 0 ? Path.of(args[0]) : Path.of("/tmp/test.pdf");

        // The native library is loaded automatically when you open a document.
        // To verify it's available:
        NativeLoader.ensureLoaded();
        System.out.println("Platform: " + NativeLoader.detectPlatform());

        // --------------------------------------------------------------------
        // 1. Open a PDF from file or bytes
        // --------------------------------------------------------------------
        System.out.println("\n=== 1. Open PDF ===");
        try (PdfDocument doc = PdfDocument.open(input)) {
            System.out.printf("Opened: %s - %d pages%n", input.getFileName(), doc.pageCount());

            // ----------------------------------------------------------------
            // 2. Query page info
            // ----------------------------------------------------------------
            System.out.println("\n=== 2. Page Info ===");
            try (PdfPage page = doc.page(0)) {
                PageSize size = page.size();
                System.out.printf("Page 0: %.1f × %.1f pt (%.1f × %.1f mm)%n",
                        size.width(), size.height(),
                        size.width() * 25.4 / 72, size.height() * 25.4 / 72);
            }

            // ----------------------------------------------------------------
            // 3. Render a page to a BufferedImage and save as PNG
            // ----------------------------------------------------------------
            System.out.println("\n=== 3. Render to PNG ===");
            try (PdfPage page = doc.page(0)) {
                RenderResult result = page.renderAt(150);  // 150 DPI
                System.out.printf("Rendered: %d × %d px (%d bytes)%n",
                        result.width(), result.height(), result.rgba().length);

                ImageIO.write(result.toBufferedImage(), "PNG", new File("/tmp/page0.png"));
                System.out.println("Saved → /tmp/page0.png");
            }

            // ----------------------------------------------------------------
            // 4. Extract text as JSON
            // ----------------------------------------------------------------
            System.out.println("\n=== 4. Text Extraction ===");
            try (PdfPage page = doc.page(0)) {
                String json = page.extractTextJson();
                System.out.printf("Extracted %d chars of JSON%n", json.length());
                System.out.println("First 200: " + json.substring(0, Math.min(200, json.length())));
            }

            // ----------------------------------------------------------------
            // 5. Search for text on a page
            // ----------------------------------------------------------------
            System.out.println("\n=== 5. Text Search ===");
            try (PdfPage page = doc.page(0)) {
                String searchJson = page.findTextJson("the");
                int matchCount = searchJson.equals("[]") ? 0 : searchJson.split("\\{").length - 1;
                System.out.printf("Found %d matches for 'the'%n", matchCount);
            }

            // ----------------------------------------------------------------
            // 6. Redact a rectangular region
            // ----------------------------------------------------------------
            System.out.println("\n=== 6. Region Redaction ===");
            try (PdfPage page = doc.page(0)) {
                // Redacts a 200x20pt region to ensure content is visually and structurally removed.
                Rect region = Rect.of(50, 700, 200, 20);
                page.redactRegion(region, 0xFF000000, true);
                System.out.printf("Redacted region: %s%n", region);
            }

            // ----------------------------------------------------------------
            // 7. Redact by regex pattern (e.g., Social Security Numbers)
            // ----------------------------------------------------------------
            System.out.println("\n=== 7. Pattern Redaction ===");
            try (PdfPage page = doc.page(0)) {
                page.redactPattern("\\d{3}-\\d{2}-\\d{4}", 0xFF000000, true);
                System.out.println("Redacted SSN patterns");
            }

            // ----------------------------------------------------------------
            // 8. Redact a word list (Stirling-PDF style)
            // ----------------------------------------------------------------
            System.out.println("\n=== 8. Word-List Redaction ===");
            try (PdfPage page = doc.page(0)) {
                String[] words = {"Confidential", "SECRET", "password"};
                page.redactWords(
                        words,
                        0xFF000000,   // black boxes
                        1.0f,         // 1pt padding
                        true,         // whole word only
                        false,        // not regex
                        true          // remove underlying content
                );
                System.out.println("Redacted words: " + String.join(", ", words));
            }

            // ----------------------------------------------------------------
            // 9. Flatten annotations (burn into content stream)
            // ----------------------------------------------------------------
            System.out.println("\n=== 9. Flatten ===");
            try (PdfPage page = doc.page(0)) {
                page.flatten();
                System.out.println("Page 0 flattened");
            }

            // ----------------------------------------------------------------
            // 10. Rasterizes the page to an image to guarantee no vector text data remains.
            // ----------------------------------------------------------------
            System.out.println("\n=== 10. Convert to Image ===");
            doc.convertPageToImage(0, 150);
            System.out.println("Page 0 converted to image at 150 DPI");

            // ----------------------------------------------------------------
            // 11. Save the modified PDF
            // ----------------------------------------------------------------
            System.out.println("\n=== 11. Save ===");
            Path output = Path.of("/tmp/output.pdf");
            doc.save(output);
            System.out.println("Saved → " + output);

            // Also available: save to byte array
            byte[] bytes = doc.saveBytes();
            System.out.printf("saveBytes(): %d bytes (%d KB)%n", bytes.length, bytes.length / 1024);
        }

        // --------------------------------------------------------------------
        // Bonus: Open from byte array
        // --------------------------------------------------------------------
        System.out.println("\n=== Bonus: Open from bytes ===");
        byte[] pdfBytes = java.nio.file.Files.readAllBytes(input);
        try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
            System.out.printf("Opened from %d bytes - %d pages%n", pdfBytes.length, doc.pageCount());
        }

        // Open password-protected PDF:
        // try (PdfDocument doc = PdfDocument.open(path, "my-password")) { ... }

        System.out.println("\nAll examples completed successfully.");
    }
}
