package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Nested {@code executeTeardown} does not switch the execution context: it
 * takes the {@code nested()} fast path and runs the operation inline, so it
 * inherits whatever context the enclosing thread already had.
 *
 * <p>That is deliberate for ordinary work cascading into retirement (a library
 * close reaching a session close must stay permitted), but it means a teardown
 * reached from inside admitted ordinary work does not gain the authority to
 * reject ordinary calls. This test documents the actual behavior rather than
 * the one a reader might assume from the name.
 */
@Isolated
class NestedTeardownContextTest {

    @AfterEach
    void restoreAndAssertClean() {
        if (PdfiumRuntime.state() != PdfiumRuntime.State.RUNNING) {
            PdfiumRuntime.restoreRunningForTests();
        }
        assertEquals(0, PdfiumRuntime.entryDepth());
        assertFalse(PdfiumRuntime.domainLocked());
    }

    @Test
    void nestedTeardownInheritsOrdinaryContext() {
        PdfiumRuntime.execute(() -> {
            PdfiumRuntime.executeTeardown(() -> {
                // Inherited ORDINARY, so ordinary work is still permitted.
                PdfiumRuntime.execute(() -> { });
                PdfiumRuntime.enterLeaf();
                try {
                    assertEquals(1, PdfiumRuntime.domainHoldCount());
                } finally {
                    PdfiumRuntime.exitLeaf();
                }
            });
        });
        assertEquals(0, PdfiumRuntime.entryDepth());
    }

    @Test
    void topLevelTeardownDoesSwitchContext() {
        // Entered from outside the domain, teardown really does take effect:
        // ordinary work is refused, and the refusal leaves nothing behind.
        PdfiumRuntime.quiesce();
        try {
            PdfiumRuntime.executeTeardown(() -> {
                assertThrows(JPDFiumException.class, () -> PdfiumRuntime.execute(() -> null));
                assertThrows(JPDFiumException.class, () -> {
                    PdfiumRuntime.enterLeaf();
                    PdfiumRuntime.exitLeaf();
                });
                assertEquals(0, PdfiumRuntime.entryDepth(),
                        "refused work inside teardown left an entry");
                // Nested teardown of another resource stays permitted.
                PdfiumRuntime.executeTeardown(() -> { });
            });
        } finally {
            PdfiumRuntime.restoreRunningForTests();
        }
        assertEquals(0, PdfiumRuntime.entryDepth());
        assertFalse(PdfiumRuntime.domainLocked());
    }
}
