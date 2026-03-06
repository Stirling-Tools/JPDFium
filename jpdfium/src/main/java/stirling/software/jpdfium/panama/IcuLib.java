package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for ICU4C text processing (NFC normalization, sentence breaking, BiDi).
 */
public final class IcuLib {

    static { NativeLoader.ensureLoaded(); }

    private IcuLib() {}

    public static String normalizeNfc(String text) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrSeg = a.allocate(ADDRESS);
            JpdfiumLib.check(JpdfiumH.jpdfium_icu_normalize_nfc(a.allocateFrom(text), ptrSeg), "icuNormalizeNfc");
            MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        }
    }

    public static String breakSentences(String text) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrSeg = a.allocate(ADDRESS);
            JpdfiumLib.check(JpdfiumH.jpdfium_icu_break_sentences(a.allocateFrom(text), ptrSeg), "icuBreakSentences");
            MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        }
    }

    public static String bidiReorder(String text) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrSeg = a.allocate(ADDRESS);
            JpdfiumLib.check(JpdfiumH.jpdfium_icu_bidi_reorder(a.allocateFrom(text), ptrSeg), "icuBidiReorder");
            MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        }
    }
}
