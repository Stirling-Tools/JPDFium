package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.EmbedPdfDocumentBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PdfiumRuntime;
import stirling.software.jpdfium.panama.XmpLib;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import stirling.software.jpdfium.exception.JPDFiumException;

/**
 * Read and query PDF document metadata: title, author, subject, keywords,
 * creator, producer, creation date, and modification date.
 *
 * <p>All metadata values are extracted via PDFium's {@code FPDF_GetMetaText}
 * using the double-call buffer pattern.
 *
 * <pre>{@code
 * try (var doc = PdfDocument.open(Path.of("input.pdf"))) {
 *     PdfMetadata meta = PdfMetadata.of(doc);
 *     System.out.println("Title: " + meta.title().orElse("(none)"));
 *     System.out.println("Author: " + meta.author().orElse("(none)"));
 *     meta.all().forEach((k, v) -> System.out.println(k + " = " + v));
 * }
 * }</pre>
 */
public final class PdfMetadata {

    private static final MetadataTag[] STANDARD_TAGS = MetadataTag.values();

    private final MemorySegment rawDocSegment;
    private final PdfDocument document;

    private PdfMetadata(MemorySegment rawDocSegment, PdfDocument document) {
        this.rawDocSegment = rawDocSegment;
        this.document = document;
    }

    /**
     * Create a PdfMetadata reader for the given document. This overload also
     * exposes the XMP packet through {@link #xmp()}.
     *
     * @param document open document
     */
    public static PdfMetadata of(PdfDocument document) {
        if (document == null) throw new IllegalArgumentException("document must not be null");
        document.rawHandleForRead();
        return new PdfMetadata(null, document);
    }

    /**
     * Create a PdfMetadata reader for the given document.
     *
     * @param rawDocSegment raw FPDF_DOCUMENT segment (from {@code JpdfiumLib.docRawHandle})
     */
    public static PdfMetadata of(MemorySegment rawDocSegment) {
        return new PdfMetadata(rawDocSegment, null);
    }

    public Optional<String> title()        { return get(MetadataTag.TITLE); }
    public Optional<String> author()       { return get(MetadataTag.AUTHOR); }
    public Optional<String> subject()      { return get(MetadataTag.SUBJECT); }
    public Optional<String> keywords()     { return get(MetadataTag.KEYWORDS); }
    public Optional<String> creator()      { return get(MetadataTag.CREATOR); }
    public Optional<String> producer()     { return get(MetadataTag.PRODUCER); }
    public Optional<String> creationDate() { return get(MetadataTag.CREATION_DATE); }
    public Optional<String> modDate()      { return get(MetadataTag.MOD_DATE); }

    /**
     * Get a metadata value by tag.
     *
     * @param tag the metadata tag
     * @return the value, or empty if not present
     */
    public Optional<String> get(MetadataTag tag) {
        return get(tag.pdfKey());
    }

    /**
     * Get a metadata value by its /Info dictionary key. Custom keys are supported.
     *
     * @param pdfKey key name, for example {@code "Title"} or {@code "Department"}
     * @return the value, or empty if not present
     */
    public Optional<String> get(String pdfKey) {
        if (pdfKey == null || pdfKey.isEmpty()) {
            return Optional.empty();
        }
        MemorySegment raw = raw();
        if (!hasHandle(raw)) {
            return Optional.empty();
        }
        return PdfiumRuntime.executeBatch(() -> {
            try (Arena arena = Arena.ofConfined()) {
                return readText(arena, raw, pdfKey);
            }
        });
    }

    /**
     * Returns the custom (non-reserved) /Info dictionary entries.
     */
    public Map<String, String> custom() {
        MemorySegment raw = raw();
        if (!hasHandle(raw) || !hasCustomKeyBindings()) {
            return Map.of();
        }
        return PdfiumRuntime.executeBatch(() -> {
            try (Arena arena = Arena.ofConfined()) {
                int count = metaKeyCount(raw);
                if (count <= 0) return Map.of();
                Map<String, String> map = new LinkedHashMap<>(count);
                readCustom(map, arena, raw, count);
                return map.isEmpty() ? Map.of() : map;
            }
        });
    }

