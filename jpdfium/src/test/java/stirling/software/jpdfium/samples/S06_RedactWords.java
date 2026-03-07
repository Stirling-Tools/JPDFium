package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.redact.PdfRedactor;
import stirling.software.jpdfium.redact.RedactOptions;
import stirling.software.jpdfium.redact.RedactResult;
import stirling.software.jpdfium.redact.pii.PiiCategory;


import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 06 - Unified redaction (single {@link PdfRedactor} API).
 *
 * <p>Demonstrates the full redaction pipeline in one builder call:
 * <ul>
 *   <li>Word-list redaction via Object Fission</li>
 *   <li>Font normalization (repairs /ToUnicode + /W before extraction)</li>
 *   <li>PCRE2 JIT PII pattern matching (email, SSN, phone, credit card)</li>
 *   <li>XMP / /Info metadata redaction</li>
 * </ul>
 *
 * <p>All capabilities are configured via a single {@link RedactOptions} builder.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S06_RedactWords {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        RedactOptions opts = RedactOptions.builder()
                // Word list
                .addWord("Hello")
                .addWord("World")
                .addWord("Overview")
                .addWord("Dummy")
                .addWord("Redaction")
                .addWord("Introduction")
                .addWord("Bold")
                .addWord("10")
                .addWord("item")
                .addWord("Gradient")
                .addWord("Row")
                .addWord("brown")
                .addWord("fox")
                .addWord("Size")
                .addWord("Languages")
                .addWord("Rot")
                .addWord("confidential")
                .addWord("custom")
                .addWord("Scale")
                .addWord("6789")
                .addWord("Consider")
                .addWord("Employ")
                .addWord("VM")
                .addWord("certificat")
                // PII patterns
                .enablePiiPatterns(PiiCategory.select(
                        PiiCategory.EMAIL,
                        PiiCategory.SSN,
                        PiiCategory.PHONE,
                        PiiCategory.CREDIT_CARD))
                // Font normalization
                .normalizeFonts(true)
                // Metadata
                .redactMetadata(true)
                // Core options
                .padding(0.0f)
                .wholeWord(false)
                .boxColor(0xFF000000)
                .removeContent(true)
                .caseSensitive(false)
                .build();

        System.out.printf("S06_Redact  |  %d PDF(s)  |  words: %d  |  piiPatterns: %d%n",
                inputs.size(), opts.words().size(), opts.piiPatterns().size());
        System.out.printf("Options: normalizeFonts=%b  redactMetadata=%b  removeContent=%b%n",
                opts.normalizeFonts(), opts.redactMetadata(), opts.removeContent());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S06_Redact", input, fi + 1, inputs.size());
            Path output = SampleBase.out("redact-words", input).resolve(input.getFileName());

            RedactResult result = PdfRedactor.redact(input, opts);
            try (var doc = result.document()) {
                doc.save(output);
            }

            produced.add(output);
            System.out.printf("  %d page(s)  %d ms  wordMatches=%d  patternMatches=%d  metadata=%d%n",
                    result.pagesProcessed(), result.durationMs(),
                    result.totalMatches(), result.patternMatches().size(),
                    result.metadataFieldsRedacted());
            for (var pr : result.pageResults()) {
                System.out.printf("  page %d: %d searched, %d matched%n",
                        pr.pageIndex(), pr.wordsSearched(), pr.matchesFound());
            }
        }

        SampleBase.done("S06_Redact", produced.toArray(Path[]::new));
    }
}
