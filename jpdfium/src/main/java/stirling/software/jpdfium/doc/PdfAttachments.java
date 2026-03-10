package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.AttachmentBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    public static int count(MemorySegment doc) {
        try {
            return (int) AttachmentBindings.FPDFDoc_GetAttachmentCount.invokeExact(doc);
        } catch (Throwable t) { throw new RuntimeException("FPDFDoc_GetAttachmentCount failed", t); }
    }

    /**
     * List all attachments in the document.
     *
     * @param doc raw FPDF_DOCUMENT segment
     * @return all attachments with name and file data
     */
    public static List<Attachment> list(MemorySegment doc) {
        int n = count(doc);
        if (n <= 0) return Collections.emptyList();

        List<Attachment> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(get(doc, i));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Get a specific attachment by index.
     *
     * @param doc   raw FPDF_DOCUMENT segment
     * @param index 0-based attachment index
     * @return the attachment with name and data
     */
    public static Attachment get(MemorySegment doc, int index) {
        MemorySegment att;
        try {
            att = (MemorySegment) AttachmentBindings.FPDFDoc_GetAttachment.invokeExact(doc, index);
        } catch (Throwable t) { throw new RuntimeException("FPDFDoc_GetAttachment failed", t); }

        if (att.equals(MemorySegment.NULL)) {
            throw new IndexOutOfBoundsException("Attachment index " + index + " not found");
        }

        String name = getAttachmentName(att);
        byte[] data = getAttachmentFile(att);
        return new Attachment(index, name, data);
    }

    /**
     * Add a new attachment to the document.
     *
     * @param doc      raw FPDF_DOCUMENT segment
     * @param name     filename for the attachment
     * @param contents the file content
     * @return true if the attachment was successfully added
     */
    public static boolean add(MemorySegment doc, String name, byte[] contents) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment wideName = FfmHelper.toWideString(arena, name);
            MemorySegment att;
            try {
                att = (MemorySegment) AttachmentBindings.FPDFDoc_AddAttachment.invokeExact(doc, wideName);
            } catch (Throwable t) { throw new RuntimeException("FPDFDoc_AddAttachment failed", t); }

            if (att.equals(MemorySegment.NULL)) {
                return false;
            }

            MemorySegment dataBuf = arena.allocate(contents.length);
            dataBuf.copyFrom(MemorySegment.ofArray(contents));

            int ok;
            try {
                ok = (int) AttachmentBindings.FPDFAttachment_SetFile.invokeExact(att, doc, dataBuf, (long) contents.length);
            } catch (Throwable t) { throw new RuntimeException("FPDFAttachment_SetFile failed", t); }
            return ok != 0;
        }
    }

    /**
     * Delete an attachment by index.
     *
     * @param doc   raw FPDF_DOCUMENT segment
     * @param index 0-based attachment index
     * @return true if the attachment was successfully deleted
     */
    public static boolean delete(MemorySegment doc, int index) {
        try {
            int ok = (int) AttachmentBindings.FPDFDoc_DeleteAttachment.invokeExact(doc, index);
            return ok != 0;
        } catch (Throwable t) { throw new RuntimeException("FPDFDoc_DeleteAttachment failed", t); }
    }

    private static String getAttachmentName(MemorySegment att) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) AttachmentBindings.FPDFAttachment_GetName.invokeExact(att,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (needed <= 2) return "";

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) AttachmentBindings.FPDFAttachment_GetName.invokeExact(att, buf, needed);
            } catch (Throwable t) { throw new RuntimeException(t); }
            return FfmHelper.fromWideString(buf, needed);
        }
    }

    private static final byte[] EMPTY_BYTES = new byte[0];

    private static byte[] getAttachmentFile(MemorySegment att) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_LONG);

            int ok;
            try {
                ok = (int) AttachmentBindings.FPDFAttachment_GetFile.invokeExact(att,
                        MemorySegment.NULL, 0L, outLen);
            } catch (Throwable t) { throw new RuntimeException(t); }

            long len = outLen.get(ValueLayout.JAVA_LONG, 0);
            if (ok == 0 || len <= 0) return EMPTY_BYTES;

            MemorySegment buf = arena.allocate(len);
            try {
                ok = (int) AttachmentBindings.FPDFAttachment_GetFile.invokeExact(att, buf, len, outLen);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (ok == 0) return EMPTY_BYTES;

            return buf.asSlice(0, outLen.get(ValueLayout.JAVA_LONG, 0))
                    .toArray(ValueLayout.JAVA_BYTE);
        }
    }
}
