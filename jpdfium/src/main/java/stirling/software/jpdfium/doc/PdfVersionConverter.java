package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.PdfVersion;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.OutputTransaction;
import stirling.software.jpdfium.panama.QpdfLib;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

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
            forcePdf2Header(tx.staging(), version);
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
        byte[] bytes = baos.toByteArray();
        if (version == PdfVersion.V2_0 && bytes.length >= 8) {
            System.arraycopy(PDF2_HEADER, 0, bytes, 0, PDF2_HEADER.length);
        }
        return bytes;
    }

    private static final byte[] PDF2_HEADER = "%PDF-2.0".getBytes(StandardCharsets.US_ASCII);

    /**
     * PDFium's {@code FPDF_SaveWithVersion} rejects version codes above 17
     * (PDF 1.7) and keeps the source document's version, so a requested PDF 2.0
     * needs the header marker rewritten. PDF 2.0 is backwards compatible with
     * the existing object graph.
     */
    private static void forcePdf2Header(Path path, PdfVersion version) {
        if (version != PdfVersion.V2_0) return;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.seek(0);
            file.write(PDF2_HEADER);
        } catch (IOException e) {
            throw new JPDFiumException("Failed to set the PDF 2.0 header on " + path, e);
        }
    }

    /**
     * Rewrite a file with an exact PDF version and no catalog /Version override.
     * Uses qpdf's forcePDFVersion so object-stream and catalog-version rules are
     * handled by the writer rather than a header-only edit.
     */
    public static boolean forceVersion(Path input, Path output, PdfVersion version) {
        if (version == null) return false;
        return QpdfLib.forceVersionToFile(input, output, version.toString());
    }

    /**
     * Rewrite PDF bytes with an exact PDF version and no catalog /Version override.
     */
    public static byte[] forceVersion(byte[] input, PdfVersion version) {
        if (input == null || version == null) return null;
        if (!QpdfLib.isForceVersionSupported()) {
            throw new JPDFiumException("forceVersion requires native QPDF version rewrite support");
        }
        byte[] out = QpdfLib.forceVersionToBytes(input, version.toString());
        if (out == null) {
            throw new JPDFiumException("forceVersion failed to rewrite PDF bytes to version " + version);
        }
        return out;
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
