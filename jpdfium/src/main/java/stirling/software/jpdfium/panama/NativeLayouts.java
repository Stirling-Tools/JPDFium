package stirling.software.jpdfium.panama;

import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Platform C layout values shared by multiple FFM bindings. */
final class NativeLayouts {

    static final ValueLayout C_ULONG =
            (ValueLayout) Linker.nativeLinker().canonicalLayouts().get("long");
    static final boolean C_ULONG_IS_64_BIT = C_ULONG.byteSize() == JAVA_LONG.byteSize();

    private NativeLayouts() {}
}
