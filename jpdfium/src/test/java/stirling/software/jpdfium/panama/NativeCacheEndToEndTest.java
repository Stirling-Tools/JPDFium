package stirling.software.jpdfium.panama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeCacheEndToEndTest {

    @Test
    void concurrentJvmsShareOneCacheEntryAndLeaveNoTempDirs(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        Path cacheRoot = tmp.resolve("cache");
        Path tmpDir = Files.createDirectories(tmp.resolve("tmp"));
        String classpath = System.getProperty("java.class.path");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        List<Process> processes = new ArrayList<>();
        for (int run = 0; run < 4; run++) {
            processes.add(
                    new ProcessBuilder(
                                    List.of(
                                            java,
                                            "-Djpdfium.native.cacheDir=" + cacheRoot,
                                            "-Djava.io.tmpdir=" + tmpDir,
                                            "-cp",
                                            classpath,
                                            NativeCacheProbeMain.class.getName()))
                            .redirectErrorStream(true)
                            .start());
        }
        for (Process process : processes) {
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), "probe failed:\n" + output);
        }

        Path platformDir = cacheRoot.resolve(NativeLoader.detectPlatform());
        try (Stream<Path> entries = Files.list(platformDir)) {
            long keys =
                    entries.filter(Files::isDirectory)
                            .filter(p -> !p.getFileName().toString().startsWith("."))
                            .count();
            assertEquals(1, keys, "all JVMs must share one content-addressed cache entry");
        }
        try (Stream<Path> leftovers = Files.list(tmpDir)) {
            long dirs =
                    leftovers
                            .filter(p -> p.getFileName().toString().startsWith("jpdfium-"))
                            .count();
            assertEquals(0, dirs, "cache mode must not leave per-JVM extraction dirs");
        }
    }
}
