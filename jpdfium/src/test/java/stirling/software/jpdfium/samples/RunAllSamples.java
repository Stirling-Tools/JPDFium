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

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path input = SampleBase.inputPdf(args);

        System.out.println("╔==================================================╗");
        System.out.println("║            JPDFium - Run All Samples             ║");
        System.out.println("╠==================================================╣");
        System.out.printf( "║  input:  %-39s║%n", input.getFileName());
        System.out.printf( "║  output: %-39s║%n", "~/" + SampleBase.OUT_ROOT
                .toString().replaceFirst(System.getProperty("user.home") + "/", ""));
        System.out.println("╚==================================================╝");
        System.out.println();

        String[] a = args.length > 0 ? args : new String[0];
        int passed = 0, failed = 0;

        passed += run("S01_Render",        () -> S01_Render.main(a));
        passed += run("S02_TextExtract",   () -> S02_TextExtract.main(a));
        passed += run("S03_TextSearch",    () -> S03_TextSearch.main(a));
        passed += run("S04_RedactRegion",  () -> S04_RedactRegion.main(a));
        passed += run("S05_RedactPattern", () -> S05_RedactPattern.main(a));
        passed += run("S06_RedactWords",   () -> S06_RedactWords.main(a));
        passed += run("S07_SecureRedact",  () -> S07_SecureRedact.main(a));
        passed += run("S08_FullPipeline",  () -> S08_FullPipeline.main(a));
        passed += run("S09_Flatten",       () -> S09_Flatten.main(a));

        // Subtract failures from passed count
        System.out.println("\n==================================================");
        System.out.printf("Results: %d/%d samples passed%n", passed, 9);
        System.out.println("Output:  " + SampleBase.OUT_ROOT.toAbsolutePath());
        System.out.println("==================================================");
    }

    private static int run(String name, ThrowingRunnable sample) {
        System.out.println("\n┌- " + name + " --------------------------------------");
        try {
            sample.run();
            System.out.println("└- " + name + " ✓");
            return 1;
        } catch (Exception e) {
            System.err.println("└- " + name + " FAILED: " + e.getMessage());
            e.printStackTrace(System.err);
            return 0;
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
