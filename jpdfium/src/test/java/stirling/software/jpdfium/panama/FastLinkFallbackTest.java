package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against the retry-on-throw fallback that used to wrap every fast
 * binding: a failing direct handle was caught, and the same operation was then
 * re-invoked through {@link JpdfiumH}, so an ordinary native error ran the
 * operation twice and reported the second outcome.
 *
 * <p>The defect was invisible for queries (a repeated getter is harmless) and
 * dangerous for cleanup and mutation ({@code docClose} could free native state
 * twice). The fix selects the binding <em>before</em> invoking, so a failure
 * surfaces as the operation's own outcome and never triggers a second call.
 */
class FastLinkFallbackTest {

    @AfterEach
    void restoreAndAssertClean() {
        if (PdfiumRuntime.state() != PdfiumRuntime.State.RUNNING) {
            PdfiumRuntime.restoreRunningForTests();
        }
        assertEquals(0, PdfiumRuntime.entryDepth(), "leaked domain entry");
        assertFalse(PdfiumRuntime.domainLocked(), "leaked the domain lock");
    }

    private static byte[] pdf() throws Exception {
        return Objects.requireNonNull(FastLinkFallbackTest.class.getResourceAsStream(
                "/pdfs/general/minimal.pdf")).readAllBytes();
    }

    @Test
    void fastBindingsArePresentSoThePathsUnderTestActuallyRun() throws Exception {
        assertNotNull(FastLinks.DOC_PAGE_COUNT, "direct handle missing");
        assertNotNull(FastLinks.PAGE_WIDTH, "direct handle missing");
        assertNotNull(FastLinks.PAGE_HEIGHT, "direct handle missing");
        assertNotNull(FastLinks.DOC_CLOSE, "direct handle missing");
        assertNotNull(FastLinks.PAGE_CLOSE, "direct handle missing");
        assertNotNull(FastLinks.PAGE_FLATTEN, "direct handle missing");
    }

    @Test
    void geometryQueriesUseTheFastBindingWithoutRetrying() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdf());
             PdfPage page = doc.page(0)) {
            // The real page must answer correctly, proving the fast path works.
            assertEquals(page.size().width(), JpdfiumLib.pageWidth(page.nativeHandle()));
            // A wrong-document error must surface as itself, not be retried and
            // then reported as a different failure.
            JPDFiumException bad = assertThrows(JPDFiumException.class,
                    () -> JpdfiumLib.pageWidth(12345L));
            assertTrue(bad.getMessage().contains("pageWidth"),
                    "failure must name its own operation: " + bad.getMessage());
            assertFalse(bad.getMessage().contains("fast-path invocation failed"),
                    "an ordinary native error must not be reported as a binding failure");
        }
    }

    @Test
    void pageCountErrorIsNotRetriedThroughTheSlowBinding() {
        JPDFiumException bad = assertThrows(JPDFiumException.class,
                () -> JpdfiumLib.docPageCount(12345L));
        assertTrue(bad.getMessage().contains("docPageCount"),
                "failure must name its own operation: " + bad.getMessage());
        assertFalse(bad.getMessage().contains("fast-path invocation failed"),
                "an ordinary native error must not be reported as a binding failure");
    }

    @Test
    void doubleCloseStillDoesNotFreeNativeStateTwice() throws Exception {
        PdfDocument doc = PdfDocument.open(pdf());
        PdfPage page = doc.page(0);
        assertEquals(3, doc.pageCount());
        page.close();
        // Second close is a no-op at the wrapper layer, so the native handle is
        // never freed twice and the accounting is decremented exactly once.
        // (Driving the raw native close on a live handle would skip the
        // runtime's counters, so that is covered separately, with never-issued
        // handles only.)
        page.close();
        assertThrows(IllegalStateException.class, page::size);
        doc.close();
        doc.close();
        assertEquals(0, PdfiumRuntime.liveResources().pages(),
                "page accounting must not underflow on a repeated close");
        assertEquals(0, PdfiumRuntime.liveResources().documents(),
                "document accounting must not underflow on a repeated close");
    }

    @Test
    void fabricatedHandlesAreRejectedNotDereferenced() throws Exception {
        // decodeDoc/decodePage now validate against a registry of issued
        // handles before any dereference, so a fabricated address surfaces as
        // JPDFIUM_ERR_INVALID instead of a segfault.
        for (long bogus : new long[]{0L, 1L, 12345L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            JPDFiumException pageErr = assertThrows(JPDFiumException.class,
                    () -> JpdfiumLib.pageWidth(bogus), "page handle " + bogus);
            assertTrue(pageErr.getMessage().contains("pageWidth"), pageErr.getMessage());
            assertThrows(JPDFiumException.class, () -> JpdfiumLib.docPageCount(bogus),
                    "doc handle " + bogus);
        }
    }

    @Test
    void neverIssuedHandlesAreRejectedSilently() {
        // A handle this bridge never issued is refused by the native registry,
        // so close is a silent no-op: it destroys nothing and reports nothing,
        // because there is no native object corresponding to it.
        //
        // These are raw JpdfiumH calls on purpose. The runtime's live-resource
        // counters live in the JpdfiumLib wrappers, so driving the raw symbols
        // is what proves the native layer alone refuses the handle.
        JpdfiumH.jpdfium_page_close(0L);
        JpdfiumH.jpdfium_page_close(999999L);
        JpdfiumH.jpdfium_page_close(-1L);
        JpdfiumH.jpdfium_doc_close(0L);
        JpdfiumH.jpdfium_doc_close(999999L);
        JpdfiumH.jpdfium_doc_close(-1L);
        JpdfiumH.jpdfium_pcre2_free(12345L);
        JpdfiumH.jpdfium_flashtext_free(12345L);
        assertEquals(0, PdfiumRuntime.liveResources().pages(),
                "rejected closes must not change live-resource accounting");
        assertEquals(0, PdfiumRuntime.liveResources().documents());
        assertEquals(0, PdfiumRuntime.entryDepth());
        assertFalse(PdfiumRuntime.domainLocked());
    }

    @Test
    void flattenFailureDoesNotApplyTheMutationTwice() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdf());
             PdfPage page = doc.page(0)) {
            // A fabricated handle must fail once and leave the document and the
            // real page untouched.
            assertThrows(JPDFiumException.class, () -> JpdfiumLib.pageFlatten(12345L));
            assertEquals(3, doc.pageCount(), "document must be unaffected");
            assertTrue(page.size().width() > 0, "page must still be usable");
        }
        // Every page opened here is retired by the try-with-resources above;
        // a leaked page would break later tests' lifecycle restore.
        assertEquals(0, PdfiumRuntime.liveResources().pages(),
                "this test must not leak a page registration");
        assertEquals(0, PdfiumRuntime.liveResources().documents());
    }
}
