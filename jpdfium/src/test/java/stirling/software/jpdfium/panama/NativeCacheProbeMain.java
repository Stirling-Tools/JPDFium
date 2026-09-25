package stirling.software.jpdfium.panama;

/** Loads the native bridge in a fresh JVM so cache reuse can be observed. */
public final class NativeCacheProbeMain {

    private NativeCacheProbeMain() {}

    public static void main(String[] args) {
        NativeLoader.ensureLoaded();
    }
}
