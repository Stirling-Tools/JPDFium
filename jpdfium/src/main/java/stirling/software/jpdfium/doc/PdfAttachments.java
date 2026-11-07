package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.FfmHelper.Scratch;
import stirling.software.jpdfium.panama.AttachmentBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import stirling.software.jpdfium.exception.JPDFiumException;

/**
 * Manage embedded file attachments in a PDF document.
 *
 * <p>Supports listing, reading, adding, and deleting attachments.
 *
 * <pre>{@code
 * try (var doc = PdfDocument.open(Path.of("with-attachments.pdf"))) {
 *     MemorySegment rawDoc = JpdfiumLib.docRawHandle(doc.nativeHandle());
 *     List<Attachment> atts = PdfAttachments.list(rawDoc);
 *     for (Attachment a : atts) {
 *         System.out.printf("  %s (%d bytes)%n", a.name(), a.data().length);
 *     }
 * }
 * }</pre>
 */
public final class PdfAttachments {

    private PdfAttachments() {}

    /**
     * Returns the number of attachments in the document.
     */
    public static int count(MemorySegment rawDocSegment) {
        if (AttachmentBindings.FPDFDoc_GetAttachmentCount == null) {
            return 0;
        }
        try {
            return (int) AttachmentBindings.FPDFDoc_GetAttachmentCount.invokeExact(rawDocSegment);
        } catch (Throwable t) {
            throw new JPDFiumException("FPDFDoc_GetAttachmentCount failed", t);
        }
    }

    /**
     * List all attachments in the document.
     *
     * @param rawDocSegment raw FPDF_DOCUMENT segment
     * @return all attachments with name and file data
     */
    public static List<Attachment> list(MemorySegment rawDocSegment) {
        int attachmentCount = count(rawDocSegment);
        if (attachmentCount <= 0) return Collections.emptyList();

        List<Attachment> result = new ArrayList<>(attachmentCount);
        for (int i = 0; i < attachmentCount; i++) {
            result.add(get(rawDocSegment, i));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Get a specific attachment by index.
     *
     * @param rawDocSegment raw FPDF_DOCUMENT segment
     * @param index         0-based attachment index
     * @return the attachment with name and data
     */
    public static Attachment get(MemorySegment rawDocSegment, int index) {
        MemorySegment attachmentSegment;
        try {
            attachmentSegment = (MemorySegment) AttachmentBindings.FPDFDoc_GetAttachment.invokeExact(rawDocSegment, index);
        } catch (Throwable t) {
            throw new JPDFiumException("FPDFDoc_GetAttachment failed", t);
        }

        if (attachmentSegment.equals(MemorySegment.NULL)) {
            throw new IndexOutOfBoundsException("Attachment index " + index + " not found");
        }

        String name = getAttachmentName(attachmentSegment);
        byte[] data = getAttachmentFile(attachmentSegment);
        return new Attachment(index, name, data);
    }

    /**
     * Add a new attachment to the document.
     *
     * @param rawDocSegment raw FPDF_DOCUMENT segment
     * @param name          filename for the attachment
     * @param contents      the file content
     * @return true if the attachment was successfully added
     */
    public static boolean add(MemorySegment rawDocSegment, String name, byte[] contents) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment wideNameSegment = FfmHelper.toWideString(arena, name);
            MemorySegment attachmentSegment;
            try {
                attachmentSegment = (MemorySegment) AttachmentBindings.FPDFDoc_AddAttachment.invokeExact(rawDocSegment, wideNameSegment);
            } catch (Throwable t) {
                throw new JPDFiumException("FPDFDoc_AddAttachment failed", t);
            }

            if (attachmentSegment.equals(MemorySegment.NULL)) {
                return false;
            }

            MemorySegment dataBufferSegment = arena.allocate(contents.length);
            dataBufferSegment.copyFrom(MemorySegment.ofArray(contents));

            int success;
            try {
                success = (int) AttachmentBindings.FPDFAttachment_SetFile.invokeExact(
                        attachmentSegment, rawDocSegment, dataBufferSegment, (long) contents.length);
            } catch (Throwable t) {
                throw new JPDFiumException("FPDFAttachment_SetFile failed", t);
            }
            return success != 0;
        }
    }

    /**
     * Delete an attachment by index.
     *
     * @param rawDocSegment raw FPDF_DOCUMENT segment
     * @param index         0-based attachment index
     * @return true if the attachment was successfully deleted
     */
    public static boolean delete(MemorySegment rawDocSegment, int index) {
        try {
            int success = (int) AttachmentBindings.FPDFDoc_DeleteAttachment.invokeExact(rawDocSegment, index);
            return success != 0;
        } catch (Throwable t) {
            throw new JPDFiumException("FPDFDoc_DeleteAttachment failed", t);
        }
    }

    private static String getAttachmentName(MemorySegment attachmentSegment) {
        Scratch scratch = FfmHelper.scratch();
        MemorySegment buf = scratch.buffer();
        long cap = scratch.bufferSize();
        long needed;
        try {
            needed = (long) AttachmentBindings.FPDFAttachment_GetName.invokeExact(attachmentSegment, buf, cap);
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
        if (needed <= 2) return "";
        if (needed > cap) {
            MemorySegment largeBuf = scratch.ensureNativeBuffer(needed);
            long written;
            try {
                written = (long) AttachmentBindings.FPDFAttachment_GetName.invokeExact(attachmentSegment, largeBuf, needed);
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
            return scratch.decodeWideString(largeBuf, written);
        }
        return scratch.decodeWideString(needed);
    }

    private static final byte[] EMPTY_BYTES = new byte[0];

    private static byte[] getAttachmentFile(MemorySegment attachmentSegment) {
        Scratch scratch = FfmHelper.scratch();
        int success;
        try {
            success = (int) AttachmentBindings.FPDFAttachment_GetFile.invokeExact(attachmentSegment,
                    MemorySegment.NULL, 0L, scratch.long0);
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }

        long len = scratch.long0.get(ValueLayout.JAVA_LONG, 0);
        if (success == 0 || len <= 0) return EMPTY_BYTES;

        MemorySegment bufferSegment = scratch.ensureNativeBuffer(len);
        try {
            success = (int) AttachmentBindings.FPDFAttachment_GetFile.invokeExact(attachmentSegment,
                    bufferSegment, len, scratch.long0);
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        }
        if (success == 0) return EMPTY_BYTES;
        // Two-call contract: the second call reports the bytes actually written, which may be
        // shorter than the probed length; validate it against the buffer before slicing.
        long actualLen = scratch.long0.get(ValueLayout.JAVA_LONG, 0);
        int checked = FfmHelper.checkedNativeLength(actualLen, bufferSegment.byteSize(),
                "attachment file");
        if (checked == 0) return EMPTY_BYTES;
        byte[] result = new byte[checked];
        MemorySegment.copy(bufferSegment, ValueLayout.JAVA_BYTE, 0, result, 0, checked);
        return result;
    }
}
