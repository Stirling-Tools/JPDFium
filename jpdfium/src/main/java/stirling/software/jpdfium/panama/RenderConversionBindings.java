package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Unguarded downcalls for pure pixel packing.
 *
 * <p>These entry points only read the source buffer and write the destination;
 * they never touch PDFium's process-wide state, so they must not take the
 * domain or block other renderers.
 */
public final class RenderConversionBindings {

    private RenderConversionBindings() {}

    /**
     * Packs {@code total} straight RGBA8 pixels into opaque {@code 0xAARRGGBB} words.
     *
     * <p>{@code src} and {@code dst} may be the same buffer. Returns the number of
     * pixels packed.
     */
    public static final MethodHandle JPDFIUM_RGBA_TO_ARGB32 = Symbols.downcallUnguarded(
            "jpdfium_rgba_to_argb32",
            FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT));

    /**
     * Packs {@code total} straight RGBA8 pixels into {@code 0x00RRGGBB} words.
     *
     * <p>{@code src} and {@code dst} may be the same buffer. Returns the number of
     * pixels packed.
     */
    public static final MethodHandle JPDFIUM_RGBA_TO_RGB32 = Symbols.downcallUnguarded(
            "jpdfium_rgba_to_rgb32",
            FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT));
}
