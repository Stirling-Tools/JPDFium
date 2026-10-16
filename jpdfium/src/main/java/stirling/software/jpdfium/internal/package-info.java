/**
 * Types that cross the public API but are not meant to be implemented.
 *
 * <p>{@link stirling.software.jpdfium.internal.RenderedPageView} holds a
 * rasterised page returned by {@link
 * stirling.software.jpdfium.panama.JpdfiumLib#renderPageView(long, int)}, and
 * {@link stirling.software.jpdfium.internal.PixelFormat} names the raw frame
 * layout inside it. The package is exported only so callers and codec
 * implementations can consume those results.
 */
package stirling.software.jpdfium.internal;
