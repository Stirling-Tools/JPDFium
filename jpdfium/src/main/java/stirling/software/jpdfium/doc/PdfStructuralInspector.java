package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.QpdfLib;

import java.io.IOException;
import java.nio.file.Path;

/**
 * qpdf-backed structural inspection. Returns JSON with page/object counts,
 * linearization, encryption, attachments, forms, signatures, and warnings.
 */
public final class PdfStructuralInspector {

    private PdfStructuralInspector() {}

    /** True when the loaded native library exposes qpdf inspection. */
    public static boolean isSupported() {
        return QpdfLib.isInspectSupported();
    }

    /** Inspect PDF bytes. */
    public static String inspect(byte[] input) {
        return QpdfLib.inspect(input);
    }

    /** Inspect PDF bytes with a password for encrypted input. */
    public static String inspect(byte[] input, String password) {
        return QpdfLib.inspect(input, password);
    }

    /** Inspect a PDF file without materializing it on the Java heap. */
    public static String inspect(Path path) throws IOException {
        return QpdfLib.inspect(path);
    }

    /** Inspect an encrypted PDF file with a password. */
    public static String inspect(Path path, String password) throws IOException {
        return QpdfLib.inspect(path, password);
    }
}
