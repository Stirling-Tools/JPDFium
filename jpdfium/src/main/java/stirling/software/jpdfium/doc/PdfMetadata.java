package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.DocBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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

    private static final String[] STANDARD_TAGS = {
            "Title", "Author", "Subject", "Keywords",
            "Creator", "Producer", "CreationDate", "ModDate"
    };

    private final MemorySegment docSeg;

    private PdfMetadata(MemorySegment docSeg) {
        this.docSeg = docSeg;
    }

    /**
     * Create a PdfMetadata reader for the given document.
     *
     * @param doc raw FPDF_DOCUMENT segment (from {@code JpdfiumLib.docRawHandle})
     */
    public static PdfMetadata of(MemorySegment doc) {
        return new PdfMetadata(doc);
    }

    public Optional<String> title()        { return get("Title"); }
    public Optional<String> author()       { return get("Author"); }
    public Optional<String> subject()      { return get("Subject"); }
    public Optional<String> keywords()     { return get("Keywords"); }
    public Optional<String> creator()      { return get("Creator"); }
    public Optional<String> producer()     { return get("Producer"); }
    public Optional<String> creationDate() { return get("CreationDate"); }
    public Optional<String> modDate()      { return get("ModDate"); }

    /**
     * Get a metadata value by tag name.
     *
     * @param tag one of: Title, Author, Subject, Keywords, Creator, Producer, CreationDate, ModDate
     * @return the value, or empty if not present
     */
    public Optional<String> get(String tag) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tagSeg = arena.allocateFrom(tag);

            // Double-call pattern: first call gets required buffer size
            long needed;
            try {
                needed = (long) DocBindings.FPDF_GetMetaText.invokeExact(docSeg, tagSeg,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) { throw new RuntimeException("FPDF_GetMetaText size call failed", t); }

            if (needed <= 2) return Optional.empty();  // only null terminator

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) DocBindings.FPDF_GetMetaText.invokeExact(docSeg, tagSeg, buf, needed);
            } catch (Throwable t) { throw new RuntimeException("FPDF_GetMetaText fill call failed", t); }

            String value = FfmHelper.fromWideString(buf, needed);
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
    }

    /**
     * Returns all standard metadata tags as a map. Only non-empty values are included.
     */
    public Map<String, String> all() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String tag : STANDARD_TAGS) {
            get(tag).ifPresent(v -> map.put(tag, v));
        }
        return map;
    }

    /**
     * Returns the document permissions as a bitmask.
     * See PDF Reference Table 3.20 for bit definitions.
     */
    public int permissions() {
        try {
            return (int) DocBindings.FPDF_GetDocPermissions.invokeExact(docSeg);
        } catch (Throwable t) { throw new RuntimeException("FPDF_GetDocPermissions failed", t); }
    }

    /**
     * Returns the security handler revision, or 0 if the document is not encrypted.
     */
    public int securityHandlerRevision() {
        try {
            return (int) DocBindings.FPDF_GetSecurityHandlerRevision.invokeExact(docSeg);
        } catch (Throwable t) { throw new RuntimeException("FPDF_GetSecurityHandlerRevision failed", t); }
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
                needed = (long) DocBindings.FPDF_GetPageLabel.invokeExact(docSeg, pageIndex,
                        MemorySegment.NULL, 0L);
            } catch (Throwable t) { throw new RuntimeException("FPDF_GetPageLabel size call", t); }

            if (needed <= 2) return Optional.empty();

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) DocBindings.FPDF_GetPageLabel.invokeExact(docSeg, pageIndex, buf, needed);
            } catch (Throwable t) { throw new RuntimeException("FPDF_GetPageLabel fill call", t); }

            String label = FfmHelper.fromWideString(buf, needed);
            return label.isEmpty() ? Optional.empty() : Optional.of(label);
        }
    }
}
