package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.SyntheticPdfFactory;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers the qpdf surface added for Stirling-PDF integration: structural
 * inspection, lossless /Info editing, and the combined QDF repair cascade.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class QpdfApiSurfaceTest {

    @BeforeAll
    static void requireFullNative() {
        assumeTrue(NativeRuntime.isFull(), "requires a full native build");
    }

    @Test
    void inspectionReportsStructureAndLinearization() throws Exception {
        assumeTrue(PdfStructuralInspector.isSupported(), "qpdf inspection unavailable");
        byte[] input = SyntheticPdfFactory.createDiverse(2);

        String report = PdfStructuralInspector.inspect(input);
        assertNotNull(report);
        assertTrue(report.contains("\"status\":\"ok\""), report);
        assertTrue(report.contains("\"pages\":2"), report);
        assertTrue(report.contains("\"encrypted\":false"), report);
        assertTrue(report.contains("\"qpdf_version\""), report);

        byte[] linearized = PdfOptimizer.optimize(input, PdfOptimizer.LINEARIZE,
                PdfOptimizer.DEFAULT, PdfOptimizer.OBJECT_STREAMS_GENERATE,
                PdfOptimizer.DEFAULT, PdfOptimizer.DEFAULT);
        assertNotNull(linearized);
        String linearizedReport = PdfStructuralInspector.inspect(linearized);
        assertTrue(linearizedReport.contains("\"linearized\":true"), linearizedReport);
        assertTrue(linearizedReport.contains("\"linearization_valid\":true"), linearizedReport);
    }

    @Test
    void inspectionHandlesEncryptedDocuments() throws Exception {
        assumeTrue(PdfStructuralInspector.isSupported(), "qpdf inspection unavailable");
        byte[] input = SyntheticPdfFactory.createDiverse(1);
        byte[] encrypted = PdfSecurity.encryptBytes(input, "user", "owner", PdfSecurity.PERM_ALL);
        assertNotNull(encrypted);

        String locked = PdfStructuralInspector.inspect(encrypted);
        assertTrue(locked.contains("\"status\":\"password_required\""), locked);

        String opened = PdfStructuralInspector.inspect(encrypted, "user");
        assertTrue(opened.contains("\"status\":\"ok\""), opened);
        assertTrue(opened.contains("\"encrypted\":true"), opened);
    }

    @Test
    void metadataEditorSetsAndRemovesInfoEntries(@TempDir Path tempDir) throws Exception {
        assumeTrue(PdfMetadataEditor.isSupported(), "qpdf metadata editor unavailable");
        byte[] input = SyntheticPdfFactory.createDiverse(1);

        byte[] withAuthor = PdfMetadataEditor.update(input, Map.of(
                PdfMetadataEditor.TITLE, "Qpdf title",
                PdfMetadataEditor.AUTHOR, "Qpdf author"));
        assertNotNull(withAuthor);
        try (PdfDocument doc = PdfDocument.open(withAuthor)) {
            assertEquals("Qpdf title", doc.metadata().get(PdfMetadataEditor.TITLE));
            assertEquals("Qpdf author", doc.metadata().get(PdfMetadataEditor.AUTHOR));
        }

        Map<String, String> removeAuthor = new HashMap<>();
        removeAuthor.put(PdfMetadataEditor.AUTHOR, null);
        byte[] removed = PdfMetadataEditor.update(withAuthor, removeAuthor);
        assertNotNull(removed);
        try (PdfDocument doc = PdfDocument.open(removed)) {
            assertFalse(doc.metadata().containsKey(PdfMetadataEditor.AUTHOR));
        }

        Path in = tempDir.resolve("metadata-in.pdf");
        Path out = tempDir.resolve("metadata-out.pdf");
        Files.write(in, input);
        PdfMetadataEditor.update(in, out, Map.of(PdfMetadataEditor.TITLE, "File title"));
        try (PdfDocument doc = PdfDocument.open(out)) {
            assertEquals("File title", doc.metadata().get(PdfMetadataEditor.TITLE));
        }
    }

    @Test
    void qdfRepairCascadeProducesOpenableOutput() throws Exception {
        assumeTrue(PdfRepair.isSupported(), "qpdf repair unavailable");
        RepairResult result = PdfRepair.repairQdf(SyntheticPdfFactory.createDiverse(2));
        assertTrue(result.isUsable(), result.status().toString());
        try (PdfDocument doc = PdfDocument.open(result.repairedPdf())) {
            assertEquals(2, doc.pageCount());
        }
    }
}
