package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLoaderTest {

    private static final Pattern PATTERN = Pattern.compile("(linux(-musl)?|darwin|windows)-(x64|arm64)");

    @Test
    void detectsPlatformCorrectly() {
        String platform = NativeLoader.detectPlatform();
        assertTrue(
                PATTERN.matcher(platform).matches(),
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
