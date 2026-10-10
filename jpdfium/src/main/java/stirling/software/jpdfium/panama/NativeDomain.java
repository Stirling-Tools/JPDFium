package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Binding to JPDFium's native PDFium domain ({@code jpdfium_api.h}), the only PDFium serializer.
 *
 * <p>Every PDFium entry point the Java bindings call is reached through its
 * {@code jpdf_guard_<name>} shim, which admits that one call natively. A refused admission
 * returns a zero value the caller cannot tell from success, so each guarded handle checks
 * {@code jpdf_guard_take_refusal} afterwards and throws {@link JPDFiumException}.
 */
public final class NativeDomain {

    /** Native runtime states ({@code JPDF_STATE_*}). */
    static final int UNINITIALIZED = 0;
    static final int RUNNING = 1;
    static final int QUIESCING = 2;
    static final int DESTROYING = 3;
    static final int STOPPED = 4;
    static final int FAILED = 5;

    static final int OK = 0;
    static final int E_BUSY = -5;
    static final int E_UNSUPPORTED = -6;
    static final int E_TIMEOUT = -21;

    // JPDF_RENDERER_* in jpdfium_api.h.
    private static final int RENDERER_AUTO = -1;
    private static final int RENDERER_AGG = 0;
    private static final int RENDERER_SKIA = 1;

