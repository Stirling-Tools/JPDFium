package stirling.software.jpdfium.transform;

import stirling.software.jpdfium.PdfDocument;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Example: PDF page operations using jpdfium-transform.
 *
 * <p>Demonstrates flatten, convert-to-image, and page rendering.
 *
 * <p><b>Run:</b>
 * <pre>
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp jpdfium-transform.jar:jpdfium-document.jar:jpdfium-bindings.jar:jpdfium-core.jar \
 *        stirling.software.jpdfium.transform.TransformExample /path/to/input.pdf
 * </pre>
 */
public class TransformExample {

    public static void main(String[] args) throws Exception {
        Path input = args.length > 0 ? Path.of(args[0]) : Path.of("/tmp/test.pdf");

        // --------------------------------------------------------------------
        // Example 1: Flatten a single page (burn annotations into content)
        // --------------------------------------------------------------------
        System.out.println("=== Example 1: Flatten single page ===");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PageOps.flatten(doc, 0);
            doc.save(Path.of("/tmp/flattened-page0.pdf"));
            System.out.println("  Page 0 flattened → /tmp/flattened-page0.pdf");
        }

        // --------------------------------------------------------------------
        // Example 2: Flatten all pages
        // --------------------------------------------------------------------
        System.out.println("\n=== Example 2: Flatten all pages ===");
        try (PdfDocument doc = PdfDocument.open(input)) {
            long t0 = System.nanoTime();
            PageOps.flattenAll(doc);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            doc.save(Path.of("/tmp/flattened-all.pdf"));
            System.out.printf("  All %d pages flattened in %d ms%n", doc.pageCount(), ms);
        }

        // --------------------------------------------------------------------
        // Example 3: Convert a page to an image-based page (PDF-Image)
        //   The page content is replaced with a single bitmap - no text can
        //   be selected or extracted afterward. Ideal for secure redaction.
        // --------------------------------------------------------------------
        System.out.println("\n=== Example 3: Convert page to image ===");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PageOps.convertToImage(doc, 0, 200);  // 200 DPI
            doc.save(Path.of("/tmp/page0-as-image.pdf"));
            System.out.println("  Page 0 → image at 200 DPI → /tmp/page0-as-image.pdf");
        }

        // --------------------------------------------------------------------
        // Example 4: Convert ALL pages to images (entire document becomes image-only)
        // --------------------------------------------------------------------
        System.out.println("\n=== Example 4: Convert all pages to images ===");
        try (PdfDocument doc = PdfDocument.open(input)) {
            long t0 = System.nanoTime();
            PageOps.convertAllToImages(doc, 150);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            doc.save(Path.of("/tmp/all-as-images.pdf"));
            System.out.printf("  %d pages → images at 150 DPI in %d ms%n",
                    doc.pageCount(), ms);
        }

        // --------------------------------------------------------------------
        // Example 5: Render page to BufferedImage and save as PNG
        //   Useful for thumbnails, previews, or visual testing.
        // --------------------------------------------------------------------
        System.out.println("\n=== Example 5: Render page to PNG ===");
        try (PdfDocument doc = PdfDocument.open(input)) {
            BufferedImage img = PageOps.renderPage(doc, 0, 300);  // 300 DPI
            ImageIO.write(img, "PNG", new File("/tmp/page0-300dpi.png"));
            System.out.printf("  Page 0 rendered at 300 DPI → %dx%d px → /tmp/page0-300dpi.png%n",
                    img.getWidth(), img.getHeight());
        }

        // --------------------------------------------------------------------
        // Example 6: Render all pages to PNG images
        // --------------------------------------------------------------------
        System.out.println("\n=== Example 6: Render all pages to PNG ===");
        try (PdfDocument doc = PdfDocument.open(input)) {
            List<BufferedImage> images = PageOps.renderAll(doc, 150);
            for (int i = 0; i < images.size(); i++) {
                File out = new File("/tmp/page" + i + ".png");
                ImageIO.write(images.get(i), "PNG", out);
                System.out.printf("  Page %d → %s (%dx%d px)%n",
                        i, out.getName(),
                        images.get(i).getWidth(), images.get(i).getHeight());
            }
        }

        // --------------------------------------------------------------------
        // Example 7: Pipeline - flatten then convert to image (belt & suspenders)
        // --------------------------------------------------------------------
        System.out.println("\n=== Example 7: Flatten + Convert pipeline ===");
        try (PdfDocument doc = PdfDocument.open(input)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                PageOps.flatten(doc, i);          // first flatten annotations
                PageOps.convertToImage(doc, i, 200); // then rasterize completely
            }
            doc.save(Path.of("/tmp/flatten-then-image.pdf"));
            System.out.printf("  %d pages: flatten → image → /tmp/flatten-then-image.pdf%n",
                    doc.pageCount());
        }

        System.out.println("\nAll examples completed successfully.");
    }
}
