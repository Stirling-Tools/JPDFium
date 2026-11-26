package stirling.software.jpdfium.instance;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import stirling.software.jpdfium.exception.NativeLoadException;
import stirling.software.jpdfium.panama.NativeLoader;

/**
 * Several independent PDFium engines in one JVM. PDFium allows one call at a time per loaded
 * copy, and JPDFium serialises on one lock per class loader; each isolated instance loads its own
 * renamed copy of the natives through its own class loader, so instances run in parallel. A crash
 * in any instance still ends the whole process: host the pool in a worker process when one bad
 * document must not take anything else down.
 *
 * <p>Instance 0 is the caller's own JPDFium and shares its lock with any other JPDFium use in the
 * JVM. Isolated instances need ELF or Mach-O natives loaded from a directory (the default
 * classpath load); on Windows {@link #maxInstances()} is 1.
 */
public final class PdfiumInstancePool implements AutoCloseable {

    private static final String JPDFIUM_PREFIX = "stirling.software.jpdfium.";
    private static final String NATIVE_LOADER = "stirling.software.jpdfium.panama.NativeLoader";
    private static final String PROBE_SYMBOL = "FPDF_InitLibrary";
    private static final Path PROC_MAPS = Path.of("/proc/self/maps");

    // The JVM refuses a library file already loaded by another class loader, so every isolated
    // instance in this process gets a number, and so a copy, of its own.
    private static final AtomicInteger NEXT_INDEX = new AtomicInteger(1);

    private final List<PdfiumInstance> instances;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition freed = lock.newCondition();
    private final Deque<PdfiumInstance> free;
    private final Set<PdfiumInstance> leased = new HashSet<>();
    private boolean closed;

    private PdfiumInstancePool(List<PdfiumInstance> instances) {
        this.instances = List.copyOf(instances);
        this.free = new ArrayDeque<>(instances);
    }

