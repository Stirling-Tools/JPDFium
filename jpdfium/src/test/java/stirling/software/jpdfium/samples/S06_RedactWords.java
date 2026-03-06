package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.redact.PdfRedactor;
import stirling.software.jpdfium.redact.RedactOptions;
import stirling.software.jpdfium.redact.RedactResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 06 - Redact a word list (high-level {@link PdfRedactor} API, Object Fission).
 *
 * <p>Provides a bulk-redaction demonstration suitable for corporate auditing workflows.
 * Leveraging the Object Fission algorithm, this sample illustrates how exact keyword
 * hits can be erased securely from the PDF streams without deteriorating the surrounding
 * typography or restructuring the document layers.
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
                .padding(0.0f)
                .wholeWord(false)
                .boxColor(0xFF000000)
                .removeContent(true)
                .caseSensitive(false)
                .build();

        System.out.printf("S06_RedactWords  |  %d PDF(s)  |  words: %s%n",
                inputs.size(), opts.words());
        System.out.printf("Options: wholeWord=%b  caseSensitive=%b  removeContent=%b%n",
                opts.wholeWord(), opts.caseSensitive(), opts.removeContent());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S06_RedactWords", input, fi + 1, inputs.size());
            Path output = SampleBase.out("redact-words", input).resolve(input.getFileName());

            RedactResult result = PdfRedactor.redact(input, opts);
            try (var doc = result.document()) {
                doc.save(output);
            }

            produced.add(output);
            System.out.printf("  %d page(s)  %d ms  totalMatches=%d%n",
                    result.pagesProcessed(), result.durationMs(), result.totalMatches());
            for (var pr : result.pageResults()) {
                System.out.printf("  page %d: %d searched, %d matched%n",
                        pr.pageIndex(), pr.wordsSearched(), pr.matchesFound());
            }
        }

        SampleBase.done("S06_RedactWords", produced.toArray(Path[]::new));
    }
}
