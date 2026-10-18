package stirling.software.jpdfium.doc;

import java.util.Optional;

/**
 * Verification-oriented facts about a signature field, read from the EmbedPDF
 * signature model. Complements {@link Signature} with the /ByteRange coverage,
 * the revision the signature seals, and the DocMDP permission it carries.
 *
 * @param index               0-based signature field index
 * @param fieldName           fully qualified field name
 * @param signed              whether the field carries a /V dictionary with /Contents
 * @param kind                0 = signature, 1 = document timestamp
 * @param coverage            0 = whole revision, 1 = partial, 2 = malformed
 * @param revisionIndex       revision the signature seals, -1 unless coverage is whole
 * @param byteRange           the four /ByteRange integers, or null when absent
 * @param docMdpPermission    DocMDP permission (1..3), 0 when none
 * @param catalogCertification whether /Perms /DocMDP points at this signature
 * @param revisionChainValid  whether the document's revision chain was valid
 * @param filter              /Filter text
 * @param subFilter           /SubFilter text
 * @param name                /Name text
 * @param reason              /Reason text
 * @param location            /Location text
 * @param contactInfo         /ContactInfo text
 * @param signingTime         /M text
 * @param contentsLength      DER length of /Contents
 */
public record SignatureDetails(
        int index,
        String fieldName,
        boolean signed,
        int kind,
        int coverage,
        int revisionIndex,
        long[] byteRange,
        int docMdpPermission,
        boolean catalogCertification,
        boolean revisionChainValid,
        String filter,
        String subFilter,
        String name,
        String reason,
        String location,
        String contactInfo,
        String signingTime,
        long contentsLength) {

    /** /ByteRange coverage is a whole revision prefix. */
    public static final int COVERAGE_WHOLE_REVISION = 0;
    /** /ByteRange is well-formed but does not describe a revision prefix. */
    public static final int COVERAGE_PARTIAL = 1;
    /** /ByteRange missing or not four valid integers. */
    public static final int COVERAGE_MALFORMED = 2;

    /** Whether this signature covers a complete revision. */
    public boolean coversWholeRevision() {
        return coverage == COVERAGE_WHOLE_REVISION;
    }

    /** /Filter as an Optional. */
    public Optional<String> filterOpt() {
        return filter == null || filter.isEmpty() ? Optional.empty() : Optional.of(filter);
    }

    /** /SubFilter as an Optional. */
    public Optional<String> subFilterOpt() {
        return subFilter == null || subFilter.isEmpty() ? Optional.empty() : Optional.of(subFilter);
    }

    /** /Reason as an Optional. */
    public Optional<String> reasonOpt() {
        return reason == null || reason.isEmpty() ? Optional.empty() : Optional.of(reason);
    }

    /** /M signing time as an Optional. */
    public Optional<String> signingTimeOpt() {
        return signingTime == null || signingTime.isEmpty() ? Optional.empty() : Optional.of(signingTime);
    }
}
