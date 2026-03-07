package stirling.software.jpdfium.doc;

/**
 * Result of a PDF repair operation.
 *
 * @param status         outcome of the repair attempt
 * @param repairedPdf    the (possibly repaired) PDF bytes, or null on failure
 * @param diagnosticJson JSON string with repair diagnostics and warnings
 */
public record RepairResult(
        Status status,
        byte[] repairedPdf,
        String diagnosticJson) {
    /** Repair outcome. */
    public enum Status {
        /** The input PDF was already valid — no changes made. */
        CLEAN,
        /** The PDF was repaired successfully (with warnings). */
        FIXED,
        /** Partial repair — some content may be lost. */
        PARTIAL,
        /** All repair strategies failed. */
        FAILED
    }

    /**
     * Returns true if the repair produced a usable output PDF.
     */
    public boolean isUsable() {
        return status != Status.FAILED && repairedPdf != null;
    }
}
