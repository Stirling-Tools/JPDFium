package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import stirling.software.jpdfium.internal.ImageCodecs;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Registers the decoder redaction uses for image encodings PDFium has no codec
 * for (JPEG XL, HEIF, AVIF), so a partly covered image keeps its uncovered
 * pixels instead of being dropped. Decoding goes through {@link ImageCodecs}
 * (libvips when {@code jpdfium-vips} is on the classpath). When nothing can read
 * the bytes the upcall reports failure and the bridge keeps its fail-closed
 * fallback: the image does not survive.
 */
final class RedactImageDecoder {

    // int32_t (*)(const uint8_t* data, int64_t len, uint8_t* out, int64_t out_len)
    private static final FunctionDescriptor DECODE_DESCRIPTOR =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);

    private RedactImageDecoder() {}

    /** Hands the decoder to the bridge; a bridge without the hook is left alone. */
    static void install() {
        MethodHandle setDecoder = Symbols.downcallOptionalUnguarded(
                "jpdfium_set_image_decoder", FunctionDescriptor.ofVoid(ADDRESS));
        if (setDecoder == null) return;
        try {
            MethodHandle decode = MethodHandles.lookup().findStatic(
                    RedactImageDecoder.class, "decode",
                    MethodType.methodType(int.class, MemorySegment.class, long.class,
                            MemorySegment.class, long.class));
            // Global arena: the bridge keeps the pointer for the JVM lifetime.
            MemorySegment stub =
                    Linker.nativeLinker().upcallStub(decode, DECODE_DESCRIPTOR, Arena.global());
            setDecoder.invokeExact(stub);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            // Runs during library init, which must not fail over this: without
            // the decoder those images are still dropped whole (fail closed).
            System.err.println("jpdfium: redaction image decoder not registered; a partly"
                    + " covered JPEG XL, HEIF or AVIF image will be removed whole: " + t);
        }
    }

    /**
     * Upcall: writes one frame ({@code [width][height]} header, then RGBA) that
     * must fill {@code out} exactly. Never throws: an exception leaving an
     * upcall terminates the JVM, so every failure, an OutOfMemoryError on one
     * huge image included, returns 0 and the bridge drops the image instead.
     */
    @SuppressWarnings("unused")
    private static int decode(MemorySegment data, long len, MemorySegment out, long outLen) {
        try {
            if (len <= 0 || len > Integer.MAX_VALUE || outLen <= 8 || outLen > Integer.MAX_VALUE) {
                return 0;
            }
            byte[] frame = ImageCodecs.decodeFrame(data.reinterpret(len).toArray(JAVA_BYTE));
            if (frame == null || frame.length != outLen) return 0;
            MemorySegment.copy(frame, 0, out.reinterpret(outLen), JAVA_BYTE, 0, frame.length);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }
}
