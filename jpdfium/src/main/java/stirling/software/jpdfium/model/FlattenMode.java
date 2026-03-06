package stirling.software.jpdfium.model;

/**
 * Controls what elements are flattened when calling {@link stirling.software.jpdfium.PdfDocument#flatten}.
 *
 * <p>All modes use native PDFium via FFM - no PDFBox involved.
 */
public enum FlattenMode {

    /**
     * Flatten annotations and form fields into static page content.
     *
     * <p>Interactive elements (form field values, redaction marks, sticky notes, etc.)
     * are baked into the content stream and are no longer editable. Text remains
     * selectable and extractable.
     *
     * <p>Uses native PDFium {@code FPDFPage_Flatten} via {@code jpdfium_page_flatten}.
     */
    ANNOTATIONS,

    /**
     * Convert each page to an image-based page (full rasterization).
     *
     * <p>The entire page - text, vector graphics, annotations, form fields - is
     * rendered at the specified DPI and replaced with a single raster image.
     * After conversion, no text can be selected or extracted.
     *
     * <p>Uses native PDFium via {@code jpdfium_page_to_image}.
     *
     * <p>Requires a DPI parameter (150 = good quality, 300 = high quality).
     */
    FULL
}
