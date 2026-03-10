package stirling.software.jpdfium.samples;

import java.nio.file.Path;

/**
 * System validation suite executing all samples sequentially.
 * Use this to verify native memory management and prevent regression of core usage patterns.
 *
 * <p>Each sample runs independently; failures in one sample are caught and reported
 * but do not stop the rest.
 *
 * <p>All output lands in {@code samples-output/} next to the working directory.
 * Open that folder in your file manager to inspect all produced PDFs and PNGs.
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class RunAllSamples {

    static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path input = SampleBase.inputPdf(args);

        System.out.println("|            JPDFium - Run All Samples             |");
        System.out.printf( "|  input:  %-39s|%n", input.getFileName());
        System.out.printf( "|  output: %-39s|%n", "~/" + SampleBase.OUT_ROOT
                .toString().replaceFirst(System.getProperty("user.home") + "/", ""));
        System.out.println();

        String[] a = args.length > 0 ? args : new String[0];
        int passed = 0, failed = 0;

        passed += run("S01_Render",        () -> S01_Render.main(a));
        passed += run("S02_TextExtract",   () -> S02_TextExtract.main(a));
        passed += run("S03_TextSearch",    () -> S03_TextSearch.main(a));
        passed += run("S04_Metadata",      () -> S04_Metadata.main(a));
        passed += run("S05_Bookmarks",     () -> S05_Bookmarks.main(a));
        passed += run("S06_Redact",        () -> S06_RedactWords.main(a));
        passed += run("S07_Annotations",   () -> S07_Annotations.main(a));
        passed += run("S08_FullPipeline",  () -> S08_FullPipeline.main(a));
        passed += run("S09_Flatten",       () -> S09_Flatten.main(a));
        passed += run("S10_Signatures",    () -> S10_Signatures.main(a));
        passed += run("S11_Attachments",   () -> S11_Attachments.main(a));
        passed += run("S12_Links",         () -> S12_Links.main(a));
        passed += run("S13_PageImport",    () -> S13_PageImport.main(a));
        passed += run("S14_StructureTree", () -> S14_StructureTree.main(a));
        passed += run("S15_Thumbnails",    () -> S15_Thumbnails.main(a));
        passed += run("S16_PageEditing",   () -> S16_PageEditing.main(a));
        passed += run("S17_NUpLayout",     () -> S17_NUpLayout.main(a));
        passed += run("S18_Repair",        () -> S18_Repair.main(a));
        passed += run("S19_PdfToImages",   () -> S19_PdfToImages.main(a));
        passed += run("S20_ImagesToPdf",   () -> S20_ImagesToPdf.main(a));
        passed += run("S21_Thumbnails",    () -> S21_Thumbnails.main(a));
        passed += run("S22_MergeSplit",    () -> S22_MergeSplit.main(a));
        passed += run("S23_Watermark",     () -> S23_Watermark.main(a));
        passed += run("S24_TableExtract",  () -> S24_TableExtract.main(a));
        passed += run("S25_PageGeometry",  () -> S25_PageGeometry.main(a));
        passed += run("S26_HeaderFooter",  () -> S26_HeaderFooter.main(a));
        passed += run("S27_Security",      () -> S27_Security.main(a));
        passed += run("S28_DocInfo",       () -> S28_DocInfo.main(a));
        passed += run("S29_RenderOptions", () -> S29_RenderOptions.main(a));
        passed += run("S30_FormReader",    () -> S30_FormReader.main(a));
        passed += run("S31_ImageExtract",  () -> S31_ImageExtract.main(a));
        passed += run("S32_PageObjects",   () -> S32_PageObjects.main(a));
        passed += run("S33_Encryption",    () -> S33_Encryption.main(a));
        passed += run("S34_Linearizer",    () -> S34_Linearizer.main(a));
        passed += run("S35_Overlay",       () -> S35_Overlay.main(a));
        passed += run("S36_AnnotationBuilder", () -> S36_AnnotationBuilder.main(a));
        passed += run("S37_PathDrawer",    () -> S37_PathDrawer.main(a));
        passed += run("S38_JavaScriptInspector", () -> S38_JavaScriptInspector.main(a));
        passed += run("S39_WebLinks",      () -> S39_WebLinks.main(a));
        passed += run("S40_PageBoxes",     () -> S40_PageBoxes.main(a));
        passed += run("S41_VersionConverter", () -> S41_VersionConverter.main(a));
        passed += run("S42_BoundedText",   () -> S42_BoundedText.main(a));
        passed += run("S43_StreamOptimizer", () -> S43_StreamOptimizer.main(a));
        passed += run("S45_PageInterleaver", () -> S45_PageInterleaver.main(a));
        passed += run("S46_NamedDestinations", () -> S46_NamedDestinations.main(a));
        passed += run("S47_BlankPageDetector", () -> S47_BlankPageDetector.main(a));
        passed += run("S48_EmbedPdfAnnotations", () -> S48_EmbedPdfAnnotations.main(a));
        passed += run("S49_NativeEncryption", () -> S49_NativeEncryption.main(a));
        passed += run("S50_NativeRedaction", () -> S50_NativeRedaction.main(a));
        passed += run("S51_Compress",      () -> S51_Compress.main(a));
        passed += run("S52_BookmarkEditor", () -> S52_BookmarkEditor.main(a));
        passed += run("S53_BarcodeGenerate", () -> S53_BarcodeGenerate.main(a));
        passed += run("S54_PageReorder",   () -> S54_PageReorder.main(a));
        passed += run("S55_ColorConvert",  () -> S55_ColorConvert.main(a));
        passed += run("S56_Booklet",       () -> S56_Booklet.main(a));
        passed += run("S58_Analytics",     () -> S58_Analytics.main(a));
        passed += run("S59_FormFill",      () -> S59_FormFill.main(a));

        int total = 59;
        System.out.printf("Results: %d/%d samples passed%n", passed, total);
        System.out.println("Output:  " + SampleBase.OUT_ROOT.toAbsolutePath());
    }

    private static int run(String name, ThrowingRunnable sample) {
        try {
            sample.run();
            System.out.println("-- " + name + " OK");
            return 1;
        } catch (Exception e) {
            System.err.println("-- " + name + " FAILED: " + e.getMessage());
            e.printStackTrace(System.err);
            return 0;
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
