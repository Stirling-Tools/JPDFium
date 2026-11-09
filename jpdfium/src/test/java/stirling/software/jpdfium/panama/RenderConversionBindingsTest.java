package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RenderConversionBindingsTest {

    @Test
    void argbPackerWritesExactWords() throws Throwable {
        assumeTrue(NativeRuntime.isFull(), "needs the real native library");
        MethodHandle handle = RenderConversionBindings.JPDFIUM_RGBA_TO_ARGB32;
        assertNotNull(handle, "jpdfium_rgba_to_argb32 must resolve in FULL mode");

        // 17 pixels: one SIMD block plus a scalar tail.
        int n = 17;
        byte[] rgba = new byte[n * 4];
        for (int i = 0; i < n; i++) {
            rgba[i * 4] = (byte) (i * 7);
            rgba[i * 4 + 1] = (byte) (i * 11);
            rgba[i * 4 + 2] = (byte) (i * 13);
            rgba[i * 4 + 3] = (byte) (i * 3);
        }
        int[] out;
        try (Arena arena = Arena.ofConfined()) {
            // The linker accepts only native segments, so stage both buffers.
            MemorySegment src = arena.allocate((long) n * 4);
            MemorySegment dst = arena.allocate((long) n * 4);
            MemorySegment.copy(rgba, 0, src, ValueLayout.JAVA_BYTE, 0, n * 4);
            int rc = (int) handle.invokeExact(src, dst, n);
            assertEquals(0, rc);
            out = dst.asSlice(0, (long) n * 4).toArray(ValueLayout.JAVA_INT);
        }
        for (int i = 0; i < n; i++) {
            int expected = ((rgba[i * 4 + 3] & 0xFF) << 24)
                    | ((rgba[i * 4] & 0xFF) << 16)
                    | ((rgba[i * 4 + 1] & 0xFF) << 8)
                    | (rgba[i * 4 + 2] & 0xFF);
            assertEquals(expected, out[i], "pixel " + i);
        }
    }
}
