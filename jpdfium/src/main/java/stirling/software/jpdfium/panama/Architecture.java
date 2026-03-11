package stirling.software.jpdfium.panama;

/**
 * CPU architecture types for native library loading.
 */
public enum Architecture {
    X64("x64"),
    ARM64("arm64");

    private final String key;

    Architecture(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /**
     * Detect the current architecture from system properties.
     */
    public static Architecture detect() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return ARM64;
        }
        return X64;
    }
}
