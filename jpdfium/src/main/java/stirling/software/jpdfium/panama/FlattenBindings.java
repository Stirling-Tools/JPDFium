package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** FFM bindings for the display-only flatten passes in {@code jpdfium.h}. */
public final class FlattenBindings {

    private FlattenBindings() {}

    /** Bakes the annotations a viewer draws, leaving no-view markup out. */
    public static final MethodHandle jpdfium_page_flatten_display =
            Symbols.downcall(
                    "jpdfium_page_flatten_display",
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

    /** Document-wide display bake used before rasterizing every page. */
    public static final MethodHandle jpdfium_doc_flatten_annotations_display =
            Symbols.downcall(
                    "jpdfium_doc_flatten_annotations_display",
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
}
