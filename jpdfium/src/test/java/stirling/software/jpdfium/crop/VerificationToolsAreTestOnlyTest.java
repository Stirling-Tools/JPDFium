package stirling.software.jpdfium.crop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layering enforcement: the page-edit pipeline (crop, redact, geometry) is strictly
 * Java -> Panama/FFM -> native PDFium. External PDF tools (qpdf, ghostscript, poppler)
 * are read-only <em>grader</em> binaries used only in the test/CI module and must never
 * be invoked from the {@code transform}/{@code panama} layers that perform the edits.
 *
 * <p>This test scans those two production packages for {@code ProcessBuilder} /
 * {@code Runtime.exec} usage and fails the build if any leaks in. It runs on every
 * build (no native library required) so the "CLI is test-only" rule cannot silently
 * erode. (Pre-existing standalone helpers under {@code doc/} - e.g. the PDF/A
 * converter - are a separate, deliberate feature and out of scope here.)
 */
class VerificationToolsAreTestOnlyTest {

    private static final Pattern EXEC_PATTERN = Pattern.compile(
            "\\b(ProcessBuilder|Runtime\\.getRuntime\\(\\)\\s*\\.\\s*exec)\\b");

    private static final List<String> SCOPED_PACKAGES = List.of(
            "stirling/software/jpdfium/panama/",
            "stirling/software/jpdfium/transform/"
    );

    @Test
    void editPipelineDoesNotSpawnExternalProcesses() throws IOException {
        Path root = mainSourceRoot();
        assertTrue(Files.isDirectory(root), "main source root not found: " + root);

        StringBuilder offenders = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                boolean scoped = SCOPED_PACKAGES.stream().anyMatch(rel::startsWith);
                if (!scoped) continue;
                String content = Files.readString(f);
                if (EXEC_PATTERN.matcher(content).find()) {
                    offenders.append("  ").append(rel).append('\n');
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "external-process invocation found in the FFM edit pipeline "
                        + "(transform/panama) - qpdf/gs/poppler are verification-only and "
                        + "must live in src/test:\n" + offenders);
    }

    private static Path mainSourceRoot() {
        Path here = Path.of("src/main/java").toAbsolutePath();
        if (Files.isDirectory(here)) return here;
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path candidate = root.resolve("jpdfium/src/main/java");
            if (Files.isDirectory(candidate)) return candidate;
            root = root.getParent();
        }
        return here;
    }
}
