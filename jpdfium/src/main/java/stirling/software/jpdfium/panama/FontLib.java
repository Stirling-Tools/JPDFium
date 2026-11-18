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
import stirling.software.jpdfium.model.ShapeVerifyResult;
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
                ? PdfiumRuntime.guarded(Linker.nativeLinker().downcallHandle(symbol, FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)))
                : null;
    }

    private static final MethodHandle jpdfium_font_covers_text =
            Symbols.downcallOptional("jpdfium_font_covers_text",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS));

    private static final MethodHandle jpdfium_text_shape =
            Symbols.downcallOptional("jpdfium_text_shape",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_FLOAT, ADDRESS));

    private static final MethodHandle jpdfium_shape_verify =
            Symbols.downcallOptional("jpdfium_shape_verify",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT,
                            JAVA_INT, ADDRESS));

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
        return PdfiumRuntime.execute(() -> {
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
            }
        });
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
        return PdfiumRuntime.execute(() -> {
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
                                Integer.parseInt(row.get("cluster")),
                                Integer.parseInt(row.getOrDefault("f", "0"))));
                    }
                    return out;
                }
            } catch (JPDFiumException e) {
                throw e;
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
        });
    }

    /**
     * Verifies that splitting {@code text} at the given UTF-8 byte offset is
     * shape-safe (HarfBuzz UNSAFE_TO_BREAK semantics): shaping both sides
     * separately and concatenating must reproduce the full run's glyphs.
     *
     * <p>Redaction and content editing use this before cutting a text run:
     * an unsafe split must extend to the whole interacting cluster instead of
     * leaving silently mis-shaped halves.
     *
     * @param splitByte UTF-8 byte offset to split at; must be a cluster start
     */
    public static ShapeVerifyResult verifyShapeSplit(byte[] fontData, String text, int splitByte) {
        byte[] utf8 = utf8OrThrow(text);
        if (fontData == null || fontData.length == 0) throw new IllegalArgumentException("fontData must not be empty");
        if (splitByte < 0 || splitByte > utf8.length) {
            throw new IllegalArgumentException("splitByte out of range: " + splitByte);
        }
        return verifyShape(fontData, utf8, 0, splitByte, 0);
    }

    /**
     * Verifies that removing the UTF-8 byte span {@code [startByte, endByte)}
     * from {@code text} is shape-safe for the surviving glyphs (HarfBuzz
     * UNSAFE_TO_CONCAT semantics): shaping the remaining text must leave the
     * before- and after-parts identical to the full run minus the removed
     * clusters.
     *
     * @param startByte UTF-8 byte offset where the removed span starts; cluster start
     * @param endByte   UTF-8 byte offset where the removed span ends; cluster start
     */
    public static ShapeVerifyResult verifyShapeRemoval(
            byte[] fontData, String text, int startByte, int endByte) {
        byte[] utf8 = utf8OrThrow(text);
        if (fontData == null || fontData.length == 0) throw new IllegalArgumentException("fontData must not be empty");
        if (startByte < 0 || endByte <= startByte || endByte > utf8.length) {
            throw new IllegalArgumentException("removal span out of range: " + startByte + ".." + endByte);
        }
        return verifyShape(fontData, utf8, 1, startByte, endByte);
    }

    private static byte[] utf8OrThrow(String text) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("text must not be empty");
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static ShapeVerifyResult verifyShape(
            byte[] fontData, byte[] utf8, int mode, int a, int b) {
        if (jpdfium_shape_verify == null) {
            throw new JPDFiumException("jpdfium_shape_verify not in this native build");
        }
        return PdfiumRuntime.execute(() -> {
            try {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment ptrSeg = arena.allocate(ADDRESS);
                    JpdfiumLib.check((int) jpdfium_shape_verify.invokeExact(
                            arena.allocateFrom(JAVA_BYTE, fontData), (long) fontData.length,
                            arena.allocateFrom(JAVA_BYTE, utf8), mode, a, b, ptrSeg), "shapeVerify");
                    MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
                    String json;
                    try {
                        json = FfmHelper.readNativeString(strPtr);
                    } finally {
                        JpdfiumH.jpdfium_free_string(strPtr);
                    }
                    Map<String, String> row = NativeJsonParser.parseArray("[" + json + "]").get(0);
                    return new ShapeVerifyResult(
                            "true".equals(row.get("safe")),
                            row.getOrDefault("reason", ""),
                            Integer.parseInt(row.getOrDefault("mode", "0")),
                            Integer.parseInt(row.getOrDefault("full_glyphs", "0")),
                            Integer.parseInt(row.getOrDefault("part_glyphs", "0")),
                            Integer.parseInt(row.getOrDefault("diff", "-1")),
                            "true".equals(row.get("hb_predicted")),
                            Integer.parseInt(row.getOrDefault("unsafe_break_glyphs", "0")),
                            Integer.parseInt(row.getOrDefault("unsafe_concat_glyphs", "0")));
                }
            } catch (JPDFiumException e) {
                throw e;
            } catch (Throwable t) {
                throw new JPDFiumException(t);
            }
        });
    }

    public static byte[] getData(long page, int fontIndex) {
        return PdfiumRuntime.execute(() -> {
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
        });
    }

    public static String classify(byte[] fontData) {
        return PdfiumRuntime.execute(() -> {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                JpdfiumLib.check(JpdfiumH.jpdfium_font_classify(
                        a.allocateFrom(JAVA_BYTE, fontData), fontData.length, ptrSeg), "fontClassify");
                MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
                String result = FfmHelper.readNativeString(strPtr);
                JpdfiumH.jpdfium_free_string(strPtr);
                return result;
            }
        });
    }

    public static int fixToUnicode(long doc, int pageIndex) {
        return PdfiumRuntime.execute(() -> {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment cSeg = a.allocate(JAVA_INT);
                JpdfiumLib.check(JpdfiumH.jpdfium_font_fix_tounicode(doc, pageIndex, cSeg), "fontFixToUnicode");
                return cSeg.get(JAVA_INT, 0);
            }
        });
    }

    public static int repairWidths(long doc, int pageIndex) {
        return PdfiumRuntime.execute(() -> {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment cSeg = a.allocate(JAVA_INT);
                JpdfiumLib.check(JpdfiumH.jpdfium_font_repair_widths(doc, pageIndex, cSeg), "fontRepairWidths");
                return cSeg.get(JAVA_INT, 0);
            }
        });
    }

    public static String normalizePage(long doc, int pageIndex) {
        return PdfiumRuntime.execute(() -> {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment ptrSeg = a.allocate(ADDRESS);
                JpdfiumLib.check(JpdfiumH.jpdfium_font_normalize_page(doc, pageIndex, ptrSeg), "fontNormalizePage");
                MemorySegment strPtr = ptrSeg.get(ADDRESS, 0);
                String result = FfmHelper.readNativeString(strPtr);
                JpdfiumH.jpdfium_free_string(strPtr);
                return result;
            }
        });
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
        return PdfiumRuntime.execute(() -> {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment cSeg = a.allocate(JAVA_INT);
                int rc;
                try {
                    rc = (int) jpdfium_strip_fonts.invokeExact(doc, cSeg);
                } catch (Throwable t) { throw new RuntimeException("jpdfium_strip_fonts failed", t); }
                JpdfiumLib.check(rc, "stripFonts");
                return cSeg.get(JAVA_INT, 0);
            }
        });
    }

    public static byte[] subset(byte[] fontData, int[] codepoints, boolean retainGids) {
        return PdfiumRuntime.execute(() -> {
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
        });
    }
}
