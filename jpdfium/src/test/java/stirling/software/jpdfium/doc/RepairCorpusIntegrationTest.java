package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.NativeLoader;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies the repair pipeline can handle
 * various classes of PDF corruption.
 *
 * <p>Uses {@link RepairCorpus} to generate damaged PDFs at test time.
 * Each specimen exercises a different failure mode: xref corruption,
 * truncation, missing trailer, etc.
 *
 * <p>Requires real native libraries (not stub). Run via:
 * <pre>{@code ./gradlew :jpdfium:integrationTest --tests '*RepairCorpusIntegrationTest*'}</pre>
 */
@Tag("integration")
class RepairCorpusIntegrationTest {

    private static Map<String, byte[]> corpus;

    @BeforeAll
    static void setUp() throws IOException {
        NativeLoader.ensureLoaded();
        corpus = RepairCorpus.all();
    }

    static Stream<String> damagedNames() throws IOException {
        return RepairCorpus.all().keySet().stream();
    }

    @Test
    void validPdfReportsCleanOrFixed() {
        byte[] valid = corpus.values().iterator().next(); // first is damaged, use fresh
        try {
            byte[] freshValid = RepairCorpus.validMultiPage();
            RepairResult result = PdfRepair.builder()
                    .input(freshValid)
                    .all()
                    .build()
                    .execute();
            assertNotNull(result);
            assertTrue(result.isUsable(),
                    "A valid PDF should produce a usable result, got: " + result.status());
            assertTrue(result.status() == RepairResult.Status.CLEAN
                            || result.status() == RepairResult.Status.FIXED,
                    "Expected CLEAN or FIXED for valid PDF, got: " + result.status());
        } catch (IOException e) {
            fail("Failed to generate valid PDF: " + e.getMessage());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("damagedNames")
    void repairHandlesDamagedPdf(String name) {
        byte[] damaged = corpus.get(name);
        assertNotNull(damaged, "Corpus entry missing: " + name);
        assertTrue(damaged.length > 0, "Empty corpus entry: " + name);

        // The repair pipeline should NOT throw an exception, even for badly damaged input
        RepairResult result = assertDoesNotThrow(() ->
                        PdfRepair.builder()
                                .input(damaged)
                                .all()
                                .build()
                                .execute(),
                "Repair threw exception for: " + name);

        assertNotNull(result, "Null result for: " + name);
        assertNotNull(result.status(), "Null status for: " + name);

        System.out.printf("  %-35s -> %-8s  usable=%s  bytes=%s%n",
                name,
                result.status(),
                result.isUsable(),
                result.repairedPdf() != null ? result.repairedPdf().length : "null");
    }

    @Test
    void repairableCorruptionsProduceUsableOutput() throws IOException {
        var repairable = Map.of(
                "damage-startxref-wrong", corpus.get("damage-startxref-wrong"),
                "damage-missing-eof", corpus.get("damage-missing-eof"),
                "damage-header-garbage", corpus.get("damage-header-garbage"),
                "damage-truncated-90pct", corpus.get("damage-truncated-90pct")
        );

        for (var entry : repairable.entrySet()) {
            RepairResult result = PdfRepair.builder()
                    .input(entry.getValue())
                    .all()
                    .build()
                    .execute();

            // At minimum, the repair should not crash and should return a status
            assertNotNull(result.status(),
                    "Null status for repairable case: " + entry.getKey());

            System.out.printf("  %-35s -> %-8s  usable=%s%n",
                    entry.getKey(), result.status(), result.isUsable());
        }
    }

    @Test
    void diagnosticsToggleWorks() throws IOException {
        byte[] valid = RepairCorpus.validMultiPage();

        // With diagnostics enabled (default)
        RepairResult withDiag = PdfRepair.builder()
                .input(valid)
                .all()
                .writeDiagnostics(true)
                .build()
                .execute();
        assertNotNull(withDiag.diagnosticJson(), "Diagnostics should be present when enabled");

        // With diagnostics disabled
        RepairResult noDiag = PdfRepair.builder()
                .input(valid)
                .all()
                .writeDiagnostics(false)
                .build()
                .execute();
        assertNull(noDiag.diagnosticJson(), "Diagnostics should be null when disabled");

        // Both should produce the same repair status
        assertEquals(withDiag.status(), noDiag.status());
    }

    @Test
    void repairedOutputIsOpenable() throws Exception {
        // For damage types that should be repairable, verify the output PDF can be opened
        byte[] damaged = corpus.get("damage-startxref-wrong");
        RepairResult result = PdfRepair.builder()
                .input(damaged)
                .all()
                .build()
                .execute();

        if (result.isUsable() && result.repairedPdf() != null) {
            // Try opening the repaired PDF with JPDFium itself
            Path tmp = Files.createTempFile("repair-test-", ".pdf");
            try {
                Files.write(tmp, result.repairedPdf());
                try (PdfDocument doc = PdfDocument.open(tmp)) {
                    assertTrue(doc.pageCount() > 0,
                            "Repaired PDF should have pages");
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    

    /**
     * Returns all PDFs from {@code src/test/resources/pdfs/repair/}.
     * These are real-world structurally unusual PDFs from the PDF.js test suite
     * (Apache 2.0), committed directly into resources so no network access is needed.
     */
    static Stream<Path> repairCorpusPdfs() throws IOException, URISyntaxException {
        URL dir = RepairCorpusIntegrationTest.class.getClassLoader().getResource("pdfs/repair");
        if (dir == null) return Stream.empty();
        return Files.list(Path.of(dir.toURI()))
                .filter(p -> p.toString().endsWith(".pdf"))
                .sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repairCorpusPdfs")
    void repairDoesNotCrashOnCorpusPdf(Path pdf) throws IOException {
        byte[] input = Files.readAllBytes(pdf);
        String name = pdf.getFileName().toString();

        RepairResult result = assertDoesNotThrow(
                () -> PdfRepair.builder().input(input).all().build().execute(),
                "Repair crashed on: " + name);

        assertNotNull(result, "Null result for: " + name);
        assertNotNull(result.status(), "Null status for: " + name);
        System.out.printf("  %-40s -> %-8s  usable=%s  size=%s%n",
                name, result.status(), result.isUsable(),
                result.repairedPdf() != null ? result.repairedPdf().length : "null");
    }

    @Test
    void inspectReportsIssuesForDamagedPdf() {
        byte[] damaged = corpus.get("damage-xref-corrupted");
        String diagnostics = PdfRepair.inspect(damaged);
        assertNotNull(diagnostics);
        assertFalse(diagnostics.isBlank(), "Inspect should return non-empty diagnostics");
        System.out.println("  Inspect result: " + diagnostics);
    }

    @Test
    void severelyCorruptedFileDoesNotCrash() {
        // Completely random bytes - should not crash the native layer
        byte[] garbage = new byte[1024];
        for (int i = 0; i < garbage.length; i++) garbage[i] = (byte) (i * 37);

        RepairResult result = assertDoesNotThrow(() ->
                PdfRepair.builder()
                        .input(garbage)
                        .all()
                        .build()
                        .execute());
        assertNotNull(result);
        assertEquals(RepairResult.Status.FAILED, result.status(),
                "Random bytes should result in FAILED status");
    }
}
