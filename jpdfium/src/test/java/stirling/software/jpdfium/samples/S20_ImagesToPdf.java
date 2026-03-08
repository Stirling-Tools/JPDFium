package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfImageConverter;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Position;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 20 - Convert images to PDF (scanner workflow, photo album).
 *
 * <p>Demonstrates combining multiple images into a single PDF document.
 * Common use cases:
 * <ul>
 *   <li>Scanner workflow (JPEG/PNG/TIFF → PDF)</li>
 *   <li>Photo album creation</li>
 *   <li>Document archiving</li>
 *   <li>Fax consolidation</li>
 * </ul>
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S20_ImagesToPdf {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();

        System.out.println("S20_ImagesToPdf  |  Creating PDF from images");

        // Generate sample images programmatically (colored pages)
        List<BufferedImage> images = createSampleImages(5);
        System.out.printf("  Created %d sample images%n", images.size());

        // Option 1: A4 pages with centered images
        ImageToPdfOptions a4Opts = ImageToPdfOptions.builder()
                .pageSize(PageSize.A4)
                .position(Position.CENTER)
                .margin(36)
                .compress(true)
                .imageQuality(85)
                .autoRotate(true)
                .build();

        PdfDocument a4Doc = PdfImageConverter.imagesToPdfFromImages(images, a4Opts);
        Path a4Out = SampleBase.out("S20_images-to-pdf", Path.of("sample")).resolve("a4-output.pdf");
        a4Doc.save(a4Out);
        a4Doc.close();
        System.out.printf("  A4 PDF saved: %s%n", a4Out);

        // Option 2: Letter pages
        ImageToPdfOptions letterOpts = ImageToPdfOptions.builder()
                .letter()
                .position(Position.CENTER)
                .margin(48)
                .build();

        PdfDocument letterDoc = PdfImageConverter.imagesToPdfFromImages(images, letterOpts);
        Path letterOut = SampleBase.out("S20_images-to-pdf", Path.of("sample")).resolve("letter-output.pdf");
        letterDoc.save(letterOut);
        letterDoc.close();
        System.out.printf("  Letter PDF saved: %s%n", letterOut);

        // Option 3: Fit to image (no fixed page size)
        ImageToPdfOptions fitOpts = ImageToPdfOptions.builder()
                .fitToImage()
                .margin(0)
                .build();

        PdfDocument fitDoc = PdfImageConverter.imagesToPdfFromImages(images, fitOpts);
        Path fitOut = SampleBase.out("S20_images-to-pdf", Path.of("sample")).resolve("fit-to-image.pdf");
        fitDoc.save(fitOut);
        fitDoc.close();
        System.out.printf("  Fit-to-image PDF saved: %s%n", fitOut);

        SampleBase.done("S20_ImagesToPdf");
    }

    /** Create sample colored images for demonstration */
    private static List<BufferedImage> createSampleImages(int count) {
        List<BufferedImage> images = new ArrayList<>();
        Color[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN};

        for (int i = 0; i < count; i++) {
            BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setColor(colors[i % colors.length]);
                g.fillRect(0, 0, 800, 600);

                g.setColor(Color.BLACK);
                g.setFont(new Font("Arial", Font.BOLD, 48));
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                String text = "Page " + (i + 1);
                FontMetrics fm = g.getFontMetrics();
                int x = (800 - fm.stringWidth(text)) / 2;
                int y = (600 + fm.getAscent()) / 2;
                g.drawString(text, x, y);
            } finally {
                g.dispose();
            }
            images.add(img);
        }

        return images;
    }
}
