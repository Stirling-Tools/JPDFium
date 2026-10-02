package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Mismatched-artifact simulation for the ABI handshake (no native call). */
class AbiHandshakeTest {

    @Test
    void matchingValuesPass() {
        assertDoesNotThrow(() -> JpdfiumLib.checkAbiCompatible(1, 8, 16, 8));
        assertDoesNotThrow(() -> JpdfiumLib.checkAbiCompatible(1, 8, 16, 8, 8, 0, 12, 4, 24, 16, 1, 0, 1));
        assertDoesNotThrow(() -> JpdfiumLib.checkAbiCompatible(1, 8, 16, 8, 4, 0, 12, 4, 24, 16, 1, 1, 1));
    }

    @Test
    void eachMismatchFailsLoudly() {
        assertThrows(JPDFiumException.class, () -> JpdfiumLib.checkAbiCompatible(999, 8, 16, 8));
        assertThrows(JPDFiumException.class, () -> JpdfiumLib.checkAbiCompatible(1, 4, 16, 8));
        assertThrows(JPDFiumException.class, () -> JpdfiumLib.checkAbiCompatible(1, 8, 12, 8));
        assertThrows(JPDFiumException.class, () -> JpdfiumLib.checkAbiCompatible(1, 8, 16, 4));
    }

    @Test
    void extendedProbesFailLoudly() {
        // ulong must be 4 or 8
        assertThrows(JPDFiumException.class,
                () -> JpdfiumLib.checkAbiCompatible(1, 8, 16, 8, 2, 0, 12, 4, 24, 16, 1, 0, 1));
        // rect offsets
        assertThrows(JPDFiumException.class,
                () -> JpdfiumLib.checkAbiCompatible(1, 8, 16, 8, 8, 4, 12, 4, 24, 16, 1, 0, 1));
        assertThrows(JPDFiumException.class,
                () -> JpdfiumLib.checkAbiCompatible(1, 8, 16, 8, 8, 0, 0, 4, 24, 16, 1, 0, 1));
        // matrix size
        assertThrows(JPDFiumException.class,
                () -> JpdfiumLib.checkAbiCompatible(1, 8, 16, 8, 8, 0, 12, 4, 8, 16, 1, 0, 1));
        // filewrite version must be 1 per fpdf_save.h
        assertThrows(JPDFiumException.class,
                () -> JpdfiumLib.checkAbiCompatible(1, 8, 16, 8, 8, 0, 12, 4, 24, 16, 2, 0, 1));
        // feature probes -1 means predated bridge
        assertThrows(JPDFiumException.class,
                () -> JpdfiumLib.checkAbiCompatible(1, 8, 16, 8, 8, 0, 12, 4, 24, 16, 1, -1, 1));
    }
}
