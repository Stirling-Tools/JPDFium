package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.panama.RepairLib;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfRepairTest {

    private static final byte[] EMPTY_BYTES = new byte[0];
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
        assertEquals("{\"error\":\"empty input\"}", PdfRepair.inspect(EMPTY_BYTES));
    }

    @Test
    void inspectHandlesNullInput() {
        assertEquals("{\"error\":\"empty input\"}", PdfRepair.inspect((byte[]) null));
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

    /**
     * A repaired PDF must be complete on disk before it is read back.
     *
     * <p>Pdfio writes the cross-reference table and trailer during its close
     * step. If the bytes are read while that writer is still open, the result is
     * a truncated PDF whose xref is missing - which an independent parser
     * rejects. This test therefore reopens the repair output with PDFBox, which
     * shares no code with the repair path.
     */
    @Test
    void repairedOutputIsReopenableByAnIndependentParser() throws Exception {
        byte[] damaged = RepairCorpus.zeroXref(RepairCorpus.validMultiPage());
        RepairResult result = PdfRepair.builder()
                .input(damaged)
                .normalizeXref(true)
                .fixStartxref(true)
                .usePdfioFallback(true)
                .build()
                .execute();

        assertTrue(result.isUsable(), "repair must succeed: " + result.status());
        assertNotNull(result.repairedPdf());
        assertTrue(result.repairedPdf().length > 0, "repair must not return empty bytes");

        try (PDDocument reopened = Loader.loadPDF(result.repairedPdf())) {
            assertTrue(reopened.getNumberOfPages() > 0,
                    "repaired output must expose its pages (finalised xref + trailer)");
        }
    }

    /**
     * Same guarantee on the PDFio path itself, invoked directly.
     *
     * <p>Going through {@link PdfRepair} is not enough: an earlier stage usually
     * succeeds first, so the PDFio branch never runs. This pins the contract at
     * the entry point that does the read-back.
     */
    @Test
    void pdfioRepairOutputIsReopenableByAnIndependentParser() throws Exception {
        // No published build links PDFio (CMakeLists.txt never defines
        // JPDFIUM_HAS_PDFIO, so the bridge compiles the stub that reports
        // unavailable). Skip rather than assert a branch that cannot run here;
        // -Djpdfium.pdfio=true enables it once a PDFio-enabled build exists.
        Assumptions.assumeTrue(Boolean.getBoolean("jpdfium.pdfio"),
                "PDFio is not linked into this native build");

        // A zeroed xref table forces PDFio to rebuild from a full object scan,
        // which is the path its page-copy salvage depends on.
        byte[] damaged = RepairCorpus.zeroXref(RepairCorpus.validMultiPage());
        RepairResult result = RepairLib.pdfioRepair(damaged);

        assertTrue(result.isUsable(),
                "pdfio repair must succeed: " + result.status() + " " + result.diagnosticJson());
        assertNotNull(result.repairedPdf());
        assertTrue(result.repairedPdf().length > 0, "pdfio repair must not return empty bytes");

        // An independent parser rejects a file whose xref/trailer was never
        // written, which is exactly what reading before the writer's close step
        // would produce.
        try (PDDocument reopened = Loader.loadPDF(result.repairedPdf())) {
            assertTrue(reopened.getNumberOfPages() > 0,
                    "pdfio output must be finalised (xref + trailer) before read-back");
        }
    }

    /**
     * Repair writes untrusted input to temporary files. They must not survive the
     * call, on any path - success, failure, or exception.
     */
    @Test
    void repairLeavesNoTemporaryFilesBehind() throws Exception {
        Set<String> before = repairTempFiles();

        PdfRepair.builder().input(RepairCorpus.zeroXref(RepairCorpus.validMultiPage()))
                .fixStartxref(true).usePdfioFallback(true).build().execute();
        // Also exercise a hopeless input so the failure path is covered too.
        PdfRepair.builder().input("not a pdf at all".getBytes(StandardCharsets.UTF_8))
                .fixStartxref(true).usePdfioFallback(true).build().execute();

        Set<String> after = repairTempFiles();
        Set<String> leaked = new TreeSet<>(after);
        leaked.removeAll(before);
        assertTrue(leaked.isEmpty(), "repair left temporary files behind: " + leaked);
    }

    /** Snapshot of any jpdfium repair temp files currently present. */
    private static Set<String> repairTempFiles() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var s = Files.list(tmp)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("jpdfium_pdfio_"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

}
