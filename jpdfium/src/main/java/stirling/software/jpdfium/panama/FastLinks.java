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
 * <p>Plain (non-critical) downcalls: every signature here already has a
 * foreign entry in {@code reachability-metadata.json}, which is what keeps
 * GraalVM native-image working since the whole {@code panama} package is
 * {@code initialize-at-run-time} and every handle is created at image
 * runtime. A strict {@code critical(false)} variant skips thread-state
 * transitions but its leaf stubs have no metadata representation that the
 * current GraalVM accepts (blank snippet in
 * {@code MissingForeignRegistrationError}), so it stays off until the
 * metadata supports it and a benchmark proves the need.
 *
 * <p>These direct handles are not wrapped by combinators; callers must explicitly acquire
 * {@link NativeGuard} before invoking them.
 */
public final class FastLinks {

    private static final Linker LINKER = Linker.nativeLinker();

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

    static {
        DOC_PAGE_COUNT  = link("jpdfium_doc_page_count", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
        PAGE_WIDTH      = link("jpdfium_page_width", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
        PAGE_HEIGHT     = link("jpdfium_page_height", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
        DOC_CLOSE       = link("jpdfium_doc_close", FunctionDescriptor.ofVoid(JAVA_LONG));
        PAGE_CLOSE      = link("jpdfium_page_close", FunctionDescriptor.ofVoid(JAVA_LONG));
        FREE_BUFFER     = link("jpdfium_free_buffer", FunctionDescriptor.ofVoid(ADDRESS));
        FREE_STRING     = link("jpdfium_free_string", FunctionDescriptor.ofVoid(ADDRESS));
        PCRE2_FREE      = link("jpdfium_pcre2_free", FunctionDescriptor.ofVoid(JAVA_LONG));
        FLASHTEXT_FREE  = link("jpdfium_flashtext_free", FunctionDescriptor.ofVoid(JAVA_LONG));
        FONT_FREE_INFO  = link("jpdfium_font_free_info", FunctionDescriptor.ofVoid(ADDRESS));
    }

    private static MethodHandle link(String name, FunctionDescriptor desc) {
        Optional<MemorySegment> sym = Symbols.find(name);
        return sym.map(memorySegment -> LINKER.downcallHandle(memorySegment, desc)).orElse(null);
    }

    private FastLinks() {}
}
