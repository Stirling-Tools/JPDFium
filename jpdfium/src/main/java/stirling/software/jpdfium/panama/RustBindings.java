package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Optional hand-written bindings for the Rust-powered bridge features.
 *
 * <p>Deliberately not jextract-generated: the stub probe build does not export
 * these, and a tolerant lookup lets callers report "feature unavailable"
 * instead of failing class initialisation.
 */
public final class RustBindings {

    private RustBindings() {}

    /** {@code int32_t jpdfium_has_rust(void)} - null when the symbol is absent. */
    public static final MethodHandle jpdfium_has_rust =
            Symbols.downcallOptional("jpdfium_has_rust", FunctionDescriptor.of(JAVA_INT));
}
