package stirling.software.jpdfium.doc;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Shared {@code FPDF_FILEWRITE} upcall sink for native saves; copies in fixed slices so the
 * upcall never allocates. Used by version-convert and N-up saves.
 */
final class FileWriteSink {

    // FPDF_FILEWRITE struct: { int version; void* WriteBlock; }
    // On 64-bit: 4 bytes int + 4 bytes padding + 8 bytes function pointer = 16 bytes
    static final StructLayout FPDF_FILEWRITE_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("version"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("WriteBlock"));

    // C unsigned long: 8 bytes on LP64, 4 on Windows LLP64.
    static final MemoryLayout C_LONG_LAYOUT = Linker.nativeLinker().canonicalLayouts().get("long");

    static final FunctionDescriptor DESCRIPTOR =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, C_LONG_LAYOUT);

    // Resolved once: the int/long variant depends only on the platform.
    static final MethodHandle CALLBACK = resolveCallback();

    static final int CHUNK_SIZE = 64 * 1024;

    // Thread-local sink for the upcall to write into. Short-lived and always
    // removed in end(); cold version-convert / N-up paths only (not renders).
    private static final ThreadLocal<OutputStream> SINK = new ThreadLocal<>();

    // IOException thrown by the sink mid-save; rethrown by the caller afterwards.
    private static final ThreadLocal<IOException> FAILURE = new ThreadLocal<>();

    // Reusable fixed-size block-copy buffer for the WriteBlock upcall.
    private static final ThreadLocal<byte[]> CHUNK = new ThreadLocal<>();

    private FileWriteSink() {}

    private static MethodHandle resolveCallback() {
        boolean longCarrier = C_LONG_LAYOUT.byteSize() != 4;
        MethodType type = longCarrier
                ? MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class,
                        long.class)
                : MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class,
                        int.class);
        String name = longCarrier ? "writeBlockCallback" : "writeBlockCallbackInt";
        try {
            return MethodHandles.lookup().findStatic(FileWriteSink.class, name, type);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Arm the sink for a save; pair with {@link #end()} in a {@code finally}. */
    static void begin(OutputStream out) {
        SINK.set(out);
        FAILURE.remove();
        CHUNK.set(new byte[CHUNK_SIZE]);
    }

    /** Clear all per-save state. */
    static void end() {
        SINK.remove();
        FAILURE.remove();
        CHUNK.remove();
    }

    /** Build the {@code FPDF_FILEWRITE} struct (version=1) in {@code arena}. */
    static MemorySegment allocateStruct(Arena arena) {
        MemorySegment stub = Linker.nativeLinker().upcallStub(CALLBACK, DESCRIPTOR, arena);
        MemorySegment fileWrite = arena.allocate(FPDF_FILEWRITE_LAYOUT);
        fileWrite.set(JAVA_INT, 0, 1);
        fileWrite.set(
                ADDRESS,
                FPDF_FILEWRITE_LAYOUT.byteOffset(
                        MemoryLayout.PathElement.groupElement("WriteBlock")),
                stub);
        return fileWrite;
    }

    /** An IOException recorded by the callback during the save, or {@code null}. */
    static IOException failure() {
        return FAILURE.get();
    }

    @SuppressWarnings("unused")
    private static int writeBlockCallback(MemorySegment pThis, MemorySegment pData, long size) {
        // The receiver is always the struct we allocated; verify the binding.
        if (pThis == null || pThis.equals(MemorySegment.NULL)) {
            return 0;
        }
        OutputStream out = SINK.get();
        byte[] buf = CHUNK.get();
        if (out == null || buf == null || pData == null || size <= 0) {
            return 0;
        }
        // pData is unsized; reinterpret to the reported length. Never throw here
        // (it would unwind through native code) - fail the write instead.
        MemorySegment src = pData.reinterpret(size);
        long remaining = size;
        long offset = 0;
        try {
            while (remaining > 0) {
                int n = (int) Math.min(remaining, buf.length);
                MemorySegment.copy(src, JAVA_BYTE, offset, buf, 0, n);
                out.write(buf, 0, n);
                offset += n;
                remaining -= n;
            }
        } catch (IOException e) {
            FAILURE.set(e);
            return 0;
        } catch (RuntimeException e) {
            FAILURE.set(new IOException("WriteBlock copy failed", e));
            return 0;
        }
        return 1;
    }

    /** LLP64 (Windows) variant: C unsigned long is 32 bits there, so the carrier is int. */
    @SuppressWarnings("unused")
    private static int writeBlockCallbackInt(MemorySegment pThis, MemorySegment pData, int size) {
        return writeBlockCallback(pThis, pData, Integer.toUnsignedLong(size));
    }
}
