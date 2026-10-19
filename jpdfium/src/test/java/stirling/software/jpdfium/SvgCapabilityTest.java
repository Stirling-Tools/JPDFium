package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.doc.PdfOverlay;
import stirling.software.jpdfium.panama.NativeRuntime;

/** Capability probes must match the native bridge that is actually loaded. */
class SvgCapabilityTest {

    @Test
    void vectorStampingIsOnlyAdvertisedByTheRealBridge() {
        boolean supported = PdfOverlay.isStampingSupported();
        if (NativeRuntime.isStub()) {
            assertFalse(supported, "the stub exports the symbol but cannot stamp");
        } else {
            assertTrue(supported, "the real qpdf bridge must expose stamping");
        }
        assertEquals(supported, SvgConverter.isOverlaySupported());
    }
}
