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
import stirling.software.jpdfium.exception.JPDFiumException;

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
    public static int count(MemorySegment rawDocSegment) {
        if (SignatureBindings.FPDF_GetSignatureCount == null) {
            return 0;
        }
        try {
            return (int) SignatureBindings.FPDF_GetSignatureCount.invokeExact(rawDocSegment);
        } catch (Throwable t) {
            throw new JPDFiumException("FPDF_GetSignatureCount failed", t);
        }
    }

    /**
     * List all signatures in the document.
     *
     * @param rawDocSegment raw FPDF_DOCUMENT segment
     * @return all signatures with their properties
     */
    public static List<Signature> list(MemorySegment rawDocSegment) {
        int signatureCount = count(rawDocSegment);
        if (signatureCount <= 0) return Collections.emptyList();

        List<Signature> result = new ArrayList<>(signatureCount);
        for (int i = 0; i < signatureCount; i++) {
            result.add(get(rawDocSegment, i));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Get a specific signature by index.
     *
     * @param rawDocSegment raw FPDF_DOCUMENT segment
     * @param index         0-based signature index
     * @return the signature
     */
    public static Signature get(MemorySegment rawDocSegment, int index) {
        MemorySegment signatureSegment;
        try {
            signatureSegment = (MemorySegment) SignatureBindings.FPDF_GetSignatureObject.invokeExact(rawDocSegment, index);
        } catch (Throwable t) {
            throw new JPDFiumException("FPDF_GetSignatureObject failed", t);
        }

        if (signatureSegment.equals(MemorySegment.NULL)) {
            throw new IndexOutOfBoundsException("Signature index " + index + " not found");
        }

        return new Signature(
                index,
                getSubFilter(signatureSegment),
                getReason(signatureSegment),
                getTime(signatureSegment),
                getContents(signatureSegment),
                getPermission(signatureSegment)
        );
    }

    private static Optional<String> getSubFilter(MemorySegment signatureSegment) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) SignatureBindings.FPDFSignatureObj_GetSubFilter.invokeExact(signatureSegment,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            if (needed <= 1) return Optional.empty();

            MemorySegment bufferSegment = arena.allocate(needed);
            try {
                long _ = (long) SignatureBindings.FPDFSignatureObj_GetSubFilter.invokeExact(signatureSegment, bufferSegment, needed);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            return Optional.of(FfmHelper.fromByteString(bufferSegment, needed));
        }
    }

    private static Optional<String> getReason(MemorySegment signatureSegment) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) SignatureBindings.FPDFSignatureObj_GetReason.invokeExact(signatureSegment,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            if (needed <= 2) return Optional.empty();

            MemorySegment bufferSegment = arena.allocate(needed);
            try {
                long _ = (long) SignatureBindings.FPDFSignatureObj_GetReason.invokeExact(signatureSegment, bufferSegment, needed);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            return Optional.of(FfmHelper.fromWideString(bufferSegment, needed));
        }
    }

    private static Optional<String> getTime(MemorySegment signatureSegment) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) SignatureBindings.FPDFSignatureObj_GetTime.invokeExact(signatureSegment,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            if (needed <= 1) return Optional.empty();

            MemorySegment bufferSegment = arena.allocate(needed);
            try {
                long _ = (long) SignatureBindings.FPDFSignatureObj_GetTime.invokeExact(signatureSegment, bufferSegment, needed);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            return Optional.of(FfmHelper.fromByteString(bufferSegment, needed));
        }
    }

    private static final byte[] EMPTY_BYTES = new byte[0];

    private static byte[] getContents(MemorySegment signatureSegment) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) SignatureBindings.FPDFSignatureObj_GetContents.invokeExact(signatureSegment,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            if (needed <= 0) return EMPTY_BYTES;

            MemorySegment bufferSegment = arena.allocate(needed);
            try {
                long _ = (long) SignatureBindings.FPDFSignatureObj_GetContents.invokeExact(signatureSegment, bufferSegment, needed);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            return bufferSegment.toArray(ValueLayout.JAVA_BYTE);
        }
    }

    private static int getPermission(MemorySegment signatureSegment) {
        if (SignatureBindings.FPDFSignatureObj_GetDocMDPPermission == null) {
            return 0;
        }
        try {
            return (int) SignatureBindings.FPDFSignatureObj_GetDocMDPPermission.invokeExact(signatureSegment);
        } catch (Throwable t) {
            return 0;
        }
    }
}
