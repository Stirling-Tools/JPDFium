package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.redact.pii.PiiCategory;
import stirling.software.jpdfium.redact.pii.PiiPatterns;
import stirling.software.jpdfium.redact.pii.PiiRedactOptions;
import stirling.software.jpdfium.redact.pii.PiiRedactResult;
import stirling.software.jpdfium.redact.pii.PiiRedactor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 10 - PII redaction with pattern matching and entity detection.
 *
 * <p>Demonstrates the full PII redaction pipeline:
 * <ol>
 *   <li>Font normalization (repair broken /ToUnicode maps)</li>
 *   <li>PCRE2 JIT pattern matching (email, SSN, phone, credit card)</li>
 *   <li>Named entity recognition via FlashText</li>
 *   <li>Word-level and glyph-level redaction</li>
 *   <li>Metadata stripping</li>
 * </ol>
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S10_PiiRedact {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        PiiRedactOptions opts = PiiRedactOptions.builder()
                .addWord("Confidential")
                .addWord("nom")
                .addWord("adresse")
                .addWord("DRAFT")
                .addWord("Helvetica")
                .addWord("Hello")
                .addWord("World")
                .addWord("Dummy")
                .addWord("Redaction")
                .addWord("全")
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
                .enablePiiPatterns(PiiPatterns.select(
                        PiiCategory.EMAIL,
                        PiiCategory.SSN,
                        PiiCategory.PHONE,
                        PiiCategory.CREDIT_CARD))
                //.addEntity("John Smith", "PERSON")
                //.addEntity("Acme Corp", "ORGANIZATION")
                .normalizeFonts(true)
                .redactMetadata(true)
                .removeContent(true)
                .boxColor(0xFF000000)
                .build();

        System.out.printf("S10_PiiRedact  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S10_PiiRedact", input, fi + 1, inputs.size());
            Path output = SampleBase.out("pii-redact", input).resolve(input.getFileName());

            PiiRedactResult result = PiiRedactor.redact(input, opts);
            try (var doc = result.document()) {
                doc.save(output);
            }

            produced.add(output);
            System.out.printf("  %d word matches, %d pattern matches, %d entities, %d ms%n",
                    result.totalWordMatches(),
                    result.patternMatches().size(),
                    result.entityMatches().size(),
                    result.durationMs());
        }

        SampleBase.done("S10_PiiRedact", produced.toArray(Path[]::new));
    }
}
