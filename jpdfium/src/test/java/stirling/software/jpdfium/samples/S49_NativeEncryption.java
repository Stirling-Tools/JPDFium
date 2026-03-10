package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfEncryption;
import stirling.software.jpdfium.panama.EmbedPdfDocumentBindings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 49 - Native Encryption via EmbedPDF Fork.
 *
 * <p>Demonstrates in-memory AES-256 encryption using the EmbedPDF fork's
 * {@code EPDF_SetEncryption} API no external tools required.
 *
 * <p>Also shows owner password unlock and permission management.
 *
 * <p>Requires PDFium built from the <a href="https://github.com/embedpdf/pdfium">embedpdf/pdfium</a> fork.
 * Demonstrates native AES-256 encryption.
 */
public class S49_NativeEncryption {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        Path outDir = SampleBase.out("S49_native-encryption");

        System.out.printf("S49_NativeEncryption  |  %d PDF(s)%n", inputs.size());

        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);

        // 1. In-memory encrypt and save
        Path encrypted = outDir.resolve(stem + "-native-encrypted.pdf");
        try (PdfDocument doc = PdfDocument.open(input)) {
            System.out.printf("  Original: pages=%d encrypted=%b%n",
                    doc.pageCount(), PdfEncryption.isEncrypted(doc.rawHandle()));

            // Set AES-256 encryption with permissions: print + copy allowed
            long perms = EmbedPdfDocumentBindings.EPDF_PERM_PRINT
                       | EmbedPdfDocumentBindings.EPDF_PERM_COPY
                       | EmbedPdfDocumentBindings.EPDF_PERM_PRINT_HIGH;
            PdfEncryption.setEncryption(doc.rawHandle(), "user123", "owner456", perms);

            doc.save(encrypted);
            System.out.printf("  Encrypted: %s (%d bytes)%n", encrypted.getFileName(), Files.size(encrypted));
        }

        // 2. Open with user password and verify
        try (PdfDocument doc = PdfDocument.open(encrypted, "user123")) {
            boolean isEnc = PdfEncryption.isEncrypted(doc.rawHandle());
            long perms = PdfEncryption.permissions(doc.rawHandle());
            System.out.printf("  Verify (user pwd): encrypted=%b  permissions=0x%08X%n", isEnc, perms);
        }

        // 3. Unlock owner permissions
        try (PdfDocument doc = PdfDocument.open(encrypted, "user123")) {
            // Initially opened with user password - restricted permissions
            boolean ownerBefore = PdfEncryption.isOwnerUnlocked(doc.rawHandle());
            System.out.printf("  Owner unlocked (before): %b%n", ownerBefore);

            // Unlock with owner password
            boolean ok = PdfEncryption.unlockOwner(doc.rawHandle(), "owner456");
            boolean ownerAfter = PdfEncryption.isOwnerUnlocked(doc.rawHandle());
            System.out.printf("  Owner unlock result: %b  unlocked=%b%n", ok, ownerAfter);
        }

        // 4. Remove encryption and save
        Path decrypted = outDir.resolve(stem + "-native-decrypted.pdf");
        try (PdfDocument doc = PdfDocument.open(encrypted, "owner456")) {
            // Explicitly remove encryption before saving
            PdfEncryption.removeEncryption(doc.rawHandle());
            doc.save(decrypted);
            System.out.printf("  Decrypted: %s (%d bytes)%n", decrypted.getFileName(), Files.size(decrypted));
        }

        try (PdfDocument doc = PdfDocument.open(decrypted)) {
            boolean stillEnc = PdfEncryption.isEncrypted(doc.rawHandle());
            System.out.printf("  Verify decrypted: encrypted=%b (should be false)%n", stillEnc);
        }

        SampleBase.done("S49_NativeEncryption", encrypted, decrypted);
    }
}
