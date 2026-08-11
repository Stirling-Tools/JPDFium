package stirling.software.jpdfium.vips;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsOption;
import stirling.software.jpdfium.internal.RenderedPageView;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class VipsEncoder {

    private static final int DEFAULT_PNG_COMPRESSION = 6;

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
        return image;
    }

    private static VBlob encodeToBlob(VImage image, VipsEncodeOptions opts) {
        VipsOption[] options = buildOptions(opts);
        return switch (opts.format()) {
            case HEIC, HEIF -> image.heifsaveBuffer(options);
            case AVIF -> {
                VipsOption[] withAv1 = append(options, VipsOption.String("compression", "av1"));
                yield image.heifsaveBuffer(withAv1);
            }
            case JXL -> image.jxlsaveBuffer(options);
            case WEBP -> image.webpsaveBuffer(options);
            case PNG -> image.pngsaveBuffer(options);
            case JPEG -> image.jpegsaveBuffer(options);
            case TIFF -> image.tiffsaveBuffer(options);
        };
    }

    private static void writeToFile(VImage image, String path, VipsEncodeOptions opts) {
        VipsOption[] options = buildOptions(opts);
        switch (opts.format()) {
            case HEIC, HEIF -> image.heifsave(path, options);
            case AVIF -> {
                VipsOption[] withAv1 = append(options, VipsOption.String("compression", "av1"));
                image.heifsave(path, withAv1);
            }
            case JXL -> image.jxlsave(path, options);
            case WEBP -> image.webpsave(path, options);
            case PNG -> image.pngsave(path, options);
            case JPEG -> image.jpegsave(path, options);
            case TIFF -> image.tiffsave(path, options);
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
        };
    }

    private static VipsOption[] buildHeifOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("Q", opts.quality()));
        if (opts.lossless()) list.add(VipsOption.Boolean("lossless", true));
        list.add(VipsOption.Int("bitdepth", opts.bitdepth()));
        list.add(VipsOption.Int("effort", opts.effort()));
        if (opts.format().compression() != null) {
            list.add(VipsOption.String("compression", opts.format().compression()));
        }
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildJxlOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("Q", opts.quality()));
        if (opts.lossless()) list.add(VipsOption.Boolean("lossless", true));
        list.add(VipsOption.Int("effort", opts.effort()));
        list.add(VipsOption.Int("bitdepth", opts.bitdepth()));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildWebpOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("Q", opts.quality()));
        if (opts.lossless()) list.add(VipsOption.Boolean("lossless", true));
        list.add(VipsOption.Int("effort", opts.effort()));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildPngOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("compression", DEFAULT_PNG_COMPRESSION));
        list.add(VipsOption.Int("effort", opts.effort()));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] buildJpegOptions(VipsEncodeOptions opts) {
        return new VipsOption[]{VipsOption.Int("Q", opts.quality())};
    }

    private static VipsOption[] buildTiffOptions(VipsEncodeOptions opts) {
        List<VipsOption> list = new ArrayList<>();
        list.add(VipsOption.Int("Q", opts.quality()));
        if (opts.lossless()) list.add(VipsOption.Boolean("lossless", true));
        return list.toArray(VipsOption[]::new);
    }

    private static VipsOption[] append(VipsOption[] base, VipsOption extra) {
        VipsOption[] result = new VipsOption[base.length + 1];
        System.arraycopy(base, 0, result, 0, base.length);
        for (int i = 0; i < base.length; i++) {
            if (base[i].key().equals(extra.key())) {
                result[i] = extra;
                return result;
            }
        }
        result[base.length] = extra;
        return result;
    }
}