    /** {@code jpdf_runtime_info}; layout probed against the native size at startup. */
    private static final StructLayout RUNTIME_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("struct_size"),
            JAVA_INT.withName("state"),
            JAVA_INT.withName("api_major"),
            JAVA_INT.withName("api_minor"),
            JAVA_INT.withName("renderer"),
            JAVA_INT.withName("reserved0"),
            ADDRESS.withName("build_id"),
            JAVA_LONG.withName("live_documents"),
            JAVA_LONG.withName("live_pages"),
            JAVA_LONG.withName("live_bitmaps"),
            JAVA_LONG.withName("live_views"),
            JAVA_LONG.withName("domain_acquisitions"),
            JAVA_LONG.withName("domain_wait_ns"),
            JAVA_LONG.withName("domain_hold_ns"));
    private static final int STRUCT_RUNTIME_INFO = 2;

    private static final MethodHandle INIT;
    private static final MethodHandle GET_INFO;
    private static final MethodHandle QUIESCE;
    private static final MethodHandle RESUME;
    private static final MethodHandle SHUTDOWN;
    private static final MethodHandle SHUTDOWN_WITHIN;

    static {
        NativeLoader.ensureLoaded();
        INIT = Guards.raw("jpdf_runtime_init", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        GET_INFO = Guards.raw("jpdf_runtime_get_info", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        QUIESCE = Guards.raw("jpdf_runtime_quiesce", FunctionDescriptor.of(JAVA_INT));
        RESUME = Guards.raw("jpdf_runtime_resume", FunctionDescriptor.of(JAVA_INT));
        SHUTDOWN = Guards.raw("jpdf_runtime_shutdown", FunctionDescriptor.of(JAVA_INT));
        SHUTDOWN_WITHIN = Guards.raw("jpdf_runtime_shutdown_within",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        long infoSize = Guards.structSize(STRUCT_RUNTIME_INFO);
        if (infoSize != RUNTIME_INFO.byteSize()) {
            throw new UnsatisfiedLinkError("jpdf_runtime_info layout mismatch: native "
                    + infoSize + " bytes, Java " + RUNTIME_INFO.byteSize());
        }
        start();
    }

    private NativeDomain() {}

    /**
     * Symbol resolution and refusal checks, kept apart from the runtime start above: loading
     * the library verifies its ABI through JpdfiumH, which resolves symbols while this class is
     * still initializing.
     */
    private static final class Guards {
        private static final String PREFIX = "jpdf_guard_";
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup LOOKUP;
        // Guard entry addresses, so jextract handles built from an address check refusals.
        private static final Set<Long> ADDRESSES = ConcurrentHashMap.newKeySet();
        private static final MethodHandle TAKE_REFUSAL;
        private static final MethodHandle LAST_ERROR;
        private static final MethodHandle STATUS_NAME;
        private static final MethodHandle STRUCT_SIZE;
        private static final MethodHandle CHECK;

        static {
            NativeLoader.ensureLoaded();
            LOOKUP = SymbolLookup.loaderLookup();
            // Trivial and non-blocking, so critical linkage is safe and keeps each check cheap.
            TAKE_REFUSAL = LINKER.downcallHandle(require("jpdf_guard_take_refusal"),
                    FunctionDescriptor.of(JAVA_INT), Linker.Option.critical(false));
            LAST_ERROR = raw("jpdf_last_error_message",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
            STATUS_NAME = raw("jpdf_status_name", FunctionDescriptor.of(ADDRESS, JAVA_INT));
            STRUCT_SIZE = raw("jpdf_abi_struct_size", FunctionDescriptor.of(JAVA_LONG, JAVA_INT));
            try {
                CHECK = MethodHandles.lookup().findStatic(Guards.class, "checkRefusal",
                        MethodType.methodType(void.class));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static MemorySegment require(String name) {
            return LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError(
                    "JPDFium native core lacks " + name + "; natives are older than this jar"));
        }

        static MethodHandle raw(String name, FunctionDescriptor desc) {
            return LINKER.downcallHandle(require(name), desc);
        }

        static Optional<MemorySegment> guard(String name) {
            Optional<MemorySegment> guard = LOOKUP.find(PREFIX + name);
            guard.ifPresent(segment -> ADDRESSES.add(segment.address()));
            return guard;
        }

        static long structSize(int id) {
            try {
                return (long) STRUCT_SIZE.invokeExact(id);
            } catch (Throwable t) {
                throw new JPDFiumException("jpdf_abi_struct_size failed", t);
            }
        }

        private static void checkRefusal() {
            int status;
            try {
                status = (int) TAKE_REFUSAL.invokeExact();
            } catch (Throwable t) {
                throw new JPDFiumException("jpdf_guard_take_refusal failed", t);
            }
            if (status != OK) {
                throw new JPDFiumException("PDFium call refused: " + describe(status));
            }
        }

        static String describe(int status) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment required = arena.allocate(JAVA_LONG);
                MemorySegment buffer = arena.allocate(512);
                int rc = (int) LAST_ERROR.invokeExact(buffer, buffer.byteSize(), required);
                String detail = rc == OK ? buffer.getString(0) : "";
                String name = ((MemorySegment) STATUS_NAME.invokeExact(status))
                        .reinterpret(64).getString(0);
                return detail.isEmpty() ? name : detail + " (" + name + ")";
            } catch (Throwable t) {
                return "status " + status;
            }
        }
    }

    /** The guard shim for {@code name}, or empty when the entry point is unguarded by design. */
    static Optional<MemorySegment> guard(String name) {
        return Guards.guard(name);
    }

    /** jextract lookup: the guard when one exists, else the raw symbol. */
    static MemorySegment symbol(String name) {
        return Guards.guard(name).orElseGet(() -> Guards.LOOKUP.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError("unresolved symbol: " + name)));
    }

    /** jextract lookup for symbols absent from some builds (QPDF): null when missing. */
    static MemorySegment optionalSymbol(String name) {
        return Guards.guard(name).or(() -> Guards.LOOKUP.find(name)).orElse(null);
    }

    /** jextract downcall: guarded addresses get the refusal check. */
    static MethodHandle downcall(MemorySegment address, FunctionDescriptor desc,
                                 Linker.Option... options) {
        boolean guarded = Guards.ADDRESSES.contains(address.address());
        if (guarded && options.length > 0) {
            // A guard can wait on the domain; critical downcalls must never block.
            throw new IllegalStateException("guarded entry points cannot use linker options");
        }
        MethodHandle handle = Guards.LINKER.downcallHandle(address, desc, options);
        return guarded ? checked(handle) : handle;
    }

    /** Appends the refusal check to a handle bound to a guard shim. */
    static MethodHandle checked(MethodHandle target) {
        Class<?> ret = target.type().returnType();
        if (ret == void.class) {
            return MethodHandles.filterReturnValue(target, Guards.CHECK);
        }
        MethodHandle check = MethodHandles.dropArguments(Guards.CHECK, 0, ret);
        return MethodHandles.filterReturnValue(target,
                MethodHandles.foldArguments(MethodHandles.identity(ret), check));
    }

    /** Status name plus this thread's native detail for the call that just failed. */
    static String describe(int status) {
        return Guards.describe(status);
    }

    /** Forces class init, which starts the native runtime exactly once per process. */
    static void ensureStarted() {
        // Intentionally empty: calling it runs the static initializer.
    }

    /**
     * Starts the runtime before any guarded call can run. Renderer selection is fixed for the
     * JVM lifetime: -Djpdfium.renderer=agg (or JPDFIUM_RENDERER=agg) forces AGG, =skia forces
     * Skia with an AGG fallback on builds that lack it.
     */
    private static void start() {
        String renderer = System.getProperty(
                "jpdfium.renderer", System.getenv().getOrDefault("JPDFIUM_RENDERER", ""));
        int rc;
        if ("agg".equalsIgnoreCase(renderer)) {
            rc = init(RENDERER_AGG);
        } else if ("skia".equalsIgnoreCase(renderer)) {
            rc = init(RENDERER_SKIA);
            if (rc == E_UNSUPPORTED) {
                System.err.println(
                        "jpdfium: Skia renderer requested but this native build has no Skia; using AGG");
                rc = init(RENDERER_AUTO);
            }
        } else {
            rc = init(RENDERER_AUTO);
        }
        if (rc != OK) {
            throw new JPDFiumException("jpdf_runtime_init failed: " + describe(rc));
        }
        // Exit teardown quiesces first and destroys only when nothing is open.
        // Platform thread required: virtual threads cannot be shutdown hooks.
        Runtime.getRuntime().addShutdownHook(
                Thread.ofPlatform().unstarted(PdfiumRuntime::shutdownOnJvmExit));
    }

    static int init(int renderer) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment options = arena.allocate(16);
            options.set(JAVA_INT, 0, 16);
            options.set(JAVA_INT, 8, renderer);
            return (int) INIT.invokeExact(options);
        } catch (Throwable t) {
            throw new JPDFiumException("jpdf_runtime_init failed", t);
        }
    }

    /** Snapshot of the native runtime: state and domain pressure. */
    record Info(int state, long acquisitions, long waitNanos, long holdNanos) {}

    static Info info() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(RUNTIME_INFO);
            info.set(JAVA_INT, 0, (int) RUNTIME_INFO.byteSize());
            int rc = (int) GET_INFO.invokeExact(info);
            if (rc != OK) throw new JPDFiumException("jpdf_runtime_get_info: " + describe(rc));
            return new Info(
                    info.get(JAVA_INT, offset("state")),
                    info.get(JAVA_LONG, offset("domain_acquisitions")),
                    info.get(JAVA_LONG, offset("domain_wait_ns")),
                    info.get(JAVA_LONG, offset("domain_hold_ns")));
        } catch (JPDFiumException e) {
            throw e;
        } catch (Throwable t) {
            throw new JPDFiumException("jpdf_runtime_get_info failed", t);
        }
    }

    private static long offset(String field) {
        return RUNTIME_INFO.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    static int quiesce() {
        return invokeInt(QUIESCE);
    }

    static int resume() {
        return invokeInt(RESUME);
    }

    static int shutdown() {
        return invokeInt(SHUTDOWN);
    }

    static int shutdownWithin(int timeoutMillis) {
        try {
            return (int) SHUTDOWN_WITHIN.invokeExact(timeoutMillis);
        } catch (Throwable t) {
            throw new JPDFiumException("jpdf_runtime_shutdown_within failed", t);
        }
    }

    private static int invokeInt(MethodHandle handle) {
        try {
            return (int) handle.invokeExact();
        } catch (Throwable t) {
            throw new JPDFiumException("native runtime call failed", t);
        }
    }
}
