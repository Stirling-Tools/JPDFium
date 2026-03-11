package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfImageReplacer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 74 - Image Replacement.
 *
 * <p>Tests PdfImageReplacer: replace images with solid-color placeholders.
 */
public class S74_ImageReplace {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S74_image-replace");
        Path txtOut = outDir.resolve("report.txt");
        var report = new StringBuilder();

        System.out.printf("S74_ImageReplace  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);
            report.append("=== ").append(stem).append(" ===\n");

            // Count images first
            int totalImages = 0;
            try (PdfDocument doc = PdfDocument.open(input)) {
                for (int p = 0; p < doc.pageCount(); p++) {
                    totalImages += PdfImageReplacer.countImages(doc, p);
                }
            }
            report.append("  Total images: ").append(totalImages).append('\n');
            System.out.printf("  %s: %d images%n", stem, totalImages);

            // Config 1: Replace first image on page 0 with red
            try (PdfDocument doc = PdfDocument.open(input)) {
                boolean ok = PdfImageReplacer.replaceWithSolid(doc, 0, 0, 100, 100, 0xFF0000);
                Path out = outDir.resolve(stem + "-replace-first-red.pdf");
                doc.save(out);
                produced.add(out);
                report.append("  Replace first with red: ").append(ok ? "OK" : "no image found").append('\n');
                System.out.printf("    replace first image: %s%n", ok ? "OK" : "no image");
            } catch (Exception e) {
                report.append("  Replace first: FAILED - ").append(e.getMessage()).append('\n');
            }

            // Config 2: Replace all images on page 0 with gray
            try (PdfDocument doc = PdfDocument.open(input)) {
                int replaced = PdfImageReplacer.replaceAllWithSolid(doc, 0, 0x808080);
                Path out = outDir.resolve(stem + "-replace-all-gray.pdf");
                doc.save(out);
                produced.add(out);
                report.append("  Replace all with gray: ").append(replaced).append(" replaced\n");
                System.out.printf("    replace all with gray: %d replaced%n", replaced);
            } catch (Exception e) {
                report.append("  Replace all: FAILED - ").append(e.getMessage()).append('\n');
            }
        }

        Files.writeString(txtOut, report.toString());
        produced.add(txtOut);
        SampleBase.done("S74_ImageReplace", produced.toArray(Path[]::new));
    }
}
