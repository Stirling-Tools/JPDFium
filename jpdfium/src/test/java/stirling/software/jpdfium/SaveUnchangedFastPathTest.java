package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.panama.NativeRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The unchanged-save fast path: EmbedPDF reports that serialization would
 * reproduce the loaded bytes, and the bridge returns those bytes instead of
 * running PDFium's writer. The output must be byte-identical, including after
 * a page has been read (a read must not force a re-serialization).
 */
class SaveUnchangedFastPathTest {

    private static byte[] pdfBytes() throws Exception {
        return SyntheticPdfFactory.createDiverse(4);
    }

    @Test
    void saveBytesAfterPageReadReturnsLoadedBytes(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs the real native library");
        byte[] original = pdfBytes();
        try (PdfDocument doc = PdfDocument.open(original)) {
            // Opening a page used to disable the untouched fast path; the
            // EmbedPDF status is authoritative and must still report unchanged.
            try (PdfPage page = doc.page(0)) {
                assertTrue(page.size().width() > 0);
            }
            assertArrayEquals(original, doc.saveBytes(),
                    "an unmodified document must save as its loaded bytes");
        }
    }

    @Test
    void saveToPathAfterPageReadReturnsLoadedBytes(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs the real native library");
        byte[] original = pdfBytes();
        Path out = tmp.resolve("unchanged.pdf");
        try (PdfDocument doc = PdfDocument.open(original)) {
            try (PdfPage page = doc.page(0)) {
                assertTrue(page.size().width() > 0);
            }
            doc.saveTo(out);
        }
        assertArrayEquals(original, Files.readAllBytes(out),
                "file save must stream the loaded bytes without serializing");
    }

    @Test
    void changedDocumentStillSerializesAndReopens(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs the real native layer runtime");
        byte[] original = pdfBytes();
        byte[] saved;
        try (PdfDocument doc = PdfDocument.open(original)) {
            assertTrue(doc.setPageRotation(0, 90));
            saved = doc.saveBytes();
        }
        assertFalse(Arrays.equals(original, saved), "a real edit must change the saved bytes");
        try (PdfDocument reopened = PdfDocument.open(saved)) {
            assertEquals(90, reopened.getPageRotation(0));
        }
    }
}
