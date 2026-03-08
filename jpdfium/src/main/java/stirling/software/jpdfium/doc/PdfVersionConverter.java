package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.PdfVersion;
import stirling.software.jpdfium.panama.DocBindings;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.*;

/**
 * Save a PDF document with a specific version number.
 *
 * <p>Uses FPDF_SaveWithVersion with an FFM upcall-based FPDF_FILEWRITE callback
 * to write the document with the specified PDF version header.
 */
public final class PdfVersionConverter {

    private PdfVersionConverter() {}

    // FPDF_FILEWRITE struct: { int version; void* WriteBlock; }
    // On 64-bit: 4 bytes int + 4 bytes padding + 8 bytes function pointer = 16 bytes
    private static final StructLayout FPDF_FILEWRITE_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("version"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("WriteBlock")
    );

    // WriteBlock signature: int (*)(FPDF_FILEWRITE* pThis, const void* pData, unsigned long size)
    private static final FunctionDescriptor WRITE_BLOCK_DESC = FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG);

    // Thread-local buffer for the upcall to write into
    private static final ThreadLocal<ByteArrayOutputStream> WRITE_BUFFER = new ThreadLocal<>();

    /**
     * Get the current PDF file version.
     *
     * @param rawDoc raw FPDF_DOCUMENT
     * @return the PDF version, or V1_7 if unknown
     */
    public static PdfVersion getVersion(MemorySegment rawDoc) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment versionSeg = arena.allocate(JAVA_INT);
            int ok = (int) DocBindings.FPDF_GetFileVersion.invokeExact(rawDoc, versionSeg);
            if (ok != 0) {
                return PdfVersion.fromCode(versionSeg.get(JAVA_INT, 0));
            }
        } catch (Throwable ignored) {}
        return PdfVersion.V1_7;
    }

    /**
     * Save the document with a specific PDF version.
     *
     * @param rawDoc  raw FPDF_DOCUMENT
     * @param version desired PDF version
     * @param path    output file path
     */
    public static void saveWithVersion(MemorySegment rawDoc, PdfVersion version, Path path) {
        byte[] bytes = saveWithVersionToBytes(rawDoc, version);
        try {
            Files.write(path, bytes);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to write PDF to " + path, e);
        }
    }

    /**
     * Save the document with a specific PDF version to a byte array.
     *
     * @param rawDoc  raw FPDF_DOCUMENT
     * @param version desired PDF version
     * @return PDF bytes with the specified version
     */
    public static byte[] saveWithVersionToBytes(MemorySegment rawDoc, PdfVersion version) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        WRITE_BUFFER.set(baos);
        try (Arena arena = Arena.ofConfined()) {
            // Create the upcall stub for WriteBlock
            MethodHandle writeBlockMH;
            try {
                writeBlockMH = MethodHandles.lookup().findStatic(
                        PdfVersionConverter.class, "writeBlockCallback",
                        MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, long.class));
            } catch (Exception e) {
                throw new RuntimeException("Failed to create WriteBlock method handle", e);
            }

            MemorySegment writeBlockStub = Linker.nativeLinker().upcallStub(
                    writeBlockMH, WRITE_BLOCK_DESC, arena);

            // Allocate FPDF_FILEWRITE struct
            MemorySegment fileWrite = arena.allocate(FPDF_FILEWRITE_LAYOUT);
            fileWrite.set(JAVA_INT, 0, 1); // version = 1
            fileWrite.set(ADDRESS, 8, writeBlockStub); // WriteBlock function pointer

            // Call FPDF_SaveWithVersion
            int ok;
            try {
                ok = (int) DocBindings.FPDF_SaveWithVersion.invokeExact(
                        rawDoc, fileWrite, 0, version.code());
            } catch (Throwable t) {
                throw new RuntimeException("FPDF_SaveWithVersion failed", t);
            }
            if (ok == 0) {
                throw new RuntimeException("FPDF_SaveWithVersion returned failure");
            }

            return baos.toByteArray();
        } finally {
            WRITE_BUFFER.remove();
        }
    }

    /**
     * Upcall target for FPDF_FILEWRITE.WriteBlock.
     */
    @SuppressWarnings("unused")
    private static int writeBlockCallback(MemorySegment pThis, MemorySegment pData, long size) {
        ByteArrayOutputStream baos = WRITE_BUFFER.get();
        if (baos == null || size <= 0) return 0;
        byte[] data = pData.reinterpret(size).toArray(JAVA_BYTE);
        baos.write(data, 0, data.length);
        return 1;
    }
}
