package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.fonts.FontNormalizer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional, caller-driven font subsetting for text-heavy documents.
 *
 * <p>This is deliberately not an automatic compressor pass. The caller must
 * supply the original font program and every Unicode codepoint still referenced
 * by the document's content streams. Subsetting uses HarfBuzz with retained
 * glyph IDs, so existing character codes keep resolving to the same glyphs.
 * Supplying an incomplete codepoint set can remove a glyph that the page still
 * draws; that is why no heuristic extraction is performed here.
 *
 * <p>The rewritten program is installed through {@link PdfFontEmbedder}, keyed
 * by the exact {@code /BaseFont} name (including a subset prefix when present).
 * A font is only replaced when the subset is strictly smaller.
 */
public final class PdfFontSubsetter {

    private PdfFontSubsetter() {}

    /**
     * A font program and the Unicode codepoints to retain.
     *
     * @param fontProgram original embedded TrueType/CFF bytes
     * @param codepoints  every Unicode codepoint still used by the document
     */
    public record Request(byte[] fontProgram, int[] codepoints) {}

    /**
     * Subset supplied fonts and replace their streams by exact {@code /BaseFont}.
     *
     * @param pdf   source document bytes
     * @param fonts font program and used codepoints keyed by exact {@code /BaseFont}
     * @return rewritten PDF; the input when no font produced a smaller subset
     * @throws IllegalArgumentException for empty input, names, programs or codepoint sets
     * @throws stirling.software.jpdfium.exception.JPDFiumException when native
     *         qpdf font replacement is unavailable
     */
    public static byte[] subset(byte[] pdf, Map<String, Request> fonts) {
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("pdf must not be empty");
        }
        if (fonts == null || fonts.isEmpty()) {
            return pdf;
        }
        Map<String, byte[]> subsets = new LinkedHashMap<>();
        for (Map.Entry<String, Request> entry : fonts.entrySet()) {
            String baseFont = entry.getKey();
            Request request = entry.getValue();
            String normalizedName = baseFont == null ? "" : baseFont.trim();
            if (normalizedName.startsWith("/")) normalizedName = normalizedName.substring(1);
            if (baseFont == null || normalizedName.isBlank() || request == null
                    || request.fontProgram() == null || request.fontProgram().length == 0
                    || request.codepoints() == null || request.codepoints().length == 0) {
                throw new IllegalArgumentException(
                        "font name, program and codepoints must not be empty");
            }
            byte[] subset;
            try {
                subset = FontNormalizer.subset(
                        request.fontProgram(), request.codepoints(), true);
            } catch (RuntimeException unsupportedFormat) {
                String message = unsupportedFormat.getMessage();
                if (message != null && message.contains("runtime is")) {
                    throw unsupportedFormat; // admission rejection is not an unsupported font
                }
                // Type1/CFF programs that HarfBuzz cannot subset are left untouched.
                continue;
            }
            if (subset != null && subset.length < request.fontProgram().length) {
                subsets.put(baseFont, subset);
            }
        }
        if (subsets.isEmpty()) return pdf;
        byte[] rewritten = PdfFontEmbedder.replace(pdf, subsets);
        return rewritten.length <= pdf.length ? rewritten : pdf;
    }
}
