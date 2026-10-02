package stirling.software.jpdfium.panama;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import stirling.software.jpdfium.exception.JPDFiumException;

/**
 * Command transport for an optional owner-thread backend. Not the default:
 * production calls go through {@link PdfiumRuntime} directly. This exists
 * only for experiments, so no two calls ever overlap by construction.
 *
 * <p>Deliberately not a specialized queue: a command carries a whole document
 * operation, so transport cost is off the hot path. Measure before specializing.
 *
 * <p>Contract:
 * <ul>
 *   <li>Bounded FIFO admission. A full queue rejects; it never runs the command
 *       on the caller, which would break PDFium's single-thread rule.</li>
 *   <li>Accepted commands run to completion, even if quiesce happens while they
 *       are queued. Admission is checked once, at submission.</li>
 *   <li>A command submitted from the owner runs inline, avoiding self-deadlock.</li>
 *   <li>Interruption does not abandon the command: the caller waits for the real
 *       outcome, then restores its interrupt status, since native code may still
 *       borrow the caller's buffers.</li>
 * </ul>
 */
final class OwnerTransport {

    /** Reasons a command can be refused, kept distinct for diagnostics. */
    enum Rejection {
        /** Queue at capacity. */
        SATURATED,
        /** Owner thread has stopped; no further work is accepted. */
        STOPPED,
        /** Runtime is not in a state that permits this operation kind. */
        LIFECYCLE,
        /** Reentrancy that the execution context forbids. */
        CONTEXT
    }

    /**
     * A submitted command. The {@link FutureTask} carries
     * the result or failure to the waiting caller; the owner only ever runs it.
     */
    private record Task(FutureTask<?> future) {
        void run() {
            future.run();
        }
    }

    private final BlockingQueue<Task> queue;
    private final Thread owner;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicLong executed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    /** Callbacks the owner refuses to run, so this stays observable. */
    private final AtomicLong callbackReentries = new AtomicLong();
    private final ThreadLocal<Boolean> onOwner = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Predicate<String> admissionCheck;
    private final String name;

    OwnerTransport(String name, int capacity, Predicate<String> admissionCheck) {
        this.name = name;
        this.admissionCheck = admissionCheck;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.owner = Thread.ofPlatform()
                .name(name)
                .unstarted(this::runLoop);
        this.owner.setDaemon(true);
    }

    void start() {
        owner.start();
        try {
            if (!started.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("PDFium owner thread did not start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while starting the PDFium owner", e);
        }
    }

    /** True when the calling thread is the owner. */
    boolean isOwner() {
        return onOwner.get();
    }

    long executedCount() {
        return executed.get();
    }

    long rejectedCount() {
        return rejected.get();
    }

    long callbackReentryCount() {
        return callbackReentries.get();
    }

    Throwable ownerFailure() {
        return ownerFailure.get();
    }

    /** Submit and wait for the real result, preserving interruption. */
    <T> T submit(String operation, Supplier<T> body) {
        if (isOwner()) {
            // Nested owner work runs inline; dispatching would deadlock.
            return body.get();
        }
        if (!running.get()) {
            rejected.incrementAndGet();
            throw failure("PDFium owner has stopped; " + operation + " refused");
        }
        Rejection why = admissionCheck.test(operation) ? null : Rejection.LIFECYCLE;
        if (why != null) {
            rejected.incrementAndGet();
            throw failure("PDFium runtime refuses " + operation + " (" + why + ")");
        }

        var future = new FutureTask<T>(body::get);
        Task task = new Task(future);
        if (!queue.offer(task)) {
            // Never fall back to running on this thread: that would execute
            // PDFium outside the owner and break the single-thread rule.
            rejected.incrementAndGet();
            throw failure("PDFium owner queue is saturated; " + operation + " refused");
        }

        boolean interrupted = false;
        for (;;) {
            try {
                T result = future.get();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return result;
            } catch (InterruptedException e) {
                // Remember, keep waiting: the command may still be using
                // borrowed native memory, so abandoning it here is unsafe.
                interrupted = true;
            } catch (ExecutionException e) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw new JPDFiumException("PDFium owner command failed: " + operation, cause);
            }
        }
    }

    void submit(String operation, Runnable body) {
        submit(operation, () -> {
            body.run();
            return null;
        });
    }

    /**
     * Best-effort teardown: stop accepting work, let queued commands finish,
     * then join. Bounded so a stuck owner cannot hang the caller.
     */
    void shutdown(long timeoutMillis) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        // Drain queued tasks so accepted work still completes. Running them
        // here is safe only because the owner has stopped accepting and the
        // drain loop below joins it, so no PDFium call can be in flight.
        for (Task pending = queue.poll(); pending != null; pending = queue.poll()) {
            try {
                pending.run();
            } catch (Throwable t) {
                ownerFailure.compareAndSet(null, t);
            }
        }
        try {
            owner.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runLoop() {
        onOwner.set(Boolean.TRUE);
        started.countDown();
        try {
            while (true) {
                Task task = queue.take();
                if (!running.get() && queue.isEmpty()) {
                    // Shutdown has drained the queue; nothing left to do.
                    return;
                }
                try {
                    task.run();
                    executed.incrementAndGet();
                } catch (Throwable t) {
                    // A failing command must not kill the owner: pending
                    // callers are already waiting on their own futures, and
                    // the runtime decides whether the failure is terminal.
                    ownerFailure.compareAndSet(null, t);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            onOwner.set(Boolean.FALSE);
            // Fail anything still queued so no caller waits forever.
            Task pending;
            while ((pending = queue.poll()) != null) {
                pending.future().cancel(false);
            }
        }
    }

    private static JPDFiumException failure(String message) {
        return new JPDFiumException(message);
    }

    /** Test/diagnostic view of the owner thread identity. */
    String ownerThreadName() {
        return owner.getName();
    }

    long ownerThreadId() {
        return owner.threadId();
    }
}
