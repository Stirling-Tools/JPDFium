package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shutdown preflight against real PDFium: refusal names live resources and
 * leaves them closeable-but-unusable, never destroyed under load. Lives in
 * the runtime package for the test-only lifecycle hook.
 */
class RuntimeShutdownTest {

    @AfterEach
    void restoreRunning() {
        if (PdfiumRuntime.state() != PdfiumRuntime.State.RUNNING) {
            PdfiumRuntime.restoreRunningForTests();
        }
    }

    @Test
    void shutdownRefusesWithOpenDocument() throws Exception {
        byte[] pdf = Objects.requireNonNull(RuntimeShutdownTest.class.getResourceAsStream(
                "/pdfs/general/minimal.pdf")).readAllBytes();
        long doc = JpdfiumLib.docOpenBytes(pdf);
        final long opened = doc;
        try {
            JPDFiumException refused = assertThrows(
                    JPDFiumException.class, PdfiumRuntime::shutdown);
            assertTrue(refused.getMessage().contains("documents=1"),
                    "refusal must name live counts: " + refused.getMessage());
            // Refusal leaves QUIESCING: closeable (teardown admitted) but not usable.
            assertThrows(JPDFiumException.class, () -> JpdfiumLib.docPageCount(opened));
            JpdfiumLib.docClose(doc);
            doc = 0;
            assertEquals(PdfiumRuntime.State.QUIESCING, PdfiumRuntime.state());
        } finally {
            if (doc != 0) {
                JpdfiumLib.docClose(doc);
            }
        }
        // Deliberately no retry here: a successful shutdown would destroy the
        // native library for the rest of this JVM's suite.
    }

}