    /** Most instances one pool can hold on this platform; isolated slots are also capped per process. */
    public static int maxInstances() {
        return InstanceNatives.currentFormat() == null ? 1 : InstanceNatives.MAX_INSTANCES + 1;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Prepares the native copies a fresh process's pool of {@code size} will load, without loading
     * any. Preparing reads and rewrites every bundled library, so a launcher can do it once, in a
     * process with heap to spare, before starting workers that build pools of that size.
     *
     * @throws NativeLoadException if the default natives cannot load or a copy cannot be written
     */
    public static void prepareCopies(int size) {
        if (size <= 1 || InstanceNatives.currentFormat() == null) {
            return;
        }
        int count = Math.min(size, maxInstances()) - 1;
        Path baseDir = Builder.defaultDirectory(PdfiumInstancePool.class.getClassLoader());
        for (int index = 1; index <= count; index++) {
            try {
                InstanceNatives.prepare(baseDir, index, InstanceNatives.currentFormat());
            } catch (IOException | RuntimeException e) {
                throw new NativeLoadException("Could not prepare PDFium instance " + index, e);
            }
        }
    }

    public int size() {
        return instances.size();
    }

    public List<PdfiumInstance> instances() {
        return instances;
    }

    /** Free instances right now; a hint only. */
    public int available() {
        lock.lock();
        try {
            return free.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until an instance is free. Give it back with {@link #release}.
     *
     * @throws IllegalStateException if the pool is closed, including while waiting
     */
    public PdfiumInstance acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                PdfiumInstance instance = takeFree();
                if (instance != null) {
                    return instance;
                }
                freed.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Null if none frees up within the timeout.
     *
     * @throws IllegalStateException if the pool is closed, including while waiting
     */
    public PdfiumInstance acquire(Duration timeout) throws InterruptedException {
        long remaining = timeout.toNanos();
        lock.lockInterruptibly();
        try {
            while (true) {
                PdfiumInstance instance = takeFree();
                if (instance != null) {
                    return instance;
                }
                if (remaining <= 0) {
                    return null;
                }
                remaining = freed.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }

    int waiting() {
        lock.lock();
        try {
            return lock.getWaitQueueLength(freed);
        } finally {
            lock.unlock();
        }
    }

    private PdfiumInstance takeFree() {
        if (closed) {
            throw new IllegalStateException("PDFium instance pool is closed");
        }
        PdfiumInstance instance = free.pollFirst();
        if (instance != null) {
            leased.add(instance);
        }
        return instance;
    }

    /** @throws IllegalStateException if the instance is not currently acquired from this pool */
    public void release(PdfiumInstance instance) {
        if (!instances.contains(instance)) {
            throw new IllegalArgumentException(instance + " does not belong to this pool");
        }
        lock.lock();
        try {
            if (!leased.remove(instance)) {
                throw new IllegalStateException(instance + " was released twice or never acquired");
            }
            free.addLast(instance);
            freed.signal();
        } finally {
            lock.unlock();
        }
    }

    /** Runs a task on the next free instance; see {@link PdfiumInstance#call}. */
    public Object call(String taskClassName, Object input) throws InterruptedException {
        PdfiumInstance instance = acquire();
        try {
            return instance.call(taskClassName, input);
        } finally {
            release(instance);
        }
    }

    /**
     * Stops handing out instances and fails every caller blocked in {@code acquire}. Loaded
     * natives stay mapped until the JVM exits, since a library cannot be unloaded while its class
     * loader might still be reachable.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            freed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public static final class Builder {

        private int size = 1;
        private final Set<String> isolated = new LinkedHashSet<>(List.of(JPDFIUM_PREFIX));
        private ClassLoader parent = PdfiumInstancePool.class.getClassLoader();

        private Builder() {}

        /** Total instances, including instance 0. */
        public Builder size(int size) {
            if (size < 1) {
                throw new IllegalArgumentException("size must be at least 1");
            }
            this.size = size;
            return this;
        }

        /**
         * Package prefixes (ending in a dot) whose classes each instance loads for itself.
         * Every class that touches JPDFium types on a task's path must be under one, or it
         * binds to instance 0. JPDFium's own packages are always included.
         */
        public Builder isolate(String... packagePrefixes) {
            for (String prefix : packagePrefixes) {
                if (!prefix.endsWith(".")) {
                    throw new IllegalArgumentException("package prefix must end with '.': " + prefix);
                }
                isolated.add(prefix);
            }
            return this;
        }

        /** Loader that reads the isolated classes and serves everything else; defaults to JPDFium's. */
        public Builder parent(ClassLoader parent) {
            this.parent = parent;
            return this;
        }

        /**
         * Loads instance 0's natives if needed, then prepares, loads and verifies each isolated
         * instance.
         *
         * @throws NativeLoadException if an instance cannot load, or its bridge resolved to a
         *     PDFium other than its own
         */
        public PdfiumInstancePool build() {
            if (size > maxInstances()) {
                throw new IllegalArgumentException("This platform supports at most "
                        + maxInstances() + " PDFium instance(s) per process, asked for " + size);
            }
            List<PdfiumInstance> created = new ArrayList<>(size);
            created.add(new PdfiumInstance(0, parent, false));
            if (size == 1) {
                return new PdfiumInstancePool(created);
            }
            Path baseDir = defaultDirectory(parent);
            InstanceNatives.Format format = InstanceNatives.currentFormat();
            List<String> prefixes = List.copyOf(isolated);
            for (int n = 1; n < size; n++) {
                int index = NEXT_INDEX.getAndIncrement();
                if (index > InstanceNatives.MAX_INSTANCES) {
                    throw new NativeLoadException("This process has used all "
                            + InstanceNatives.MAX_INSTANCES + " isolated PDFium instance slots");
                }
                InstanceNatives.Prepared prepared;
                try {
                    prepared = InstanceNatives.prepare(baseDir, index, format);
                } catch (IOException | RuntimeException e) {
                    throw new NativeLoadException("Could not prepare PDFium instance " + index, e);
                }
                IsolatingClassLoader loader =
                        new IsolatingClassLoader("pdfium-instance-" + index, parent, prefixes);
                invokeStatic(loader, "loadPrepared", prepared.pdfium(), prepared.bridge());
                verifyOwnPdfium(index, prepared, baseDir, boundAddress(loader));
                created.add(new PdfiumInstance(index, loader, true));
            }
            verifyMappings(baseDir, created);
            return new PdfiumInstancePool(created);
        }

        private static void verifyMappings(Path baseDir, List<PdfiumInstance> created) {
            if (InstanceNatives.currentFormat() != InstanceNatives.Format.ELF
                    || !Files.isReadable(PROC_MAPS)) {
                return;
            }
            List<Integer> indices = new ArrayList<>();
            for (PdfiumInstance instance : created) {
                if (instance.isolated()) {
                    indices.add(instance.id());
                }
            }
            try {
                InstanceNatives.verifyMappings(Files.readString(PROC_MAPS), baseDir, indices);
            } catch (IOException e) {
                throw new NativeLoadException("PDFium instances are not isolated: " + e.getMessage(), e);
            }
        }

        private static Path defaultDirectory(ClassLoader parent) {
            Object dir;
            try {
                Class<?> loader = Class.forName(NATIVE_LOADER, true, parent);
                dir = loader.getMethod("loadedDirectory").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new NativeLoadException("Could not load the default PDFium natives", unwrap(e));
            }
            if (dir == null) {
                throw new NativeLoadException("PDFium was loaded from the system library path, "
                        + "which leaves no copy to isolate; load it from the classpath natives");
            }
            return (Path) dir;
        }

        private static void invokeStatic(ClassLoader loader, String method, Path pdfium, Path bridge) {
            try {
                Class<?> type = Class.forName(NATIVE_LOADER, true, loader);
                type.getMethod(method, Path.class, Path.class).invoke(null, pdfium, bridge);
            } catch (ReflectiveOperationException e) {
                throw new NativeLoadException("Could not load PDFium copy " + pdfium, unwrap(e));
            }
        }

        // dlsym on the bridge searches the bridge's own dependencies, so it finds the PDFium the
        // bridge actually bound to.
        // Two views must both land on the instance's own copy: dlsym on the bridge, which follows
        // the bridge's own dependencies, and the instance's Java bindings, which follow every
        // library its class loader loaded.
        private static void verifyOwnPdfium(int index, InstanceNatives.Prepared prepared,
                Path baseDir, long viaBindings) {
            try (Arena arena = Arena.ofConfined()) {
                long viaBridge = address(arena, prepared.bridge());
                long own = address(arena, prepared.pdfium());
                long shared = address(arena, baseDir.resolve(NativeLoader.libraryFileName("pdfium")));
                if (viaBridge != own || viaBindings != own || own == shared) {
                    throw new NativeLoadException("PDFium instance " + index
                            + " is not isolated: " + PROBE_SYMBOL + " resolves to 0x"
                            + Long.toHexString(viaBridge) + " through its bridge and 0x"
                            + Long.toHexString(viaBindings) + " through its bindings; its own copy"
                            + " is at 0x" + Long.toHexString(own) + ", the shared copy at 0x"
                            + Long.toHexString(shared));
                }
            }
        }

        private static long boundAddress(ClassLoader loader) {
            try {
                Class<?> type = Class.forName(NATIVE_LOADER, true, loader);
                return (long) type.getMethod("boundAddress", String.class).invoke(null, PROBE_SYMBOL);
            } catch (ReflectiveOperationException e) {
                throw new NativeLoadException("Could not read the bindings of a PDFium instance",
                        unwrap(e));
            }
        }

        private static long address(Arena arena, Path library) {
            return SymbolLookup.libraryLookup(library, arena)
                    .find(PROBE_SYMBOL)
                    .map(MemorySegment::address)
                    .orElseThrow(() -> new NativeLoadException(
                            PROBE_SYMBOL + " not found through " + library));
        }

        private static Throwable unwrap(ReflectiveOperationException e) {
            return e instanceof InvocationTargetException ite && ite.getCause() != null
                    ? ite.getCause() : e;
        }
    }
}
