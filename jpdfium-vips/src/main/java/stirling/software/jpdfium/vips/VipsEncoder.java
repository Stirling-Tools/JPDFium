package stirling.software.jpdfium.vips;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsForeignHeifCompression;
import app.photofox.vipsffm.enums.VipsForeignTiffCompression;
import app.photofox.vipsffm.enums.VipsForeignTiffPredictor;
import app.photofox.vipsffm.enums.VipsInterpretation;
import stirling.software.jpdfium.internal.RenderedPageView;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class VipsEncoder {

    private static final int DEFAULT_PNG_COMPRESSION = 6;

    private enum TiffLayout {
        ARGB(4, true, 8, VipsInterpretation.INTERPRETATION_sRGB),
        RGB(3, false, 8, VipsInterpretation.INTERPRETATION_sRGB),
        GRAY(1, false, 8, VipsInterpretation.INTERPRETATION_B_W),
        BINARY(1, false, 1, VipsInterpretation.INTERPRETATION_B_W);

        final int bands;
        final boolean alpha;
        final int bitDepth;
        final VipsInterpretation interpretation;

        TiffLayout(int bands, boolean alpha, int bitDepth, VipsInterpretation interpretation) {
            this.bands = bands;
            this.alpha = alpha;
            this.bitDepth = bitDepth;
            this.interpretation = interpretation;
        }

        static TiffLayout forImage(BufferedImage image) {
            return switch (image.getType()) {
                case BufferedImage.TYPE_INT_ARGB -> ARGB;
                case BufferedImage.TYPE_INT_RGB -> RGB;
                case BufferedImage.TYPE_BYTE_GRAY -> GRAY;
                case BufferedImage.TYPE_BYTE_BINARY ->
                        image.getColorModel().getPixelSize() == 1 ? BINARY : null;
                default -> null;
            };
        }
    }

    private VipsEncoder() {}

    public static byte[] encodeToBytes(RenderedPageView view, VipsEncodeOptions opts) {
        VipsAvailability.require(opts.format());
        byte[][] result = new byte[1][];
        Vips.run(arena -> {
            VImage image = wrapView(arena, view);
            VBlob blob = encodeToBlob(image, opts);
            result[0] = blob.getBytes();
        });
        return result[0];
    }

    /**
     * Encode {@link BufferedImage} frames as consecutive pages of one TIFF
     * without materialising an intermediate RGBA {@code byte[]} per page.
     * Pixels are copied row-by-row from the Java image into the native arena.
     */
    static void encodeImagesToStream(
            List<BufferedImage> images, VipsEncodeOptions opts, OutputStream output) throws IOException {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("At least one frame is required");
        }
        if (opts.format() != VipsFormat.TIFF) {
            throw new IllegalArgumentException("Multi-page encoding requires TIFF");
        }
        BufferedImage first = images.get(0);
        if (first == null) {
            throw new IllegalArgumentException("Frame 0 is null");
        }
        int width = first.getWidth();
        int height = first.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid frame dimensions " + width + "x" + height);
        }
        for (int i = 0; i < images.size(); i++) {
            BufferedImage image = images.get(i);
            if (image == null) {
                throw new IllegalArgumentException("Frame " + i + " is null");
            }
            if (image.getWidth() != width || image.getHeight() != height) {
                throw new IllegalArgumentException(
                        "All TIFF pages must have the same dimensions; frame " + i + " differs");
            }
        }

        TiffLayout layout = null;
        for (BufferedImage image : images) {
            TiffLayout current = TiffLayout.forImage(image);
            if (current == null) {
                throw new UnsupportedOperationException(
                        "libvips multi-page TIFF supports RGB, ARGB, grey, and 1-bit images;"
                                + " use ImageIO for this image type");
            }
            if (layout != null && layout != current) {
                throw new UnsupportedOperationException(
                        "libvips multi-page TIFF requires one color type per file;"
                                + " use ImageIO for mixed image types");
            }
            layout = current;
        }
        int bands = layout.bands;
        long pageBytes = (long) width * height * bands;
        long totalBytes = pageBytes * images.size();
        if (pageBytes > Integer.MAX_VALUE || totalBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Multi-page TIFF is too large: " + width + "x" + height
                    + " x " + images.size() + " pages");
        }
        int pageBytesInt = (int) pageBytes;
        TiffLayout selected = layout;
        IOException[] writeFailure = new IOException[1];
        Vips.run(arena -> {
            MemorySegment pixels = arena.allocate(totalBytes);
            byte[] row = new byte[width * bands];
            for (int i = 0; i < images.size(); i++) {
                copyImageToSegment(images.get(i), pixels, (long) i * pageBytesInt, row, selected);
            }
            VImage image = VImage.newFromMemory(
                    arena, pixels, width, height * images.size(), bands, 0);
            image = image.copy(
                    VipsOption.Enum("interpretation", selected.interpretation));
            VBlob blob = image.tiffsaveBuffer(
                    encodeMultiPageOptions(opts, height, selected.bitDepth));
            ByteBuffer bytes = blob.asArenaScopedByteBuffer();
            byte[] chunk = new byte[64 * 1024];
            try {
                while (bytes.hasRemaining()) {
                    int count = Math.min(bytes.remaining(), chunk.length);
                    bytes.get(chunk, 0, count);
                    output.write(chunk, 0, count);
                }
            } catch (IOException e) {
                writeFailure[0] = e;
            }
        });
        if (writeFailure[0] != null) {
            throw writeFailure[0];
        }
    }

    private static void copyImageToSegment(
            BufferedImage image,
            MemorySegment destination,
            long offset,
            byte[] row,
            TiffLayout layout) {
        int width = image.getWidth();
        int height = image.getHeight();
        int bands = layout.bands;
        int[] packed = null;
        int[] rowPixels = null;
        if (layout == TiffLayout.GRAY) {
            // Preserve the raw gray sample; getRGB would apply the gray color
            // space's gamma conversion and change the stored value.
        } else if ((layout == TiffLayout.ARGB || layout == TiffLayout.RGB)
                && image.getRaster().getSampleModel() instanceof SinglePixelPackedSampleModel sppsm
                && sppsm.getScanlineStride() == width
                && image.getRaster().getDataBuffer() instanceof DataBufferInt dbi
                && dbi.getOffset() == 0
                && image.getRaster().getParent() == null
                && image.getRaster().getMinX() == 0
                && image.getRaster().getMinY() == 0
                && image.getRaster().getSampleModelTranslateX() == 0
                && image.getRaster().getSampleModelTranslateY() == 0
                && dbi.getData().length >= width * height) {
            packed = dbi.getData();
        } else {
            rowPixels = new int[width];
        }

        for (int y = 0; y < height; y++) {
            if (layout == TiffLayout.GRAY) {
                image.getRaster().getDataElements(0, y, width, 1, row);
            } else if (packed != null) {
                int src = y * width;
                for (int x = 0, out = 0; x < width; x++, out += bands) {
                    int p = packed[src + x];
                    row[out] = (byte) ((p >> 16) & 0xFF);
                    if (bands >= 3) {
                        row[out + 1] = (byte) ((p >> 8) & 0xFF);
                        row[out + 2] = (byte) (p & 0xFF);
                    }
                    if (layout.alpha) {
                        row[out + 3] = (byte) ((p >> 24) & 0xFF);
                    }
                }
            } else {
                image.getRGB(0, y, width, 1, rowPixels, 0, width);
                for (int x = 0, out = 0; x < width; x++, out += bands) {
                    int p = rowPixels[x];
                    int r = (p >> 16) & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = p & 0xFF;
                    if (layout == TiffLayout.BINARY) {
                        row[out] = (byte) (((r + g + b) / 3 >= 128) ? 0xFF : 0x00);
                    } else {
                        row[out] = (byte) r;
                        row[out + 1] = (byte) g;
                        row[out + 2] = (byte) b;
                        if (layout.alpha) {
                            row[out + 3] = (byte) ((p >> 24) & 0xFF);
                        }
                    }
                }
            }
            MemorySegment.copy(MemorySegment.ofArray(row), 0, destination,
                    offset + (long) y * row.length, row.length);
        }
    }

    public static void encodeToFile(RenderedPageView view, Path output, VipsEncodeOptions opts)
            throws IOException {
        VipsAvailability.require(opts.format());
        Vips.run(arena -> {
            VImage image = wrapView(arena, view);
            writeToFile(image, output.toAbsolutePath().toString(), opts);
        });
    }

    private static VImage wrapView(Arena arena, RenderedPageView view) {
        if (!view.isTight()) {
            throw new IllegalArgumentException(
                    "RenderedPageView stride " + view.stride() + " != width*bands "
                    + (view.width() * view.bands()));
        }
        VImage image = VImage.newFromMemory(
                arena, view.pixels(), view.width(), view.height(), view.bands(), 0);
        if (view.format().name().contains("PREMUL")) {
            image = image.unpremultiply();
        }
        return image.copy(
                VipsOption.Enum("interpretation", VipsInterpretation.INTERPRETATION_sRGB));
    }

    static VBlob encodeToBlob(VImage image, VipsEncodeOptions opts) {
        VipsOption[] options = buildOptions(opts);
        return switch (opts.format()) {
            case HEIC, HEIF, AVIF -> image.heifsaveBuffer(options);
            case JXL -> image.jxlsaveBuffer(options);
            case WEBP -> image.webpsaveBuffer(options);
            case PNG -> image.pngsaveBuffer(options);
            case JPEG -> image.jpegsaveBuffer(options);
            case TIFF -> image.tiffsaveBuffer(options);
            case JPEG2000 -> image.jp2ksaveBuffer(options);
        };
    }

    private static VipsOption[] encodeMultiPageOptions(
            VipsEncodeOptions opts, int pageHeight, int bitDepth) {
        VipsOption[] base = buildOptions(opts);
        boolean customBitDepth = bitDepth < 8;
        VipsOption[] options = Arrays.copyOf(base, base.length + (customBitDepth ? 2 : 1));
        options[base.length] = VipsOption.Int("page-height", pageHeight);
        if (customBitDepth) {
            options[base.length + 1] = VipsOption.Int("bitdepth", bitDepth);
        }
        return options;
    }

    private static void writeToFile(VImage image, String path, VipsEncodeOptions opts) {
        VipsOption[] options = buildOptions(opts);
        switch (opts.format()) {
            case HEIC, HEIF, AVIF -> image.heifsave(path, options);
            case JXL -> image.jxlsave(path, options);
            case WEBP -> image.webpsave(path, options);
            case PNG -> image.pngsave(path, options);
            case JPEG -> image.jpegsave(path, options);
            case TIFF -> image.tiffsave(path, options);
            case JPEG2000 -> image.jp2ksave(path, options);
        }
    }

    static VipsOption[] buildOptions(VipsEncodeOptions opts) {
        return switch (opts.format()) {
            case HEIC, HEIF, AVIF -> buildHeifOptions(opts);
            case JXL -> buildJxlOptions(opts);
            case WEBP -> buildWebpOptions(opts);
            case PNG -> buildPngOptions(opts);
            case JPEG -> buildJpegOptions(opts);
            case TIFF -> buildTiffOptions(opts);
            case JPEG2000 -> buildJpeg2000Options(opts);
        };
    }

    private static VipsOption[] buildHeifOptions(VipsEncodeOptions opts) {
        if (opts.format() == VipsFormat.AVIF && opts.bitdepth() > 8) {
            throw new UnsupportedOperationException(
                    "AVIF high-bit-depth encoding is disabled in this build; use 8-bit AVIF");
        }
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("Q", opts.quality()));
        if (opts.lossless()) list.add(VipsOption.Boolean("lossless", true));
        list.add(VipsOption.Int("bitdepth", opts.bitdepth()));
        list.add(VipsOption.Int("effort", opts.effort()));
        VipsForeignHeifCompression codec = switch (opts.format()) {
            case AVIF -> VipsForeignHeifCompression.FOREIGN_HEIF_COMPRESSION_AV1;
            case HEIC, HEIF -> VipsForeignHeifCompression.FOREIGN_HEIF_COMPRESSION_HEVC;
            default -> null;
        };
        if (codec != null) list.add(VipsOption.Enum("compression", codec));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildJxlOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("Q", opts.quality()));
        if (opts.lossless()) list.add(VipsOption.Boolean("lossless", true));
        list.add(VipsOption.Int("effort", opts.effort()));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildWebpOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("Q", opts.quality()));
        if (opts.lossless()) list.add(VipsOption.Boolean("lossless", true));
        list.add(VipsOption.Int("effort", opts.effort()));
        list.add(VipsOption.Boolean("strip", true));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildPngOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("compression", DEFAULT_PNG_COMPRESSION));
        list.add(VipsOption.Int("effort", opts.effort()));
        list.add(VipsOption.Boolean("strip", true));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildJpegOptions(VipsEncodeOptions opts) {
        return new VipsOption[]{
            VipsOption.Int("Q", opts.quality()),
            VipsOption.Boolean("strip", true),
            VipsOption.Boolean("optimize_coding", true)
        };
    }

    private static VipsOption[] buildTiffOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("Q", opts.quality()));
        if (opts.lossless()) list.add(VipsOption.Boolean("lossless", true));
        list.add(VipsOption.Enum("compression", VipsForeignTiffCompression.FOREIGN_TIFF_COMPRESSION_DEFLATE));
        list.add(VipsOption.Enum("predictor", VipsForeignTiffPredictor.FOREIGN_TIFF_PREDICTOR_HORIZONTAL));
        list.add(VipsOption.Boolean("strip", true));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildJpeg2000Options(VipsEncodeOptions opts) {
        return new VipsOption[]{VipsOption.Int("Q", opts.quality())};
    }
}
