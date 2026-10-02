package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Hand-linked direct downcall method handles for hot leaf native operations.
 *
 * <p>Handles marked critical use {@code Linker.Option.critical(false)} when
 * the {@code jpdfium.ffm.critical} system property is {@code true} (the
 * default); any failure falls back to a plain downcall in {@link #link}.
 * Every signature here already has a foreign entry in
 * {@code reachability-metadata.json}, which is what keeps GraalVM
 * native-image working since the whole {@code panama} package is
 * {@code initialize-at-run-time} and every handle is created at image
 * runtime. Resource-cleanup handles ({@code DOC_CLOSE}, {@code PAGE_CLOSE},
 * {@code FLASHTEXT_FREE}) always use plain downcalls because cleanup work
 * does not satisfy the short-call contract.
 *
 * <p>These direct handles are not wrapped by combinators; callers must invoke
 * them from inside the {@link PdfiumRuntime} execution domain
 * ({@code execute}/{@code executeTeardown}).
 */
public final class FastLinks {

    private static final Linker LINKER = Linker.nativeLinker();

    private static final boolean CRITICAL_ENABLED = Boolean.parseBoolean(
            System.getProperty("jpdfium.ffm.critical", "true"));

    public static final MethodHandle DOC_PAGE_COUNT;
    public static final MethodHandle PAGE_WIDTH;
    public static final MethodHandle PAGE_HEIGHT;
    public static final MethodHandle DOC_CLOSE;
    public static final MethodHandle PAGE_CLOSE;
    public static final MethodHandle FREE_BUFFER;
    public static final MethodHandle FREE_STRING;
    public static final MethodHandle PCRE2_FREE;
    public static final MethodHandle FLASHTEXT_FREE;
    public static final MethodHandle FONT_FREE_INFO;
    public static final MethodHandle PAGE_FLATTEN;

    // Critical-suitability audit: critical downcalls must be short in all cases
    // and never call back into Java, lock, or run variable-length PDFium work.
    // Eligible here are trivial getters (page count/dimensions), plain free()s,
    // and small fixed-struct releases. Rendering, parsing, saving, redaction,
    // text extraction, flattening, and all cleanup that walks heap state stay
    // plain - they go through the guarded JpdfiumH bindings, never this table.
    static {
        DOC_PAGE_COUNT  = link("jpdfium_doc_page_count", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS), true);
        PAGE_WIDTH      = link("jpdfium_page_width", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS), true);
        PAGE_HEIGHT     = link("jpdfium_page_height", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS), true);
        DOC_CLOSE       = link("jpdfium_doc_close", FunctionDescriptor.ofVoid(JAVA_LONG), false);
        PAGE_CLOSE      = link("jpdfium_page_close", FunctionDescriptor.ofVoid(JAVA_LONG), false);
        FREE_BUFFER     = link("jpdfium_free_buffer", FunctionDescriptor.ofVoid(ADDRESS), true);
        FREE_STRING     = link("jpdfium_free_string", FunctionDescriptor.ofVoid(ADDRESS), true);
        PCRE2_FREE      = link("jpdfium_pcre2_free", FunctionDescriptor.ofVoid(JAVA_LONG), true);
        FLASHTEXT_FREE  = link("jpdfium_flashtext_free", FunctionDescriptor.ofVoid(JAVA_LONG), false);
        FONT_FREE_INFO  = link("jpdfium_font_free_info", FunctionDescriptor.ofVoid(ADDRESS), true);
        PAGE_FLATTEN    = link("jpdfium_page_flatten", FunctionDescriptor.of(JAVA_INT, JAVA_LONG), false);
    }

    private static MethodHandle link(String name, FunctionDescriptor desc, boolean isLeafCritical) {
        Optional<MemorySegment> sym = Symbols.find(name);
        if (sym.isEmpty()) return null;
        if (isLeafCritical && CRITICAL_ENABLED) {
            try {
                return LINKER.downcallHandle(sym.get(), desc, Linker.Option.critical(false));
            } catch (Throwable _) {
            }
        }
        return LINKER.downcallHandle(sym.get(), desc);
    }

    private FastLinks() {}
}
