package stirling.software.jpdfium.model;

/** One shaped glyph: HarfBuzz output in points at the requested size. */
public record ShapedGlyph(
        int glyphId,
        float advanceX,
        float advanceY,
        float offsetX,
        float offsetY,
        int cluster) {}
