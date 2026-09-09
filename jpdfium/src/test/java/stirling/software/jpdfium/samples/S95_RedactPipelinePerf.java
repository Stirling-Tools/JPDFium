package stirling.software.jpdfium.samples;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.redact.PdfRedactor;
import stirling.software.jpdfium.redact.RedactOptions;
import stirling.software.jpdfium.redact.RedactResult;
import stirling.software.jpdfium.redact.pii.PiiCategory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;

/**
 * SAMPLE 95 - Redaction pipeline performance harness.
 *
 * <p>Measures end-to-end {@link PdfRedactor} cost (mark+scan+Object Fission
 * + audit + serialize) on a fresh synthetic multi-page document per iteration,
 * so every iteration does real work. A pure {@code open+save} baseline is
 * subtracted so the reported cost isolates the redaction pipeline from
 * parse/serialize overhead. Reported per operation: p50/p95/p99/max in ms.
 *
 * <p>Results are written to {@code samples-output/S95_redact-pipeline-perf/perf.csv}.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S95_RedactPipelinePerf {

    private static final int PAGES = 30;
    private static final int WARMUP = 4;
    private static final int ITERATIONS = 12;

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path outDir = SampleBase.out("S95_redact-pipeline-perf");
        Path csv = outDir.resolve("perf.csv");

        byte[] pdf = generateReportPdf(PAGES);
        System.out.printf("S95_RedactPerf | %d-page synthetic report PDF (%d bytes)%n",
                PAGES, pdf.length);

        Sample sample = new Sample(pdf);
        sample.run("baseline-open-save", pdfBytes -> {
            try (PdfDocument doc = PdfDocument.open(pdfBytes)) {
                doc.saveBytes();
            }
        });
        sample.run("redact-words", S95_RedactPipelinePerf::redactWords);
        sample.run("redact-nomatch", S95_RedactPipelinePerf::redactNoMatch);
        sample.run("redact-words+pii", S95_RedactPipelinePerf::redactWordsPii);
        sample.run("redact-words+pii+sanitize", S95_RedactPipelinePerf::redactWordsPiiSanitize);
        sample.run("redact+normalizeFonts", S95_RedactPipelinePerf::redactNormalizeFonts);

        StringBuilder sb = new StringBuilder();
        sb.append("op,p50_ms,p95_ms,p99_ms,max_ms,op_cost_p50_ms\n");
        sample.writeTo(sb);

        Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);
        System.out.println(sb);
        System.out.printf("%nPerf results written to %s%n", csv.toAbsolutePath());
    }

    private static RedactResult redactWords(byte[] pdf) {
        RedactOptions opts = RedactOptions.builder()
                .addWord("Confidential")
                .addWord("secret")
                .removeContent(true)
                .build();
        return runAndClose(pdf, opts);
    }

    /** Word absent from the whole document: exercises the full-scan, zero-match path on every page. */
    private static RedactResult redactNoMatch(byte[] pdf) {
        RedactOptions opts = RedactOptions.builder()
                .addWord("zzznomatchzzz")
                .removeContent(true)
                .build();
        return runAndClose(pdf, opts);
    }

    private static RedactResult redactWordsPii(byte[] pdf) {
        RedactOptions opts = RedactOptions.builder()
                .addWord("Confidential")
                .addWord("secret")
                .enablePiiPatterns(PiiCategory.select(
                        PiiCategory.EMAIL, PiiCategory.SSN,
                        PiiCategory.PHONE, PiiCategory.CREDIT_CARD))
                .removeContent(true)
                .build();
        return runAndClose(pdf, opts);
    }

    private static RedactResult redactWordsPiiSanitize(byte[] pdf) {
        RedactOptions opts = RedactOptions.builder()
                .addWord("Confidential")
                .addWord("secret")
                .enablePiiPatterns(PiiCategory.select(
                        PiiCategory.EMAIL, PiiCategory.SSN,
                        PiiCategory.PHONE, PiiCategory.CREDIT_CARD))
                .sanitizeStructure(true)
                .removeContent(true)
                .build();
        return runAndClose(pdf, opts);
    }

    private static RedactResult redactNormalizeFonts(byte[] pdf) {
        RedactOptions opts = RedactOptions.builder()
                .addWord("Confidential")
                .addWord("secret")
                .normalizeFonts(true)
                .removeContent(true)
                .build();
        return runAndClose(pdf, opts);
    }

    /** Runs the pipeline, serializes the result, and closes - mirroring a real caller. */
    private static RedactResult runAndClose(byte[] pdf, RedactOptions opts) {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            RedactResult result = PdfRedactor.redact(doc, opts);
            result.document().saveBytes();
            result.document().close();
            return result;
        }
    }

    // ------------------------------------------------------------------
    // Synthetic content
    // ------------------------------------------------------------------

    private static byte[] generateReportPdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < pages; p++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs =
                             new PDPageContentStream(doc, page)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    float y = 760;
                    for (int line = 0; line < 22 && y > 40; line++) {
                        String text = lineText(p, line);
                        cs.beginText();
                        cs.newLineAtOffset(56, y);
                        cs.showText(text);
                        cs.endText();
                        y -= 32;
                    }
                }
            }
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                doc.save(bos);
                return bos.toByteArray();
            }
        }
    }

    private static String lineText(int page, int line) {
        String[] filler = {
                "The quarterly review of the department budget was completed",
                "All staff must treat the contents of this document as Confidential",
                "Progress on the Alpha project remains on schedule and under budget",
                "The 123-45-6789 record was verified against the master ledger",
                "Contact john.doe@example.com or call (555) 010-9988 for access",
                "A secret key rotation is planned for the next maintenance window",
                "Expenses were categorized and reconciled with the general ledger",
                "The committee approved the new procurement guidelines last month",
                "Figures from Q3 indicate a steady increase in overall throughput",
                "Please ensure that all confidential drafts are shredded securely",
                "Vendor invoices were matched against purchase orders and receipts",
                "The audit trail shows no unauthorized access during the period",
                "Deliverables for the upcoming release were frozen this morning",
                "Card number 4532 1234 5678 9012 was used for the travel booking",
                "Minutes from the steering group were circulated to all members",
                "Risk assessment flagged the migration plan as requiring attention",
                "Employee onboarding documentation was updated for the new year",
                "The internal wiki now hosts the revised operational playbooks",
                "Quarterly targets were met despite the temporary staffing gap",
                "Storage quotas were raised to accommodate the archival backlog",
                "The final sign-off is pending until the legal review concludes",
                "Backups are verified nightly and retained for six months",
        };
        return filler[(page * 7 + line) % filler.length];
    }

    // ------------------------------------------------------------------
    // Harness (fresh document per iteration)
    // ------------------------------------------------------------------

    private static final class Sample {
        private final byte[] pdf;
        private final LinkedHashMap<String, double[]> totals = new LinkedHashMap<>();
        private double[] baseline;

        interface Op { void run(byte[] pdf) throws Exception; }

        Sample(byte[] pdf) { this.pdf = pdf; }

        void run(String label, Op op) {
            for (int i = 0; i < WARMUP; i++) {
                try { op.run(pdf); } catch (Exception e) { throw new RuntimeException(e); }
            }
            double[] samples = new double[ITERATIONS];
            for (int i = 0; i < ITERATIONS; i++) {
                long t0 = System.nanoTime();
                try { op.run(pdf); } catch (Exception e) { throw new RuntimeException(e); }
                samples[i] = (System.nanoTime() - t0) / 1_000_000.0;  // ms
            }
            if ("baseline-open-save".equals(label)) baseline = samples; else totals.put(label, samples);
        }

        void writeTo(StringBuilder sb) {
            if (baseline == null) return;
            Arrays.sort(baseline);
            double base50 = percentile(baseline, 0.50);
            for (var e : totals.entrySet()) {
                double[] s = e.getValue();
                Arrays.sort(s);
                double p50 = percentile(s, 0.50);
                double p95 = percentile(s, 0.95);
                double p99 = percentile(s, 0.99);
                double max = s[s.length - 1];
                double opCost50 = Math.max(0, p50 - base50);
                sb.append(String.format("%s,%.1f,%.1f,%.1f,%.1f,%.1f%n",
                        e.getKey(), p50, p95, p99, max, opCost50));
                System.out.printf("%-26s p50=%8.1fms p95=%8.1fms p99=%8.1fms max=%8.1fms"
                                + "  | pipeline cost vs baseline: %.1fms%n",
                        e.getKey(), p50, p95, p99, max, opCost50);
            }
            System.out.printf("%-26s p50=%8.1fms (open + full serialize only)%n", "baseline", base50);
        }

        private static double percentile(double[] sorted, double q) {
            return sorted[(int) Math.round(q * (sorted.length - 1))];
        }
    }
}
