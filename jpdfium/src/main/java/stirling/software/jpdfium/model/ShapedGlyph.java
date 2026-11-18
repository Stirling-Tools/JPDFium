package stirling.software.jpdfium.model;

/**
 * One shaped glyph: HarfBuzz output in points at the requested size.
 *
 * @param glyphId  HarfBuzz glyph id in the shaped font
 * @param advanceX horizontal advance in points
 * @param advanceY vertical advance in points
 * @param offsetX  horizontal displacement in points
 * @param offsetY  vertical displacement in points
 * @param cluster  byte index of the first UTF-8 character this glyph came from
 * @param flags    HarfBuzz glyph flags: bit 0 = UNSAFE_TO_BREAK (breaking the
 *                 text at this cluster start would change shaping), bit 1 =
 *                 UNSAFE_TO_CONCAT (changing text on either side of this
 *                 cluster start would change shaping)
 */
public record ShapedGlyph(
        int glyphId,
        float advanceX,
        float advanceY,
        float offsetX,
        float offsetY,
        int cluster,
        int flags) {}
