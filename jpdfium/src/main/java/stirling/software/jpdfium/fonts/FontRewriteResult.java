package stirling.software.jpdfium.fonts;

import java.util.List;

/**
 * Result of running {@link PdfFontRewriter#rewrite(byte[], PdfFontRewriteOptions)}.
 */
public record FontRewriteResult(
        byte[] pdf,
        String reportJson,
        List<String> transformsApplied,
        boolean modified) {

    public FontRewriteResult {
        pdf = pdf == null ? new byte[0] : pdf.clone();
        reportJson = reportJson == null ? "{}" : reportJson;
        transformsApplied = transformsApplied == null ? List.of() : List.copyOf(transformsApplied);
    }

    @Override
    public byte[] pdf() {
        return pdf.clone();
    }
}
