package stirling.software.jpdfium.transform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.NativeLoader;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests for {@link PageOps}.
 */
class PageOpsTest {

    @BeforeAll
    static void loadNative() {
        NativeLoader.ensureLoaded();
    }

    private static Path samplePdf() throws Exception {
        Path tmp = Files.createTempFile("pageops-test-", ".pdf");
        try (InputStream in = PageOpsTest.class.getResourceAsStream("/pdfs/general/minimal.pdf")) {
            Files.write(tmp, Objects.requireNonNull(in).readAllBytes());
        }
        return tmp;
    }

    @Test
    void renderPageProducesValidImage() throws Exception {
        Path path = samplePdf();
        try (PdfDocument doc = PdfDocument.open(path)) {
            BufferedImage image = PageOps.renderPage(doc, 0, 72);
            assertNotNull(image);
            assertTrue(image.getWidth() > 0);
            assertTrue(image.getHeight() > 0);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void renderAllRendersEachPage() throws Exception {
        Path path = samplePdf();
        try (PdfDocument doc = PdfDocument.open(path)) {
            List<BufferedImage> images = PageOps.renderAll(doc, 72);
            assertNotNull(images);
            assertEquals(doc.pageCount(), images.size());
            for (BufferedImage image : images) {
                assertNotNull(image);
                assertTrue(image.getWidth() > 0);
                assertTrue(image.getHeight() > 0);
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void flattenSinglePageSucceeds() throws Exception {
        Path path = samplePdf();
        try (PdfDocument doc = PdfDocument.open(path)) {
            PageOps.flatten(doc, 0);
            assertEquals(3, doc.pageCount());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void flattenAllPagesSucceeds() throws Exception {
        Path path = samplePdf();
        try (PdfDocument doc = PdfDocument.open(path)) {
            PageOps.flattenAll(doc);
            assertEquals(3, doc.pageCount());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void convertToImageSucceeds() throws Exception {
        Path path = samplePdf();
        try (PdfDocument doc = PdfDocument.open(path)) {
            PageOps.convertToImage(doc, 0, 72);
            assertEquals(3, doc.pageCount());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void convertAllToImagesSucceeds() throws Exception {
        Path path = samplePdf();
        try (PdfDocument doc = PdfDocument.open(path)) {
            PageOps.convertAllToImages(doc, 72);
            assertEquals(3, doc.pageCount());
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
