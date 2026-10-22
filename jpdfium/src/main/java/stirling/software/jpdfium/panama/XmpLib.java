package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

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
            return PdfiumRuntime.execute(() -> {
                JpdfiumLib.check(JpdfiumH.jpdfium_xmp_redact_patterns(doc, ptrs, patterns.length, countSeg),
                        "xmpRedactPatterns");
                return countSeg.get(JAVA_INT, 0);
            });
        }
    }

    public static void metadataStrip(long doc, String[] keys) {
        if (keys == null || keys.length == 0) return;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrs = a.allocate(ADDRESS, keys.length);
            for (int i = 0; i < keys.length; i++) {
                ptrs.setAtIndex(ADDRESS, i, a.allocateFrom(keys[i]));
            }
            PdfiumRuntime.execute(() -> {
                JpdfiumLib.check(JpdfiumH.jpdfium_metadata_strip(doc, ptrs, keys.length), "metadataStrip");
            });
        }
    }

    public static void metadataStripAll(long doc) {
        PdfiumRuntime.execute(() -> {
            JpdfiumLib.check(JpdfiumH.jpdfium_metadata_strip_all(doc), "metadataStripAll");
        });
    }

    /**
     * Replace the catalog XMP packet. A null or empty packet removes the stream.
     *
     * @param doc    native document handle
     * @param packet UTF-8 XMP XML, or null to remove the packet
     */
    public static void setXmp(long doc, byte[] packet) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = MemorySegment.NULL;
            int length = 0;
            if (packet != null && packet.length > 0) {
                length = packet.length;
                data = arena.allocate(length);
                MemorySegment.copy(packet, 0, data, JAVA_BYTE, 0, length);
            }
            MemorySegment segment = data;
            int size = length;
            PdfiumRuntime.execute(() -> {
                JpdfiumLib.check(JpdfiumH.jpdfium_metadata_set_xmp(doc, segment, size), "metadataSetXmp");
            });
        }
    }

    /**
     * Read the catalog XMP packet.
     *
     * @param doc native document handle
     * @return the UTF-8 XML packet, or empty when the catalog has no metadata stream
     */
    public static Optional<byte[]> getXmp(long doc) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            MemorySegment length = arena.allocate(JAVA_INT);
            int rc = PdfiumRuntime.executeInt(() ->
                    JpdfiumH.jpdfium_metadata_get_xmp(doc, out, length));
            if (rc == JpdfiumLib.ERR_NOT_FOUND) return Optional.empty();
            JpdfiumLib.check(rc, "metadataGetXmp");

            int size = length.get(JAVA_INT, 0);
            if (size <= 0) return Optional.empty();
            MemorySegment data = out.get(ADDRESS, 0);
            try {
                return Optional.of(data.reinterpret(size).toArray(JAVA_BYTE));
            } finally {
                JpdfiumH.jpdfium_free_buffer(data);
            }
        }
    }
}
