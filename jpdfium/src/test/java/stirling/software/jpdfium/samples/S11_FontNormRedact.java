package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.redact.PdfRedactor;
import stirling.software.jpdfium.redact.RedactOptions;
import stirling.software.jpdfium.redact.RedactResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 11 - Font normalization before redaction ({@link PdfRedactor} API).
 *
 * <p>Same builder pattern as S06_RedactWords, plus two extra toggles:
 * <ul>
 *   <li>{@code normalizeFonts(true)} — repairs broken /ToUnicode maps and
 *       /W width tables <b>before</b> text extraction, so redaction patterns
 *       hit reliably even on garbled PDFs.</li>
 *   <li>{@code incrementalSave(true)} — writes only changed objects, keeping
 *       the document handle live for further operations.</li>
 * </ul>
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S11_FontNormRedact {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        RedactOptions opts = RedactOptions.builder()
                .addWord("Hello")
                .addWord("Languages")
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
                .normalizeFonts(true)
                .incrementalSave(true)
                .build();

        System.out.printf("S11_FontNormRedact  |  %d PDF(s)  |  words: %s%n",
                inputs.size(), opts.words());
        System.out.printf("Options: normalizeFonts=%b  incrementalSave=%b  removeContent=%b%n",
                opts.normalizeFonts(), opts.incrementalSave(), opts.removeContent());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S11_FontNormRedact", input, fi + 1, inputs.size());
            Path output = SampleBase.out("font-norm-redact", input).resolve(input.getFileName());

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

        SampleBase.done("S11_FontNormRedact", produced.toArray(Path[]::new));
    }
}
