package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * QPDFJob bindings for copying encryption without reconstructing either password.
 * Each invocation owns its job, logger and confined argument storage; no PDFium state is touched.
 */
final class QpdfEncryptionCopy {
    private static final int DISCARD_LOGS = 3;
    private static final MethodHandle INIT = bind("qpdfjob_init", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle CLEANUP = bind("qpdfjob_cleanup", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle INITIALIZE = bind("qpdfjob_initialize_from_argv",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle RUN = bind("qpdfjob_run", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SET_LOGGER = bind("qpdfjob_set_logger",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle LOGGER_CREATE = bind("qpdflogger_create", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle LOGGER_CLEANUP = bind("qpdflogger_cleanup", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle LOGGER_INFO = loggerBinding("qpdflogger_set_info");
    private static final MethodHandle LOGGER_WARN = loggerBinding("qpdflogger_set_warn");
    private static final MethodHandle LOGGER_ERROR = loggerBinding("qpdflogger_set_error");

    private QpdfEncryptionCopy() {}

    static boolean isSupported() {
        return INIT != null && CLEANUP != null && INITIALIZE != null && RUN != null
                && SET_LOGGER != null && LOGGER_CREATE != null && LOGGER_CLEANUP != null
                && LOGGER_INFO != null && LOGGER_WARN != null && LOGGER_ERROR != null;
    }

    static void copy(Path input, Path source, String password, Path staging) {
        requireEncrypted(source);
        if (run("jpdfium", "--is-encrypted", input.toAbsolutePath().toString()) == 0) {
            throw new JPDFiumException("copyEncryption requires an unencrypted input PDF");
        }
        int result = run("jpdfium", input.toAbsolutePath().toString(), staging.toAbsolutePath().toString(),
                "--copy-encryption=" + source.toAbsolutePath(), "--encryption-file-password=" + password);
        if (result != 0 && result != 3) {
            throw new JPDFiumException("PDF encryption copy failed");
        }
        requireEncrypted(staging);
    }

    private static void requireEncrypted(Path file) {
        // QPDFJob's is-encrypted probe works without a password. A nonzero status must never
        // let a plaintext source or a failed copy be published as a protected result.
        if (run("jpdfium", "--is-encrypted", file.toAbsolutePath().toString()) != 0) {
            throw new JPDFiumException("PDF encryption copy requires an encrypted source and result");
        }
    }

    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
        return Symbols.downcallOptionalUnguarded(name, descriptor);
    }

    private static MethodHandle loggerBinding(String name) {
        return bind(name, FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    }

    private static int run(String... arguments) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment argv = arena.allocate(ADDRESS, arguments.length + 1L);
            MemorySegment[] strings = new MemorySegment[arguments.length];
            try {
                for (int i = 0; i < arguments.length; i++) {
                    strings[i] = arena.allocateFrom(arguments[i]);
                    argv.setAtIndex(ADDRESS, i, strings[i]);
                }
                argv.setAtIndex(ADDRESS, arguments.length, MemorySegment.NULL);
                return run(arena, argv);
            } finally {
                for (MemorySegment string : strings) {
                    if (string != null) string.fill((byte) 0);
                }
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("Native PDF encryption copy failed", t);
        }
    }

    private static int run(Arena arena, MemorySegment argv) throws Throwable {
        MemorySegment jobSlot = arena.allocate(ADDRESS);
        MemorySegment loggerSlot = arena.allocate(ADDRESS);
        MemorySegment job = (MemorySegment) INIT.invokeExact();
        if (job.equals(MemorySegment.NULL)) throw new JPDFiumException("Unable to create QPDF job");
        jobSlot.set(ADDRESS, 0, job);
        try {
            MemorySegment logger = (MemorySegment) LOGGER_CREATE.invokeExact();
            if (logger.equals(MemorySegment.NULL)) throw new JPDFiumException("Unable to create QPDF logger");
            loggerSlot.set(ADDRESS, 0, logger);
            try {
                // A private logger keeps passwords out of native diagnostics without muting other jobs.
                LOGGER_INFO.invokeExact(logger, DISCARD_LOGS, MemorySegment.NULL, MemorySegment.NULL);
                LOGGER_WARN.invokeExact(logger, DISCARD_LOGS, MemorySegment.NULL, MemorySegment.NULL);
                LOGGER_ERROR.invokeExact(logger, DISCARD_LOGS, MemorySegment.NULL, MemorySegment.NULL);
                SET_LOGGER.invokeExact(job, logger);
                int initialized = (int) INITIALIZE.invokeExact(job, argv);
                if (initialized != 0) throw new JPDFiumException("Unable to initialize QPDF encryption copy");
                return (int) RUN.invokeExact(job);
            } finally {
                LOGGER_CLEANUP.invokeExact(loggerSlot);
            }
        } finally {
            CLEANUP.invokeExact(jobSlot);
        }
    }
}
