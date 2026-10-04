package stirling.software.jpdfium.doc;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.FormFillException;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.FormFillBindings;
import stirling.software.jpdfium.panama.JpdfiumH;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.panama.PdfiumRuntime;
import stirling.software.jpdfium.panama.RenderBindings;

/**
 * Renders pages with their form widgets drawn on top.
 *
 * <p>{@link PdfPage#renderAt(int)} draws the page content and annotation appearance
 * streams, but widgets without an appearance stream (for example a form with
 * {@code /NeedAppearances true} that was never saved by a viewer) come out blank.
 * This renderer draws the page and then calls {@code FPDF_FFLDraw} with a form
 * fill environment, which builds and paints those widget appearances.
 *
 * <pre>{@code
 * try (var doc = PdfDocument.open(Path.of("form.pdf"))) {
 *     RenderResult page = PdfFormRenderer.renderPage(doc, 0, 150);
 * }
 * }</pre>
 */
public final class PdfFormRenderer {

    private PdfFormRenderer() {}

    /** Render flags: annotations plus RGBA byte order (matches PdfPage.renderInto). */
    private static final int RENDER_FLAGS =
            RenderBindings.FPDF_REVERSE_BYTE_ORDER | RenderBindings.FPDF_ANNOT;

    /**
     * Render one page with its form widgets drawn on top, at the given DPI.
     *
     * @param document  open document
     * @param pageIndex zero-based page index
     * @param dpi       render resolution (150 = good quality, 300 = high quality)
     * @return the page as straight RGBA bytes plus its pixel dimensions
     * @throws FormFillException if the form environment or the widget draw fails
     */
    public static RenderResult renderPage(PdfDocument document, int pageIndex, int dpi) {
        if (dpi <= 0) {
            throw new IllegalArgumentException("dpi must be > 0, got " + dpi);
        }
        if (FormFillBindings.FPDF_FFLDraw == null) {
            throw new FormFillException("FPDF_FFLDraw is not available in this native build");
        }
        // The form environment and page must stay on one thread for the whole
        // open/render/draw/teardown sequence: PDFium form state is process-wide.
        return PdfiumRuntime.execute(() -> {
            MemorySegment rawDoc = document.rawHandle();
            PdfFormFiller.FormEnv env = PdfFormFiller.initFormEnvironment(rawDoc);
            try (PdfPage page = document.page(pageIndex)) {
                MemorySegment rawPage = page.rawHandle();
                PageSize size = page.size();
                int width = Math.max(1, (int) Math.round(size.width() * dpi / 72.0));
                int height = Math.max(1, (int) Math.round(size.height() * dpi / 72.0));
                return render(env, rawPage, width, height);
            } finally {
                try {
                    DocBindings.FPDFDOC_ExitFormFillEnvironment.invokeExact(env.formHandle());
                } catch (Throwable ignored) {
                    // never mask a render failure while tearing the environment down
                }
                env.arena().close();
            }
        });
    }

    private static RenderResult render(PdfFormFiller.FormEnv env, MemorySegment rawPage,
                                       int width, int height) {
        // Validate the requested geometry before allocating: a rejected render
        // must not first reserve width*height*4 bytes of native memory.
        int stride = Math.multiplyExact(width, 4);
        long required = (long) stride * height;
        long maxPixels = JpdfiumLib.maxRenderPixels();
        if (maxPixels > 0 && (long) width * height > maxPixels) {
            throw new FormFillException("render size " + width + "x" + height
                    + " exceeds jpdfium.maxRenderPixels=" + maxPixels);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(required);
            try {
                FormFillBindings.FORM_OnAfterLoadPage.invokeExact(rawPage, env.formHandle());
            } catch (Throwable t) {
                throw new FormFillException("FORM_OnAfterLoadPage failed", t);
            }
            try {
                // Page + FFLDraw centralized in the bridge: Skia-aware bitmap
                // format, matrix rendering, unpremultiply, and
                // FPDF_REVERSE_BYTE_ORDER handling all live in one place.
                JpdfiumLib.checkRenderIntoArgs(buffer, width, height);
                int rc = JpdfiumH.jpdfium_render_page_form_into(
                        rawPage, env.formHandle(), buffer, buffer.byteSize(), width, height,
                        stride, RENDER_FLAGS);
                if (rc != 0) {
                    throw new FormFillException(
                            "jpdfium_render_page_form_into failed (rc=" + rc + ")");
                }
            } catch (FormFillException fe) {
                throw fe;
            } catch (Throwable t) {
                throw new FormFillException("FPDF form render failed", t);
            } finally {
                try {
                    FormFillBindings.FORM_OnBeforeClosePage.invokeExact(rawPage, env.formHandle());
                } catch (Throwable ignored) {
                    // teardown must not mask a render failure
                }
            }
            return new RenderResult(width, height, buffer.toArray(JAVA_BYTE));
        }
    }
}
