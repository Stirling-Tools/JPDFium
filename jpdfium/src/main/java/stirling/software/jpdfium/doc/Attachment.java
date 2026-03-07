package stirling.software.jpdfium.doc;

import java.util.Optional;

/**
 * Record representing an embedded file attachment in a PDF document.
 *
 * @param index the 0-based index of this attachment in the document
 * @param name  the name (filename) of the attachment
 * @param data  the attachment file content, empty if retrieval failed
 */
public record Attachment(
        int index,
        String name,
        byte[] data
) {
    /**
     * Returns the file extension of the attachment name, if present.
     */
    public Optional<String> extension() {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            return Optional.of(name.substring(dot + 1));
        }
        return Optional.empty();
    }

    /**
     * Returns true if this attachment has non-empty data.
     */
    public boolean hasData() {
        return data.length > 0;
    }
}
