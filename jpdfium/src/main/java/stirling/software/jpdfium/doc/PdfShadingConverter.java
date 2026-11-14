package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.QpdfLib;

/**
 * Rewrites the simple RGB shadings that the PDFium object API cannot convert
 * through {@code PdfColorConverter}.
 *
 * <p>Only Type 2 functions with RGB {@code /C0} and {@code /C1} arrays are
 * handled today; sampled and PostScript calculator functions are left
 * unchanged.
 */
public final class PdfShadingConverter {

    private PdfShadingConverter() {}

    /** True when the loaded native library exposes the shading rewrite. */
    public static boolean isAvailable() {
        return QpdfLib.isShadingConversionSupported();
    }

    /**
     * Convert simple {@code /DeviceRGB} shadings to {@code /DeviceCMYK}.
     *
     * @param pdf source PDF bytes
     * @return rewritten PDF, or {@code null} when no convertible shading exists
     */
    public static byte[] toCmyk(byte[] pdf) {
        return QpdfLib.shadingsToCmyk(pdf);
    }
}
