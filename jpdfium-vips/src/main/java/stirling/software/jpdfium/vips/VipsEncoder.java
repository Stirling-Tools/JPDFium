package stirling.software.jpdfium.vips;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsForeignHeifCompression;
import app.photofox.vipsffm.enums.VipsInterpretation;
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
        // A raw newFromMemory image has an undefined colour interpretation.
        // libjxl's encoder rejects a 4-band image with an undefined
        // interpretation (JxlEncoderSetBasicInfo error) while the other savers
        // silently accept it - and tagging sRGB lets every saver write correct
        // colour metadata. Same thing a PNG/JPEG loader would produce.
        return image.copy(
                VipsOption.Enum("interpretation", VipsInterpretation.INTERPRETATION_sRGB));
    }

    private static VBlob encodeToBlob(VImage image, VipsEncodeOptions opts) {
        VipsOption[] options = buildOptions(opts);
        return switch (opts.format()) {
            case HEIC, HEIF, AVIF -> image.heifsaveBuffer(options);
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
            case HEIC, HEIF, AVIF -> image.heifsave(path, options);
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
        // The heif `compression` property is a VipsForeignHeifCompression enum
        // (GType int), not a string - passing a gchararray makes GLib refuse the
        // property and, for AV1, the save then fails. Use the enum option so the
        // raw int reaches vips_heifsave.
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
}
