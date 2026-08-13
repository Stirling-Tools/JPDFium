package stirling.software.jpdfium.crop;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.JpdfiumLib;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zero-allocation verification for the crop-remove-content hot path.
 *
 * <p>Uses the JDK's exact per-thread allocation counter
 * ({@link ThreadMXBean#getThreadAllocatedBytes}) - not GC logs - to prove the FFM
 * wrapper around {@code jpdfium_crop_remove_content} performs no per-call heap
 * allocation. The native side manages its own memory; this test guards the Java
 * layer against boxing, varargs and array/string churn regressions.
 *
 * <p>The same budget must hold whether the crop is a no-op (nothing outside the
 * rect) or a real removal, and it must NOT scale with the number of characters on
 * the page - the Java wrapper never touches page text.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CropAllocationTest {

    /** Budget per call, in bytes. Generous enough for JIT/tracing noise, tight enough
     *  to catch any per-call boxing, String or array allocation. */
    private static final long ALLOC_BUDGET_PER_CALL = 64;
    private static final int BATCH = 1000;

    private static final ThreadMXBean TMB =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static Path pdfPath(String name) throws Exception {
        var url = CropAllocationTest.class.getResource("/pdfs/general/" + name);
        assertTrue(url != null, name + " missing from test resources");
        return Path.of(url.toURI());
    }

    @Test
    void fastPathNoOpAllocatesNothingPerCall() throws Exception {
        // Crop to the full page: nothing is outside, native fast-paths out.
        try (PdfDocument doc = PdfDocument.open(pdfPath("minimal.pdf"));
             PdfPage page = doc.page(0)) {
            float w = page.size().width();
            float h = page.size().height();
            assertStableAllocation(page, 0, 0, w, h, "fast-path full-page crop");
        }
    }

    @Test
    void realCropAllocatesNothingPerCallOnSmallPage() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfPath("minimal.pdf"));
             PdfPage page = doc.page(0)) {
            // Left-half crop: real removal of the right-half text.
            assertStableAllocation(page, 0, 0, page.size().width() / 2, page.size().height(),
                    "real left-half crop (small page)");
        }
    }

    @Test
    void realCropAllocationDoesNotScaleWithPageContent() throws Exception {
        // A much larger page (IRS form ~5k chars) must allocate no more per call
        // than the tiny page above - the wrapper is content-independent.
        try (PdfDocument doc = PdfDocument.open(pdfPath("irs_f1040.pdf"));
             PdfPage page = doc.page(1)) {
            long perCall = measurePerCall(page, 0, 0, page.size().width() / 2, page.size().height(),
                    BATCH);
            assertTrue(perCall < ALLOC_BUDGET_PER_CALL,
                    "large-page crop allocated " + perCall + " bytes/call");
        }
    }

    /**
     * Warmed-up measurement: returns allocated bytes per call averaged over {@link #BATCH}
     * iterations, after discarding warmup (class loading + JIT).
     */
    private long measurePerCall(PdfPage page, float x, float y, float w, float h, int batch) {
        long nativeHandle = page.nativeHandle();
        // Warmup: JIT + any one-time class-init/tracing.
        for (int i = 0; i < 200; i++) {
            JpdfiumLib.cropRemoveContent(nativeHandle, x, y, w, h);
        }
        long baseline = allocatedBytes();
        for (int i = 0; i < batch; i++) {
            JpdfiumLib.cropRemoveContent(nativeHandle, x, y, w, h);
        }
        long delta = allocatedBytes() - baseline;
        return (long) Math.ceil((double) delta / batch);
    }

    private void assertStableAllocation(PdfPage page, float x, float y, float w, float h,
                                        String ctx) {
        long perCall = measurePerCall(page, x, y, w, h, BATCH);
        assertTrue(perCall < ALLOC_BUDGET_PER_CALL,
                ctx + " allocated " + perCall + " bytes/call (budget "
                        + ALLOC_BUDGET_PER_CALL + ")");
    }

    private static long allocatedBytes() {
        return TMB.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
}
