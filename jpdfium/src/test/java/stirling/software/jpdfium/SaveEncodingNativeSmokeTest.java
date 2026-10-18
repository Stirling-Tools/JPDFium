package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Compares a file save against the byte save of the same document. Needs full natives. */
class SaveEncodingNativeSmokeTest {

    @TempDir
    Path tmp;

    @Test
    void fileSaveWritesWhatTheByteSaveWrites() throws Exception {
        requireFullNative();
        Path fileSave = tmp.resolve("file-save.pdf");
        AtomicReference<byte[]> byteSave = new AtomicReference<>();
        try (PdfDocument doc = PdfDocument.open(SyntheticPdfFactory.createDiverse(1))) {
            doc.save(fileSave);
            byteSave.set(doc.saveBytes());
        }

        byte[] fileBytes = Files.readAllBytes(fileSave);
        assertTrue(fileBytes.length > 0, "the file save produced no bytes");
        assertSameSerialization("file save", fileBytes, byteSave.get());

        try (PdfDocument reopened = PdfDocument.open(fileSave)) {
            assertEquals(1, reopened.pageCount(), "page count changed across the file save");
            assertTrue(reopened.page(0).extractText().contains("Synthetic corpus page 1"),
                    "page text is unreadable after the file save: "
                            + reopened.page(0).extractText());
        }
    }

    @Test
    void fileSaveOfAMutatedDocumentStaysReadable() throws Exception {
        requireFullNative();
        Path fileSave = tmp.resolve("redacted.pdf");
        AtomicReference<byte[]> byteSave = new AtomicReference<>();
        try (PdfDocument doc = PdfDocument.open(SyntheticPdfFactory.createDiverse(1))) {
            try (PdfPage page = doc.page(0)) {
                page.redactPattern("Synthetic", 0xFF000000);
            }
            doc.save(fileSave);
            byteSave.set(doc.saveBytes());
        }

        assertSameSerialization("redacted file save", Files.readAllBytes(fileSave), byteSave.get());
        try (PdfDocument reopened = PdfDocument.open(fileSave)) {
            assertEquals(1, reopened.pageCount());
            String text = reopened.page(0).extractText();
            assertFalse(text.contains("Synthetic"),
                    "the redaction was lost across the file save: " + text);
            assertTrue(text.contains("corpus page 1"),
                    "the surviving text is unreadable after the file save: " + text);
        }
    }

    // Windows wrote the file through a text mode descriptor, which adds one CR per LF,
    // so the size and the CR count diverge while the LF count stays the same.
    private static void assertSameSerialization(String what, byte[] fileBytes, byte[] memoryBytes) {
        assertEquals(memoryBytes.length, fileBytes.length,
                what + " is " + fileBytes.length + " bytes but the byte save is " + memoryBytes.length
                        + " bytes: the file was written through a translating file descriptor");
        assertEquals(count(memoryBytes, (byte) '\n'), count(fileBytes, (byte) '\n'),
                what + " has a different LF count than the byte save");
        assertEquals(count(memoryBytes, (byte) '\r'), count(fileBytes, (byte) '\r'),
                what + " has a different CR count than the byte save");
    }

    private static int count(byte[] data, byte value) {
        int seen = 0;
        for (byte b : data) {
            if (b == value) seen++;
        }
        return seen;
    }

    private static void requireFullNative() {
        try {
            NativeLoader.ensureLoaded();
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            assumeTrue(false, "native library not available: " + t);
        }
        assumeTrue(NativeRuntime.isFull(), "needs the full native PDFium library");
    }
}