    /**
     * Returns the catalog XMP packet. Requires {@link #of(PdfDocument)} because the
     * packet is read through the bridge handle rather than the raw PDFium pointer.
     *
     * @throws IllegalStateException when this reader was created from a raw segment
     */
    public Optional<String> xmp() {
        if (document == null) {
            throw new IllegalStateException("XMP access requires PdfMetadata.of(PdfDocument)");
        }
        return XmpLib.getXmp(document.nativeHandleForRead())
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Returns the /Trapped status.
     */
    public TrappedStatus trapped() {
        MemorySegment raw = raw();
        if (!hasHandle(raw) || EmbedPdfDocumentBindings.EPDF_GetMetaTrapped == null) {
            return TrappedStatus.NOT_SET;
        }
        int code = PdfiumRuntime.executeInt(() -> {
            try {
                return (int) EmbedPdfDocumentBindings.EPDF_GetMetaTrapped.invokeExact(raw);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                return 0;
            }
        });
        return TrappedStatus.fromCode(code);
    }

    /**
     * Returns all standard and custom metadata entries. Only non-empty values are included.
     */
    public Map<String, String> all() {
        MemorySegment raw = raw();
        if (!hasHandle(raw)) return Map.of();
        return PdfiumRuntime.executeBatch(() -> {
            try (Arena arena = Arena.ofConfined()) {
                Map<String, String> map = new LinkedHashMap<>(STANDARD_TAGS.length + 2);
                for (MetadataTag tag : STANDARD_TAGS) {
                    readText(arena, raw, tag.pdfKey()).ifPresent(value -> map.put(tag.pdfKey(), value));
                }
                if (hasCustomKeyBindings()) {
                    readCustom(map, arena, raw, metaKeyCount(raw));
                }
                return map;
            }
        });
    }

    private Optional<String> readText(Arena arena, MemorySegment raw, String pdfKey) {
        if (DocBindings.FPDF_GetMetaText == null) return Optional.empty();
        try {
            MemorySegment tag = arena.allocateFrom(pdfKey);
            long needed = (long) DocBindings.FPDF_GetMetaText.invokeExact(
                    raw, tag, MemorySegment.NULL, 0L);
            if (needed <= 2) return Optional.empty();

            MemorySegment buffer = arena.allocate(needed);
            long written = (long) DocBindings.FPDF_GetMetaText.invokeExact(
                    raw, tag, buffer, needed);
            if (written < 0) return Optional.empty();

            String value = FfmHelper.fromWideString(buffer, needed);
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return Optional.empty();
        }
    }

    private void readCustom(Map<String, String> target, Arena arena, MemorySegment raw, int count) {
        for (int i = 0; i < count; i++) {
            String key = metaKey(arena, raw, i);
            if (key == null || key.isEmpty()) continue;
            readText(arena, raw, key).ifPresent(value -> target.put(key, value));
        }
    }

    private static boolean hasCustomKeyBindings() {
        return EmbedPdfDocumentBindings.EPDF_GetMetaKeyCount != null
                && EmbedPdfDocumentBindings.EPDF_GetMetaKeyName != null;
    }

    private int metaKeyCount(MemorySegment raw) {
        try {
            return (int) EmbedPdfDocumentBindings.EPDF_GetMetaKeyCount.invokeExact(raw, 1);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return 0;
        }
    }

    private String metaKey(Arena arena, MemorySegment raw, int index) {
        try {
            long needed = (long) EmbedPdfDocumentBindings.EPDF_GetMetaKeyName.invokeExact(
                    raw, index, 1, MemorySegment.NULL, 0L);
            if (needed <= 1 || needed > FfmHelper.MAX_NATIVE_STRING_BYTES) return null;

            MemorySegment buffer = arena.allocate(needed);
            long written = (long) EmbedPdfDocumentBindings.EPDF_GetMetaKeyName.invokeExact(
                    raw, index, 1, buffer, needed);
            return written <= 0 ? null : FfmHelper.fromByteString(buffer, written);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return null;
        }
    }

    private MemorySegment raw() {
        return document != null ? document.rawHandleForRead() : rawDocSegment;
    }

    private static boolean hasHandle(MemorySegment raw) {
        return raw != null
                && !raw.equals(MemorySegment.NULL)
                && raw.address() != 0;
    }

    /**
     * Returns the document permissions as a bitmask.
     * See PDF Reference Table 3.20 for bit definitions.
     */
    public int permissions() {
        MemorySegment raw = raw();
        if (!hasHandle(raw)) return -1;
        return PdfiumRuntime.execute(() -> {
            try {
                if (DocBindings.FPDF_GetDocPermissions == null) return -1;
                return (int) DocBindings.FPDF_GetDocPermissions.invokeExact(raw);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                return -1;
            }
        });
    }

    /**
     * Returns the security handler revision, or 0 if the document is not encrypted.
     */
    public int securityHandlerRevision() {
        MemorySegment raw = raw();
        if (!hasHandle(raw)) return 0;
        return PdfiumRuntime.execute(() -> {
            try {
                if (DocBindings.FPDF_GetSecurityHandlerRevision == null) return 0;
                return (int) DocBindings.FPDF_GetSecurityHandlerRevision.invokeExact(raw);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                return 0;
            }
        });
    }

    /**
     * Get the page label for a given page index (e.g., "i", "ii", "1", "2").
     *
     * @param pageIndex 0-based page index
     * @return the page label, or empty if not defined
     */
    public Optional<String> pageLabel(int pageIndex) {
        MemorySegment raw = raw();
        if (!hasHandle(raw)) return Optional.empty();
        try (Arena arena = Arena.ofConfined()) {
            long needed = PdfiumRuntime.executeLong(() -> {
                try {
                    if (DocBindings.FPDF_GetPageLabel == null) return -1L;
                    return (long) DocBindings.FPDF_GetPageLabel.invokeExact(raw, pageIndex,
                            MemorySegment.NULL, 0L);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    throw new JPDFiumException("FPDF_GetPageLabel size call", t);
                }
            });

            if (needed <= 2) return Optional.empty();

            MemorySegment bufferSegment = arena.allocate(needed);
            boolean fillOk = PdfiumRuntime.execute(() -> {
                try {
                    // FPDF_GetPageLabel returns the length it stored, or -1 on
                    // failure. A negative result means the buffer was never
                    // written, so decoding it would read uninitialized memory.
                    long written = (long) DocBindings.FPDF_GetPageLabel.invokeExact(raw, pageIndex, bufferSegment, needed);
                    return written >= 0;
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    return false;
                }
            });
            if (!fillOk) return Optional.empty();

            String label = FfmHelper.fromWideString(bufferSegment, needed);
            return label.isEmpty() ? Optional.empty() : Optional.of(label);
        }
    }
}
