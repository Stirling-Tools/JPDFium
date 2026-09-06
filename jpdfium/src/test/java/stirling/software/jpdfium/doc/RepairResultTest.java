package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairResultTest {

    @Test
    void cleanStatusIsUsable() {
        var result = new RepairResult(RepairResult.Status.CLEAN, new byte[] { 1 }, "{}");
        assertTrue(result.isUsable());
    }

    @Test
    void fixedStatusIsUsable() {
        var result = new RepairResult(RepairResult.Status.FIXED, new byte[] { 1, 2 }, "{}");
        assertTrue(result.isUsable());
    }

    @Test
    void partialStatusIsUsable() {
        var result = new RepairResult(RepairResult.Status.PARTIAL, new byte[] { 1 }, "{}");
        assertTrue(result.isUsable());
    }

    @Test
    void failedStatusIsNotUsable() {
        var result = new RepairResult(RepairResult.Status.FAILED, null, "error");
        assertFalse(result.isUsable());
    }

    @Test
    void failedWithBytesStillNotUsable() {
        var result = new RepairResult(RepairResult.Status.FAILED, new byte[] { 1 }, "error");
        assertFalse(result.isUsable());
    }

    @Test
    void nullBytesNotUsable() {
        var result = new RepairResult(RepairResult.Status.FIXED, null, "{}");
        assertFalse(result.isUsable());
    }
}
