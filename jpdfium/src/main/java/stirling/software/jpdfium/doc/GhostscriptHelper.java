package stirling.software.jpdfium.doc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Helper for invoking Ghostscript for PDF compression operations.
 *
 * <p>Ghostscript provides image resampling, font subsetting, and lossy compression
 * that cannot be done through qpdf or PDFium alone. Requires {@code gs} to be
 * installed and on the system PATH.
 */
final class GhostscriptHelper {

    private GhostscriptHelper() {}

    /**
     * Ghostscript PDFSETTINGS presets mapping to compression aggressiveness.
     */
    enum GsPreset {
        SCREEN("/screen"),     // 72 dpi, maximum compression
        EBOOK("/ebook"),       // 150 dpi, moderate quality
        PRINTER("/printer"),   // 300 dpi, high quality
        PREPRESS("/prepress"), // 300 dpi, maximum quality
        DEFAULT("/default");   // no specific PDFSETTINGS

        final String value;
        GsPreset(String v) { this.value = v; }
    }

    /**
     * Check if Ghostscript is available on the system PATH.
     */
    static boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("gs", "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Compress a PDF using Ghostscript's pdfwrite device with a preset.
     *
     * @param input  input PDF path
     * @param output output PDF path
     * @param preset Ghostscript PDFSETTINGS preset
     */
    static void compress(Path input, Path output, GsPreset preset) {
        List<String> args = new ArrayList<>();
        args.add("-dNOPAUSE");
        args.add("-dBATCH");
        args.add("-dQUIET");
        args.add("-sDEVICE=pdfwrite");
        args.add("-dCompatibilityLevel=1.5");
        if (preset != GsPreset.DEFAULT) {
            args.add("-dPDFSETTINGS=" + preset.value);
        }
        args.add("-sOutputFile=" + output.toAbsolutePath());
        args.add(input.toAbsolutePath().toString());
        run(args.toArray(String[]::new));
    }

    /**
     * Compress a PDF with explicit image DPI and JPEG quality settings.
     *
     * @param input        input PDF path
     * @param output       output PDF path
     * @param imageQuality JPEG quality (1-100)
     * @param maxDpi       maximum image DPI for downsampling
     */
    static void compressCustom(Path input, Path output, int imageQuality, int maxDpi) {
        List<String> args = new ArrayList<>();
        args.add("-dNOPAUSE");
        args.add("-dBATCH");
        args.add("-dQUIET");
        args.add("-sDEVICE=pdfwrite");
        args.add("-dCompatibilityLevel=1.5");
        args.add("-sOutputFile=" + output.toAbsolutePath());

        // Image downsampling
        if (maxDpi > 0) {
            args.add("-dDownsampleColorImages=true");
            args.add("-dColorImageResolution=" + maxDpi);
            args.add("-dDownsampleGrayImages=true");
            args.add("-dGrayImageResolution=" + maxDpi);
            args.add("-dDownsampleMonoImages=true");
            args.add("-dMonoImageResolution=" + Math.max(maxDpi, 150));
        }

        // JPEG quality
        if (imageQuality > 0 && imageQuality <= 100) {
            args.add("-dAutoFilterColorImages=false");
            args.add("-dColorImageFilter=/DCTEncode");
            args.add("-dAutoFilterGrayImages=false");
            args.add("-dGrayImageFilter=/DCTEncode");
            // Map quality 1-100 to Ghostscript's 0.0-1.0 scale
            String quality = String.format("%.2f", imageQuality / 100.0);
            args.add("-c");
            args.add("<< /ColorImageDict << /QFactor " + quality + " /Blend 1 /HSamples [2 1 1 2] /VSamples [2 1 1 2] >> >> setdistillerparams");
            args.add("<< /GrayImageDict << /QFactor " + quality + " /Blend 1 /HSamples [2 1 1 2] /VSamples [2 1 1 2] >> >> setdistillerparams");
            args.add("-f");
        }

        args.add(input.toAbsolutePath().toString());
        run(args.toArray(String[]::new));
    }

    /**
     * Run a Ghostscript command with the given arguments.
     */
    static void run(String... args) {
        List<String> command = new ArrayList<>();
        command.add("gs");
        Collections.addAll(command, args);

        try {
            Process p = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            byte[] output = p.getInputStream().readAllBytes();
            boolean finished = p.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new RuntimeException("Ghostscript timed out after 300 seconds");
            }
            if (p.exitValue() != 0) {
                throw new RuntimeException("Ghostscript failed (exit=" + p.exitValue() + "): "
                        + new String(output).trim());
            }
        } catch (IOException e) {
            throw new RuntimeException("gs not found on PATH. Install Ghostscript: " +
                    "apt install ghostscript / brew install ghostscript", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Ghostscript interrupted", e);
        }
    }
}
