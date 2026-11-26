package stirling.software.jpdfium.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import stirling.software.jpdfium.panama.PdfiumRuntime;

class PdfiumInstancePoolTest {

    private static final int SIZE = 3;
    private static PdfiumInstancePool pool;

    @BeforeAll
    static void createPool() {
        assumeTrue(PdfiumInstancePool.maxInstances() >= SIZE, "platform hosts one instance only");
        pool = PdfiumInstancePool.builder().size(SIZE).build();
    }

    private static byte[] pdf(String name) throws IOException {
        return Objects.requireNonNull(
                PdfiumInstancePoolTest.class.getResourceAsStream("/pdfs/general/" + name))
                .readAllBytes();
    }

    @Test
    void eachIsolatedInstanceHasItsOwnJpdfium() {
        Set<Object> loaders = new HashSet<>();
        for (PdfiumInstance instance : pool.instances()) {
            loaders.add(instance.call(LoaderIdentityTask.class.getName(), null));
        }
        assertEquals(SIZE, loaders.size());
        assertTrue(loaders.contains(PdfiumRuntime.class.getClassLoader()));
        assertFalse(pool.instances().getFirst().isolated());
    }

    @Test
    @Timeout(60)
    void instancesHoldTheirPdfiumLocksAtTheSameTime() throws Exception {
        byte[] pdf = pdf("minimal.pdf");
        int pages = (int) pool.instances().getFirst().call(LockHoldingTask.class.getName(),
                new Object[] {pdf, new CyclicBarrier(1)});
        CyclicBarrier barrier = new CyclicBarrier(SIZE);
        ExecutorService threads = Executors.newFixedThreadPool(SIZE);
        try {
            List<Future<Object>> results = new ArrayList<>();
            for (PdfiumInstance instance : pool.instances()) {
                results.add(threads.submit(() ->
                        instance.call(LockHoldingTask.class.getName(), new Object[] {pdf, barrier})));
            }
            for (Future<Object> result : results) {
                assertEquals(pages, result.get());
            }
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void everyInstanceExtractsTheSameText() throws Exception {
        byte[] pdf = pdf("minimal.pdf");
        Object expected = pool.instances().getFirst().call(TextTask.class.getName(), pdf);
        for (PdfiumInstance instance : pool.instances()) {
            assertEquals(expected, instance.call(TextTask.class.getName(), pdf));
        }
    }

    @Test
    void tasksOutsideTheIsolatedPackagesAreRefused() {
        PdfiumInstance isolated = pool.instances().get(1);
        assertThrows(IllegalArgumentException.class,
                () -> isolated.call("java.util.function.Function", null));
    }

    @Test
    void acquireHandsOutEachInstanceOnce() throws Exception {
        List<PdfiumInstance> taken = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            taken.add(pool.acquire(Duration.ofSeconds(5)));
        }
        try {
            assertNull(pool.acquire(Duration.ofMillis(50)));
            assertEquals(SIZE, new HashSet<>(taken).size());
            assertThrows(IllegalStateException.class, () -> {
                pool.release(taken.getFirst());
                pool.release(taken.getFirst());
            });
        } finally {
            for (PdfiumInstance instance : taken.subList(1, taken.size())) {
                pool.release(instance);
            }
        }
        assertEquals(SIZE, pool.available());
    }

    @Test
    void aSecondPoolLoadsCopiesOfItsOwn() {
        PdfiumInstancePool second = PdfiumInstancePool.builder().size(2).build();
        assertSame(PdfiumRuntime.class.getClassLoader(),
                second.instances().getFirst().call(LoaderIdentityTask.class.getName(), null));
        assertNotSame(pool.instances().get(1).classLoader(),
                second.instances().get(1).classLoader());
    }

    @Test
    @Timeout(300)
    void instancesRenderAndExtractImagesInParallelAndAgreeWithTheSharedInstance() throws Exception {
        for (String task : List.of(RenderTask.class.getName(), ImagesTask.class.getName())) {
            for (String name : List.of("mozilla_tracemonkey.pdf", "pdfjs_TAMReview.pdf")) {
                byte[] pdf = pdf(name);
                Object expected = pool.instances().getFirst().call(task, pdf);
                ExecutorService threads = Executors.newFixedThreadPool(SIZE);
                try {
                    List<Future<Object>> results = new ArrayList<>();
                    for (int round = 0; round < 4; round++) {
                        for (int i = 0; i < SIZE; i++) {
                            results.add(threads.submit(() -> pool.call(task, pdf)));
                        }
                    }
                    for (Future<Object> result : results) {
                        assertEquals(expected, result.get(), task + " " + name);
                    }
                } finally {
                    threads.shutdownNow();
                }
            }
        }
    }

    @Test
    void preparedCopiesAreReusedByTheNextPool() {
        PdfiumInstancePool.prepareCopies(3);
        assertEquals(2, PdfiumInstancePool.builder().size(2).build().size());
    }

    @Test
    @Timeout(10)
    void closingFailsCallersWaitingForAnInstance() throws Exception {
        PdfiumInstancePool single = PdfiumInstancePool.builder().size(1).build();
        PdfiumInstance only = single.acquire();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> untimed = threads.submit(() -> single.acquire());
            Future<?> timed = threads.submit(() -> single.acquire(Duration.ofMinutes(5)));
            while (single.waiting() < 2) {
                Thread.onSpinWait();
            }
            single.close();
            for (Future<?> waiting : List.of(untimed, timed)) {
                ExecutionException failed = assertThrows(ExecutionException.class, waiting::get);
                assertTrue(failed.getCause() instanceof IllegalStateException);
            }
            single.release(only);
            assertThrows(IllegalStateException.class, () -> single.release(only));
        } finally {
            threads.shutdownNow();
        }
    }
}
