package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IccCmykTransformTest {

    @Test
    void buildsA33GridLutFromAValidCmykProfile() {
        IccCmykTransform transform = new IccCmykTransform(PdfIccProfiles.compactCmyk());
        int[] first = new int[4];
        int[] second = new int[4];
        transform.toCmyk(12, 130, 240, first);
        transform.toCmyk(12, 130, 240, second);
        assertEquals(IccCmykTransform.LUT_GRID * IccCmykTransform.LUT_GRID
                * IccCmykTransform.LUT_GRID * 4, transform.lutBytes().length);
        for (int i = 0; i < 4; i++) {
            assertEquals(first[i], second[i], "LUT conversion must be deterministic");
        }
    }

    @Test
    void rejectsProfileWithTagOutsideTheDeclaredSizeBeforeCallingTheJdk() {
        byte[] malformed = new byte[200];
        ByteBuffer buffer = ByteBuffer.wrap(malformed);
        buffer.putInt(0, malformed.length);
        buffer.put(16, "CMYK".getBytes(StandardCharsets.US_ASCII));
        buffer.put(36, "acsp".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(128, 1);
        buffer.putInt(132, 0x64657363);
        buffer.putInt(136, 500);
        buffer.putInt(140, 4);

        JPDFiumException error = assertThrows(JPDFiumException.class,
                () -> new IccCmykTransform(malformed));
        assertTrue(error.getMessage().contains("tag"), "unexpected error: " + error.getMessage());
    }
}
