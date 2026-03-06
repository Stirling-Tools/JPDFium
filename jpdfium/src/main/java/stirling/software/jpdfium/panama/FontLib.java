package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for the font normalization pipeline (FreeType + HarfBuzz + qpdf).
 */
public final class FontLib {

    static { NativeLoader.ensureLoaded(); }

    private FontLib() {}

    public static byte[] getData(long page, int fontIndex) {
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
    }

    public static String classify(byte[] fontData) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrSeg = a.allocate(ADDRESS);
            JpdfiumLib.check(JpdfiumH.jpdfium_font_classify(
                    a.allocateFrom(JAVA_BYTE, fontData), fontData.length, ptrSeg), "fontClassify");
            MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        }
    }

    public static int fixToUnicode(long doc, int pageIndex) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cSeg = a.allocate(JAVA_INT);
            JpdfiumLib.check(JpdfiumH.jpdfium_font_fix_tounicode(doc, pageIndex, cSeg), "fontFixToUnicode");
            return cSeg.get(JAVA_INT, 0);
        }
    }

    public static int repairWidths(long doc, int pageIndex) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cSeg = a.allocate(JAVA_INT);
            JpdfiumLib.check(JpdfiumH.jpdfium_font_repair_widths(doc, pageIndex, cSeg), "fontRepairWidths");
            return cSeg.get(JAVA_INT, 0);
        }
    }

    public static String normalizePage(long doc, int pageIndex) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrSeg = a.allocate(ADDRESS);
            JpdfiumLib.check(JpdfiumH.jpdfium_font_normalize_page(doc, pageIndex, ptrSeg), "fontNormalizePage");
            MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
            String result = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return result;
        }
    }

    public static byte[] subset(byte[] fontData, int[] codepoints, boolean retainGids) {
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
    }
}
