package stirling.software.jpdfium.samples;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * System validation suite executing all samples sequentially with memory management.
 *
 * <p>Each sample runs independently with:
 * <ul>
 *   <li>Per-sample timeout (default 120s, configurable via {@code -Dsample.timeout=<seconds>})</li>
 *   <li>Explicit GC between samples to release native memory</li>
 *   <li>500ms pause between samples for heap stabilization</li>
 * </ul>
 *
 * <p>Failures in one sample are caught and reported but do not stop the rest.
 *
 * <p>All output lands in {@code samples-output/} next to the working directory.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class RunAllSamples {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path input = SampleBase.inputPdf(args);

        int timeoutSec = Integer.getInteger("sample.timeout", DEFAULT_TIMEOUT_SECONDS);

        System.out.println("+==================================================+");
        System.out.println("|            JPDFium - Run All Samples             |");
        System.out.println("+==================================================+");
        System.out.printf( "|  input:   %-38s|%n", input.getFileName());
        System.out.printf( "|  output:  %-38s|%n", "~/" + SampleBase.OUT_ROOT
                .toString().replaceFirst(System.getProperty("user.home") + "/", ""));
        System.out.printf( "|  timeout: %-38s|%n", timeoutSec + "s per sample");
        System.out.println("+==================================================+");
        System.out.println();

        String[] a = args.length > 0 ? args : new String[0];
        int passed = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();

        record Sample(String name, ThrowingRunnable action) {}

        List<Sample> samples = List.of(
            new Sample("S01_Render",        () -> S01_Render.main(a)),
            new Sample("S02_TextExtract",   () -> S02_TextExtract.main(a)),
            new Sample("S03_TextSearch",    () -> S03_TextSearch.main(a)),
            new Sample("S04_Metadata",      () -> S04_Metadata.main(a)),
            new Sample("S05_Bookmarks",     () -> S05_Bookmarks.main(a)),
            new Sample("S06_Redact",        () -> S06_RedactWords.main(a)),
            new Sample("S07_Annotations",   () -> S07_Annotations.main(a)),
            new Sample("S08_FullPipeline",  () -> S08_FullPipeline.main(a)),
            new Sample("S09_Flatten",       () -> S09_Flatten.main(a)),
            new Sample("S10_Signatures",    () -> S10_Signatures.main(a)),
            new Sample("S11_Attachments",   () -> S11_Attachments.main(a)),
            new Sample("S12_Links",         () -> S12_Links.main(a)),
            new Sample("S13_PageImport",    () -> S13_PageImport.main(a)),
            new Sample("S14_StructureTree", () -> S14_StructureTree.main(a)),
            new Sample("S15_Thumbnails",    () -> S15_Thumbnails.main(a)),
            new Sample("S16_PageEditing",   () -> S16_PageEditing.main(a)),
            new Sample("S17_NUpLayout",     () -> S17_NUpLayout.main(a)),
            new Sample("S18_Repair",        () -> S18_Repair.main(a)),
            new Sample("S19_PdfToImages",   () -> S19_PdfToImages.main(a)),
            new Sample("S20_ImagesToPdf",   () -> S20_ImagesToPdf.main(a)),
            new Sample("S21_Thumbnails",    () -> S21_Thumbnails.main(a)),
            new Sample("S22_MergeSplit",    () -> S22_MergeSplit.main(a)),
            new Sample("S23_Watermark",     () -> S23_Watermark.main(a)),
            new Sample("S24_TableExtract",  () -> S24_TableExtract.main(a)),
            new Sample("S25_PageGeometry",  () -> S25_PageGeometry.main(a)),
            new Sample("S26_HeaderFooter",  () -> S26_HeaderFooter.main(a)),
            new Sample("S27_Security",      () -> S27_Security.main(a)),
            new Sample("S28_DocInfo",       () -> S28_DocInfo.main(a)),
            new Sample("S29_RenderOptions", () -> S29_RenderOptions.main(a)),
            new Sample("S30_FormReader",    () -> S30_FormReader.main(a)),
            new Sample("S31_ImageExtract",  () -> S31_ImageExtract.main(a)),
            new Sample("S32_PageObjects",   () -> S32_PageObjects.main(a)),
            new Sample("S33_Encryption",    () -> S33_Encryption.main(a)),
            new Sample("S34_Linearizer",    () -> S34_Linearizer.main(a)),
            new Sample("S35_Overlay",       () -> S35_Overlay.main(a)),
            new Sample("S36_AnnotationBuilder", () -> S36_AnnotationBuilder.main(a)),
            new Sample("S37_PathDrawer",    () -> S37_PathDrawer.main(a)),
            new Sample("S38_JavaScriptInspector", () -> S38_JavaScriptInspector.main(a)),
            new Sample("S39_WebLinks",      () -> S39_WebLinks.main(a)),
            new Sample("S40_PageBoxes",     () -> S40_PageBoxes.main(a)),
            new Sample("S41_VersionConverter", () -> S41_VersionConverter.main(a)),
            new Sample("S42_BoundedText",   () -> S42_BoundedText.main(a)),
            new Sample("S43_StreamOptimizer", () -> S43_StreamOptimizer.main(a)),
            new Sample("S45_PageInterleaver", () -> S45_PageInterleaver.main(a)),
            new Sample("S46_NamedDestinations", () -> S46_NamedDestinations.main(a)),
            new Sample("S47_BlankPageDetector", () -> S47_BlankPageDetector.main(a)),
            new Sample("S48_EmbedPdfAnnotations", () -> S48_EmbedPdfAnnotations.main(a)),
            new Sample("S49_NativeEncryption", () -> S49_NativeEncryption.main(a)),
            new Sample("S50_NativeRedaction", () -> S50_NativeRedaction.main(a)),
            new Sample("S51_Compress",      () -> S51_Compress.main(a)),
            new Sample("S52_BookmarkEditor", () -> S52_BookmarkEditor.main(a)),
            new Sample("S53_BarcodeGenerate", () -> S53_BarcodeGenerate.main(a)),
            new Sample("S54_PageReorder",   () -> S54_PageReorder.main(a)),
            new Sample("S55_ColorConvert",  () -> S55_ColorConvert.main(a)),
            new Sample("S56_Booklet",       () -> S56_Booklet.main(a)),
            new Sample("S58_Analytics",     () -> S58_Analytics.main(a)),
            new Sample("S59_FormFill",      () -> S59_FormFill.main(a)),
            new Sample("S60_AutoCrop",      () -> S60_AutoCrop.main(a)),
            new Sample("S61_SearchHighlight", () -> S61_SearchHighlight.main(a)),
            new Sample("S62_PageSplit2Up",  () -> S62_PageSplit2Up.main(a)),
            new Sample("S63_PageLabels",    () -> S63_PageLabels.main(a)),
            new Sample("S64_LinkValidation", () -> S64_LinkValidation.main(a)),
            new Sample("S65_Posterize",     () -> S65_Posterize.main(a)),
            new Sample("S66_PdfDiff",       () -> S66_PdfDiff.main(a)),
            new Sample("S67_AutoDeskew",    () -> S67_AutoDeskew.main(a)),
            new Sample("S68_FontAudit",     () -> S68_FontAudit.main(a)),
            new Sample("S69_PdfAConversion", () -> S69_PdfAConversion.main(a)),
            new Sample("S70_PageScaling",   () -> S70_PageScaling.main(a)),
            new Sample("S71_MarginAdjust",  () -> S71_MarginAdjust.main(a)),
            new Sample("S72_SelectiveFlatten", () -> S72_SelectiveFlatten.main(a)),
            new Sample("S73_AnnotExport",   () -> S73_AnnotExport.main(a)),
            new Sample("S74_ImageReplace",  () -> S74_ImageReplace.main(a)),
            new Sample("S75_LongImage",     () -> S75_LongImage.main(a)),
            new Sample("S76_DuplicateDetect", () -> S76_DuplicateDetect.main(a)),
            new Sample("S77_ColumnExtract", () -> S77_ColumnExtract.main(a)),
            new Sample("S78_ImageDpi",      () -> S78_ImageDpi.main(a)),
            new Sample("S79_PageMirror",    () -> S79_PageMirror.main(a)),
            new Sample("S80_Background",    () -> S80_Background.main(a)),
            new Sample("S81_ReadingOrder",  () -> S81_ReadingOrder.main(a)),
            new Sample("S82_ResourceDedup", () -> S82_ResourceDedup.main(a)),
            new Sample("S83_TocGenerate",   () -> S83_TocGenerate.main(a)),
            new Sample("S84_SelectiveRaster", () -> S84_SelectiveRaster.main(a)),
            new Sample("S85_AnnotStats",    () -> S85_AnnotStats.main(a)),
            new Sample("S86_PosterizeSizes", () -> S86_PosterizeSizes.main(a)),
            new Sample("S87_AutoCropMargins", () -> S87_AutoCropMargins.main(a))
        );

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sample-runner");
            t.setDaemon(true);
            return t;
        });

        try {
            for (int i = 0; i < samples.size(); i++) {
                Sample s = samples.get(i);
                System.out.printf("[%d/%d] %s ...%n", i + 1, samples.size(), s.name());
                System.out.flush();

                Future<?> future = executor.submit(() -> {
                    try {
                        s.action().run();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                try {
                    future.get(timeoutSec, TimeUnit.SECONDS);
                    System.out.println("  -> " + s.name() + " OK");
                    passed++;
                } catch (TimeoutException e) {
                    future.cancel(true);
                    System.err.println("  -> " + s.name() + " TIMEOUT (>" + timeoutSec + "s)");
                    failures.add(s.name() + " (TIMEOUT)");
                    failed++;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof RuntimeException re && re.getCause() != null) {
                        cause = re.getCause();
                    }
                    System.err.println("  -> " + s.name() + " FAILED: " + cause.getMessage());
                    failures.add(s.name() + ": " + cause.getMessage());
                    failed++;
                }

                // Memory management: GC + pause between samples
                System.gc();
                Thread.sleep(500);
            }
        } finally {
            executor.shutdownNow();
        }

        // Summary
        System.out.println();
        System.out.println("+==================================================+");
        System.out.printf( "|  Results: %d/%d passed, %d failed                  %n",
                passed, samples.size(), failed);
        System.out.println("+==================================================+");
        if (!failures.isEmpty()) {
            System.out.println("Failures:");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
        }
        System.out.println("Output: " + SampleBase.OUT_ROOT.toAbsolutePath());
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
