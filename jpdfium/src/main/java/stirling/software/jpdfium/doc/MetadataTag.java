package stirling.software.jpdfium.doc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Standard PDF document metadata tags.
 */
public enum MetadataTag {
    TITLE("Title"),
    AUTHOR("Author"),
    SUBJECT("Subject"),
    KEYWORDS("Keywords"),
    CREATOR("Creator"),
    PRODUCER("Producer"),
    CREATION_DATE("CreationDate"),
    MOD_DATE("ModDate");

    private final String pdfKey;
    private final MemorySegment nativeKey;

    MetadataTag(String pdfKey) {
        this.pdfKey = pdfKey;
        this.nativeKey = Arena.global().allocateFrom(pdfKey);
    }

    /** The PDF metadata key string (e.g. "Title", "Author"). */
    public String pdfKey() {
        return pdfKey;
    }

    /** The pre-allocated UTF-8 null-terminated native segment for this key. */
    public MemorySegment nativeKey() {
        return nativeKey;
    }
}
