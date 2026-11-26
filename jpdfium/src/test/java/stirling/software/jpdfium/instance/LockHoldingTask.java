package stirling.software.jpdfium.instance;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.PdfiumRuntime;

public class LockHoldingTask implements Function<Object, Object> {

    @Override
    public Object apply(Object input) {
        Object[] args = (Object[]) input;
        byte[] pdf = (byte[]) args[0];
        CyclicBarrier barrier = (CyclicBarrier) args[1];
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            int[] pages = new int[1];
            PdfiumRuntime.execute(() -> {
                pages[0] = doc.pageCount();
                try {
                    barrier.await(20, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("instances did not run at the same time", e);
                }
            });
            return pages[0];
        }
    }
}
