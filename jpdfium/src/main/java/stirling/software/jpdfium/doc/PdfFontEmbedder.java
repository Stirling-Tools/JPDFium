package stirling.software.jpdfium.doc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.QpdfLib;

/**
 * Replace existing font program streams when the caller already has matching
 * font bytes.
 *
 * <p>This is deliberately not a font-substitution engine: it does not search
 * for a font, change encodings, widths, CID maps or Type3 procedures, and it
 * cannot invent a licensed substitute. Callers must key the data by the
 * document's {@code /BaseFont} and supply a program that matches the font
 * dictionary. A subset prefix such as {@code ABCDEF+} is ignored while matching.
 *
 * <p>The replacement is written through qpdf, preserves the existing
 * {@code /FontFile}, {@code /FontFile2} or {@code /FontFile3} stream type, and
 * updates {@code /Length1} for TrueType programs. When no matching font
 * descriptor exists, the call fails instead of returning a silently unchanged
 * PDF.
 */
public final class PdfFontEmbedder {

    private PdfFontEmbedder() {}

    /** True when this native build can replace existing font streams. */
    public static boolean isAvailable() {
        return QpdfLib.isFontReplacementSupported();
    }

    /**
     * Replace font streams by {@code /BaseFont}.
     *
     * @param pdf   source document bytes
     * @param fonts font program bytes keyed by {@code /BaseFont}; the subset
     *              prefix is optional
     * @return rewritten PDF
     * @throws JPDFiumException when no matching font descriptor exists or the
     *                          replacement cannot be written
     */
    public static byte[] embed(byte[] pdf, Map<String, byte[]> fonts) {
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("pdf must not be empty");
        }
        if (fonts == null || fonts.isEmpty()) return pdf;
        if (!isAvailable()) {
            throw new JPDFiumException("font embedding requires native qpdf support");
        }
        Map<String, byte[]> normalized = new LinkedHashMap<>();
        for (var entry : fonts.entrySet()) {
            String name = entry.getKey();
            byte[] data = entry.getValue();
            if (name == null || name.isBlank() || data == null || data.length == 0) {
                throw new IllegalArgumentException("font name and data must not be empty");
            }
            String existing = normalizeName(name);
            byte[] previous = normalized.putIfAbsent(existing, data);
            if (previous != null && !Arrays.equals(previous, data)) {
                throw new IllegalArgumentException(
                        "conflicting font data for /BaseFont " + name);
            }
        }
        byte[] current = pdf;
        for (var entry : normalized.entrySet()) {
            current = QpdfLib.replaceFontStream(current, entry.getKey(), entry.getValue());
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

    private static String normalizeName(String name) {
        String trimmed = name.trim();
        return trimmed.length() > 7 && trimmed.charAt(6) == '+' ? trimmed.substring(7) : trimmed;
    }
}
