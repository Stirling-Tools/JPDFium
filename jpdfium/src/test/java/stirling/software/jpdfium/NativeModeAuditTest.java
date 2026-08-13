package stirling.software.jpdfium;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.Symbols;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit test for native symbol resolution mode.
 *
 * <p>Logs whether tests are executing against the real native PDFium library
 * (FULL mode) or the stub bridge (STUB mode). In FULL mode, asserts that every
 * expected symbol is resolved with zero missing symbols.
 */
@DisplayName("Native Mode & Symbol Audit")
class NativeModeAuditTest {

    @BeforeAll
    static void reportMode() {
        System.out.println("==================================================");
        System.out.println("  JPDFium Test Environment Native Mode: " + NativeRuntime.mode());
        System.out.println("  Is Stub: " + NativeRuntime.isStub());
        System.out.println("  Is Full: " + NativeRuntime.isFull());
        System.out.println("==================================================");
    }

    @Test
    @DisplayName("All binding classes load without missing symbols in FULL mode")
    void allExpectedSymbolsResolveInFullMode() throws Exception {
        // Touch representative binding classes to trigger static initialisers
        Class.forName(DocBindings.class.getName());
        Class.forName(AnnotationBindings.class.getName());
        Class.forName(PageEditBindings.class.getName());
        Class.forName(RenderBindings.class.getName());
        Class.forName(TextPageBindings.class.getName());

        if (NativeRuntime.isFull()) {
            List<String> missing = Symbols.auditMissing();
            assertTrue(missing.isEmpty(),
                "In FULL native mode, all expected symbols must resolve. Missing: " + missing);
        } else {
            System.out.println("  Skipped FULL-mode symbol audit (currently running against STUB bridge)");
        }
    }

    @Test
    @DisplayName("NativeRuntime mode is non-null and deterministic")
    void nativeModeIsDeterministic() {
        assertNotNull(NativeRuntime.mode(), "NativeRuntime.mode() must not be null");
        assertEquals(NativeRuntime.mode(), NativeRuntime.mode(), "NativeRuntime.mode() must be stable");
    }
}
