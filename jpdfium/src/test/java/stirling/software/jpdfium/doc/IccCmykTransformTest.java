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
    void cmykSourceBlackStaysBlackInk() {
        byte[] profile = PdfIccProfiles.compactCmyk();
        IccCmykTransform transform = new IccCmykTransform(profile);
        int[] out = new int[4];
        transform.toCmykFromIcc(profile, new float[] {0f, 0f, 0f, 1f}, out);
        assertTrue(out[3] >= 200, "pure K source must keep a strong K component");
        assertTrue(out[0] <= 32 && out[1] <= 32 && out[2] <= 32,
                "pure K source must not become four-colour black");
    }

    @Test
    void bundledProfileHasOutputClassTags() {
        java.awt.color.ICC_Profile profile =
                java.awt.color.ICC_Profile.getInstance(PdfIccProfiles.compactCmyk());
        int tagCount = ByteBuffer.wrap(profile.getData()).getInt(128);
        assertTrue(tagCount >= 8,
                "output profile is missing required tags, tagCount=" + tagCount);
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
