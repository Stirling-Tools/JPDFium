package stirling.software.jpdfium;

import stirling.software.jpdfium.model.FontName;
import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.model.PageSize;

import java.lang.foreign.MemorySegment;
import java.time.LocalDate;

/**
 * Apply headers, footers, and Bates numbering to PDF documents.
 *
 * <pre>{@code
 * // Header and footer
 * HeaderFooter hf = HeaderFooter.builder()
 *     .footer("{page} of {pages}")
 *     .header("Case No. 2025-CV-1234")
 *     .font(FontName.HELVETICA).size(9)
 *     .margin(36)
 *     .build();
 * HeaderFooterApplier.apply(doc, hf);
 *
 * // Bates numbering
 * HeaderFooterApplier.applyBatesNumbering(doc, "ABC", 1, 6);
 * // produces: ABC000001, ABC000002, ...
 * }</pre>
 */
public final class HeaderFooterApplier {

    private HeaderFooterApplier() {}

    /**
     * Apply header and/or footer text to all pages.
     *
     * @param doc                document to modify
     * @param headerFooterConfig header/footer configuration
     */
    public static void apply(PdfDocument doc, HeaderFooter headerFooterConfig) {
        int totalPages = doc.pageCount();

        for (int i = 0; i < totalPages; i++) {
            try (PdfPage page = doc.page(i)) {
                MemorySegment rawDoc = doc.rawHandle();
                MemorySegment rawPage = page.rawHandle();
                PageSize size = page.size();

                if (headerFooterConfig.header() != null) {
                    String text = expandTemplate(headerFooterConfig.header(), i + 1, totalPages);
                    addText(rawDoc, rawPage, text, headerFooterConfig,
                            size.width() / 2f, size.height() - headerFooterConfig.margin());
                }

                if (headerFooterConfig.footer() != null) {
                    String text = expandTemplate(headerFooterConfig.footer(), i + 1, totalPages);
                    addText(rawDoc, rawPage, text, headerFooterConfig,
                            size.width() / 2f, headerFooterConfig.margin() - headerFooterConfig.fontSize());
                }
            }
        }
    }

    /**
     * Apply Bates numbering as a footer on all pages.
     *
     * @param doc        document to modify
     * @param prefix     prefix string (e.g. "ABC")
     * @param startNum   starting number (e.g. 1)
     * @param numDigits  number of digits to pad (e.g. 6 -> ABC000001)
     */
    public static void applyBatesNumbering(PdfDocument doc, String prefix,
                                            int startNum, int numDigits) {
        HeaderFooter headerFooterConfig = HeaderFooter.builder()
                .footer("placeholder")
                .font(FontName.COURIER).size(8)
                .margin(36)
                .build();

        int totalPages = doc.pageCount();
        for (int i = 0; i < totalPages; i++) {
            String batesNum = prefix + String.format("%0" + numDigits + "d", startNum + i);

            try (PdfPage page = doc.page(i)) {
                MemorySegment rawDoc = doc.rawHandle();
                MemorySegment rawPage = page.rawHandle();
                PageSize size = page.size();

                addText(rawDoc, rawPage, batesNum, headerFooterConfig,
                        size.width() / 2f, headerFooterConfig.margin() - headerFooterConfig.fontSize());
            }
        }
    }

    private static String expandTemplate(String template, int pageNum, int totalPages) {
        return template
                .replace("{page}", String.valueOf(pageNum))
                .replace("{pages}", String.valueOf(totalPages))
                .replace("{date}", LocalDate.now().toString());
    }

    private static void addText(MemorySegment rawDoc, MemorySegment rawPage,
                                 String text, HeaderFooter headerFooterConfig,
                                 float centerX, float y) {
        MemorySegment textObject = PdfPageEditor.createTextObject(
                rawDoc, headerFooterConfig.fontName().fontName(), headerFooterConfig.fontSize());
        PdfPageEditor.setText(textObject, text);

        int argb = headerFooterConfig.argbColor();
        int alpha = (argb >> 24) & 0xFF;
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        PdfPageEditor.setFillColor(textObject, red, green, blue, alpha);

        float estWidth = text.length() * headerFooterConfig.fontSize() * 0.45f;
        float x = centerX - estWidth / 2f;

        PdfPageEditor.transform(textObject, 1, 0, 0, 1, x, y);
        PdfPageEditor.insertObject(rawPage, textObject);
        PdfPageEditor.generateContent(rawPage);
    }
}
