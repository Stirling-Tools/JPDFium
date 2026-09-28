package stirling.software.jpdfium.model;

/**
 * Standard PDFium page rendering flag bitmasks.
 */
public final class RenderFlags {

    /** Render annotations that do not require interactive widgets. */
    public static final int ANNOTATIONS = 0x01;

    /** Subpixel LCD text antialiasing (ClearType-style). */
    public static final int LCD_TEXT = 0x02;

    /** Force vector outline text rendering instead of native fonts. */
    public static final int NO_NATIVE_TEXT = 0x04;

    /** Render page contents in grayscale. */
    public static final int GRAYSCALE = 0x08;

    /** Reverse byte order (BGR/BGRA instead of RGB/RGBA). */
    public static final int REVERSE_BYTE_ORDER = 0x10;

    /** Include internal debug info if supported by build. */
    public static final int DEBUG_INFO = 0x80;

    /** Do not catch exceptions internally in rendering. */
    public static final int NO_CATCH = 0x100;

    /** Restrict image cache size during rendering. */
    public static final int RENDER_LIMITED_IMAGE_CACHE = 0x200;

    /** Force halftone simulation for graphics. */
    public static final int RENDER_FORCE_HALFTONE = 0x400;

    /** Optimized settings for print rendering. */
    public static final int PRINTING = 0x800;

    /** Disable text antialiasing for maximum rendering speed. */
    public static final int NO_SMOOTH_TEXT = 0x1000;

    /** Disable image smoothing/bicubic interpolation. */
    public static final int NO_SMOOTH_IMAGE = 0x2000;

    /** Disable path antialiasing for vector graphics. */
    public static final int NO_SMOOTH_PATH = 0x4000;

    private RenderFlags() {}
}
