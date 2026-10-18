package stirling.software.jpdfium.redact.pii;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The PCRE2 limits: aborted scans fail loudly, concurrent scans agree. Needs full natives. */
class PatternEngineLimitsTest {

    @Test
    void ordinaryPatternsStillMatch() {
        requireFullNative();
        try (PatternEngine engine = PatternEngine.compile("\\d{3}")) {
            assertEquals(2, engine.findAll("invoice 123 and 456").size());
        }
    }

    @Test
    void legitimateButHeavyPatternStillCompletes() {
        requireFullNative();
        try (PatternEngine engine = PatternEngine.compile("(?:\\w+\\s*){5}")) {
            String text = "alpha beta gamma delta epsilon zeta eta theta ".repeat(20);
            assertFalse(engine.findAll(text).isEmpty(),
                    "a heavy but bounded pattern must stay inside the limits");
        }
    }

    @Test
    void catastrophicBacktrackingFailsLoudly() {
        requireFullNative();
        String subject = "a".repeat(60) + "b";
        try (PatternEngine engine = PatternEngine.compile("(a+)+$")) {
            assertThrows(JPDFiumException.class, () -> engine.findAll(subject),
                    "an aborted match must surface as a failure, not as a partial result");
        }
    }

    @Test
    void engineStaysUsableAfterAnAbortedScan() {
        requireFullNative();
        try (PatternEngine engine = PatternEngine.compile("(a+)+$")) {
            assertThrows(JPDFiumException.class, () -> engine.findAll("a".repeat(60) + "b"));
            assertEquals(List.of(), engine.findAll("nothing matches here"));
        }
    }

    @Test
    void concurrentScansOnOneHandleAgree() throws Exception {
        requireFullNative();
        String text = "card 4111 1111 1111 1111, mail a@b.co, date 2024-01-31";
        try (PatternEngine engine = PatternEngine.compile(
                "\\d{4}[- ]\\d{2}[- ]\\d{2}|[\\w.]+@[\\w.]+|\\d{4} \\d{4} \\d{4} \\d{4}")) {
            List<PatternEngine.Match> expected = engine.findAll(text);
            assertFalse(expected.isEmpty(), "the fixture text must produce matches");

            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<List<PatternEngine.Match>>> tasks = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    tasks.add(() -> engine.findAll(text));
                }
                for (Future<List<PatternEngine.Match>> future : pool.invokeAll(tasks)) {
                    assertEquals(expected, future.get(),
                            "a concurrent scan disagreed with the sequential one");
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static void requireFullNative() {
        try {
            NativeLoader.ensureLoaded();
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            assumeTrue(false, "native library not available: " + t);
        }
        assumeTrue(NativeRuntime.isFull(), "needs the full native PDFium library");
    }
}
