package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.RepairLib;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void repairFilePassesCleanSignedDocumentThroughUntouched(@TempDir Path tmp) throws Exception {
        Path in = signedFixture(tmp);
        Path out = tmp.resolve("repaired.pdf");

        RepairResult.Status status = PdfRepair.repairFile(in, out, 0);

        assertEquals(RepairResult.Status.CLEAN, status,
                "a clean signed document must pass through untouched instead of being rewritten");
        assertArrayEquals(Files.readAllBytes(in), Files.readAllBytes(out),
                "pass-through must publish the input bytes verbatim");
        try (PdfDocument doc = PdfDocument.open(out)) {
            assertTrue(doc.pageCount() > 0);
        }
    }

    @Test
    void sanitizeRequestOnCleanSignedDocumentReturnsConflict(@TempDir Path tmp) throws Exception {
        byte[] input = Files.readAllBytes(signedFixture(tmp));

        RepairResult withSanitize = PdfRepair.builder()
                .input(input)
                .sanitize(true)
                .writeDiagnostics(true)
                .build()
                .execute();
        assertEquals(RepairResult.Status.FAILED, withSanitize.status(),
                "sanitizing a signed document must report the signature conflict");
        assertNull(withSanitize.repairedPdf());

        RepairResult passThrough = PdfRepair.builder()
                .input(input)
                .writeDiagnostics(false)
                .build()
                .execute();
        assertEquals(RepairResult.Status.CLEAN, passThrough.status(),
                "a clean signed document must still pass through when sanitization is not requested");
        assertArrayEquals(input, passThrough.repairedPdf(),
                "signed pass-through must remain byte-identical");
    }

    @Test
    void repairFileRefusesDamagedSignedDocument(@TempDir Path tmp) throws Exception {
        Path in = damagedSignedFixture(tmp);
        Path out = tmp.resolve("repaired.pdf");

        RepairResult.Status status = PdfRepair.repairFile(in, out, 0);

        assertEquals(RepairResult.Status.FAILED, status,
                "a damaged signed document must fail instead of invalidating its signature");
        assertFalse(Files.exists(out), "a refused repair must not publish an output file");
    }

    @Test
    void nativeFileRouteRefusesSignedInput(@TempDir Path tmp) throws Exception {
        assertTrue(RepairLib.isFileRepairSupported(),
                "jpdfium_repair_pdf_file must be bound for the native file route");
        Path in = signedFixture(tmp);
        Path out = tmp.resolve("repaired.pdf");

        // RepairLib bypasses the Java pass-through gate, so this exercises the
        // native qpdf route's own signed-document refusal.
        RepairResult.Status status = RepairLib.repairToFile(in, out, 0);

        assertEquals(RepairResult.Status.FAILED, status,
                "the native file route must refuse a signed input");
        assertFalse(Files.exists(out), "a refused native repair must not publish an output file");
    }

    private static Path signedFixture(Path tmp) throws Exception {
        URL url = PdfRepairFileBackedTest.class.getResource("/pdfs/general/irs_f1040ez.pdf");
        assertNotNull(url, "irs_f1040ez.pdf test resource missing");
        Path in = tmp.resolve("signed.pdf");
        Files.copy(Path.of(url.toURI()), in);
        return in;
    }

    /**
     * Destroys the {@code %PDF-} header magic while keeping every later byte
     * (including the signature marker). A recognizable-as-PDF input would be
     * repaired by PDFium's recovery; with the magic gone the document is
     * damaged past openability, which is the refusal branch's precondition.
     */
    private static Path damagedSignedFixture(Path tmp) throws Exception {
        byte[] bytes = Files.readAllBytes(signedFixture(tmp));
        byte[] damaged = bytes.clone();
        byte[] magic = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        byte[] destroyed = "XXXXX".getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= Math.min(damaged.length - magic.length, 1024); i++) {
            for (int j = 0; j < magic.length; j++) {
                if (damaged[i + j] != magic[j]) continue outer;
            }
            System.arraycopy(destroyed, 0, damaged, i, destroyed.length);
            break;
        }

        assertTrue(containsAscii(damaged, "/ByteRange"),
                "damage must keep the signature marker in the file");
        assertFalse(containsAscii(damaged, "%PDF-"),
                "damage must destroy the header magic");
        Path in = tmp.resolve("damaged-signed.pdf");
        Files.write(in, damaged);
        // Precondition: the damage must make the document genuinely unopenable
        // by PDFium. If PDFium learns to recover this pattern, strengthen the
        // damage rather than relaxing the refusal assertions.
        assertThrows(RuntimeException.class, () -> PdfDocument.open(in),
                "fixture precondition: damaged input must not be PDFium-openable");
        return in;
    }

    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= haystack.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (haystack[i + j] != target[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
