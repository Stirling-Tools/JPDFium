package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfBarcode;
import stirling.software.jpdfium.doc.PdfBarcode.QrOptions;
import stirling.software.jpdfium.doc.PdfBarcode.EccLevel;
import stirling.software.jpdfium.model.Position;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 53 - QR Code / Barcode Generation with verification.
 *
 * <p>Adds QR codes to PDF pages and verifies they actually rendered by
 * rendering back to image and checking for dark modules in the expected region.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S53_BarcodeGenerate {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S53_barcode-generate");
        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);

        System.out.printf("S53_BarcodeGenerate  |  input: %s%n", input.getFileName());

        // 1. Bottom-right QR code with URL
        SampleBase.section("Bottom-right QR code");
        Path qrBottomRight;
        try (PdfDocument doc = PdfDocument.open(input)) {
            QrOptions opts = QrOptions.builder()
                    .content("https://github.com/Stirling-Tools/JPDFium")
                    .size(72)
                    .position(Position.BOTTOM_RIGHT)
                    .margin(18)
                    .build();
            PdfBarcode.addQrCode(doc, 0, opts);
            qrBottomRight = outDir.resolve(stem + "-qr-bottom-right.pdf");
            doc.save(qrBottomRight);
            produced.add(qrBottomRight);
            System.out.printf("  QR at BOTTOM_RIGHT -> %s%n", qrBottomRight.getFileName());
        }

        // 2. Top-left QR code with custom colors and high ECC
        SampleBase.section("Top-left QR with high ECC");
        try (PdfDocument doc = PdfDocument.open(input)) {
            QrOptions opts = QrOptions.builder()
                    .content("HIGH-ECC-CONTENT-12345")
                    .size(100)
                    .position(Position.TOP_LEFT)
                    .margin(24)
                    .fgColor(0xFF003366)
                    .bgColor(0xFFFFFFFF)
                    .eccLevel(EccLevel.HIGH)
                    .build();
            PdfBarcode.addQrCode(doc, 0, opts);
            Path outFile = outDir.resolve(stem + "-qr-top-left.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  QR at TOP_LEFT with HIGH ECC -> %s%n", outFile.getFileName());
        }

        // 3. QR code on all pages
        SampleBase.section("QR on all pages");
        try (PdfDocument doc = PdfDocument.open(input)) {
            QrOptions opts = QrOptions.builder()
                    .content("Page stamp - JPDFium")
                    .size(50)
                    .position(Position.BOTTOM_LEFT)
                    .margin(10)
                    .build();
            PdfBarcode.addQrCodeToAll(doc, opts);
            Path outFile = outDir.resolve(stem + "-qr-all-pages.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  QR on all %d pages -> %s%n",
                    doc.pageCount(), outFile.getFileName());
        }

        // 4. Verification - Render QR PDF back to image, check dark pixels in QR area
        SampleBase.section("Verification");
        try (PdfDocument doc = PdfDocument.open(qrBottomRight);
             PdfPage page = doc.page(0)) {
            BufferedImage img = page.renderAt(150).toBufferedImage();
            int imgW = img.getWidth();
            int imgH = img.getHeight();

            // QR is at bottom-right with 18pt margin, 72pt size
            // At 150 DPI: 18pt margin ~ 37px, 72pt QR ~ 150px
            int regionSize = 120;
            int marginPx = 20;
            int darkCount = 0, totalChecked = 0;
            for (int x = imgW - regionSize - marginPx; x < imgW - marginPx; x++) {
                for (int y = imgH - regionSize - marginPx; y < imgH - marginPx; y++) {
                    if (x < 0 || y < 0) continue;
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    totalChecked++;
                    if (r < 80 && g < 80 && b < 80) darkCount++;
                }
            }
            float darkRatio = totalChecked > 0 ? (float) darkCount / totalChecked : 0;
            // A valid QR code should have ~30-60% dark modules
            boolean qrDetected = darkRatio > 0.15f && darkRatio < 0.85f;

            // Also save the render for visual inspection
            Path renderPng = outDir.resolve(stem + "-qr-render-verify.png");
            ImageIO.write(img, "PNG", renderPng.toFile());
            produced.add(renderPng);

            System.out.printf("  Render: %dx%d, dark ratio in QR region: %.1f%% (%d/%d)%n",
                    imgW, imgH, darkRatio * 100, darkCount, totalChecked);
            if (qrDetected) {
                System.out.println("  QR code detected (dark/light mix in expected region)");
            } else {
                System.out.printf("  QR code NOT detected - dark ratio %.1f%% (expected 15-85%%)%n",
                        darkRatio * 100);
                throw new RuntimeException("QR code verification failed: dark ratio " +
                        String.format("%.1f%%", darkRatio * 100) + " - code did not render");
            }
        }

        SampleBase.done("S53_BarcodeGenerate", produced.toArray(Path[]::new));
    }
}
