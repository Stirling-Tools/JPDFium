package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfFontAuditor;

import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 68 - Font Audit.
 *
 * <p>Demonstrates PdfFontAuditor: enumerating all fonts used in a PDF document,
 * reporting embedding status, weight, flags, and font data size.
 */
public class S68_FontAudit {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S68_FontAudit  |  %d PDF(s)%n", inputs.size());

        for (Path input : inputs) {
            String stem = SampleBase.stem(input);

            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfFontAuditor.AuditReport report = PdfFontAuditor.audit(doc);

                System.out.printf("  %s: %d fonts (%d embedded, %d non-embedded)%n",
                        stem, report.totalFontCount(),
                        report.embeddedCount(), report.nonEmbeddedCount());

                for (PdfFontAuditor.FontInfo fi : report.fonts()) {
                    System.out.printf("    %-30s embedded=%-5b weight=%4d flags=0x%04x data=%dB pages=%s%n",
                            fi.baseName(), fi.embedded(), fi.weight(), fi.flags(),
                            fi.fontDataSize(),
                            fi.pages().size() > 5
                                    ? fi.pages().size() + " pages"
                                    : fi.pages().toString());
                }

                if (!report.nonEmbeddedFonts().isEmpty()) {
                    System.out.println("    ⚠ Non-embedded fonts:");
                    for (PdfFontAuditor.FontInfo fi : report.nonEmbeddedFonts()) {
                        System.out.printf("      - %s (family: %s)%n", fi.baseName(), fi.familyName());
                    }
                }
            }
        }

        SampleBase.done("S68_FontAudit");
    }
}
