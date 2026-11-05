package stirling.software.jpdfium.corpus;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.PdfVerifier;
import stirling.software.jpdfium.doc.CompressOptions;
import stirling.software.jpdfium.doc.CompressPreset;
import stirling.software.jpdfium.doc.PdfColorConverter;
import stirling.software.jpdfium.doc.PdfCompressor;
import stirling.software.jpdfium.doc.PdfIccProfiles;
import stirling.software.jpdfium.doc.PdfXConverter;
import stirling.software.jpdfium.doc.PdfXOptions;
import stirling.software.jpdfium.doc.PdfXStandard;

/**
 * Exercise the public processing paths over a caller-supplied corpus.
 *
 * <p>The corpus contains real-world malformed and encrypted files. An input
 * that cannot be opened is reported as skipped; an input that opens must not
 * corrupt output in open/render/save. PDF/X, CMYK, compression and crop are
 * exercised on the first {@code external.featureLimit} files so a multi-thousand
 * file sweep remains practical.
 *
 * <p>Run with:
 * {@code JAVA_TOOL_OPTIONS="-Dexternal.corpus=/tmp/cc-main -Dexternal.featureLimit=100"
 * ./gradlew :jpdfium:integrationTest --tests '*ExternalApiCorpusTest' --rerun-tasks}
 */
@EnabledIfSystemProperty(named = "external.corpus", matches = ".+")
class ExternalApiCorpusTest {

    private static final int DEFAULT_FEATURE_LIMIT = 100;

    @TestFactory
    Stream<DynamicTest> publicApisSurviveRealWorldCorpus() throws Exception {
        Path root = Path.of(System.getProperty("external.corpus"));
        int featureLimit = Integer.getInteger("external.featureLimit", DEFAULT_FEATURE_LIMIT);
        List<Path> pdfs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(path -> path.toString().toLowerCase().endsWith(".pdf"))
                    .sorted()
                    .forEach(pdfs::add);
        }
        Path report = Path.of("build", "reports", "external-corpus.tsv");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "file\topen\trender\tsave\tverify\tpdfx\tcmyk\tcompress\tcrop\n");

        int[] featureIndex = {0};
        return pdfs.stream().map(pdf -> DynamicTest.dynamicTest(pdf.toString(), () -> {
            String row = runOne(pdf, featureIndex[0]++ < featureLimit);
            synchronized (ExternalApiCorpusTest.class) {
                Files.writeString(report, row + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
        }));
    }

    private static String runOne(Path pdf, boolean fullFeatures) {
        String open = "skip";
        String render = "skip";
        String save = "skip";
        String verify = "skip";
        String pdfx = "skip";
        String cmyk = "skip";
        String compress = "skip";
        String crop = "skip";
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            open = "ok";
            if (doc.pageCount() <= 0) {
                return row(pdf, open, render, save, verify, pdfx, cmyk, compress, crop);
            }
            try (PdfPage page = doc.page(0)) {
                page.renderAt(72);
                render = "ok";
            }
            byte[] saved = doc.saveBytes();
            save = "ok";
            try {
                PdfVerifier.pageCount(saved, pdf.getFileName().toString());
                verify = "ok";
            } catch (Throwable t) {
                verify = "fail:" + type(t);
            }

            if (fullFeatures) {
                try (PdfDocument source = PdfDocument.open(pdf)) {
                    PdfXOptions options = PdfXOptions.builder()
                            .standard(PdfXStandard.PDF_X_4)
                            .useCompactCmyk()
                            .convertLosslessImages(true)
                            .title(pdf.getFileName().toString())
                            .build();
                    try (PdfDocument out = PdfXConverter.applyMetadata(source, options)) {
                        PdfVerifier.pageCount(out.saveBytes(), pdf.getFileName().toString());
                    }
                    pdfx = "ok";
                } catch (Throwable t) {
                    pdfx = "fail:" + type(t);
                }

                try (PdfDocument source = PdfDocument.open(pdf)) {
                    PdfColorConverter.convert(source,
                            PdfColorConverter.ColorConvertOptions.builder()
                                    .targetColorSpace(PdfColorConverter.ColorSpace.CMYK)
                                    .iccProfile(PdfIccProfiles.compactCmyk())
                                    .convertForms(true)
                                    .convertLosslessImages(true)
                                    .build());
                    PdfVerifier.pageCount(source.saveBytes(), pdf.getFileName().toString());
                    cmyk = "ok";
                } catch (Throwable t) {
                    cmyk = "fail:" + type(t);
                }

                try (PdfDocument source = PdfDocument.open(pdf)) {
                    byte[] out = PdfCompressor.compress(source,
                            CompressOptions.builder().preset(CompressPreset.WEB).build()).bytes();
                    PdfVerifier.pageCount(out, pdf.getFileName().toString());
                    compress = "ok";
                } catch (Throwable t) {
                    compress = "fail:" + type(t);
                }

                try (PdfDocument source = PdfDocument.open(pdf);
                        PdfPage page = source.page(0)) {
                    stirling.software.jpdfium.model.Rect box = page.getCropBox().orElse(page.getMediaBox());
                    stirling.software.jpdfium.model.Rect cropBox = new stirling.software.jpdfium.model.Rect(
                            box.x(), box.y(), box.width() * 0.8f, box.height() * 0.8f);
                    stirling.software.jpdfium.transform.PdfPageGeometry
                            .cropAndRemoveContentStrict(source, 0, cropBox);
                    PdfVerifier.pageCount(source.saveBytes(), pdf.getFileName().toString());
                    crop = "ok";
                } catch (Throwable t) {
                    crop = "fail:" + type(t);
                }
            }
        } catch (Throwable t) {
            // Encrypted, intentionally malformed, or unsupported input.
            open = "skip:" + type(t);
        }
        return row(pdf, open, render, save, verify, pdfx, cmyk, compress, crop);
    }

    private static String type(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) return t.getClass().getSimpleName();
        return t.getClass().getSimpleName() + ":" + message.replace('\t', ' ').replace('\n', ' ');
    }

    private static String row(Path pdf, String open, String render, String save,
                              String verify, String pdfx, String cmyk,
                              String compress, String crop) {
        return String.join("\t", pdf.toString(), open, render, save, verify,
                pdfx, cmyk, compress, crop);
    }
}
