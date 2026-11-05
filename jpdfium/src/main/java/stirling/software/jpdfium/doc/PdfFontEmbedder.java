package stirling.software.jpdfium.doc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.PodofoLib;

/**
 * Replace existing font program streams when the caller already has matching
 * font bytes.
 *
 * <p>This is deliberately not a font-substitution engine: it does not search
 * for a font, change encodings, widths, CID maps or Type3 procedures, and it
 * cannot invent a licensed substitute. Callers must key the data by the
 * document's existing {@code /BaseFont} and supply a program that matches the
 * font dictionary. A build without PoDoFo rejects the request instead of
 * silently returning an unembedded document.
 */
public final class PdfFontEmbedder {

    private PdfFontEmbedder() {}

    /** True when this native build can replace existing font streams. */
    public static boolean isAvailable() {
        return PodofoLib.isAvailable();
    }

    /**
     * Replace font streams by {@code /BaseFont}.
     *
     * @param pdf   source document bytes
     * @param fonts font program bytes keyed by the exact BaseFont name
     * @return rewritten PDF
     * @throws JPDFiumException when PoDoFo is absent, a font is not found, or
     *                          the replacement cannot be written
     */
    public static byte[] embed(byte[] pdf, Map<String, byte[]> fonts) {
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("pdf must not be empty");
        }
        if (fonts == null || fonts.isEmpty()) return pdf;
        if (!PodofoLib.isAvailable()) {
            throw new JPDFiumException("font embedding requires a build with PoDoFo linked");
        }
        byte[] current = pdf;
        for (var entry : fonts.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().length == 0) {
                throw new IllegalArgumentException("font name and data must not be empty");
            }
            current = PodofoLib.embedFontData(current, entry.getKey(), entry.getValue());
            if (current == null) {
                throw new JPDFiumException(
                        "no font descriptor or program for /BaseFont " + entry.getKey());
            }
        }
        return current;
    }

    /** File-backed variant of {@link #embed(byte[], Map)}. */
    public static byte[] embed(Path input, Map<String, byte[]> fonts) throws IOException {
        return embed(Files.readAllBytes(input), fonts);
    }
}
