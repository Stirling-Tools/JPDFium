package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.RustBridgeBindings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF compression and file size reduction.
 *
 * <p>Combines four compression strategies in an optimal pipeline:
 * <ol>
 *   <li><strong>Ghostscript</strong> (if available): image resampling, font subsetting,
 *       lossy JPEG compression, and PDF stream re-encoding</li>
 *   <li><strong>qpdf</strong> (if available): structural optimization via object streams,
 *       cross-reference stream compression, and unreferenced object removal</li>
 *   <li><strong>PDFium</strong>: metadata stripping via {@link PdfSecurity}</li>
 *   <li><strong>Rust/zopfli</strong> (optional, if compiled in): lopdf reloads the output
 *       and recompresses every FlateDecode stream with zopfli, typically saving a further
 *       10-25% over standard DEFLATE. Enabled via
 *       {@link CompressOptions.Builder#useZopfliDeflate(boolean)}.</li>
 * </ol>
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("large.pdf"))) {
 *     var result = PdfCompressor.compress(doc, CompressOptions.builder()
 *         .preset(CompressPreset.WEB)
 *         .build());
 *     Files.write(Path.of("compressed.pdf"), result.bytes());
 *     System.out.println(result.summary());
 * }
 *
 * // One-liner:
 * byte[] small = PdfCompressor.forWeb(doc);
 * }</pre>
 */
public final class PdfCompressor {

    private PdfCompressor() {}

    /**
     * Compress a document using the given options.
     * Pipeline: Ghostscript (lossy) -> qpdf (structural) -> metadata strip
     *           -> Rust/zopfli recompression (optional).
     *
     * @param doc  the source document
     * @param opts compression options
     * @return compression result with statistics and the compressed PDF bytes
     */
    public static CompressResultWithBytes compress(PdfDocument doc, CompressOptions opts) {
        List<String> actions = new ArrayList<>();
        long originalSize = doc.saveBytes().length;
        int metadataRemoved = 0;
        int imagesOptimized = 0;

        // 1. Remove metadata if requested (done first, before saving to disk)
        if (opts.removeMetadata()) {
            PdfSecurity.Result sec = PdfSecurity.builder()
                    .removeXmpMetadata(true)
                    .removeDocumentMetadata(true)
                    .build()
                    .execute(doc);
            metadataRemoved = sec.xmpMetadataFieldsRemoved() + sec.documentMetadataFieldsRemoved();
            if (metadataRemoved > 0) {
                actions.add("Removed %d metadata fields".formatted(metadataRemoved));
            }
        }

        // Save document to temp for external tool processing
        Path tempIn = null;
        Path tempGs = null;
        Path tempOut = null;
        byte[] resultBytes;
        boolean streamsOptimized = false;

        try {
            tempIn = Files.createTempFile("jpdfium-compress-in-", ".pdf");
            doc.save(tempIn);
            Path currentInput = tempIn;

            // 2. Ghostscript pass: image resampling + lossy compression
            boolean wantGsPass = opts.imageQuality() > 0 || opts.maxImageDpi() > 0
                    || opts.convertPngToJpeg() || opts.recompressLossless();
            if (wantGsPass && GhostscriptHelper.isAvailable()) {
                tempGs = Files.createTempFile("jpdfium-compress-gs-", ".pdf");
                GsPresetMapping gsMap = mapToGsPreset(opts);
                if (gsMap.useCustom) {
                    GhostscriptHelper.compressCustom(currentInput, tempGs,
                            opts.imageQuality(), opts.maxImageDpi());
                    actions.add("Ghostscript: images recompressed (JPEG quality=%d, max DPI=%d)"
                            .formatted(opts.imageQuality(), opts.maxImageDpi()));
                } else {
                    GhostscriptHelper.compress(currentInput, tempGs, gsMap.preset);
                    actions.add("Ghostscript: compressed with %s preset".formatted(gsMap.preset));
                }
                imagesOptimized = 1; // gs processes all images in one pass
                currentInput = tempGs;
            }

            // 3. qpdf pass: structural optimization
            if (opts.optimizeStreams() && QpdfHelper.isAvailable()) {
                tempOut = Files.createTempFile("jpdfium-compress-out-", ".pdf");
                List<String> qpdfArgs = new ArrayList<>();
                qpdfArgs.add("--object-streams=generate");
                qpdfArgs.add(currentInput.toAbsolutePath().toString());
                qpdfArgs.add(tempOut.toAbsolutePath().toString());
                QpdfHelper.run(qpdfArgs.toArray(String[]::new));
                resultBytes = Files.readAllBytes(tempOut);
                streamsOptimized = true;
                actions.add("qpdf: optimized object streams and cross-reference tables");
            } else if (opts.removeUnusedObjects() && QpdfHelper.isAvailable()) {
                tempOut = Files.createTempFile("jpdfium-compact-out-", ".pdf");
                PdfStreamOptimizer.compact(currentInput, tempOut);
                resultBytes = Files.readAllBytes(tempOut);
                actions.add("qpdf: compacted (removed unreferenced objects)");
            } else {
                // Read from the best intermediate result
                resultBytes = Files.readAllBytes(currentInput);
            }
        } catch (IOException e) {
            throw new RuntimeException("Compression failed", e);
        } finally {
            deleteQuietly(tempIn);
            deleteQuietly(tempGs);
            deleteQuietly(tempOut);
        }

        // 4. Rust/zopfli post-processing pass (optional)
        if (opts.useZopfliDeflate()) {
            byte[] zopfliResult = RustBridgeBindings.rustCompressPdf(
                    resultBytes, opts.zopfliIterations());
            if (zopfliResult != null && zopfliResult.length < resultBytes.length) {
                resultBytes = zopfliResult;
                actions.add("Rust/zopfli: recompressed FlateDecode streams (%d iterations)"
                        .formatted(opts.zopfliIterations()));
            } else if (zopfliResult != null) {
                // zopfli produced larger output (unlikely but possible for tiny PDFs) - discard
                actions.add("Rust/zopfli: skipped (zopfli output not smaller)");
            }
            // null means Rust not available - silently skip
        }

        CompressResult result = new CompressResult(
                originalSize, resultBytes.length,
                imagesOptimized, metadataRemoved, streamsOptimized,
                List.copyOf(actions)
        );

        return new CompressResultWithBytes(result, resultBytes);
    }

