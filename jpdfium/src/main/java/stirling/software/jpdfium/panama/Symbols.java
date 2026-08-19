package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized FFM symbol lookup, downcall binding, and resolution tracking.
 *
 * <p>All binding classes delegate downcall construction to {@link Symbols}.
 * In {@link NativeRuntime.NativeMode#FULL} mode, any missing symbol throws an
 * immediate {@link UnsatisfiedLinkError} at class-init time rather than returning
 * null or failing silently later. In {@link NativeRuntime.NativeMode#STUB} mode,
 * missing symbols return {@code null} so stub execution degrades gracefully.
 */
public final class Symbols {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private static final Set<String> RESOLVED_SYMBOLS = ConcurrentHashMap.newKeySet();
    private static final List<String> MISSING_SYMBOLS = new CopyOnWriteArrayList<>();

    private Symbols() {}

    /**
     * Find a symbol by name, checking both exact name and {@code "jpdfium_" + name}.
     */
    public static Optional<MemorySegment> find(String name) {
        NativeLoader.ensureLoaded();
        return LOOKUP.find(name).or(() -> LOOKUP.find("jpdfium_" + name));
    }

    /**
     * Create a guarded downcall method handle.
     *
     * @throws UnsatisfiedLinkError if symbol is absent in FULL mode
     */
    public static MethodHandle downcall(String name, FunctionDescriptor desc, Linker.Option... options) {
        Optional<MemorySegment> symbolOpt = find(name);
        if (symbolOpt.isEmpty()) {
            MISSING_SYMBOLS.add(name);
            if (NativeRuntime.isFull()) {
                throw new UnsatisfiedLinkError("Missing required native symbol in FULL mode: " + name);
            }
            return null;
        }
        RESOLVED_SYMBOLS.add(name);
        MethodHandle handle = LINKER.downcallHandle(symbolOpt.get(), desc, options);
        return NativeGuard.guard(handle);
    }

    /**
     * Create a guarded downcall handle for an optional symbol, or return {@code null}
     * if the symbol is absent without throwing an exception even in FULL mode.
     *
     * <p>Optional symbols that are absent are not recorded in
     * {@link #MISSING_SYMBOLS}: that list is reserved for <em>required</em>
     * symbols that fail to resolve, so {@link #auditMissing()} stays meaningful.
     */
    public static MethodHandle downcallOptional(String name, FunctionDescriptor desc, Linker.Option... options) {
        Optional<MemorySegment> symbolOpt = find(name);
        if (symbolOpt.isEmpty()) {
            return null;
        }
        RESOLVED_SYMBOLS.add(name);
        MethodHandle handle = LINKER.downcallHandle(symbolOpt.get(), desc, options);
        return NativeGuard.guard(handle);
    }

    /**
     * Create a downcall method handle (delegates to guarded downcall).
     */
    public static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
        return downcall(name, desc);
    }

    /**
     * List of symbols requested but not found in the current native library.
     */
    public static List<String> auditMissing() {
        return Collections.unmodifiableList(MISSING_SYMBOLS);
    }

    /**
     * Set of all symbols successfully resolved and bound.
     */
    public static Set<String> resolvedSymbols() {
        return Collections.unmodifiableSet(RESOLVED_SYMBOLS);
    }
}
