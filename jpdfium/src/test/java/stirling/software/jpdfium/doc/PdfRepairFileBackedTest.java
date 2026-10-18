package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.RepairLib;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfRepairFileBackedTest {

    private static Path fixture() throws Exception {
        URL url = PdfRepairFileBackedTest.class.getResource("/pdfs/general/basic-text.pdf");
        assertNotNull(url, "basic-text.pdf test resource missing");
        return Path.of(url.toURI());
    }

    @Test
    void repairFileProducesReadablePdf(@TempDir Path tmp) throws Exception {
        assertTrue(RepairLib.isFileRepairSupported(),
                "jpdfium_repair_pdf_file must be bound for the native file route");
        Path in = fixture();
        Path out = tmp.resolve("repaired.pdf");

        RepairResult.Status status = PdfRepair.repairFile(in, out, 0);

        assertNotEquals(RepairResult.Status.FAILED, status, "repairFile reported failure");
        assertTrue(Files.size(out) > 0, "repaired file is empty");

        try (PdfDocument a = PdfDocument.open(in);
             PdfDocument b = PdfDocument.open(out)) {
            assertEquals(a.pageCount(), b.pageCount(), "page count must be preserved");
        }
    }

    @Test
    void byteArrayRepairProducesReadablePdf(@TempDir Path tmp) throws Exception {
        Path in = fixture();

        byte[] repaired = RepairLib.repair(Files.readAllBytes(in), 0).repairedPdf();
        assertNotNull(repaired, "byte[] repair returned no output");
        assertTrue(repaired.length > 0, "byte[] repair returned an empty document");

        Path out = tmp.resolve("repaired.pdf");
        Files.write(out, repaired);
        try (PdfDocument b = PdfDocument.open(out)) {
            assertTrue(b.pageCount() > 0);
        }
    }
}
