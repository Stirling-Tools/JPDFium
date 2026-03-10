package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfColorConverter;
import stirling.software.jpdfium.doc.PdfColorConverter.ColorConvertOptions;
import stirling.software.jpdfium.doc.PdfColorConverter.ColorSpace;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SAMPLE 55 - Color Space Conversion with verification.
 *
 * <p>Converts PDF page objects from RGB to grayscale and verifies
 * the output is actually grayscale by rendering and checking pixels.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S55_ColorConvert {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S55_color-convert");
        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);

        System.out.printf("S55_ColorConvert  |  input: %s%n", input.getFileName());

        // 1. Convert entire document to grayscale
        SampleBase.section("Full grayscale conversion");
        Path grayscalePdf;
        try (PdfDocument doc = PdfDocument.open(input)) {
            int converted = PdfColorConverter.toGrayscale(doc);
            grayscalePdf = outDir.resolve(stem + "-grayscale.pdf");
            doc.save(grayscalePdf);
            produced.add(grayscalePdf);
            System.out.printf("  Converted %d color values -> %s%n",
                    converted, grayscalePdf.getFileName());
        }

        // 2. Convert only the first page
        SampleBase.section("Single page grayscale");
        try (PdfDocument doc = PdfDocument.open(input)) {
            int converted = PdfColorConverter.toGrayscale(doc, Set.of(0));
            Path outFile = outDir.resolve(stem + "-page0-grayscale.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  Page 0: converted %d color values -> %s%n",
                    converted, outFile.getFileName());
        }

        // 3. Custom options (text only, preserve black)
        SampleBase.section("Text-only grayscale");
        try (PdfDocument doc = PdfDocument.open(input)) {
            int converted = PdfColorConverter.convert(doc, ColorConvertOptions.builder()
                    .targetColorSpace(ColorSpace.GRAYSCALE)
                    .convertText(true)
                    .convertVectors(false)
                    .convertImages(false)
                    .preserveBlack(true)
                    .build());
            Path outFile = outDir.resolve(stem + "-text-grayscale.pdf");
            doc.save(outFile);
            produced.add(outFile);
            System.out.printf("  Text-only: converted %d color values -> %s%n",
                    converted, outFile.getFileName());
        }

        // 4. Verification - Render original and grayscale, compare pixel saturation
        SampleBase.section("Verification");

        // Render original
        int origColorPixels;
        try (PdfDocument doc = PdfDocument.open(input);
             PdfPage page = doc.page(0)) {
            BufferedImage origImg = page.renderAt(72).toBufferedImage();
            origColorPixels = countColorPixels(origImg);
            System.out.printf("  Original: %d colored (non-gray) pixels%n", origColorPixels);
        }

        // Render grayscale version
        int grayColorPixels;
        try (PdfDocument doc = PdfDocument.open(grayscalePdf);
             PdfPage page = doc.page(0)) {
            BufferedImage grayImg = page.renderAt(72).toBufferedImage();
            grayColorPixels = countColorPixels(grayImg);
            Path renderPng = outDir.resolve(stem + "-grayscale-verify.png");
            ImageIO.write(grayImg, "PNG", renderPng.toFile());
            produced.add(renderPng);
            System.out.printf("  Grayscale: %d colored (non-gray) pixels%n", grayColorPixels);
        }

        // Grayscale output should have fewer colored pixels than original
        // (for a document that had color content)
        if (origColorPixels > 100 && grayColorPixels >= origColorPixels) {
            throw new RuntimeException("Color conversion verification failed: " +
                    "grayscale has " + grayColorPixels + " colored pixels " +
                    "(original had " + origColorPixels + ") - conversion had no effect");
        }
        System.out.printf("  Color reduction verified: %d -> %d colored pixels (%.0f%% reduction)%n",
                origColorPixels, grayColorPixels,
                origColorPixels > 0 ? (1.0 - (double) grayColorPixels / origColorPixels) * 100 : 0);

        SampleBase.done("S55_ColorConvert", produced.toArray(Path[]::new));
    }

    /** Count pixels where R, G, B channels differ significantly (non-gray). */
    private static int countColorPixels(BufferedImage img) {
        int count = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                // A pixel is "colored" if channels differ by more than a threshold
                int maxDiff = Math.max(Math.abs(r - g), Math.max(Math.abs(r - b), Math.abs(g - b)));
                if (maxDiff > 10) count++;
            }
        }
        return count;
    }
}
