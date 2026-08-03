package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for the FlashText keyword processor (O(n) dictionary NER matching).
 *
 * <p>Create a processor with {@link #create}, populate it with {@link #addKeyword},
 * run matches with {@link #find}, and free it with {@link #free}.
 */
public final class FlashTextLib {

    static { NativeLoader.ensureLoaded(); }

    private FlashTextLib() {}

    public static long create() {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment hSeg = a.allocate(JAVA_LONG);
                JpdfiumLib.check(JpdfiumH.jpdfium_flashtext_create(hSeg), "flashtextCreate");
                return hSeg.get(JAVA_LONG, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static void addKeyword(long handle, String keyword, String label) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                JpdfiumLib.check(JpdfiumH.jpdfium_flashtext_add_keyword(handle,
                        a.allocateFrom(keyword), a.allocateFrom(label)), "flashtextAddKeyword");
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static void addKeywordsJson(long handle, String json) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                JpdfiumLib.check(JpdfiumH.jpdfium_flashtext_add_keywords_json(handle,
                        a.allocateFrom(json)), "flashtextAddKeywordsJson");
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static String find(long handle, String text) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                JpdfiumLib.check(JpdfiumH.jpdfium_flashtext_find(handle, a.allocateFrom(text), ptrSeg), "flashtextFind");
                MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
                String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
                JpdfiumH.jpdfium_free_string(strPtr);
                return result;
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static void free(long handle) {
        NativeGuard.acquire();
        try {
            JpdfiumH.jpdfium_flashtext_free(handle);
        } finally {
            NativeGuard.release();
        }
    }
}
