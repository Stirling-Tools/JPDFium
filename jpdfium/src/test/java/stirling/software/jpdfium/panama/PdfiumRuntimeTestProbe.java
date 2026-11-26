package stirling.software.jpdfium.panama;

/** Test-only accessor for package-private {@link PdfiumRuntime} lock diagnostics. */
public final class PdfiumRuntimeTestProbe {

    private PdfiumRuntimeTestProbe() {}

    public static boolean domainLocked() {
        return PdfiumRuntime.domainLocked();
    }

    public static int entryDepth() {
        return PdfiumRuntime.entryDepth();
    }
}