    /**
     * Convenience: compress for web delivery (Ghostscript + qpdf, moderate quality).
     */
    public static byte[] forWeb(PdfDocument doc) {
        return compress(doc, CompressOptions.builder()
                .preset(CompressPreset.WEB)
                .build()).bytes();
    }

    /**
     * Convenience: lossless compression (qpdf structural optimization only).
     */
    public static byte[] lossless(PdfDocument doc) {
        return compress(doc, CompressOptions.builder()
                .preset(CompressPreset.LOSSLESS)
                .build()).bytes();
    }

    /**
     * Convenience: maximum compression (aggressive Ghostscript + qpdf).
     */
    public static byte[] maximum(PdfDocument doc) {
        return compress(doc, CompressOptions.builder()
                .preset(CompressPreset.MAXIMUM)
                .build()).bytes();
    }

    // Map CompressOptions to a Ghostscript preset or custom settings
    private record GsPresetMapping(GhostscriptHelper.GsPreset preset, boolean useCustom) {}

    private static GsPresetMapping mapToGsPreset(CompressOptions opts) {
        // If both quality and DPI are specified, use custom settings
        if (opts.imageQuality() > 0 && opts.maxImageDpi() > 0) {
            return new GsPresetMapping(GhostscriptHelper.GsPreset.DEFAULT, true);
        }
        // Map DPI ranges to Ghostscript presets
        if (opts.maxImageDpi() <= 96) {
            return new GsPresetMapping(GhostscriptHelper.GsPreset.SCREEN, false);
        }
        if (opts.maxImageDpi() <= 150) {
            return new GsPresetMapping(GhostscriptHelper.GsPreset.EBOOK, false);
        }
        if (opts.maxImageDpi() <= 300) {
            return new GsPresetMapping(GhostscriptHelper.GsPreset.PRINTER, false);
        }
        return new GsPresetMapping(GhostscriptHelper.GsPreset.EBOOK, false);
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
    }

    /**
     * Compression result bundled with the compressed PDF bytes.
     */
    public record CompressResultWithBytes(CompressResult result, byte[] bytes) {
        public String summary() { return result.summary(); }
        public String toJson() { return result.toJson(); }
        public long bytesSaved() { return result.bytesSaved(); }
        public double compressionPercent() { return result.compressionPercent(); }
    }
}
