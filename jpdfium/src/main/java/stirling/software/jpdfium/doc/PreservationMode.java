package stirling.software.jpdfium.doc;

/**
 * Preservation contract for a compression run.
 *
 * <p>The mode is the single knob that decides which actions are permitted and
 * how much pixel drift the verify-and-rollback pass tolerates. Presets map onto
 * modes; an explicit {@link CompressOptions.Builder#preservationMode} overrides
 * the preset's choice.
 *
 * <p>Whatever the mode, the result is still monotonic: it is never larger than
 * the input.
 */
public enum PreservationMode {

    /**
     * No lossy or content-changing step: structural and stream recompression
     * only. Image downsampling and metadata removal are disabled.
     */
    EXACT(0.0, false),

    /**
     * Downsampling and metadata removal are allowed under a tight visual
     * tolerance. An image pass whose per-page previews drift by more than 2.5
     * mean-abs-diff per channel byte is rolled back.
     */
    VISUALLY_CONSTRAINED(2.5, true),

    /**
     * Scanned-document tradeoff: aggressive downsampling with a looser visual
     * tolerance (6.0 mean-abs-diff per channel byte). Intended for image-only
     * scans where mild softening is acceptable.
     */
    SCAN(6.0, true),

    /**
     * Everything allowed. The fidelity check is skipped entirely: the tolerance
     * is 255, so the verifier never runs and no preview comparison or rollback
     * happens for this mode.
     */
    DESTRUCTIVE(255.0, true);

    private final double maxMeanAbsDiff;
    private final boolean lossyAllowed;

    PreservationMode(double maxMeanAbsDiff, boolean lossyAllowed) {
        this.maxMeanAbsDiff = maxMeanAbsDiff;
        this.lossyAllowed = lossyAllowed;
    }

    /**
     * Maximum tolerated per-channel mean-abs-diff between before/after page
     * previews. {@link #DESTRUCTIVE} uses a value large enough never to trigger
     * a rollback.
     */
    public double maxMeanAbsDiff() { return maxMeanAbsDiff; }

    /** Whether lossy image downsampling and metadata removal are permitted. */
    public boolean lossyAllowed() { return lossyAllowed; }
}
