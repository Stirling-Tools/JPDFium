package stirling.software.jpdfium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Shared binary assertion helpers for PDF serialization determinism tests.
 */
public final class PdfTestAssertions {

    private PdfTestAssertions() {}

    /**
     * Asserts byte-for-byte equality except for the second trailer {@code /ID} hex string,
     * which PDFium regenerates on every save when an input document already had an {@code /ID}
     * (ISO 32000-1 §14.4). Still validates that {@code /ID[0]} is identical and {@code /ID[1]}
     * contains well-formed even-length hex digits.
     */
    public static void assertBytesEqualExceptTrailerId(byte[] expected, byte[] actual, String message) {
        TrailerIdSlots expSlots = findTrailerIdSlots(expected);
        TrailerIdSlots actSlots = findTrailerIdSlots(actual);
        if (expSlots == null || !expSlots.sameSecondRange(actSlots)) {
            assertEquals(expected.length, actual.length, message + " (length mismatch)");
            assertTrue(Arrays.equals(expected, actual), message);
            return;
        }
        assertValidHexSlot(expected, expSlots.secondStart, expSlots.secondEnd, message + " (expected /ID[1])");
        assertValidHexSlot(actual, actSlots.secondStart, actSlots.secondEnd, message + " (actual /ID[1])");
        assertBytesEqualOutsideRange(expected, actual, expSlots.secondStart + 1, expSlots.secondEnd - 1, message);
    }

    /**
     * Asserts byte-for-byte equality except for both trailer {@code /ID} hex strings, which
     * PDFium generates fresh on each save when the input document had no {@code /ID} (ISO 32000-1
     * §14.4). Validates that both hex slots remain well-formed even-length hex digits.
     */
    public static void assertBytesEqualExceptGeneratedTrailerIds(byte[] expected, byte[] actual, String message) {
        TrailerIdSlots expSlots = findTrailerIdSlots(expected);
        TrailerIdSlots actSlots = findTrailerIdSlots(actual);
        if (expSlots == null || !expSlots.sameOverallRange(actSlots)) {
            assertEquals(expected.length, actual.length, message + " (length mismatch)");
            assertTrue(Arrays.equals(expected, actual), message);
            return;
        }
        assertValidHexSlot(expected, expSlots.firstStart, expSlots.firstEnd, message + " (expected /ID[0])");
        assertValidHexSlot(actual, actSlots.firstStart, actSlots.firstEnd, message + " (actual /ID[0])");
        assertValidHexSlot(expected, expSlots.secondStart, expSlots.secondEnd, message + " (expected /ID[1])");
        assertValidHexSlot(actual, actSlots.secondStart, actSlots.secondEnd, message + " (actual /ID[1])");
        assertBytesEqualOutsideRange(expected, actual, expSlots.firstStart + 1, expSlots.secondEnd - 1, message);
    }

    private static void assertBytesEqualOutsideRange(byte[] expected, byte[] actual,
                                                     int skipStart, int skipEnd, String message) {
        assertEquals(expected.length, actual.length, message + " (length mismatch)");
        int prefixMismatch = Arrays.mismatch(expected, 0, skipStart, actual, 0, skipStart);
        if (prefixMismatch >= 0) {
            throw new AssertionError(message + " (bytes differ before /ID at offset " + prefixMismatch
                    + ": expected=" + snippet(expected, prefixMismatch)
                    + ", actual=" + snippet(actual, prefixMismatch) + ")");
        }
        int suffixMismatch = Arrays.mismatch(expected, skipEnd, expected.length, actual, skipEnd, actual.length);
        if (suffixMismatch >= 0) {
            int pos = skipEnd + suffixMismatch;
            throw new AssertionError(message + " (bytes differ after /ID at offset " + pos
                    + ": expected=" + snippet(expected, pos)
                    + ", actual=" + snippet(actual, pos) + ")");
        }
    }

    private static String snippet(byte[] data, int center) {
        int lo = Math.max(0, center - 24);
        int hi = Math.min(data.length, center + 40);
        return new String(data, lo, hi - lo, StandardCharsets.ISO_8859_1)
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void assertValidHexSlot(byte[] pdf, int start, int end, String label) {
        int hexLen = end - start - 2;
        assertTrue(hexLen > 0 && (hexLen & 1) == 0 && pdf[start] == '<' && pdf[end - 1] == '>',
                label + " must be a non-empty even-length hex string");
        for (int i = start + 1; i < end - 1; i++) {
            byte b = pdf[i];
            boolean isHex = (b >= '0' && b <= '9')
                    || (b >= 'a' && b <= 'f')
                    || (b >= 'A' && b <= 'F');
            assertTrue(isHex, label + " contains non-hex byte at offset " + i);
        }
    }

    private record TrailerIdSlots(int firstStart, int firstEnd, int secondStart, int secondEnd) {
        boolean sameSecondRange(TrailerIdSlots other) {
            return other != null && secondStart == other.secondStart && secondEnd == other.secondEnd;
        }

        boolean sameOverallRange(TrailerIdSlots other) {
            return other != null && firstStart == other.firstStart && secondEnd == other.secondEnd;
        }
    }

    private static TrailerIdSlots findTrailerIdSlots(byte[] pdf) {
        int id = -1;
        for (int i = pdf.length - 3; i >= 0; i--) {
            if (pdf[i] == '/' && pdf[i + 1] == 'I' && pdf[i + 2] == 'D') {
                id = i;
                break;
            }
        }
        if (id < 0) {
            return null;
        }
        int firstOpen = indexOfByte(pdf, (byte) '<', id + 3);
        int firstClose = firstOpen < 0 ? -1 : indexOfByte(pdf, (byte) '>', firstOpen + 1);
        int secondOpen = firstClose < 0 ? -1 : indexOfByte(pdf, (byte) '<', firstClose + 1);
        int secondClose = secondOpen < 0 ? -1 : indexOfByte(pdf, (byte) '>', secondOpen + 1);
        if (secondClose < 0) {
            return null;
        }
        return new TrailerIdSlots(firstOpen, firstClose + 1, secondOpen, secondClose + 1);
    }

    private static int indexOfByte(byte[] haystack, byte needle, int from) {
        for (int i = Math.max(from, 0); i < haystack.length; i++) {
            if (haystack[i] == needle) {
                return i;
            }
        }
        return -1;
    }
}
