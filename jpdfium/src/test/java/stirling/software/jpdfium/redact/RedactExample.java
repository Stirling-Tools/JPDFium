package stirling.software.jpdfium.redact;

import stirling.software.jpdfium.PdfDocument;

import java.nio.file.Path;

/**
 * Example: PDF redaction using jpdfium-redact.
 *
 * <p>Demonstrates the full Stirling-PDF-style auto-redaction workflow:
 * word list, regex patterns, color/padding options, and PDF-to-image conversion.
 *
 * <p><b>Run:</b>
 * <pre>
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp jpdfium-redact.jar:jpdfium-document.jar:jpdfium-bindings.jar:jpdfium-core.jar \
 *        stirling.software.jpdfium.redact.RedactExample /path/to/input.pdf
 * </pre>
 */
public class RedactExample {

    public static void main(String[] args) throws Exception {
        Path input  = args.length > 0 ? Path.of(args[0]) : Path.of("/tmp/test.pdf");
        Path output = Path.of("/tmp/redacted.pdf");

        // Example 1: Simple word redaction - black boxes over specific words
        System.out.println("=== Example 1: Simple word redaction ===");
        {
            RedactOptions opts = RedactOptions.builder()
                    .addWord("Confidential")
                    .addWord("Top-Secret")
                    .boxColor(0xFF000000)    // black (ARGB)
                    .removeContent(true)     // truly remove text, not just paint over
                    .build();

            RedactResult result = PdfRedactor.redact(input, opts);
            result.document().save(output);
            result.document().close();

            System.out.printf("  Processed %d pages in %d ms%n",
                    result.pagesProcessed(), result.durationMs());
            System.out.println("  Saved -> " + output);
        }

        // Example 2: Regex-based redaction - SSNs, emails, credit card numbers
        System.out.println("\n=== Example 2: Regex-based redaction ===");
        {
            RedactOptions opts = RedactOptions.builder()
                    .addWord("\\d{3}-\\d{2}-\\d{4}")                              // SSN
                    .addWord("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")   // Email
                    .addWord("\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}")          // Credit card
                    .useRegex(true)
                    .boxColor(0xFFFF0000)    // red boxes
                    .padding(1.5f)           // 1.5pt padding
                    .build();

            RedactResult result = PdfRedactor.redact(input, opts);
            result.document().save(Path.of("/tmp/redacted-regex.pdf"));
            result.document().close();

            System.out.printf("  Processed %d pages in %d ms%n",
                    result.pagesProcessed(), result.durationMs());
        }

        // Example 3: Whole-word matching with padding
        System.out.println("\n=== Example 3: Whole-word matching ===");
        {
            RedactOptions opts = RedactOptions.builder()
                    .addWord("John")
                    .addWord("Doe")
                    .wholeWord(true)         // won't match "Johnson" or "Does"
                    .padding(2.0f)           // extra 2pt border around matches
                    .boxColor(0xFF333333)    // dark gray
                    .build();

            RedactResult result = PdfRedactor.redact(input, opts);
            result.document().save(Path.of("/tmp/redacted-wholeword.pdf"));
            result.document().close();

            System.out.printf("  Processed %d pages in %d ms%n",
                    result.pagesProcessed(), result.durationMs());
        }

        // Example 4: Most secure - redact + convert to PDF-Image
        //   This re-renders every page as a flat image. No extractable text
        //   or object metadata survives. Ideal for leaked-document prevention.
        System.out.println("\n=== Example 4: Redact + Convert to PDF-Image ===");
        {
            RedactOptions opts = RedactOptions.builder()
                    .addWord("SECRET")
                    .addWord("CLASSIFIED")
                    .convertToImage(true)    // flatten to image-only pages
                    .imageDpi(200)           // high quality
                    .removeContent(true)
                    .build();

            RedactResult result = PdfRedactor.redact(input, opts);
            result.document().save(Path.of("/tmp/redacted-image.pdf"));
            result.document().close();

            System.out.printf("  Processed %d pages in %d ms (image conversion)%n",
                    result.pagesProcessed(), result.durationMs());
        }

        // Example 5: Redact in-place on an already-open document
        System.out.println("\n=== Example 5: In-place redaction on open document ===");
        {
            try (PdfDocument doc = PdfDocument.open(input)) {
                RedactOptions opts = RedactOptions.builder()
                        .addWord("password")
                        .addWord("secret")
                        .wholeWord(false)       // match even as part of larger words
                        .removeContent(true)
                        .build();

                RedactResult result = PdfRedactor.redact(doc, opts);
                // The same 'doc' is now modified - save it
                doc.save(Path.of("/tmp/redacted-inplace.pdf"));

                System.out.printf("  Processed %d pages in %d ms%n",
                        result.pagesProcessed(), result.durationMs());
            }
        }

        System.out.println("\nAll examples completed successfully.");
    }
}
