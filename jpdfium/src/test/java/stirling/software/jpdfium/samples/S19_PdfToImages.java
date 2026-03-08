package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfImageConverter;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.PdfToImageOptions;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * SAMPLE 19 - Convert PDF pages to images (PNG, JPEG, TIFF, WEBP, BMP).
 *
 * <p>Demonstrates conversion to all supported formats using page 0 only,
 * keeping output small but covering every codec path.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S19_PdfToImages {

    /** Formats to exercise, each with a representative DPI and quality. */
    private record FormatSpec(ImageFormat format, int dpi, int quality, boolean transparent) {}

    private static final List<FormatSpec> SPECS = List.of(
            new FormatSpec(ImageFormat.PNG,  150, 90, false),
            new FormatSpec(ImageFormat.JPEG, 150, 85, false),
            new FormatSpec(ImageFormat.TIFF, 200, 90, false),
            new FormatSpec(ImageFormat.WEBP, 150, 80, false),
            new FormatSpec(ImageFormat.BMP,  72,  90, false),
            // PNG with transparency
            new FormatSpec(ImageFormat.PNG,  72,  90, true)
    );

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S19_PdfToImages  |  %d PDF(s), %d format specs%n",
                inputs.size(), SPECS.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S19_PdfToImages", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                int pages = doc.pageCount();
                System.out.printf("  pages: %d%n", pages);

                for (FormatSpec spec : SPECS) {
                    String label = spec.format.name().toLowerCase()
                            + (spec.transparent ? "-transparent" : "");

                    if (!PdfImageConverter.canWrite(spec.format)) {
                        System.out.printf("  %-18s SKIPPED (no writer available)%n", label + ":");
                        continue;
                    }

                    Path outDir = SampleBase.out("pdf-to-images", input).resolve(label);
                    PdfToImageOptions opts = PdfToImageOptions.builder()
                            .format(spec.format)
                            .dpi(spec.dpi)
                            .quality(spec.quality)
                            .transparent(spec.transparent)
                            .pages(Set.of(0))   // first page only - keeps output small
                            .build();
                    List<Path> files = PdfImageConverter.pdfToImages(doc, opts, outDir);
                    long totalBytes = files.stream()
                            .mapToLong(p -> p.toFile().length()).sum();
                    System.out.printf("  %-18s %d file(s), %,d bytes -> %s%n",
                            label + ":", files.size(), totalBytes, outDir);
                }

                // Single page to bytes round-trip (for web APIs)
                if (pages > 0) {
                    byte[] thumb = PdfImageConverter.pageToBytes(
                            doc, 0, 72, ImageFormat.JPEG, 85, false);
                    System.out.printf("  page-0 JPEG bytes: %,d bytes%n", thumb.length);
                }
            }
        }

        SampleBase.done("S19_PdfToImages");
    }
}
