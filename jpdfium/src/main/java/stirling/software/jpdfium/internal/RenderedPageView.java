package stirling.software.jpdfium.internal;

import java.lang.foreign.MemorySegment;

public final class RenderedPageView implements AutoCloseable {

    private final int width;
    private final int height;
    private final int stride;
    private final int bands;
    private final PixelFormat format;
    private final MemorySegment pixels;
    private final Runnable cleanup;
    private boolean closed;

    public RenderedPageView(int width, int height, int stride, int bands,
                            PixelFormat format, MemorySegment pixels, Runnable cleanup) {
        this.width = width;
        this.height = height;
        this.stride = stride;
        this.bands = bands;
        this.format = format;
        this.pixels = pixels;
        this.cleanup = cleanup;
    }

    public int width() { return width; }
    public int height() { return height; }
    public int stride() { return stride; }
    public int bands() { return bands; }
    public PixelFormat format() { return format; }
    public MemorySegment pixels() { return pixels; }

    public boolean isTight() {
        return stride == width * bands;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (cleanup != null) cleanup.run();
        }
    }
}
