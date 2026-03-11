package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.EmbedPdfDocumentBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * PDF Encryption operations.
 *
 * <p>Supports two encryption backends:
 * <ol>
 *   <li><b>Native (in-memory)</b> - AES-256 via {@code EPDF_SetEncryption}, applied
 *       in-memory before save. No external process needed.</li>
 *   <li><b>qpdf subprocess</b> - AES-128/256, file-to-file operations.
 *       Useful when working with file paths directly.</li>
 * </ol>
 */
public final class PdfEncryption {

    private PdfEncryption() {}

    /**
     * Check if the document is encrypted.
     *
     * @param rawDoc raw FPDF_DOCUMENT
     * @return true if the security handler revision > 0
     */
    public static boolean isEncrypted(MemorySegment rawDoc) {
        try {
            return (int) EmbedPdfDocumentBindings.EPDF_IsEncrypted.invokeExact(rawDoc) != 0;
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    /**
     * Set AES-256 encryption on an in-memory document.
     * Must be called <b>before</b> saving. The encryption takes effect during
     * {@code FPDF_SaveAsCopy} / {@code FPDF_SaveWithVersion}.
     *
     * @param rawDoc        raw FPDF_DOCUMENT
     * @param userPassword  user password (empty string = document opens without password)
     * @param ownerPassword owner password
     * @param permissions   permission bitmask (see {@link EmbedPdfDocumentBindings} constants)
     */
    public static void setEncryption(MemorySegment rawDoc,
                                      String userPassword, String ownerPassword, long permissions) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment userSeg = arena.allocateFrom(userPassword);
            MemorySegment ownerSeg = arena.allocateFrom(ownerPassword);
            int ok = (int) EmbedPdfDocumentBindings.EPDF_SetEncryption.invokeExact(
                    rawDoc, userSeg, ownerSeg, permissions);
            if (ok == 0) throw new RuntimeException("EPDF_SetEncryption failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    /**
     * Remove pending (not-yet-saved) encryption from a document.
     *
     * @param rawDoc raw FPDF_DOCUMENT
     */
    public static void removeEncryption(MemorySegment rawDoc) {
        try {
            int ok = (int) EmbedPdfDocumentBindings.EPDF_RemoveEncryption.invokeExact(rawDoc);
            if (ok == 0) throw new RuntimeException("EPDF_RemoveEncryption failed");
        } catch (RuntimeException e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    /**
     * Unlock owner permissions on an encrypted, already-opened document.
     * After unlocking, modifications are allowed even though the document was
     * opened with the user password.
     *
     * @param rawDoc        raw FPDF_DOCUMENT
     * @param ownerPassword owner password
     * @return true if the owner password was correct and permissions were unlocked
     */
    public static boolean unlockOwner(MemorySegment rawDoc, String ownerPassword) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pwSeg = arena.allocateFrom(ownerPassword);
            return (int) EmbedPdfDocumentBindings.EPDF_UnlockOwnerPermissions.invokeExact(rawDoc, pwSeg) != 0;
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    /**
     * Check if the owner permissions have been unlocked.
     *
     * @param rawDoc raw FPDF_DOCUMENT
     */
    public static boolean isOwnerUnlocked(MemorySegment rawDoc) {
        try {
            return (int) EmbedPdfDocumentBindings.EPDF_IsOwnerUnlocked.invokeExact(rawDoc) != 0;
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    /**
     * Check if any encryption backend is available.
     *
     * @return true if qpdf is available (native encryption is always available)
     */
    public static boolean isSupported() {
        return true;
    }

    /**
     * Encrypt a PDF file with AES and the specified passwords using qpdf.
     *
     * @param input         path to the input PDF
     * @param output        path for the encrypted output PDF
     * @param userPassword  password required to open the document (empty string = no password to open)
     * @param ownerPassword password to control permissions
     * @param keyLength     encryption key length: 128 or 256 (AES-128 or AES-256)
     */
    public static void encrypt(Path input, Path output,
                                String userPassword, String ownerPassword, int keyLength) {
        if (keyLength != 128 && keyLength != 256) {
            throw new IllegalArgumentException("keyLength must be 128 or 256, got: " + keyLength);
        }
        QpdfHelper.run("--encrypt", userPassword, ownerPassword,
                String.valueOf(keyLength), "--",
                input.toAbsolutePath().toString(),
                output.toAbsolutePath().toString());
    }

    /**
     * Encrypt a PDF with AES-256 (default) using qpdf.
     *
     * @param input         path to the input PDF
     * @param output        path for the encrypted output PDF
     * @param userPassword  user password
     * @param ownerPassword owner password
     */
    public static void encrypt(Path input, Path output,
                                String userPassword, String ownerPassword) {
        encrypt(input, output, userPassword, ownerPassword, 256);
    }

    /**
     * Decrypt a PDF file (remove encryption) using qpdf.
     *
     * @param input    path to the encrypted PDF
     * @param output   path for the decrypted output PDF
     * @param password password to open the file
     */
    public static void decrypt(Path input, Path output, String password) {
        QpdfHelper.run("--password=" + password, "--decrypt",
                input.toAbsolutePath().toString(),
                output.toAbsolutePath().toString());
    }

    /**
     * Check if qpdf-based encryption is available.
     *
     * @return true if qpdf is found on the system PATH
     */
    public static boolean isQpdfAvailable() {
        return QpdfHelper.isAvailable();
    }

    // Metadata helpers

    /**
     * Check the security handler revision.
     */
    public static int securityRevision(MemorySegment rawDoc) {
        return PdfMetadata.of(rawDoc).securityHandlerRevision();
    }

    /**
     * Get the document permissions bitmask.
     */
    public static long permissions(MemorySegment rawDoc) {
        return PdfMetadata.of(rawDoc).permissions();
    }
}
