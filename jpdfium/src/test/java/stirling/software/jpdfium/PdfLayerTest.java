package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.redact.RedactionSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfLayerTest {

    @Test
    void pageRotationUsesObjectNumberFastPath(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium runtime");
        Path base = tmp.resolve("rotate.pdf");
        Files.write(base, SyntheticPdfFactory.createDiverse(4));
        try (PdfDocument doc = PdfDocument.open(base)) {
            assertTrue(doc.setPageRotation(0, 90));
            assertEquals(90, doc.getPageRotation(0));
            assertTrue(doc.setPageRotation(2, 270));
            assertEquals(270, doc.getPageRotation(2));
        }
    }

    @Test
    void layerIdentityAndPromotedObjectCount(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium layer runtime");
        Path base = tmp.resolve("identity.pdf");
        byte[] baseBytes = SyntheticPdfFactory.createDiverse(2);
        Files.write(base, baseBytes);
        String expectedSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(baseBytes));

        try (PdfDocument layer = PdfDocument.openLayer(base, null)) {
            assertEquals(expectedSha, layer.layerBaseSha256().orElseThrow());
            assertEquals(0L, layer.layerPromotedObjectCount());
            try (PdfPage page = layer.page(0)) {
                PdfPageEditor.setRotation(page.rawHandle(), 1);
            }
            assertTrue(layer.layerPromotedObjectCount() > 0);
        }

        try (PdfDocument plain = PdfDocument.open(base)) {
            assertTrue(plain.layerBaseSha256().isEmpty());
            assertEquals(-1L, plain.layerPromotedObjectCount());
        }
    }

    @Test
    void layersShareOneBaseWithoutLeakingEdits(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium layer runtime");
        Path base = tmp.resolve("shared-base.pdf");
        Files.write(base, SyntheticPdfFactory.createDiverse(8));

        try (PdfDocument a = PdfDocument.openLayer(base, null);
                PdfDocument b = PdfDocument.openLayer(base, null)) {
            assertEquals(8, a.pageCount());
            assertEquals(8, b.pageCount());
            try (PdfPage page = a.page(0)) {
                PdfPageEditor.setRotation(page.rawHandle(), 1);
            }
            try (PdfPage page = b.page(1)) {
                PdfPageEditor.setRotation(page.rawHandle(), 2);
            }
            Path outA = tmp.resolve("a.pdf");
            Path outB = tmp.resolve("b.pdf");
            a.saveLayerMaterialized(outA);
            b.saveLayerMaterialized(outB);
            try (PdfDocument checkA = PdfDocument.open(outA);
                    PdfDocument checkB = PdfDocument.open(outB)) {
                assertEquals(90, checkA.getPageRotation(0));
                assertEquals(0, checkA.getPageRotation(1));
                assertEquals(0, checkB.getPageRotation(0));
                assertEquals(180, checkB.getPageRotation(1));
            }
        }
    }

    @Test
    void layerEditsMaterializeAndReopen(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium layer runtime");

        Path base = tmp.resolve("base.pdf");
        Path artifact = tmp.resolve("layer.bin");
        Path materialized = tmp.resolve("materialized.pdf");
        Files.write(base, SyntheticPdfFactory.createDiverse(6));

        try (PdfDocument layer = PdfDocument.openLayer(base, null)) {
            assertTrue(layer.isLayerDocument());
            assertEquals(6, layer.pageCount());
            try (PdfPage page = layer.page(0)) {
                PdfPageEditor.setRotation(page.rawHandle(), 1);
            }
            assertThrows(IllegalStateException.class, () -> layer.save(tmp.resolve("wrong.pdf")));
            assertThrows(IllegalArgumentException.class, () -> RedactionSession.wrap(layer));
            layer.saveLayerArtifact(artifact);
            layer.saveLayerMaterialized(materialized);
        }

        assertTrue(Files.size(artifact) > 0, "layer artifact must not be empty");
        try (PdfDocument check = PdfDocument.open(materialized)) {
            assertEquals(6, check.pageCount());
            assertEquals(90, check.getPageRotation(0));
        }

        try (PdfDocument reopened = PdfDocument.openLayer(base, artifact)) {
            assertEquals(90, reopened.getPageRotation(0));
            try (PdfPage page = reopened.page(1)) {
                PdfPageEditor.setRotation(page.rawHandle(), 2);
            }
            reopened.saveLayerArtifact(tmp.resolve("layer2.bin"));
            reopened.saveLayerMaterialized(materialized);
        }
        try (PdfDocument check = PdfDocument.open(materialized)) {
            assertEquals(90, check.getPageRotation(0));
            assertEquals(180, check.getPageRotation(1));
        }
    }
}
