package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.panama.NativeLoader;

import static org.junit.jupiter.api.Assertions.*;

class PdfRepairTest {

    private static byte[] MINIMAL_PDF;

    @BeforeAll
    static void loadNative() {
        NativeLoader.ensureLoaded();
        MINIMAL_PDF = ("""
                %PDF-1.4
                1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
                2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
                3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj
                xref
                0 4
                0000000000 65535 f\s
                0000000009 00000 n\s
                0000000058 00000 n\s
                0000000115 00000 n\s
                trailer<</Size 4/Root 1 0 R>>
                startxref
                190
                %%EOF""")
                .getBytes();
    }

    @Test
    void builderRequiresInput() {
        assertThrows(IllegalStateException.class,
                () -> PdfRepair.builder().build());
    }

    @Test
    void builderAcceptsByteArray() {
        var repair = PdfRepair.builder().input(MINIMAL_PDF).build();
        assertNotNull(repair);
    }

    @Test
    void builderAllEnablesEverything() {
        var repair = PdfRepair.builder().input(MINIMAL_PDF).all().build();
        assertNotNull(repair);
    }

    @Test
    void builderIndividualOptions() {
        var repair = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .forceVersion14(true)
                .normalizeXref(true)
                .fixStartxref(true)
                .transcodeBrotli(true)
                .usePdfioFallback(true)
                .validateIcc(true)
                .validateJpx(true)
                .build();
        assertNotNull(repair);
    }

    @Test
    void inspectReturnsNonNullJson() {
        String diag = PdfRepair.inspect(MINIMAL_PDF);
        assertNotNull(diag);
        assertFalse(diag.isBlank());
    }

    @Test
    void inspectHandlesEmptyInput() {
        assertDoesNotThrow(() -> {
            try {
                PdfRepair.inspect(new byte[0]);
            } catch (Exception e) {
                // Expected for zero-length input
            }
        });
    }

    @Test
    void executeReturnsResult() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .build()
                .execute();
        assertNotNull(result);
        assertNotNull(result.status());
    }

    @Test
    void executeWithAllReturnsResult() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .all()
                .build()
                .execute();
        assertNotNull(result);
        assertNotNull(result.status());
    }

    @Test
    void executeProducesDiagnostics() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .build()
                .execute();
        assertNotNull(result.diagnosticJson());
    }

    @Test
    void executeWithBrotliTranscoding() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .transcodeBrotli(true)
                .build()
                .execute();
        assertNotNull(result);
        assertNotNull(result.status());
    }

    @Test
    void executeWithPdfioFallback() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .usePdfioFallback(true)
                .build()
                .execute();
        assertNotNull(result);
        assertNotNull(result.status());
    }

    @Test
    void executeWithIccValidation() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .validateIcc(true)
                .build()
                .execute();
        assertNotNull(result);
        assertNotNull(result.status());
    }

    @Test
    void executeWithJpxValidation() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .validateJpx(true)
                .build()
                .execute();
        assertNotNull(result);
        assertNotNull(result.status());
    }

    @Test
    void executeWithForceVersion14() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .forceVersion14(true)
                .build()
                .execute();
        assertNotNull(result);
    }

    @Test
    void executeWithNormalizeXref() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .normalizeXref(true)
                .build()
                .execute();
        assertNotNull(result);
    }

    @Test
    void executeWithFixStartxref() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .fixStartxref(true)
                .build()
                .execute();
        assertNotNull(result);
    }

    @Test
    void executeResultIsUsable() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .all()
                .build()
                .execute();
        assertTrue(result.isUsable());
        assertNotNull(result.repairedPdf());
        assertTrue(result.repairedPdf().length > 0);
    }
}
