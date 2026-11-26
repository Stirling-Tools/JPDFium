package stirling.software.jpdfium.doc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import stirling.software.jpdfium.fonts.FontRewriteResult;
import stirling.software.jpdfium.fonts.PdfFontRewriter;
import stirling.software.jpdfium.panama.JpdfiumLib;

/**
 * Child-JVM probe that races unwarmed cold-start class initialization of PDFium
 * ({@link JpdfiumLib} and {@link PdfXConverter}) against QPDF/rewriter
 * ({@link PdfFontRewriter} and {@link PdfAConverter}) behind a {@link CyclicBarrier}.
 */
public final class ColdStartConcurrencyProbeMain {

    private ColdStartConcurrencyProbeMain() {}

    public static void main(String[] args) throws Exception {
        byte[] pdfBytes = Files.readAllBytes(Path.of(args[0]));
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Void>> futures = new ArrayList<>(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final int id = i;
                futures.add(pool.submit(() -> {
                    barrier.await();
                    if ((id & 1) == 0) {
                        JpdfiumLib.ensureInitialized();
                        PdfXOptions opts = PdfXOptions.builder()
                                .standard(PdfXStandard.PDF_X_4)
                                .useCompactCmyk()
                                .title("Cold Start X4")
                                .build();
                        byte[] out = PdfXConverter.applyMetadata(pdfBytes, opts);
                        if (out.length < 16) {
                            throw new IllegalStateException("Empty PDF/X output");
                        }
                    } else {
                        if (!PdfFontRewriter.isAvailable()) {
                            throw new IllegalStateException("PdfFontRewriter unavailable");
                        }
                        FontRewriteResult res = PdfAConverter.toPdfA2b(pdfBytes);
                        if (res.pdf().length < 16) {
                            throw new IllegalStateException("Empty PDF/A output");
                        }
                    }
                    return null;
                }));
            }
            for (Future<Void> future : futures) {
                future.get();
            }
        }
    }
}
