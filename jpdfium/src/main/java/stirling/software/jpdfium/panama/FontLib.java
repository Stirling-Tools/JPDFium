package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.ShapedGlyph;
import stirling.software.jpdfium.util.NativeJsonParser;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
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

    private static final MethodHandle jpdfium_font_covers_text =
            Symbols.downcallOptional("jpdfium_font_covers_text",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS));

    private static final MethodHandle jpdfium_text_shape =
            Symbols.downcallOptional("jpdfium_text_shape",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_FLOAT, ADDRESS));

    private FontLib() {}

    /**
     * Per-codepoint coverage: result[i] is true when the font program has a
     * glyph for codepoints[i]. Drives rewrite-vs-regenerate decisions.
     */
    public static boolean[] coversText(byte[] fontData, int[] codepoints) {
        if (fontData == null || fontData.length == 0) throw new IllegalArgumentException("fontData must not be empty");
        if (codepoints == null || codepoints.length == 0) throw new IllegalArgumentException("codepoints must not be empty");
        if (jpdfium_font_covers_text == null) {
            throw new JPDFiumException(
                    "jpdfium_font_covers_text not in this native build");
        }
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment out = a.allocate(JAVA_BYTE, codepoints.length);
                JpdfiumLib.check((int) jpdfium_font_covers_text.invokeExact(
                        a.allocateFrom(JAVA_BYTE, fontData), (long) fontData.length,
                        a.allocateFrom(JAVA_INT, codepoints), codepoints.length, out),
                        "fontCoversText");
                boolean[] covered = new boolean[codepoints.length];
                MemorySegment bytes = out.reinterpret(codepoints.length);
                for (int i = 0; i < covered.length; i++) {
                    covered[i] = bytes.get(JAVA_BYTE, i) != 0;
                }
                return covered;
            }
        } catch (JPDFiumException e) {
            throw e;
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        } finally {
            NativeGuard.release();
        }
    }

    /**
     * Shape text with a font program. Returns one entry per glyph with
     * advances and offsets in points at the requested size.
     *
     * <p>Shapes one run: HarfBuzz guesses a single direction and script for the
     * whole string, so mixed-direction or mixed-script text must be split by
     * the caller and shaped run by run.
     */
    public static List<ShapedGlyph> shapeText(
            byte[] fontData, String text, float fontSize) {
        if (jpdfium_text_shape == null) {
            throw new JPDFiumException("jpdfium_text_shape not in this native build");
        }
        if (fontData == null || fontData.length == 0) throw new IllegalArgumentException("fontData must not be empty");
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("text must not be empty");
        if (!Float.isFinite(fontSize) || fontSize <= 0 || fontSize > Integer.MAX_VALUE / 64.0) {
            throw new IllegalArgumentException("fontSize must be finite and fit a 26.6 fixed-point scale");
        }
        NativeGuard.acquire();
        try {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                JpdfiumLib.check((int) jpdfium_text_shape.invokeExact(
                        a.allocateFrom(JAVA_BYTE, fontData), (long) fontData.length,
                        a.allocateFrom(text), fontSize, ptrSeg), "textShape");
                MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
                String json;
                try {
                    json = FfmHelper.readNativeString(strPtr);
                } finally {
                    JpdfiumH.jpdfium_free_string(strPtr);
                }
                List<Map<String, String>> rows = NativeJsonParser.parseArray(json);
                List<ShapedGlyph> out = new ArrayList<>(rows.size());
                for (Map<String, String> row : rows) {
                    out.add(new ShapedGlyph(
                            Integer.parseInt(row.get("g")),
                            Integer.parseInt(row.get("ax")) / 64f,
                            Integer.parseInt(row.get("ay")) / 64f,
                            Integer.parseInt(row.get("dx")) / 64f,
                            Integer.parseInt(row.get("dy")) / 64f,
                            Integer.parseInt(row.get("cluster"))));
                }
                return out;
            }
        } catch (JPDFiumException e) {
            throw e;
        } catch (Throwable t) {
            throw new JPDFiumException(t);
        } finally {
            NativeGuard.release();
        }
    }

    public static byte[] getData(long page, int fontIndex) {        NativeGuard.acquire();
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
                String result = FfmHelper.readNativeString(strPtr);
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
                String result = FfmHelper.readNativeString(strPtr);
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
