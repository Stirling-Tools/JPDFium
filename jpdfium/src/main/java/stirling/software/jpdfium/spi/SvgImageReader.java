package stirling.software.jpdfium.spi;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import stirling.software.jpdfium.SvgConverter;
import stirling.software.jpdfium.model.RenderResult;

/**
 * {@link ImageReader} for SVG documents, backed by JPDFium's native resvg
 * rasterizer.
 */
public final class SvgImageReader extends ImageReader {

    /** Refuse absurd inputs before handing them to the native parser. */
    static final int MAX_BYTES = 64 << 20;

    private byte[] bytes;
    private RenderResult rendered;

    SvgImageReader(SvgImageReaderSpi spi) {
        super(spi);
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        checkInput();
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return render().width();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return render().height();
    }

    @Override
    public ImageTypeSpecifier getRawImageType(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return ImageTypeSpecifier.createFromRenderedImage(render().toBufferedImage());
    }

    @Override
    public java.util.Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        List<ImageTypeSpecifier> types = new ArrayList<>(1);
        types.add(ImageTypeSpecifier.createFromRenderedImage(render().toBufferedImage()));
        return types.iterator();
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        BufferedImage image = render().toBufferedImage();
        BufferedImage source = applySourceRegion(image, param);
        BufferedImage result = applySubsampling(source, param);
        BufferedImage destination = param == null ? null : param.getDestination();
        if (destination != null) {
            if (destination.getWidth() < result.getWidth()
                    || destination.getHeight() < result.getHeight()) {
                throw new IOException("supplied destination image is too small");
            }
            java.awt.Graphics2D g = destination.createGraphics();
            try {
                g.drawImage(result, 0, 0, null);
            } finally {
                g.dispose();
            }
            return destination;
        }
        return result;
    }

    /** SVG has no Java-ImageIO stream metadata tree to expose. */
    @Override
    public IIOMetadata getStreamMetadata() throws IOException {
        return null;
    }

    /**
     * SVG has no Java-ImageIO metadata tree, so there is none to expose;
     * returning {@code null} is the documented "no metadata available" answer.
     */
    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return null;
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        bytes = toBytes(input);
        rendered = null;
    }

    @Override
    public void reset() {
        bytes = null;
        rendered = null;
    }

    @Override
    public void dispose() {
        reset();
        super.dispose();
    }

    /** Rasterize once and reuse for the width/height/read sequence. */
    private RenderResult render() throws IOException {
        if (rendered == null) {
            try {
                rendered = SvgConverter.toRgba(bytes);
            } catch (RuntimeException e) {
                throw new IOException("SVG rasterization failed", e);
            }
        }
        return rendered;
    }

    private void checkInput() {
        if (bytes == null) {
            throw new IllegalStateException("no input set; call setInput first");
        }
    }

    private void checkIndex(int imageIndex) throws IOException {
        checkInput();
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("imageIndex must be 0, was " + imageIndex);
        }
    }

    private static BufferedImage applySourceRegion(BufferedImage image, ImageReadParam param) {
        Rectangle region = param == null ? null : param.getSourceRegion();
        if (region == null) {
            return image;
        }
        Rectangle bounds = new Rectangle(0, 0, image.getWidth(), image.getHeight());
        Rectangle clipped = region.intersection(bounds);
        if (clipped.isEmpty()) {
            throw new IllegalArgumentException("source region " + region + " is outside the image");
        }
        return image.getSubimage(clipped.x, clipped.y, clipped.width, clipped.height);
    }

    private static BufferedImage applySubsampling(BufferedImage image, ImageReadParam param) {
        int xStep = param == null ? 1 : param.getSourceXSubsampling();
        int yStep = param == null ? 1 : param.getSourceYSubsampling();
        if (xStep <= 1 && yStep <= 1) {
            return image;
        }
        int width = (image.getWidth() + xStep - 1) / xStep;
        int height = (image.getHeight() + yStep - 1) / yStep;
        BufferedImage out = new BufferedImage(width, height, image.getType() == 0
                ? BufferedImage.TYPE_INT_ARGB
                : image.getType());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, image.getRGB(Math.min(x * xStep, image.getWidth() - 1),
                        Math.min(y * yStep, image.getHeight() - 1)));
            }
        }
        return out;
    }

    private static byte[] toBytes(Object input) {
        if (input instanceof byte[] array) {
            return array.length > MAX_BYTES ? null : array.clone();
        }
        try {
            if (input instanceof ImageInputStream stream) {
                return readLimited(stream);
            }
            if (input instanceof InputStream stream) {
                return readLimited(stream);
            }
            if (input instanceof File file) {
                return Files.size(file.toPath()) > MAX_BYTES
                        ? null
                        : Files.readAllBytes(file.toPath());
            }
            if (input instanceof Path path) {
                return Files.size(path) > MAX_BYTES ? null : Files.readAllBytes(path);
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BYTES) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** {@link ImageInputStream} is a {@link java.io.DataInput}, not an InputStream. */
    private static byte[] readLimited(ImageInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while (total <= MAX_BYTES && (read = in.read(buffer)) != -1) {
            total += read;
            out.write(buffer, 0, read);
        }
        return total > MAX_BYTES ? null : out.toByteArray();
    }

}
