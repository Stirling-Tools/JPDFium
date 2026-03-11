package stirling.software.jpdfium.doc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Convert a PDF to PDF/A conformance using Ghostscript.
 *
 * <p>Ghostscript's {@code pdfwrite} device with {@code -dPDFA} produces
 * PDF/A-1b, PDF/A-2b, or PDF/A-3b compliant output by embedding fonts,
 * converting color spaces to the specified ICC profile, and writing the
 * required XMP metadata.
 *
 * <p>Requires {@code gs} (Ghostscript) on the system PATH. An sRGB ICC
 * profile must be available (the method attempts well-known system paths
 * or accepts a user-specified path).
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("input.pdf"))) {
 *     doc.save(Path.of("/tmp/input.pdf"));
 * }
 * PdfAConverter.convert(
 *     Path.of("/tmp/input.pdf"),
 *     Path.of("output-pdfa.pdf"),
 *     PdfAConverter.PdfALevel.PDFA_2B
 * );
 * }</pre>
 */
public final class PdfAConverter {

    private PdfAConverter() {}

    /**
     * PDF/A conformance levels supported by Ghostscript.
     */
    public enum PdfALevel {
        /** PDF/A-1b (ISO 19005-1, basic conformance) */
        PDFA_1B(1, "1.4"),
        /** PDF/A-2b (ISO 19005-2, basic conformance) */
        PDFA_2B(2, "1.7"),
        /** PDF/A-3b (ISO 19005-3, basic conformance) */
        PDFA_3B(3, "2.0");

        final int gsValue;
        final String compatLevel;

        PdfALevel(int gsValue, String compatLevel) {
            this.gsValue = gsValue;
            this.compatLevel = compatLevel;
        }
    }

    /**
     * Result of a PDF/A conversion.
     */
    public record ConversionResult(
            Path outputPath,
            PdfALevel level,
            long inputSize,
            long outputSize,
            String iccProfileUsed
    ) {}

    /**
     * Convert a PDF file to PDF/A using system sRGB profile.
     *
     * @param input  input PDF path
     * @param output output PDF/A path
     * @param level  target PDF/A conformance level
     * @return conversion result
     */
    public static ConversionResult convert(Path input, Path output, PdfALevel level) {
        return convert(input, output, level, null);
    }

    /**
     * Convert a PDF file to PDF/A.
     *
     * @param input      input PDF path
     * @param output     output PDF/A path
     * @param level      target PDF/A conformance level
     * @param iccProfile path to sRGB ICC profile (null for auto-detect)
     * @return conversion result
     */
    public static ConversionResult convert(Path input, Path output, PdfALevel level,
                                            Path iccProfile) {
        if (!GhostscriptHelper.isAvailable()) {
            throw new RuntimeException("Ghostscript is not available. Install: " +
                    "apt install ghostscript / brew install ghostscript");
        }

        Path icc = (iccProfile != null) ? iccProfile : findSrgbProfile();
        if (icc == null || !Files.isReadable(icc)) {
            throw new RuntimeException("sRGB ICC profile not found. " +
                    "Install colord or icc-profiles-free, or provide a profile path.");
        }

        long inputSize;
        try {
            inputSize = Files.size(input);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read input file: " + input, e);
        }

        // Create the PDFA_def.ps file that Ghostscript needs
        Path pdfaDef;
        try {
            pdfaDef = Files.createTempFile("pdfa_def", ".ps");
            String defContent = createPdfaDefPs(icc, level);
            Files.writeString(pdfaDef, defContent);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create PDFA_def.ps temp file", e);
        }

        try {
            List<String> args = new ArrayList<>();
            args.add("-dNOPAUSE");
            args.add("-dBATCH");
            args.add("-dQUIET");
            args.add("-sDEVICE=pdfwrite");
            args.add("-dPDFA=" + level.gsValue);
            args.add("-dCompatibilityLevel=" + level.compatLevel);
            args.add("-dPDFACompatibilityPolicy=1"); // try to fix non-conformance
            args.add("-sColorConversionStrategy=RGB");
            args.add("-sOutputFile=" + output.toAbsolutePath());
            args.add(pdfaDef.toAbsolutePath().toString());
            args.add(input.toAbsolutePath().toString());

            GhostscriptHelper.run(args.toArray(String[]::new));

            long outputSize;
            try {
                outputSize = Files.size(output);
            } catch (IOException e) {
                outputSize = -1;
            }

            return new ConversionResult(output, level, inputSize, outputSize, icc.toString());
        } finally {
            try { Files.deleteIfExists(pdfaDef); } catch (IOException ignored) {}
        }
    }

    /**
     * Check if Ghostscript is available for PDF/A conversion.
     */
    public static boolean isAvailable() {
        return GhostscriptHelper.isAvailable();
    }

    /**
     * Create the PDFA_def.ps PostScript preamble that tells Ghostscript
     * which ICC profile to use and which PDF/A level to target.
     */
    private static String createPdfaDefPs(Path iccProfile, PdfALevel level) {
        // Ghostscript reads this PostScript file before the input PDF
        // to set up the PDF/A output intent
        String iccPath = iccProfile.toAbsolutePath().toString().replace("\\", "/");
        return "%% PDFA definition file for Ghostscript\n"
             + "/ICCProfile (" + iccPath + ") def\n"
             + "[\n"
             + "  /Title (PDF/A conversion)\n"
             + "  /DOCINFO pdfmark\n"
             + "\n"
             + "[\n"
             + "  /OutputConditionIdentifier (sRGB)\n"
             + "  /OutputCondition (sRGB IEC61966-2.1)\n"
             + "  /S /GTS_PDFA1\n"
             + "  /DestOutputProfile ICCProfile\n"
             + "  /RegistryName (http://www.color.org)\n"
             + "  /OutputIntents pdfmark\n";
    }

    /**
     * Search well-known system paths for an sRGB ICC profile.
     */
    private static Path findSrgbProfile() {
        String[] candidates = {
                // Linux (colord / icc-profiles-free)
                "/usr/share/color/icc/colord/sRGB.icc",
                "/usr/share/color/icc/sRGB.icc",
                "/usr/share/ghostscript/icc/srgb.icc",
                // Ghostscript bundled
                "/usr/share/ghostscript/default.icc",
                "/usr/share/ghostscript/iccprofiles/default_rgb.icc",
                // macOS
                "/System/Library/ColorSync/Profiles/sRGB Profile.icc",
                "/Library/ColorSync/Profiles/sRGB Profile.icc",
                // Windows (via WSL or standard paths)
                "/mnt/c/Windows/System32/spool/drivers/color/sRGB Color Space Profile.icm",
        };

        for (String candidate : candidates) {
            Path p = Path.of(candidate);
            if (Files.isReadable(p)) return p;
        }

        // Try to find via Ghostscript's resource directory
        try {
            ProcessBuilder pb = new ProcessBuilder("gs", "-q", "-dNODISPLAY",
                    "-c", "(default_rgb.icc) findlibfile { pop print } if quit");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!output.isEmpty()) {
                Path found = Path.of(output);
                if (Files.isReadable(found)) return found;
            }
        } catch (IOException | InterruptedException ignored) {}

        return null;
    }
}
