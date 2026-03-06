package stirling.software.jpdfium.redact;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * High-level PDF redaction service that applies {@link RedactOptions} to an entire document.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Open PDF</li>
 *   <li>For each page, find and redact all matching words/patterns</li>
 *   <li>Flatten annotations</li>
 *   <li>Optionally convert pages to images (most secure)</li>
 *   <li>Save result</li>
 * </ol>
 *
 * <p><b>Usage Example</b></p>
 * <pre>{@code
 * RedactOptions opts = RedactOptions.builder()
 *     .addWord("Confidential")
 *     .addWord("\\d{3}-\\d{2}-\\d{4}")  // SSN
 *     .useRegex(true)
 *     .boxColor(0xFF000000)
 *     .padding(1.5f)
 *     .wholeWord(false)
 *     .convertToImage(true)
 *     .build();
 *
 * RedactResult result = PdfRedactor.redact(Path.of("input.pdf"), opts);
 * result.document().save(Path.of("output.pdf"));
 * result.document().close();
 * }</pre>
 */
public final class PdfRedactor {

    private PdfRedactor() {}

    /**
     * Redact a PDF file using the given options.
     *
     * @param inputPath path to the input PDF
     * @param options   redaction configuration
     * @return result containing the modified document and statistics
     */
    public static RedactResult redact(Path inputPath, RedactOptions options) {
        PdfDocument doc = PdfDocument.open(inputPath);
        return redact(doc, options);
    }

    /**
     * Redact a PDF from bytes using the given options.
     *
     * @param pdfBytes raw PDF bytes
     * @param options  redaction configuration
     * @return result containing the modified document and statistics
     */
    public static RedactResult redact(byte[] pdfBytes, RedactOptions options) {
        PdfDocument doc = PdfDocument.open(pdfBytes);
        return redact(doc, options);
    }

    /**
     * Redact an already-open document using the given options.
     * The caller is responsible for closing the document.
     *
     * @param doc     open PDF document
     * @param options redaction configuration
     * @return result with statistics (same document reference)
     */
    public static RedactResult redact(PdfDocument doc, RedactOptions options) {
        long t0 = System.nanoTime();
        int totalPages = doc.pageCount();
        String[] words = options.words().toArray(new String[0]);
        List<RedactResult.PageResult> pageResults = new ArrayList<>();

        for (int i = 0; i < totalPages; i++) {
            int matchesOnPage;
            try (PdfPage page = doc.page(i)) {
                matchesOnPage = page.redactWordsEx(words, options.boxColor(), options.padding(),
                        options.wholeWord(), options.useRegex(), options.removeContent(),
                        options.caseSensitive());
                page.flatten();
            }

            if (options.convertToImage()) {
                doc.convertPageToImage(i, options.imageDpi());
            }

            pageResults.add(new RedactResult.PageResult(i, words.length, matchesOnPage));
        }

        long durationMs = (System.nanoTime() - t0) / 1_000_000;
        return new RedactResult(doc, pageResults, durationMs);
    }
}
