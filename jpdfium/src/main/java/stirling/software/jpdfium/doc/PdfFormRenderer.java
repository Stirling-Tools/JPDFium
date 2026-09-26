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
import stirling.software.jpdfium.panama.PageEditBindings;
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

    /** Matches the 256M pixel cap used by the bridge render path. */
    private static final long MAX_PIXELS = 268435456L;

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
        if (FormFillBindings.FPDF_FFLDraw == null) {
            throw new FormFillException("FPDF_FFLDraw is not available in this native build");
        }
        MemorySegment rawDoc = document.rawHandle();
        PdfFormFiller.FormEnv env = PdfFormFiller.initFormEnvironment(rawDoc);
        try (PdfPage page = document.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            PageSize size = page.size();
            int width = Math.max(1, (int) Math.round(size.width() * dpi / 72.0));
            int height = Math.max(1, (int) Math.round(size.height() * dpi / 72.0));
            if ((long) width * height > MAX_PIXELS) {
                throw new FormFillException("render size exceeds the 256M pixel cap");
            }
            return render(env, rawPage, width, height);
        } finally {
            try {
                DocBindings.FPDFDOC_ExitFormFillEnvironment.invokeExact(env.formHandle());
            } catch (Throwable ignored) {
                // never mask a render failure while tearing the environment down
            }
            env.arena().close();
        }
    }

    private static RenderResult render(PdfFormFiller.FormEnv env, MemorySegment rawPage,
                                       int width, int height) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate((long) width * height * 4);
            MemorySegment bitmap;
            try {
                bitmap = (MemorySegment) PageEditBindings.FPDFBitmap_CreateEx.invokeExact(
                        width, height, 4, buffer, width * 4);
            } catch (Throwable t) {
                throw new FormFillException("FPDFBitmap_CreateEx failed", t);
            }
            if (MemorySegment.NULL.equals(bitmap)) {
                throw new FormFillException("FPDFBitmap_CreateEx returned NULL");
            }
            try {
                try {
                    FormFillBindings.FORM_OnAfterLoadPage.invokeExact(rawPage, env.formHandle());
                } catch (Throwable t) {
                    throw new FormFillException("FORM_OnAfterLoadPage failed", t);
                }
                try {
                    RenderBindings.FPDF_RenderPageBitmap.invokeExact(
                            bitmap, rawPage, 0, 0, width, height, 0, RENDER_FLAGS);
                } catch (Throwable t) {
                    throw new FormFillException("FPDF_RenderPageBitmap failed", t);
                }
                try {
                    FormFillBindings.FPDF_FFLDraw.invokeExact(
                            env.formHandle(), bitmap, rawPage, 0, 0, width, height, 0, RENDER_FLAGS);
                } catch (Throwable t) {
                    throw new FormFillException("FPDF_FFLDraw failed", t);
                }
                return new RenderResult(width, height, buffer.toArray(JAVA_BYTE));
            } finally {
                try {
                    FormFillBindings.FORM_OnBeforeClosePage.invokeExact(rawPage, env.formHandle());
                } catch (Throwable ignored) {
                    // teardown must not mask a render failure
                }
                try {
                    PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap);
                } catch (Throwable ignored) {
                    // ditto
                }
            }
        }
    }
}
