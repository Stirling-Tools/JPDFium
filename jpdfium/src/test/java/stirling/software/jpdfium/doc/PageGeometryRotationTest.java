package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Rotation semantics for {@link PdfPageGeometry#rotateAll}. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PageGeometryRotationTest {

    @Test
    void rotateAllSetsAbsoluteRotation() throws Exception {
        Path input = Path.of(getClass().getResource("/pdfs/general/minimal.pdf").toURI());
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.rotateAll(doc, 90);
            assertEquals(90, PdfPageGeometry.getRotation(doc, 0));
            PdfPageGeometry.rotateAll(doc, 270);
            assertEquals(270, PdfPageGeometry.getRotation(doc, 0),
                    "rotateAll sets the requested angle instead of adding to the current one");
            PdfPageGeometry.rotateAll(doc, 0);
            assertEquals(0, PdfPageGeometry.getRotation(doc, 0));
        }
    }

    @Test
    void rotateAllRejectsNonRightAngles() throws Exception {
        Path input = Path.of(getClass().getResource("/pdfs/general/minimal.pdf").toURI());
        try (PdfDocument doc = PdfDocument.open(input)) {
            assertThrows(IllegalArgumentException.class, () -> PdfPageGeometry.rotateAll(doc, 45));
        }
    }

    @Test
    void rotateAllNormalizesNegativeAngles() throws Exception {
        Path input = Path.of(getClass().getResource("/pdfs/general/minimal.pdf").toURI());
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.rotateAll(doc, -90);
            assertEquals(270, PdfPageGeometry.getRotation(doc, 0));
            PdfPageGeometry.rotateAll(doc, 360);
            assertEquals(0, PdfPageGeometry.getRotation(doc, 0));
        }
    }
}
