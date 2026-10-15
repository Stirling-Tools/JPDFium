/**
 * Low-level PDFium bindings for callers who need more than the core API.
 *
 * <p>{@link stirling.software.jpdfium.panama.JpdfiumLib} wraps the
 * jextract-generated bindings and handles arena lifecycle, string conversion,
 * and result-code translation. Focused companions such as {@link
 * stirling.software.jpdfium.panama.DocBindings}, {@link
 * stirling.software.jpdfium.panama.PageEditBindings}, and {@link
 * stirling.software.jpdfium.panama.Pcre2Lib} expose individual function
 * groups. {@link stirling.software.jpdfium.panama.NativeLoader} finds and
 * loads the native library.
 *
 * <p>Most applications should use {@link stirling.software.jpdfium.PdfDocument}
 * instead.
 */
package stirling.software.jpdfium.panama;
