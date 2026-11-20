package stirling.software.jpdfium.doc;

/**
 * Summary of a {@link PdfMetadataEditor#apply()} call.
 *
 * @param infoFieldsSet      standard /Info entries written
 * @param infoFieldsRemoved  standard /Info entries removed
 * @param customFieldsSet    custom /Info entries written
 * @param customFieldsRemoved custom /Info entries removed
 * @param trappedChanged     whether /Trapped was written
 * @param xmpChanged         whether the catalog XMP packet was replaced or removed
 */
public record MetadataUpdate(
        int infoFieldsSet,
        int infoFieldsRemoved,
        int customFieldsSet,
        int customFieldsRemoved,
        boolean trappedChanged,
        boolean xmpChanged
) {
    /** Shared result for an edit with no pending changes. */
    public static final MetadataUpdate NONE = new MetadataUpdate(0, 0, 0, 0, false, false);

    /** True when the edit changed at least one entry. */
    public boolean changed() {
        return infoFieldsSet + infoFieldsRemoved + customFieldsSet + customFieldsRemoved > 0
                || trappedChanged || xmpChanged;
    }
}
