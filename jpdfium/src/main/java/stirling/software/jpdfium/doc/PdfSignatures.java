package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.SignatureBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Inspect digital signatures in a PDF document.
 *
 * <p>PDFium provides read-only access to signatures - it cannot create or verify them.
 * For verification, extract the contents bytes and use a cryptographic library
 * (e.g., BouncyCastle) to validate the PKCS#7 data.
 *
 * <pre>{@code
 * try (var doc = PdfDocument.open(Path.of("signed.pdf"))) {
 *     MemorySegment rawDoc = JpdfiumLib.docRawHandle(doc.nativeHandle());
 *     List<Signature> sigs = PdfSignatures.list(rawDoc);
 *     for (Signature sig : sigs) {
 *         System.out.printf("  Signature %d: %s, time=%s%n",
 *             sig.index(), sig.subFilter().orElse("unknown"),
 *             sig.signingTime().orElse("unknown"));
 *     }
 * }
 * }</pre>
 */
public final class PdfSignatures {

    private PdfSignatures() {}

    /**
     * Returns the number of signatures in the document.
     */
    public static int count(MemorySegment doc) {
        try {
            return (int) SignatureBindings.FPDF_GetSignatureCount.invokeExact(doc);
        } catch (Throwable t) { throw new RuntimeException("FPDF_GetSignatureCount failed", t); }
    }

    /**
     * List all signatures in the document.
     *
     * @param doc raw FPDF_DOCUMENT segment
     * @return all signatures with their properties
     */
    public static List<Signature> list(MemorySegment doc) {
        int n = count(doc);
        if (n <= 0) return Collections.emptyList();

        List<Signature> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(get(doc, i));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Get a specific signature by index.
     *
     * @param doc   raw FPDF_DOCUMENT segment
     * @param index 0-based signature index
     * @return the signature
     */
    public static Signature get(MemorySegment doc, int index) {
        MemorySegment sig;
        try {
            sig = (MemorySegment) SignatureBindings.FPDF_GetSignatureObject.invokeExact(doc, index);
        } catch (Throwable t) { throw new RuntimeException("FPDF_GetSignatureObject failed", t); }

        if (sig.equals(MemorySegment.NULL)) {
            throw new IndexOutOfBoundsException("Signature index " + index + " not found");
        }

        return new Signature(
                index,
                getSubFilter(sig),
                getReason(sig),
                getTime(sig),
                getContents(sig),
                getPermission(sig)
        );
    }

    private static Optional<String> getSubFilter(MemorySegment sig) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) SignatureBindings.FPDFSignatureObj_GetSubFilter.invokeExact(sig,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (needed <= 1) return Optional.empty();

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) SignatureBindings.FPDFSignatureObj_GetSubFilter.invokeExact(sig, buf, needed);
            } catch (Throwable t) { throw new RuntimeException(t); }
            return Optional.of(FfmHelper.fromByteString(buf, needed));
        }
    }

    private static Optional<String> getReason(MemorySegment sig) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) SignatureBindings.FPDFSignatureObj_GetReason.invokeExact(sig,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (needed <= 2) return Optional.empty();

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) SignatureBindings.FPDFSignatureObj_GetReason.invokeExact(sig, buf, needed);
            } catch (Throwable t) { throw new RuntimeException(t); }
            return Optional.of(FfmHelper.fromWideString(buf, needed));
        }
    }

    private static Optional<String> getTime(MemorySegment sig) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) SignatureBindings.FPDFSignatureObj_GetTime.invokeExact(sig,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (needed <= 1) return Optional.empty();

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) SignatureBindings.FPDFSignatureObj_GetTime.invokeExact(sig, buf, needed);
            } catch (Throwable t) { throw new RuntimeException(t); }
            return Optional.of(FfmHelper.fromByteString(buf, needed));
        }
    }

    private static byte[] getContents(MemorySegment sig) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) SignatureBindings.FPDFSignatureObj_GetContents.invokeExact(sig,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (needed <= 0) return new byte[0];

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) SignatureBindings.FPDFSignatureObj_GetContents.invokeExact(sig, buf, needed);
            } catch (Throwable t) { throw new RuntimeException(t); }
            return buf.toArray(ValueLayout.JAVA_BYTE);
        }
    }

    private static int getPermission(MemorySegment sig) {
        try {
            return (int) SignatureBindings.FPDFSignatureObj_GetDocMDPPermission.invokeExact(sig);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }
}
