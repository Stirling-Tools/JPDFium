package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import stirling.software.jpdfium.PdfDocument;

/**
 * Regression: a rejected admission must never leave the domain locked.
 *
 * <p>The failure this covers was found the hard way, it hung the build at JVM
 * exit rather than failing a test, because the shutdown hook waited on a domain
 * no thread was going to release. The shape matters: callers write
 * {@code enterLeaf(); try { ... } finally { exitLeaf(); }}, so the
 * {@code finally} is attached only after entry returns. An admission that
 * throws inside {@code enterLeaf} therefore never reaches its caller's
 * {@code exitLeaf}, and unless entry unwinds itself the domain stays locked
 * forever.
 *
 * <p>Each step asserts the lock is free, which attributes a leak to the exact
 * call that caused it instead of surfacing later as an unexplained hang.
 */
class DomainLeakTest {

    @AfterEach
    void restoreRunning() {
        if (PdfiumRuntime.state() != PdfiumRuntime.State.RUNNING) {
            PdfiumRuntime.restoreRunningForTests();
        }
        assertFalse(PdfiumRuntime.domainLocked(),
                "test finished holding the domain - admission leaked");
        assertEquals(0, PdfiumRuntime.entryDepth(), "unmatched domain entries");
    }

    @Test
    void rejectedLeafCallReleasesItsAdmission() throws Exception {
        byte[] pdf = Objects.requireNonNull(DomainLeakTest.class.getResourceAsStream(
                "/pdfs/general/minimal.pdf")).readAllBytes();
        long doc = JpdfiumLib.docOpenBytes(pdf);
        final long opened = doc;
        boolean closed = false;
        try {
            // Quiesce while the document is still live: leaf calls must now be
            // refused, and each refusal must still give the domain back.
            PdfiumRuntime.quiesce();
            assertThrows(JPDFiumException.class, () -> JpdfiumLib.docPageCount(opened));
            assertFalse(PdfiumRuntime.domainLocked(),
                    "a refused leaf call must release the admission it took");
            assertEquals(0, PdfiumRuntime.entryDepth(),
                    "a refused leaf call must not leave an unmatched entry");

            // Repeated refusals must not accumulate anything.
            for (int i = 0; i < 3; i++) {
                assertThrows(JPDFiumException.class, () -> JpdfiumLib.docPageCount(opened));
            }
            assertFalse(PdfiumRuntime.domainLocked(), "repeated refusals must stay balanced");

            // And the runtime is still usable for permitted work afterwards.
            JpdfiumLib.docClose(doc);
            closed = true;
            assertEquals(PdfiumRuntime.State.QUIESCING, PdfiumRuntime.state());
        } finally {
            if (!closed) {
                JpdfiumLib.docClose(doc);
            }
        }
    }

    @Test
    void rejectedGuardedCallReleasesItsAdmission() throws Throwable {
        PdfiumRuntime.quiesce();
        MethodHandle guarded = PdfiumRuntime.guarded(
                MethodHandles.empty(
                        MethodType.methodType(void.class)));
        assertThrows(JPDFiumException.class, guarded::invokeExact);
        assertFalse(PdfiumRuntime.domainLocked(),
                "a refused guarded handle must release the admission it took");
        assertEquals(0, PdfiumRuntime.entryDepth(),
                "a refused guarded handle must not leave an unmatched entry");
    }

    @Test
    void ordinaryWorkReleasesTheDomainEveryTime() throws Exception {
        byte[] pdf = Objects.requireNonNull(DomainLeakTest.class.getResourceAsStream(
                "/pdfs/general/minimal.pdf")).readAllBytes();
        try (PdfDocument doc =
                     PdfDocument.open(pdf)) {
            for (int i = 0; i < 50; i++) {
                assertFalse(PdfiumRuntime.domainLocked(),
                        "domain still held after iteration " + i);
                assertEquals(0, PdfiumRuntime.entryDepth(),
                        "unmatched entry after iteration " + i);
                doc.pageCount();
            }
        }
        assertFalse(PdfiumRuntime.domainLocked(), "domain held after document close");
    }
}
