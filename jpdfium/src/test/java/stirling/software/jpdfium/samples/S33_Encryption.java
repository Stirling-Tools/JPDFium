package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfEncryption;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 33 - Encryption & Decryption.
 *
 * <p>Demonstrates PdfEncryption: checking encryption status, encrypting a PDF
 * with AES-256, and decrypting it back. Requires qpdf on PATH.
 */
public class S33_Encryption {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        Path outDir = SampleBase.out("S33_encryption");

        System.out.printf("S33_Encryption  |  %d PDF(s)  qpdf=%b%n",
                inputs.size(), PdfEncryption.isSupported());

        if (!PdfEncryption.isSupported()) {
            System.out.println("  qpdf not found - skipping encryption tests");
            SampleBase.done("S33_Encryption");
            return;
        }

        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);

        // 1. Check encryption status
        try (PdfDocument doc = PdfDocument.open(input)) {
            boolean encrypted = PdfEncryption.isEncrypted(doc.rawHandle());
            int revision = PdfEncryption.securityRevision(doc.rawHandle());
            long perms = PdfEncryption.permissions(doc.rawHandle());
            System.out.printf("  Original: encrypted=%b  revision=%d  permissions=0x%08X%n",
                    encrypted, revision, perms);
        }

        // 2. Encrypt with AES-256
        Path encrypted = outDir.resolve(stem + "-encrypted.pdf");
        try {
            PdfEncryption.encrypt(input, encrypted, "user123", "owner456");
            System.out.printf("  Encrypted: %s (%d bytes)%n", encrypted.getFileName(),
                    Files.size(encrypted));
        } catch (Exception e) {
            System.out.printf("  Encryption failed: %s%n", e.getMessage());
            e.printStackTrace();
            SampleBase.done("S33_Encryption");
            return;
        }

        // 3. Verify encryption
        try (PdfDocument doc = PdfDocument.open(encrypted, "user123")) {
            boolean isEnc = PdfEncryption.isEncrypted(doc.rawHandle());
            System.out.printf("  Verification: encrypted=%b%n", isEnc);
        }

        // 4. Decrypt back
        Path decrypted = outDir.resolve(stem + "-decrypted.pdf");
        try {
            PdfEncryption.decrypt(encrypted, decrypted, "user123");
            System.out.printf("  Decrypted: %s (%d bytes)%n", decrypted.getFileName(),
                    Files.size(decrypted));

            // Verify decryption
            try (PdfDocument doc = PdfDocument.open(decrypted)) {
                boolean stillEnc = PdfEncryption.isEncrypted(doc.rawHandle());
                System.out.printf("  Verification: encrypted=%b (should be false)%n", stillEnc);
            }
        } catch (Exception e) {
            System.out.printf("  Decryption failed: %s%n", e.getMessage());
            e.printStackTrace();
        }

        SampleBase.done("S33_Encryption", encrypted, decrypted);
    }
}
