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
     * @param doc document to modify
     * @param hf  header/footer configuration
     */
    public static void apply(PdfDocument doc, HeaderFooter hf) {
        int totalPages = doc.pageCount();

        for (int i = 0; i < totalPages; i++) {
            try (PdfPage page = doc.page(i)) {
                MemorySegment rawDoc = doc.rawHandle();
                MemorySegment rawPage = page.rawHandle();
                PageSize size = page.size();

                if (hf.header() != null) {
                    String text = expandTemplate(hf.header(), i + 1, totalPages);
                    addText(rawDoc, rawPage, text, hf,
                            size.width() / 2f, size.height() - hf.margin());
                }

                if (hf.footer() != null) {
                    String text = expandTemplate(hf.footer(), i + 1, totalPages);
                    addText(rawDoc, rawPage, text, hf,
                            size.width() / 2f, hf.margin() - hf.fontSize());
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
        HeaderFooter hf = HeaderFooter.builder()
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

                addText(rawDoc, rawPage, batesNum, hf,
                        size.width() / 2f, hf.margin() - hf.fontSize());
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
                                 String text, HeaderFooter hf,
                                 float centerX, float y) {
        MemorySegment textObj = PdfPageEditor.createTextObject(rawDoc, hf.fontName().fontName(), hf.fontSize());
        PdfPageEditor.setText(textObj, text);

        int a = (hf.argbColor() >> 24) & 0xFF;
        int r = (hf.argbColor() >> 16) & 0xFF;
        int g = (hf.argbColor() >> 8) & 0xFF;
        int b = hf.argbColor() & 0xFF;
        PdfPageEditor.setFillColor(textObj, r, g, b, a);

        float estWidth = text.length() * hf.fontSize() * 0.45f;
        float x = centerX - estWidth / 2f;

        PdfPageEditor.transform(textObj, 1, 0, 0, 1, x, y);
        PdfPageEditor.insertObject(rawPage, textObj);
        PdfPageEditor.generateContent(rawPage);
    }
}
