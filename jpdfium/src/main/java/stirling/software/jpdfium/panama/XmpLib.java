package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for XMP metadata and /Info dictionary redaction (pugixml + qpdf).
 */
public final class XmpLib {

    static { NativeLoader.ensureLoaded(); }

    private XmpLib() {}

    public static int redactPatterns(long doc, String[] patterns) {
        if (patterns == null || patterns.length == 0) return 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrs = a.allocate(ADDRESS, patterns.length);
            for (int i = 0; i < patterns.length; i++) {
                ptrs.setAtIndex(ADDRESS, i, a.allocateFrom(patterns[i]));
            }
            MemorySegment countSeg = a.allocate(JAVA_INT);
            JpdfiumLib.check(JpdfiumH.jpdfium_xmp_redact_patterns(doc, ptrs, patterns.length, countSeg),
                    "xmpRedactPatterns");
            return countSeg.get(JAVA_INT, 0);
        }
    }

    public static void metadataStrip(long doc, String[] keys) {
        if (keys == null || keys.length == 0) return;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrs = a.allocate(ADDRESS, keys.length);
            for (int i = 0; i < keys.length; i++) {
                ptrs.setAtIndex(ADDRESS, i, a.allocateFrom(keys[i]));
            }
            JpdfiumLib.check(JpdfiumH.jpdfium_metadata_strip(doc, ptrs, keys.length), "metadataStrip");
        }
    }

    public static void metadataStripAll(long doc) {
        JpdfiumLib.check(JpdfiumH.jpdfium_metadata_strip_all(doc), "metadataStripAll");
    }
}
