package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.PdfVersion;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.OutputTransaction;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import stirling.software.jpdfium.exception.JPDFiumException;

/**
 * Save a PDF document with a specific version number.
 *
 * <p>Uses FPDF_SaveWithVersion with an FFM upcall-based FPDF_FILEWRITE callback
 * to write the document with the specified PDF version header.
 */
public final class PdfVersionConverter {

    private PdfVersionConverter() {}

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
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
        }
        return PdfVersion.V1_7;
    }

    /**
     * Save the document with a specific PDF version, streaming to a staging file and publishing
     * only after the native save succeeds.
     *
     * @param rawDoc  raw FPDF_DOCUMENT
     * @param version desired PDF version
     * @param path    output file path
     */
    public static void saveWithVersion(MemorySegment rawDoc, PdfVersion version, Path path) {
        try (OutputTransaction tx = OutputTransaction.begin(path);
                OutputStream out = new BufferedOutputStream(
                        Files.newOutputStream(tx.staging()))) {
            saveWithVersionToStream(rawDoc, version, out);
            out.flush();
            tx.publish(null);
        } catch (IOException e) {
            throw new JPDFiumException("Failed to write PDF to " + path, e);
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
        try {
            saveWithVersionToStream(rawDoc, version, baos);
        } catch (IOException e) {
            throw new JPDFiumException("FPDF_SaveWithVersion failed", e);
        }
        return baos.toByteArray();
    }

    /**
     * Save the document with a specific PDF version to a caller-supplied sink, writing blocks as
     * they arrive so the document is never materialized on the heap.
     */
    public static void saveWithVersionToStream(MemorySegment rawDoc, PdfVersion version,
            OutputStream out) throws IOException {
        FileWriteSink.begin(out);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fileWrite = FileWriteSink.allocateStruct(arena);

            int ok;
            try {
                ok = (int) DocBindings.FPDF_SaveWithVersion.invokeExact(
                        rawDoc, fileWrite, 0, version.code());
            } catch (Throwable t) {
                throw new JPDFiumException("FPDF_SaveWithVersion failed", t);
            }
            // Surface the sink's own failure before the generic status check so
            // the root cause (full disk, closed stream) is not lost.
            IOException sinkFailure = FileWriteSink.failure();
            if (sinkFailure != null) {
                throw sinkFailure;
            }
            if (ok == 0) {
                throw new JPDFiumException("FPDF_SaveWithVersion returned failure");
            }
        } finally {
            FileWriteSink.end();
        }
    }
}
