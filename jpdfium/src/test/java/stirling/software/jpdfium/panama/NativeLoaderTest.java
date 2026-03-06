package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeLoaderTest {

    @Test
    void detectsPlatformCorrectly() {
        String platform = NativeLoader.detectPlatform();
        assertTrue(
                platform.matches("(linux|darwin|windows)-(x64|arm64)"),
                "Unexpected platform string: " + platform);
    }

    @Test
    void loadsNativeLibrary() {
        assertDoesNotThrow(NativeLoader::ensureLoaded);
    }

    @Test
    void idempotentLoad() {
        // Calling twice must not throw
        NativeLoader.ensureLoaded();
        assertDoesNotThrow(NativeLoader::ensureLoaded);
    }
}
