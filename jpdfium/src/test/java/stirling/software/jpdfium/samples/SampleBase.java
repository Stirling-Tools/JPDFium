package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.panama.NativeLoader;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;

/**
 * Shared utilities for all JPDFium sample classes.
 *
 * <p><strong>IntelliJ setup (one-time):</strong><br>
 * Run -> Edit Configurations -> Templates -> Application -> VM Options:<br>
 * {@code --enable-native-access=ALL-UNNAMED}
 *
 * <p><strong>Output location:</strong><br>
 * {@code {working-dir}/samples-output/<feature>/<pdf-name>/}
 * The absolute path is printed when each sample runs.
 *
 * <p><strong>Input PDFs:</strong><br>
 * Automatically discovers every {@code *.pdf} in {@code src/test/resources/}.
 * Drop a new PDF there and every sample will pick it up on the next run.
 * Pass explicit paths as CLI args to override (processed instead of resources).
 */
final class SampleBase {

    /** Root output directory. The absolute path is printed when each sample starts so you can find the output. */
    static final Path OUT_ROOT;
    static final Path PROJECT_ROOT;

    static {
        // Find the project root by looking for settings.gradle.kts or falling back to user.dir
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        PROJECT_ROOT = (current != null) ? current : Path.of(System.getProperty("user.dir")).toAbsolutePath();

        String override = System.getProperty("samples.output");
        OUT_ROOT = (override != null)
                ? Path.of(override).toAbsolutePath()
                : PROJECT_ROOT.resolve("samples-output");
    }

    private SampleBase() {}

    /** Validates library initialization state. Required before accessing native handles to prevent segmentation faults. */
    static void ensureNative() {
        NativeLoader.ensureLoaded();
    }

    /**
     * Returns all PDF inputs to process:
     * <ul>
     *   <li>If {@code args} is non-empty, uses those paths verbatim.</li>
     *   <li>Otherwise, scans the test-resource classpath root for every {@code *.pdf},
     *       sorted by filename, <b>excluding {@code pdfs/repair/}</b> (those are only
     *       for the repair sample - see {@link #inputRepairPdfs}).</li>
     * </ul>
     */
    static List<Path> inputPdfs(String[] args) throws Exception {
        if (args != null && args.length > 0) {
            return Arrays.stream(args).map(Path::of).toList();
        }
        // Enumerate ALL classpath roots (classes dir + resources dir are separate entries)
        Enumeration<URL> roots = SampleBase.class.getClassLoader().getResources("");
        List<Path> pdfs = new ArrayList<>();
        String repairSep = java.io.File.separator + "repair" + java.io.File.separator;
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            if (!"file".equals(root.getProtocol())) continue;
            Path rootPath = Path.of(root.toURI());
            try (Stream<Path> walk = Files.walk(rootPath)) {
                walk.filter(p -> p.toString().endsWith(".pdf")
                             && !p.toString().contains(repairSep))
                    .forEach(pdfs::add);
            }
        }
        assert !pdfs.isEmpty() :
                "No *.pdf files found on classpath - add PDFs to src/test/resources/";
        Collections.sort(pdfs);
        return pdfs;
    }

    /**
     * Returns PDFs from {@code pdfs/repair/} on the classpath - intended exclusively
     * for the repair sample (S18). Other samples use {@link #inputPdfs} which
     * excludes this directory.
     *
     * <p>If {@code args} is non-empty, those paths are used verbatim instead.
     */
    static List<Path> inputRepairPdfs(String[] args) throws Exception {
        if (args != null && args.length > 0) {
            return Arrays.stream(args).map(Path::of).toList();
        }
        URL dir = SampleBase.class.getClassLoader().getResource("pdfs/repair");
        if (dir == null || !"file".equals(dir.getProtocol())) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(Path.of(dir.toURI()))) {
            return stream.filter(p -> p.toString().endsWith(".pdf"))
                         .sorted()
                         .toList();
        }
    }

    /**
     * Convenience: first PDF (or minimal.pdf fallback) for samples that only need one.
     * Kept for backward compatibility.
     */
    static Path inputPdf(String[] args) throws Exception {
        return inputPdfs(args).get(0);
    }

    /**
     * Creates and returns the output directory for a specific PDF within a feature.
     * e.g. {@code out("render", Path.of("minimal.pdf"))} ->
     *      {@code {CWD}/samples-output/render/minimal/}
     */
    static Path out(String feature, Path pdf) throws Exception {
        return out(feature);
    }

    /**
     * Creates and returns a shared output directory for a feature (no per-PDF split).
     * Use when a sample produces a single output regardless of input count.
     */
    static Path out(String feature) throws Exception {
        Path dir = OUT_ROOT.resolve(feature);
        Files.createDirectories(dir);
        return dir;
    }

    /** Filename without extension, e.g. {@code "minimal.pdf"} -> {@code "minimal"}. */
    static String stem(Path pdf) {
        String name = pdf.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Prints a completion banner listing all produced output files. */
    static void done(String sampleName, Path... outputs) {
        System.out.println();
        System.out.println("+==================================================+");
        System.out.printf( "|  %-48s|%n", sampleName + " - DONE");
        System.out.println("+==================================================+");
        for (Path p : outputs) {
            String rel = p.toAbsolutePath().toString()
                    .replaceFirst(System.getProperty("user.home"), "~");
            System.out.printf("|  -> %-44s|%n", rel.length() > 44 ? "..." + rel.substring(rel.length() - 43) : rel);
        }
        System.out.println("+==================================================+");
    }

    static void section(String title) {
        System.out.println("\n-- " + title + " --------------------------------------");
    }

    static void pdfHeader(String sample, Path pdf, int index, int total) {
        System.out.printf("%n[%d/%d] %s  ->  %s%n", index, total, sample, pdf.getFileName());
    }
}
