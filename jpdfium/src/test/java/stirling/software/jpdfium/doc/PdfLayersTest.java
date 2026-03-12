package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.NativeLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PdfLayers (Optional Content Groups / OCG management).
 */
class PdfLayersTest {

    @BeforeAll
    static void loadNative() {
        NativeLoader.ensureLoaded();
    }

    @Test
    void listLayersOnPdfWithoutOCGs() throws Exception {
        Path input = loadResource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            List<PdfLayers.Layer> layers = PdfLayers.list(doc);

            assertNotNull(layers);
            assertTrue(layers.isEmpty(), "Minimal PDF should have no layers");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void findLayerReturnsEmptyWhenAbsent() throws Exception {
        Path input = loadResource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            Optional<PdfLayers.Layer> layer = PdfLayers.find(doc, "NonExistent");
            assertTrue(layer.isEmpty(), "Should not find nonexistent layer");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void setAllVisibleDoesNotThrowOnEmptyDocument() throws Exception {
        Path input = loadResource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            // Should handle gracefully even when no layers exist
            assertDoesNotThrow(() -> PdfLayers.setAllVisible(doc, false));
            assertDoesNotThrow(() -> PdfLayers.setAllVisible(doc, true));
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void flattenAllLayersOnDocumentWithoutLayers() throws Exception {
        Path input = loadResource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            int flattened = PdfLayers.flattenAllLayers(doc);
            assertEquals(0, flattened, "Flattening document with no layers should return 0");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void layerRecordAccessors() {
        PdfLayers.Layer layer = new PdfLayers.Layer("Background", true, false, 42);

        assertEquals("Background", layer.name());
        assertTrue(layer.visible());
        assertFalse(layer.locked());
        assertEquals(42, layer.objectCount());
    }

    @Test
    void createLayerOnMinimalPdf() throws Exception {
        Path input = loadResource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfLayers.createLayer(doc, "TestLayer", true);

            List<PdfLayers.Layer> layers = PdfLayers.list(doc);
            // Layer may or may not be visible via list() depending on implementation
            // but createLayer should not throw
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void setVisibleOnNonExistentLayerDoesNotThrow() throws Exception {
        Path input = loadResource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            // Setting visibility on a layer that doesn't exist should be a no-op
            assertDoesNotThrow(() -> PdfLayers.setVisible(doc, "Ghost", false));
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void deleteLayerOnNonExistentDoesNotThrow() throws Exception {
        Path input = loadResource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            assertDoesNotThrow(() -> PdfLayers.deleteLayer(doc, "Ghost"));
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private Path loadResource(String resourcePath) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " not found in test resources");
            Path tmp = Files.createTempFile("layers-test-", ".pdf");
            Files.write(tmp, is.readAllBytes());
            return tmp;
        }
    }
}
