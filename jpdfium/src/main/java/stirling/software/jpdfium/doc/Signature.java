package stirling.software.jpdfium.doc;

import java.util.Optional;

/**
 * A digital signature in a PDF document.
 *
 * @param index       0-based index of the signature
 * @param subFilter   encoding (e.g., "adbe.pkcs7.detached")
 * @param reason      signing reason, if present
 * @param signingTime signing time string (format: D:YYYYMMDDHHMMSS+XX'YY')
 * @param contents    raw DER-encoded signature bytes
 * @param permission  DocMDP permission level (1=no changes, 2=fill/sign, 3=annotate)
 */
public record Signature(
        int index,
        Optional<String> subFilter,
        Optional<String> reason,
        Optional<String> signingTime,
        byte[] contents,
        int permission
) {}
