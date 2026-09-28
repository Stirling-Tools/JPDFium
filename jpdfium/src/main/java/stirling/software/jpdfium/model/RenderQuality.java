package stirling.software.jpdfium.model;

/**
 * Presets and custom bitmask configuration for page rasterization quality.
 */
public record RenderQuality(int flags) {

    /** Default high-fidelity quality suitable for printing and archiving. */
    public static final RenderQuality PRINT = new RenderQuality(RenderFlags.ANNOTATIONS | RenderFlags.PRINTING);

    /** Screen quality with subpixel LCD text antialiasing for display. */
    public static final RenderQuality SCREEN = new RenderQuality(RenderFlags.ANNOTATIONS | RenderFlags.LCD_TEXT);

    /** Fast draft quality disabling text, image, and path antialiasing. */
    public static final RenderQuality FAST = new RenderQuality(
            RenderFlags.ANNOTATIONS | RenderFlags.NO_SMOOTH_TEXT | RenderFlags.NO_SMOOTH_IMAGE | RenderFlags.NO_SMOOTH_PATH);

    /**
     * Create a custom render quality configuration from raw PDFium flag bits.
     */
    public static RenderQuality custom(int flags) {
        return new RenderQuality(flags);
    }
}
