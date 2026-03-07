package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.Annotation;
import stirling.software.jpdfium.doc.PdfAnnotations;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 07 - List and inspect page annotations.
 *
 * <p>Demonstrates reading annotation types, rectangles, flags, and contents
 * from PDF pages using the direct PDFium FFM annotation API.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S07_Annotations {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S07_Annotations  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S07_Annotations", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                int pageCount = doc.pageCount();
                int totalAnnots = 0;

                for (int p = 0; p < pageCount; p++) {
                    try (PdfPage page = doc.page(p)) {
                        List<Annotation> annots = page.annotations();
                        if (!annots.isEmpty()) {
                            System.out.printf("  Page %d: %d annotation(s)%n", p, annots.size());
                            for (Annotation a : annots) {
                                System.out.printf("    [%d] %-12s rect=(%.1f,%.1f,%.1f,%.1f) flags=0x%X%s%n",
                                        a.index(), a.type(),
                                        a.rect().x(), a.rect().y(), a.rect().width(), a.rect().height(),
                                        a.flags(),
                                        a.contents().map(c -> " \"" + truncate(c, 40) + "\"").orElse(""));
                            }
                            totalAnnots += annots.size();
                        }
                    }
                }

                if (totalAnnots == 0) {
                    System.out.println("  (no annotations)");
                } else {
                    System.out.printf("  Total: %d annotation(s) across %d page(s)%n",
                            totalAnnots, pageCount);
                }
            }
        }

        SampleBase.done("S07_Annotations");
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
