package stirling.software.jpdfium;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import stirling.software.jpdfium.exception.JPDFiumException;

/**
 * Bounded pool of warm worker processes: the supported way to run PDFium work in true parallel,
 * since one JVM serializes PDFium on a global lock. Thread-safe; dead workers are replaced.
 */
public final class PdfProcessPool implements AutoCloseable {

    private final List<Worker> workers = new ArrayList<>();
    private final BlockingQueue<Worker> idle;
    private final Spawner spawner;
    private final long startupTimeoutMillis;
    private final long jobTimeoutMillis;
    private final Object lock = new Object();
    private final AtomicInteger liveWorkers = new AtomicInteger();
    private volatile boolean closed;

    private PdfProcessPool(Builder b) {
        this.spawner = new Spawner(b);
        this.startupTimeoutMillis = b.startupTimeout.toMillis();
        this.jobTimeoutMillis = b.jobTimeout.toMillis();
        int size = Math.max(1, b.size);
        this.idle = new ArrayBlockingQueue<>(size);
        try {
            for (int i = 0; i < size; i++) {
                Worker w = spawner.spawn();
                workers.add(w);
                idle.add(w);
                liveWorkers.incrementAndGet();
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    /** Returns a new builder with sensible defaults for this machine. */
    public static Builder builder() {
        return new Builder();
    }

    /** Number of live worker processes currently in the pool. */
    public int size() {
        synchronized (lock) {
            return workers.size();
        }
    }

    /** Runs one job on an idle worker, blocking until one is free. */
    public int submit(List<String> argv) {
        Worker worker = acquire();
        try {
            return worker.run(argv);
        } finally {
            recycle(worker);
        }
    }

    /** Runs several jobs concurrently, at most {@link #size()} at a time, in the given order. */
    public List<Integer> submitAll(List<List<String>> jobs) {
        Objects.requireNonNull(jobs, "jobs");
        if (jobs.isEmpty()) {
            return List.of();
        }
        if (closed) {
            throw new JPDFiumException("process pool is closed");
        }
        ExecutorService dispatcher =
                Executors.newFixedThreadPool(Math.min(Math.max(1, size()), jobs.size()));
        try {
            List<Future<Integer>> futures = new ArrayList<>(jobs.size());
            for (List<String> job : jobs) {
                futures.add(
                        dispatcher.submit(
                                () -> {
                                    Worker worker = acquire();
                                    try {
                                        return worker.run(job);
                                    } finally {
                                        recycle(worker);
                                    }
                                }));
            }
            List<Integer> results = new ArrayList<>(jobs.size());
            for (Future<Integer> f : futures) {
                try {
                    results.add(f.get());
                } catch (ExecutionException e) {
                    // Drop queued jobs; let running ones finish so their workers recycle
                    // normally instead of being torn down as desynchronized.
                    futures.forEach(pending -> pending.cancel(false));
                    throw new JPDFiumException("process pool job failed", e.getCause());
                } catch (InterruptedException e) {
                    futures.forEach(pending -> pending.cancel(false));
                    Thread.currentThread().interrupt();
                    throw new JPDFiumException("interrupted while running process pool jobs", e);
                }
            }
            return results;
        } finally {
            // Let jobs already running finish and recycle their workers before the
            // pool can be reused; only force-cancel if they overstay the job budget.
            dispatcher.shutdown();
            try {
                if (!dispatcher.awaitTermination(jobTimeoutMillis + 5_000, TimeUnit.MILLISECONDS)) {
                    dispatcher.shutdownNow();
                }
            } catch (InterruptedException e) {
                dispatcher.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Blocks until a worker is free, failing fast if the pool has no live workers left. */
    private Worker acquire() {
        while (true) {
            if (closed) {
                throw new JPDFiumException("process pool is closed");
            }
            Worker worker = idle.poll();
            if (worker != null) {
                return worker;
            }
            if (liveWorkers.get() <= 0) {
                throw new JPDFiumException("no worker processes available");
            }
            try {
                worker = idle.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JPDFiumException("interrupted waiting for a worker process", e);
            }
            if (worker != null) {
                return worker;
            }
        }
    }

    /** Returns a healthy worker to the pool, replacing one that died or lost protocol sync. */
    private void recycle(Worker worker) {
        if (worker.isAlive() && !worker.isBroken() && !closed) {
            returnToIdle(worker);
            return;
        }
        worker.destroyQuietly();
        Worker fresh = null;
        // destroyQuietly() can leave the interrupt flag set; clear it across the
        // respawn so awaitReady() does not abort and silently shrink the pool.
        boolean interrupted = Thread.interrupted();
        try {
            if (!closed) {
                fresh = spawner.spawn();
            }
        } catch (RuntimeException ignored) {
            // Pool shrinks rather than failing the recycling thread.
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            workers.remove(worker);
            if (fresh != null && !closed) {
                workers.add(fresh);
            } else if (fresh != null) {
                fresh.destroyQuietly();
                fresh = null;
            }
        }
        if (fresh != null) {
            returnToIdle(fresh);
        } else {
            liveWorkers.decrementAndGet();
        }
    }

    /** Returns a worker to the idle queue; retries {@code put} so it is never orphaned. */
    private void returnToIdle(Worker worker) {
        // put() cannot block on capacity (every worker was taken from the queue), but it
        // can be interrupted; retry so the worker is never orphaned.
        boolean interrupted = Thread.interrupted();
        while (true) {
            try {
                idle.put(worker);
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<Worker> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(workers);
            workers.clear();
        }
        for (Worker worker : snapshot) {
            worker.destroyQuietly();
        }
        liveWorkers.set(0);
        idle.clear();
    }

    /** Spawns and initializes one worker process. */
    private final class Spawner {
        private final Builder config;

        Spawner(Builder b) {
            this.config = b;
        }

        Worker spawn() {
            List<String> command = new ArrayList<>();
            command.add(config.javaBinary);
            command.add("--enable-native-access=ALL-UNNAMED");
            command.addAll(config.jvmArgs);
            for (Map.Entry<String, String> e : config.systemProperties.entrySet()) {
                command.add("-D" + e.getKey() + "=" + e.getValue());
            }
            command.add("-cp");
            command.add(config.classpath);
            command.add(JpdfiumWorker.class.getName());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            if (config.workingDirectory != null) {
                pb.directory(config.workingDirectory);
            }
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new JPDFiumException("failed to start worker process: " + e.getMessage(), e);
            }
            Worker worker = new Worker(process, jobTimeoutMillis);
            try {
                worker.awaitReady(startupTimeoutMillis);
            } catch (RuntimeException e) {
                worker.destroyQuietly();
                throw e;
            }
            return worker;
        }
    }

    /** A single warm worker process and its line protocol. */
    private static final class Worker {
        private static final boolean DEBUG = Boolean.getBoolean("jpdfium.pool.debug");
        private final Process process;
        private final long jobTimeoutMillis;
        private final BufferedWriter toChild;
        private final BufferedReader fromChild;
        // One daemon reader per worker: a process pipe cannot be unblocked by an
        // interrupt, so reads happen off-thread and the caller only waits on the queue.
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private volatile boolean streamEnded;
        private volatile Throwable readFailure;
        private volatile boolean broken;

        Worker(Process process, long jobTimeoutMillis) {
            this.process = process;
            this.jobTimeoutMillis = jobTimeoutMillis;
            this.toChild =
                    new BufferedWriter(
                            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.fromChild =
                    new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(this::pump, "jpdfium-pool-reader");
            reader.setDaemon(true);
            reader.start();
        }

        private void pump() {
            try {
                String line;
                while ((line = fromChild.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException e) {
                readFailure = e;
            } finally {
                streamEnded = true;
            }
        }

        void awaitReady(long timeoutMillis) {
            String line = readLine(timeoutMillis, "startup");
            if (line == null || !"READY".equals(line.trim())) {
                broken = true;
                throw new JPDFiumException("worker did not start correctly (got " + line + ")");
            }
        }

        boolean isAlive() {
            return process.isAlive();
        }

        boolean isBroken() {
            return broken;
        }

        int run(List<String> argv) {
            try {
                if (DEBUG) {
                    System.err.println("POOL-> " + argv);
                }
                toChild.write(Integer.toString(argv.size()));
                toChild.newLine();
                for (String token : argv) {
                    toChild.write(JpdfiumWorker.escape(token));
                    toChild.newLine();
                }
                toChild.flush();
            } catch (IOException e) {
                broken = true;
                throw new JPDFiumException("worker I/O failure: " + e.getMessage(), e);
            }

            String reply = readLine(jobTimeoutMillis, "job");
            if (DEBUG) {
                System.err.println("POOL<- " + reply);
            }
            if (reply == null) {
                broken = true;
                throw new JPDFiumException("worker process exited mid-job");
            }
            reply = reply.trim();
            if (reply.startsWith("OK ")) {
                try {
                    return Integer.parseInt(reply.substring(3).trim());
                } catch (NumberFormatException e) {
                    broken = true;
                    throw new JPDFiumException("malformed worker reply: " + reply, e);
                }
            }
            if (reply.startsWith("ERR ")) {
                // A well-formed failure reply means the worker is still in sync and can be reused.
                throw new JPDFiumException("worker job failed: " + reply.substring(4).trim());
            }
            broken = true;
            throw new JPDFiumException("unexpected worker reply: " + reply);
        }

        /** Reads one protocol line bounded by {@code timeoutMillis}; a failure marks it broken. */
        private String readLine(long timeoutMillis, String what) {
            try {
                String line = lines.poll(timeoutMillis, TimeUnit.MILLISECONDS);
                if (line != null) {
                    return line;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                broken = true;
                throw new JPDFiumException("interrupted waiting for worker (" + what + ")", e);
            }
            if (streamEnded) {
                if (readFailure != null) {
                    broken = true;
                    throw new JPDFiumException("worker read failed (" + what + ")", readFailure);
                }
                return null;
            }
            broken = true;
            throw new JPDFiumException(
                    "worker did not respond within " + timeoutMillis + " ms (" + what + ")");
        }

        void destroyQuietly() {
            try {
                closeQuietly(toChild);
                try {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            } finally {
                closeQuietly(fromChild);
            }
        }

        private static void closeQuietly(AutoCloseable c) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    /** Builder for {@link PdfProcessPool}. */
    public static final class Builder {
        private int size = Math.max(1, Runtime.getRuntime().availableProcessors());
        private String javaBinary = defaultJavaBinary();
        private String classpath = System.getProperty("java.class.path", "");
        private final List<String> jvmArgs = new ArrayList<>();
        private final Map<String, String> systemProperties = new LinkedHashMap<>();
        private File workingDirectory;
        private Duration startupTimeout = Duration.ofSeconds(30);
        private Duration jobTimeout = Duration.ofMinutes(10);

        Builder() {
            // Forward JPDFium's own system properties so workers discover and load
            // the native library exactly as the parent does.
            for (Map.Entry<Object, Object> e : System.getProperties().entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.startsWith("jpdfium.") || key.startsWith("vipsffm.")) {
                    systemProperties.put(key, String.valueOf(e.getValue()));
                }
            }
        }

        /** Number of worker processes to keep warm. Defaults to the CPU count. */
        public Builder size(int size) {
            if (size < 1) {
                throw new IllegalArgumentException("size must be >= 1");
            }
            this.size = size;
            return this;
        }

        /** Java executable used to launch workers. Defaults to the current JVM's. */
        public Builder javaBinary(String javaBinary) {
            this.javaBinary = Objects.requireNonNull(javaBinary, "javaBinary");
            return this;
        }

        /** Classpath for workers. Defaults to the current process classpath. */
        public Builder classpath(String classpath) {
            this.classpath = Objects.requireNonNull(classpath, "classpath");
            return this;
        }

        /** Extra JVM arguments passed to every worker. */
        public Builder jvmArg(String arg) {
            jvmArgs.add(Objects.requireNonNull(arg, "arg"));
            return this;
        }

        /** Extra {@code -D} system property passed to every worker. */
        public Builder systemProperty(String key, String value) {
            systemProperties.put(
                    Objects.requireNonNull(key, "key"),
                    String.valueOf(Objects.requireNonNull(value, "value")));
            return this;
        }

        /** Working directory for worker processes. */
        public Builder workingDirectory(File dir) {
            this.workingDirectory = dir;
            return this;
        }

        /** How long to wait for each worker to report ready. Defaults to 30 seconds. */
        public Builder startupTimeout(Duration timeout) {
            this.startupTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** How long a single job may run before its worker is treated as hung. Defaults to 10 min. */
        public Builder jobTimeout(Duration timeout) {
            this.jobTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public PdfProcessPool build() {
            return new PdfProcessPool(this);
        }

        private static String defaultJavaBinary() {
            return System.getProperty("java.home")
                    + File.separator
                    + "bin"
                    + File.separator
                    + "java";
        }
    }
}
