package stirling.software.jpdfium.instance;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One PDFium engine inside a {@link PdfiumInstancePool}. An isolated instance has its own copy of
 * JPDFium's classes and of the native library, so it runs in parallel with every other instance.
 * Code reaches it only through a task: a class under one of the pool's isolated packages that
 * implements {@code Function<Object, Object>} and has a public no-argument constructor. Inputs and
 * results must be JDK types (strings, byte arrays, paths, collections of them), because JPDFium
 * types from one instance are different classes in every other.
 */
public final class PdfiumInstance {

    private final int id;
    private final ClassLoader loader;
    private final boolean isolated;
    private final Map<String, Function<Object, Object>> tasks = new ConcurrentHashMap<>();

    PdfiumInstance(int id, ClassLoader loader, boolean isolated) {
        this.id = id;
        this.loader = loader;
        this.isolated = isolated;
    }

    /** 0 for the instance that shares the caller's own JPDFium; isolated ones are numbered uniquely per process. */
    public int id() {
        return id;
    }

    public boolean isolated() {
        return isolated;
    }

    public ClassLoader classLoader() {
        return loader;
    }

    /**
     * Runs {@code taskClassName} in this instance. The task object is created once per instance
     * and reused, so it must be stateless or thread-safe. Exceptions from the task propagate
     * unchanged; their classes may belong to this instance's loader.
     */
    public Object call(String taskClassName, Object input) {
        Function<Object, Object> task = tasks.computeIfAbsent(taskClassName, this::newTask);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return task.apply(input);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private Function<Object, Object> newTask(String className) {
        if (isolated && !((IsolatingClassLoader) loader).isolates(className)) {
            throw new IllegalArgumentException("Task " + className
                    + " is not in an isolated package, so it would run on the shared PDFium");
        }
        try {
            Class<?> type = Class.forName(className, true, loader);
            if (!Function.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException("Task " + className
                        + " must implement java.util.function.Function");
            }
            return (Function<Object, Object>) type.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
                | IllegalAccessException e) {
            throw new IllegalArgumentException("Cannot create task " + className, e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Task " + className + " failed to construct",
                    e.getCause());
        }
    }

    @Override
    public String toString() {
        return "PdfiumInstance[" + id + (isolated ? ", isolated" : ", shared") + "]";
    }
}
