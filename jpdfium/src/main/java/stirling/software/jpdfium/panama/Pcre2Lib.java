package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for the PCRE2 JIT-compiled regex engine.
 *
 * <p>All methods delegate to the native bridge via {@link JpdfiumH}.
 * Compile patterns with {@link #compile} and free them with {@link #free} when done.
 */
public final class Pcre2Lib {

    public static final int PCRE2_CASELESS  = 0x00000001;
    public static final int PCRE2_MULTILINE = 0x00000002;
    public static final int PCRE2_DOTALL    = 0x00000004;
    public static final int PCRE2_UTF       = 0x00000008;
    public static final int PCRE2_UCP       = 0x00000010;

    static { NativeLoader.ensureLoaded(); }

    private Pcre2Lib() {}

    public static long compile(String pattern, int flags) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment hSeg = a.allocate(JAVA_LONG);
            JpdfiumLib.check(JpdfiumH.jpdfium_pcre2_compile(a.allocateFrom(pattern), flags, hSeg), "pcre2Compile");
            return hSeg.get(JAVA_LONG, 0);
        }
    }

    public static String matchAll(long patternHandle, String text) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrSeg = a.allocate(ADDRESS);
            JpdfiumLib.check(JpdfiumH.jpdfium_pcre2_match_all(patternHandle, a.allocateFrom(text), ptrSeg), "pcre2MatchAll");
            MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        }
    }

    public static void free(long patternHandle) {
        JpdfiumH.jpdfium_pcre2_free(patternHandle);
    }

    public static boolean luhnValidate(String number) {
        try (Arena a = Arena.ofConfined()) {
            return JpdfiumH.jpdfium_luhn_validate(a.allocateFrom(number)) == 1;
        }
    }
}
