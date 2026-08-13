package stirling.software.jpdfium.crop;

import com.sun.management.OperatingSystemMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native stress/leak check for the crop-remove-content path.
 *
 * <p>Runs thousands of crops - alternating the fast path (nothing outside) and a real
 * removal - on one live document, then proves the document still renders, saves and
 * re-opens. Every crop internally opens/closes a {@code FPDF_TEXTPAGE} and creates/
 * destroys {@code FPDF_PAGEOBJECT}s, so a leaked or double-freed native handle here
 * would crash or corrupt the heap. The committed-memory delta is a coarse native-leak
 * signal; the definitive leak audit is a valgrind/ASan build of the bridge (see the
 * {@code JPDFIUM_SANITIZE} CMake option) on the CI Linux runner.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CropStressTest {

    private static final int ITERATIONS = 5000;
    /** Lenient cap: 5k crops must not leak tens of MB of native memory. */
    private static final long MAX_COMMITTED_GROWTH_BYTES = 64L * 1024 * 1024;

    @Test
    void thousandsOfCropsDoNotCorruptOrLeak() throws Exception {
        byte[] input = CropTestPdfGenerator.textGridPdf();
        float w, h;
        try (PdfDocument doc = PdfDocument.open(input); PdfPage page = doc.page(0)) {
            w = page.size().width();
            h = page.size().height();
        }

        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long committedBefore = os.getCommittedVirtualMemorySize();

        byte[] output;
        try (PdfDocument doc = PdfDocument.open(input)) {
            for (int i = 0; i < ITERATIONS; i++) {
                if ((i & 1) == 0) {
                    // Real removal: left-half crop.
                    PdfPageGeometry.cropAndRemoveContent(doc, 0, new Rect(0, 0, w / 2, h));
                } else {
                    // Fast path: full-page crop (nothing outside).
                    PdfPageGeometry.cropAndRemoveContent(doc, 0, new Rect(0, 0, w, h));
                }
            }
            // Document must still save + reopen cleanly after thousands of mutations.
            output = doc.saveBytes();
        }

        long committedAfter = os.getCommittedVirtualMemorySize();
        long growth = Math.max(0, committedAfter - committedBefore);

        try (PdfDocument reopened = PdfDocument.open(output); PdfPage page = reopened.page(0)) {
            int area = page.renderAt(72).width() * page.renderAt(72).height();
            assertTrue(area > 0, "document must still render after stress");
        }
        assertTrue(growth < MAX_COMMITTED_GROWTH_BYTES,
                "committed memory grew by " + (growth / 1024 / 1024) + " MiB over "
                        + ITERATIONS + " crops - possible native leak");
    }
}
