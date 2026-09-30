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
    }

    @Test
    void eachMismatchFailsLoudly() {
        assertThrows(JPDFiumException.class, () -> JpdfiumLib.checkAbiCompatible(999, 8, 16, 8));
        assertThrows(JPDFiumException.class, () -> JpdfiumLib.checkAbiCompatible(1, 4, 16, 8));
        assertThrows(JPDFiumException.class, () -> JpdfiumLib.checkAbiCompatible(1, 8, 12, 8));
        assertThrows(JPDFiumException.class, () -> JpdfiumLib.checkAbiCompatible(1, 8, 16, 4));
    }
}
