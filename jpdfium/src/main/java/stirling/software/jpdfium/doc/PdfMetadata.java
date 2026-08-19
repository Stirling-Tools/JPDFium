package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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

    private final MemorySegment rawDocSegment;

    private PdfMetadata(MemorySegment rawDocSegment) {
        this.rawDocSegment = rawDocSegment;
    }

    /**
     * Create a PdfMetadata reader for the given document.
     *
     * @param rawDocSegment raw FPDF_DOCUMENT segment (from {@code JpdfiumLib.docRawHandle})
     */
    public static PdfMetadata of(MemorySegment rawDocSegment) {
        return new PdfMetadata(rawDocSegment);
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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tagSegment = arena.allocateFrom(tag.pdfKey());

            long needed;
            try {
                if (DocBindings.FPDF_GetMetaText == null) return Optional.empty();
                needed = (long) DocBindings.FPDF_GetMetaText.invokeExact(rawDocSegment, tagSegment,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                return Optional.empty();
            }

            if (needed <= 2) return Optional.empty();

            MemorySegment bufferSegment = arena.allocate(needed);
            try {
                long _ = (long) DocBindings.FPDF_GetMetaText.invokeExact(rawDocSegment, tagSegment, bufferSegment, needed);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                return Optional.empty();
            }

            String value = FfmHelper.fromWideString(bufferSegment, needed);
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
    }

    /**
     * Returns all standard metadata tags as a map. Only non-empty values are included.
     */
    public Map<String, String> all() {
        Map<String, String> map = new LinkedHashMap<>();
        for (MetadataTag tag : MetadataTag.values()) {
            get(tag).ifPresent(v -> map.put(tag.pdfKey(), v));
        }
        return map;
    }

    /**
     * Returns the document permissions as a bitmask.
     * See PDF Reference Table 3.20 for bit definitions.
     */
    public int permissions() {
        try {
            if (DocBindings.FPDF_GetDocPermissions == null) return -1;
            return (int) DocBindings.FPDF_GetDocPermissions.invokeExact(rawDocSegment);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return -1;
        }
    }

    /**
     * Returns the security handler revision, or 0 if the document is not encrypted.
     */
    public int securityHandlerRevision() {
        try {
            if (DocBindings.FPDF_GetSecurityHandlerRevision == null) return 0;
            return (int) DocBindings.FPDF_GetSecurityHandlerRevision.invokeExact(rawDocSegment);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return 0;
        }
    }

    /**
     * Get the page label for a given page index (e.g., "i", "ii", "1", "2").
     *
     * @param pageIndex 0-based page index
     * @return the page label, or empty if not defined
     */
    public Optional<String> pageLabel(int pageIndex) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) DocBindings.FPDF_GetPageLabel.invokeExact(rawDocSegment, pageIndex,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) {
                throw new JPDFiumException("FPDF_GetPageLabel size call", t);
            }

            if (needed <= 2) return Optional.empty();

            MemorySegment bufferSegment = arena.allocate(needed);
            try {
                long _ = (long) DocBindings.FPDF_GetPageLabel.invokeExact(rawDocSegment, pageIndex, bufferSegment, needed);
            } catch (Throwable t) {
                throw new JPDFiumException("FPDF_GetPageLabel fill call", t);
            }

            String label = FfmHelper.fromWideString(bufferSegment, needed);
            return label.isEmpty() ? Optional.empty() : Optional.of(label);
        }
    }
}
