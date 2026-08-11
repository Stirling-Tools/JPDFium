package stirling.software.jpdfium.panama;

import java.lang.foreign.SymbolLookup;

/**
 * Execution mode and runtime diagnostics for the native JPDFium bridge.
 */
public final class NativeRuntime {

    public enum NativeMode {
        /** Real native PDFium library loaded. All FPDF_* symbols are expected to resolve. */
        FULL,
        /** Stub bridge loaded (no native PDFium backend linked). */
        STUB
    }

    private NativeRuntime() {}

    /**
     * Current execution mode (FULL or STUB).
     */
    public static NativeMode mode() {
        return isStub() ? NativeMode.STUB : NativeMode.FULL;
    }

    /**
     * True when running against the stub bridge rather than full PDFium.
     */
    public static boolean isStub() {
        NativeLoader.ensureLoaded();
        return SymbolLookup.loaderLookup().find("FPDF_InitLibrary").isEmpty();
    }

    /**
     * True when running against the real native PDFium library.
     */
    public static boolean isFull() {
        return !isStub();
    }

    /**
     * Rethrows JVM-level {@link Error} instances (e.g., OutOfMemoryError, StackOverflowError)
     * so exception handlers never silently swallow fatal runtime failures.
     */
    public static void rethrowFatal(Throwable t) {
        if (t instanceof Error e) {
            throw e;
        }
    }
}
