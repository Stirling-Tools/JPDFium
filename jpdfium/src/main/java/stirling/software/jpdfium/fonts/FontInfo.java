package stirling.software.jpdfium.fonts;

import java.util.List;

/**
 * Represents font information extracted from a PDF.
 *
 * @param name     font name (e.g., "Helvetica", "TimesNewRoman-Bold")
 * @param type     font type (e.g., "Type1", "TrueType", "CIDFontType2")
 * @param embedded whether the font data is embedded in the PDF
 */
public record FontInfo(String name, String type, boolean embedded) {

    /**
     * Check if this is a standard 14 PDF font (always available, never needs embedding).
     */
    public boolean isStandard14() {
        return STANDARD_14.stream().anyMatch(s -> name.toLowerCase().contains(s.toLowerCase()));
    }

    /** The 14 standard PDF fonts guaranteed to be available in all PDF viewers. */
    public static final List<String> STANDARD_14 = List.of(
            "Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique",
            "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique",
            "Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic",
            "Symbol", "ZapfDingbats"
    );
}
