package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for the font normalization pipeline (FreeType + HarfBuzz + qpdf).
 */
public final class FontLib {

    private static final MethodHandle jpdfium_strip_fonts;

    static {
        NativeLoader.ensureLoaded();
        var symbol = SymbolLookup.loaderLookup().find("jpdfium_strip_fonts").orElse(null);
        jpdfium_strip_fonts = (symbol != null)
                ? NativeGuard.guard(Linker.nativeLinker().downcallHandle(symbol, FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)))
                : null;
    }

    private FontLib() {}

    public static byte[] getData(long page, int fontIndex) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                MemorySegment lenSeg = a.allocate(JAVA_LONG);
                JpdfiumLib.check(JpdfiumH.jpdfium_font_get_data(page, fontIndex, ptrSeg, lenSeg), "fontGetData");
                MemorySegment nativePtr = ptrSeg.get(ADDRESS, 0);
                long len = lenSeg.get(JAVA_LONG, 0);
                byte[] result = nativePtr.reinterpret(len).toArray(JAVA_BYTE);
                JpdfiumH.jpdfium_free_buffer(nativePtr);
                return result;
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static String classify(byte[] fontData) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                JpdfiumLib.check(JpdfiumH.jpdfium_font_classify(
                        a.allocateFrom(JAVA_BYTE, fontData), fontData.length, ptrSeg), "fontClassify");
                MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
                String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
                JpdfiumH.jpdfium_free_string(strPtr);
                return result;
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static int fixToUnicode(long doc, int pageIndex) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment cSeg = a.allocate(JAVA_INT);
                JpdfiumLib.check(JpdfiumH.jpdfium_font_fix_tounicode(doc, pageIndex, cSeg), "fontFixToUnicode");
                return cSeg.get(JAVA_INT, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static int repairWidths(long doc, int pageIndex) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment cSeg = a.allocate(JAVA_INT);
                JpdfiumLib.check(JpdfiumH.jpdfium_font_repair_widths(doc, pageIndex, cSeg), "fontRepairWidths");
                return cSeg.get(JAVA_INT, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static String normalizePage(long doc, int pageIndex) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                JpdfiumLib.check(JpdfiumH.jpdfium_font_normalize_page(doc, pageIndex, ptrSeg), "fontNormalizePage");
                MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
                String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
                JpdfiumH.jpdfium_free_string(strPtr);
                return result;
            }
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Strip embedded font resources from all pages using qpdf /Resources dict manipulation.
     *
     * @param doc bridge document handle
     * @return number of font entries removed
     */
    public static int stripFonts(long doc) {
        if (jpdfium_strip_fonts == null) {
            return 0;
        }
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment cSeg = a.allocate(JAVA_INT);
                int rc;
                try {
                    rc = (int) jpdfium_strip_fonts.invokeExact(doc, cSeg);
                } catch (Throwable t) { throw new RuntimeException("jpdfium_strip_fonts failed", t); }
                JpdfiumLib.check(rc, "stripFonts");
                return cSeg.get(JAVA_INT, 0);
            }
        } finally {
            NativeGuard.release();
        }
    }

    public static byte[] subset(byte[] fontData, int[] codepoints, boolean retainGids) {
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                MemorySegment lenSeg = a.allocate(JAVA_LONG);
                JpdfiumLib.check(JpdfiumH.jpdfium_font_subset(
                        a.allocateFrom(JAVA_BYTE, fontData), fontData.length,
                        a.allocateFrom(JAVA_INT, codepoints), codepoints.length,
                        retainGids ? 1 : 0,
                        ptrSeg, lenSeg), "fontSubset");
                MemorySegment nativePtr = ptrSeg.get(ADDRESS, 0);
                long len = lenSeg.get(JAVA_LONG, 0);
                byte[] result = nativePtr.reinterpret(len).toArray(JAVA_BYTE);
                JpdfiumH.jpdfium_free_buffer(nativePtr);
                return result;
            }
        } finally {
            NativeGuard.release();
        }
    }
}
