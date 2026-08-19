package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.QpdfLib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * In-process qpdf structural sanitization (FFM, no CLI).
 *
 * <p>Thin wrapper over {@link QpdfLib} for the structural scrubbing Stirling-PDF
 * used to do through the qpdf CLI: metadata/info/structure stripping, JavaScript
 * action removal, embedded-file removal, AcroForm removal, annotation flattening.
 *
 * <p>This is <b>not</b> visual redaction. Removing the underlying text of a
 * content stream is the pdfium side's job; run that first, then this pass to
 * clean up the structural copies (structure tree, annotation text, metadata)
 * redaction leaves behind.
 */
public final class PdfSanitizer {

    public static final int METADATA = 0x01;     // drop /Metadata from catalog
    public static final int INFO = 0x02;         // drop /Info trailer dict
    public static final int STRUCTURE = 0x04;     // drop /StructTreeRoot (tagged PDF)
    public static final int JAVASCRIPT = 0x08;    // drop /OpenAction, /AA, /Names/JavaScript
    public static final int ATTACHMENTS = 0x10;   // drop embedded files
    public static final int ACROFORM = 0x20;      // drop /AcroForm + widget annotations
    public static final int FLATTEN = 0x40;       // flatten annotations

    private PdfSanitizer() {}

    public static byte[] sanitize(byte[] input, int flags) {
        return QpdfLib.sanitize(input, flags);
    }

    public static byte[] sanitize(Path input, int flags) throws IOException {
        return sanitize(Files.readAllBytes(input), flags);
    }

    public static void sanitize(Path input, Path output, int flags) throws IOException {
        byte[] result = sanitize(input, flags);
        if (result == null) {
            throw new IOException("qpdf sanitization produced no output");
        }
        Files.write(output, result);
    }

    /** Check if in-process QPDF sanitization is available. */
    public static boolean isSupported() {
        return QpdfLib.isSanitizeSupported();
    }
}
