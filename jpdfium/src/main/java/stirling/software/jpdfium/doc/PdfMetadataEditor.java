package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.OutputTransaction;
import stirling.software.jpdfium.panama.QpdfLib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Lossless {@code /Info} editing through qpdf. Values are written as UTF-16
 * strings; XMP metadata is untouched.
 */
public final class PdfMetadataEditor {

    /** Standard {@code /Title} key. */
    public static final String TITLE = "Title";
    /** Standard {@code /Author} key. */
    public static final String AUTHOR = "Author";
    /** Standard {@code /Subject} key. */
    public static final String SUBJECT = "Subject";
    /** Standard {@code /Keywords} key. */
    public static final String KEYWORDS = "Keywords";
    /** Standard {@code /Creator} key. */
    public static final String CREATOR = "Creator";
    /** Standard {@code /Producer} key. */
    public static final String PRODUCER = "Producer";
    /** Standard {@code /CreationDate} key. */
    public static final String CREATION_DATE = "CreationDate";
    /** Standard {@code /ModDate} key. */
    public static final String MOD_DATE = "ModDate";

    private PdfMetadataEditor() {}

    /** True when the native /Info editing entry points are available. */
    public static boolean isSupported() {
        return QpdfLib.isSetDocInfoSupported();
    }

    /** Update or remove {@code /Info} entries; a {@code null} value removes a key. */
    public static byte[] update(byte[] input, Map<String, String> fields) {
        return QpdfLib.setDocInfo(input, fields, null);
    }

    /** Update or remove {@code /Info} entries in an encrypted document. */
    public static byte[] update(byte[] input, Map<String, String> fields, String password) {
        return QpdfLib.setDocInfo(input, fields, password);
    }

    /** Update or remove {@code /Info} entries file-to-file. */
    public static void update(Path input, Path output, Map<String, String> fields)
            throws IOException {
        update(input, output, fields, null);
    }

    /** Update or remove {@code /Info} entries file-to-file with a password. */
    public static void update(Path input, Path output, Map<String, String> fields,
            String password) throws IOException {
        if (QpdfLib.setDocInfoToFile(input, output, fields, password)) {
            return;
        }
        byte[] result = update(Files.readAllBytes(input), fields, password);
        if (result == null) {
            throw new JPDFiumException("qpdf metadata update produced no output");
        }
        try (OutputTransaction tx = OutputTransaction.begin(output)) {
            Files.write(tx.staging(), result);
            tx.publish(null);
        }
    }
}
