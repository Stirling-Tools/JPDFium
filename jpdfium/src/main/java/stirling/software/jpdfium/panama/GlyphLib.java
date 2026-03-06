package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for glyph-level redaction (HarfBuzz + ICU BiDi + grapheme clusters).
 */
public final class GlyphLib {

    public static final int GLYPH_LIGATURE_AWARE = 0x0001;
    public static final int GLYPH_BIDI_AWARE     = 0x0002;
    public static final int GLYPH_GRAPHEME_SAFE  = 0x0004;
    public static final int GLYPH_REMOVE_STREAM  = 0x0008;
    public static final int GLYPH_ALL            = 0x000F;

    public record GlyphRedactResult(int matchCount, String resultJson) {}

    static { NativeLoader.ensureLoaded(); }

    private GlyphLib() {}

    public static GlyphRedactResult redactGlyphAware(long page, String[] words,
                                                      int argb, float padding, int flags) {
        if (words == null || words.length == 0) return new GlyphRedactResult(0, "[]");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrs = a.allocate(ADDRESS, words.length);
            for (int i = 0; i < words.length; i++) {
                ptrs.setAtIndex(ADDRESS, i, a.allocateFrom(words[i]));
            }
            MemorySegment countSeg = a.allocate(JAVA_INT);
            MemorySegment jsonSeg  = a.allocate(ADDRESS);
            JpdfiumLib.check(JpdfiumH.jpdfium_redact_glyph_aware(page, ptrs, words.length,
                    argb, padding, flags, countSeg, jsonSeg), "redactGlyphAware");
            int count = countSeg.get(JAVA_INT, 0);
            MemorySegment strPtr = jsonSeg.get(ADDRESS, 0);
            String json = strPtr.reinterpret(Long.MAX_VALUE).getString(0);
            JpdfiumH.jpdfium_free_string(strPtr);
            return new GlyphRedactResult(count, json);
        }
    }
}
