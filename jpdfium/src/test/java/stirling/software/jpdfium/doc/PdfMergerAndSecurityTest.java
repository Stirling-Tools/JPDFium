package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.SyntheticPdfFactory;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfMergerAndSecurityTest {

    @Test
    void testPdfMergerBasic(@TempDir Path tempDir) throws IOException {
        byte[] pdf1 = SyntheticPdfFactory.singlePageWithText("Doc 1");
        byte[] pdf2 = SyntheticPdfFactory.singlePageWithText("Doc 2");

        if (PdfMerger.isSupported()) {
            byte[] merged = PdfMerger.mergeBytes(List.of(pdf1, pdf2));
            assertNotNull(merged);
            assertTrue(merged.length > 0);

            Path p1 = tempDir.resolve("doc1.pdf");
            Path p2 = tempDir.resolve("doc2.pdf");
            Path out = tempDir.resolve("merged.pdf");
            Files.write(p1, pdf1);
            Files.write(p2, pdf2);

            PdfMerger.merge(List.of(p1, p2), out);
            assertTrue(Files.exists(out));
            assertTrue(Files.size(out) > 0);
        }
    }

    @Test
    void testPdfSecurityEncryptAndDecrypt(@TempDir Path tempDir) throws IOException {
        byte[] pdf = SyntheticPdfFactory.singlePageWithText("Sensitive Document");

        if (PdfSecurity.isSupported()) {
            byte[] encrypted = PdfSecurity.encryptBytes(pdf, "userPass", "ownerPass", PdfSecurity.PERM_ALL);
            assertNotNull(encrypted);
            assertTrue(encrypted.length > 0);
            // The stub bridge passes bytes through instead of encrypting, so it
            // cannot model the protection half of the contract. Real natives
            // can, and there the whole point is checked.
            boolean canEncrypt = NativeRuntime.isFull();
            if (canEncrypt) {
                assertThrows(Exception.class, () -> {
                    try (PdfDocument d = PdfDocument.open(encrypted)) {
                        d.pageCount();
                    }
                }, "the encrypted fixture must not open without a password");
            }

            // Length alone proves nothing: qpdf's writer preserves the source
            // encryption unless it is told not to, so a "decrypted" result can
            // still be a valid, fully encrypted PDF. The only check that proves
            // the contract is opening the output with no password at all.
            byte[] decrypted = PdfSecurity.decryptBytes(encrypted, "ownerPass");
            assertNotNull(decrypted);
            assertTrue(decrypted.length > 0);
            if (canEncrypt) {
                try (PdfDocument d = PdfDocument.open(decrypted)) {
                    assertTrue(d.pageCount() > 0,
                            "decrypted bytes must open without a password");
                }
            }

            Path inPath = tempDir.resolve("plain.pdf");
            Path encPath = tempDir.resolve("enc.pdf");
            Path decPath = tempDir.resolve("dec.pdf");
            Files.write(inPath, pdf);

            PdfSecurity.encrypt(inPath, encPath, "userPass", "ownerPass", PdfSecurity.PERM_ALL);
            assertTrue(Files.exists(encPath));

            PdfSecurity.decrypt(encPath, decPath, "ownerPass");
            assertTrue(Files.exists(decPath));
            if (canEncrypt) {
                try (PdfDocument d = PdfDocument.open(decPath)) {
                    assertTrue(d.pageCount() > 0,
                            "the decrypted file must open without a password");
                }
            }
        }
    }

    @Test
    void testPdfDocumentMergeConvenience() {
        byte[] pdf1 = SyntheticPdfFactory.singlePageWithText("Page 1");
        byte[] pdf2 = SyntheticPdfFactory.singlePageWithText("Page 2");

        if (PdfMerger.isSupported()) {
            byte[] merged = PdfDocument.mergeBytes(List.of(pdf1, pdf2));
            assertNotNull(merged);
            assertTrue(merged.length > 0);
        }
    }
}
