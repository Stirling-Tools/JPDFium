package stirling.software.jpdfium.model;

/**
 * Verdict of a shaping-safety check at a text edit boundary.
 *
 * <p>Mirrors the re-shape-and-compare verification behind HarfBuzz's
 * UNSAFE_TO_BREAK / UNSAFE_TO_CONCAT glyph flags: the full run is shaped once,
 * the edited pieces are re-shaped under the full run's segment properties, and
 * the glyph sequences must agree in glyph id, cluster, advance and offset.
 *
 * @param safe             true when the edit is shape-safe (empirical verdict)
 * @param reason           machine-readable verdict reason, e.g.
 *                         {@code "identical"}, {@code "split inside cluster"},
 *                         {@code "glyph mismatch after removal"}
 * @param mode             0 = split verification, 1 = span-removal verification
 * @param fullGlyphs       glyph count of the full reference run
 * @param partGlyphs       glyph count of the re-shaped pieces
 * @param diffIndex        first differing glyph index, -1 when none
 * @param hbPredicted      HarfBuzz's conservative UNSAFE_TO_BREAK /
 *                         UNSAFE_TO_CONCAT prediction at the edit boundary
 * @param unsafeBreakGlyphs glyphs in the full run flagged UNSAFE_TO_BREAK
 * @param unsafeConcatGlyphs glyphs in the full run flagged UNSAFE_TO_CONCAT
 */
public record ShapeVerifyResult(
        boolean safe,
        String reason,
        int mode,
        int fullGlyphs,
        int partGlyphs,
        int diffIndex,
        boolean hbPredicted,
        int unsafeBreakGlyphs,
        int unsafeConcatGlyphs) {}
