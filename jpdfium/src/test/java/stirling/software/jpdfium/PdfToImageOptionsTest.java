package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.model.ColorType;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.PdfToImageOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfToImageOptionsTest {

    private static final Path MULTI_PAGE_PDF = Path.of("src/test/resources/pdfs/general/irs_f1040.pdf");

    @Test
    void parsePageRangeVariants() {
        // "1-3,5" with 10 total pages -> 0, 1, 2, 4
        Set<Integer> r1 = PdfToImageOptions.parsePageRange("1-3,5", 10);
        assertEquals(Set.of(0, 1, 2, 4), r1);

        // "all" -> all pages
        Set<Integer> all = PdfToImageOptions.parsePageRange("all", 4);
        assertEquals(Set.of(0, 1, 2, 3), all);

        // "-2" -> 0, 1
        Set<Integer> upTo2 = PdfToImageOptions.parsePageRange("-2", 5);
        assertEquals(Set.of(0, 1), upTo2);

        // "4-" -> 3, 4 with 5 total pages
        Set<Integer> from4 = PdfToImageOptions.parsePageRange("4-", 5);
        assertEquals(Set.of(3, 4), from4);

        // Out of bounds pages clamped safely
        Set<Integer> outOfBounds = PdfToImageOptions.parsePageRange("99-100", 5);
        assertTrue(outOfBounds.isEmpty());

        // Null / blank defaults to all pages
        Set<Integer> blank = PdfToImageOptions.parsePageRange("", 3);
        assertEquals(Set.of(0, 1, 2), blank);
    }

    @Test
    void builderOptions() {
        PdfToImageOptions options = PdfToImageOptions.builder()
                .format(ImageFormat.JPEG)
                .dpi(200)
                .pageRange("1-2")
                .colorType(ColorType.GRAY)
                .singleImage(true)
                .quality(85)
                .transparent(false)
                .build();

        assertEquals(ImageFormat.JPEG, options.format());
        assertEquals(200, options.dpi());
        assertEquals("1-2", options.pageRange());
        assertEquals(ColorType.GRAY, options.colorType());
        assertTrue(options.singleImage());
        assertEquals(85, options.quality());
        assertFalse(options.transparent());

        Set<Integer> resolved = options.resolvedPages(5);
        assertEquals(Set.of(0, 1), resolved);
    }

    @Test
    void pdfToImagesWithPageRangeAndSingleImage(@TempDir Path tempDir) throws Exception {
        try (PdfDocument doc = PdfDocument.open(MULTI_PAGE_PDF)) {
            assertTrue(doc.pageCount() >= 2);

            // Export only page 1
            PdfToImageOptions page1Options = PdfToImageOptions.builder()
                    .format(ImageFormat.PNG)
                    .dpi(100)
                    .pageRange("1")
                    .build();

            Path outDir1 = tempDir.resolve("out1");
            List<Path> files1 = PdfImageConverter.pdfToImages(doc, page1Options, outDir1);
            assertEquals(1, files1.size());
            assertTrue(Files.exists(files1.get(0)));

            // Export as single stitched image
            PdfToImageOptions singleImageOptions = PdfToImageOptions.builder()
                    .format(ImageFormat.PNG)
                    .dpi(100)
                    .singleImage(true)
                    .build();

            Path outDir2 = tempDir.resolve("out2");
            List<Path> files2 = PdfImageConverter.pdfToImages(doc, singleImageOptions, outDir2);
            assertEquals(1, files2.size());
            assertTrue(Files.exists(files2.get(0)));
            assertEquals("combined.png", files2.get(0).getFileName().toString());
        }
    }
}
