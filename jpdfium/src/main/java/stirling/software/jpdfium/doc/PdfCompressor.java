package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.RustBridgeBindings;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * PDF compression and file size reduction.
 *
 * <p>Combines compression strategies in an optimal pipeline:
 * <ol>
 *   <li><strong>Signed PDFs</strong>: documents containing digital signatures are
 *       returned byte-for-byte unchanged - any rewrite invalidates the signature.</li>
 *   <li><strong>Images</strong> (in-process): downsampling and recompression via
 *       {@link PdfImageOptimizer} (skipped for PDF/A documents)</li>
 *   <li><strong>qpdf</strong> (in-process FFM): structural optimization via object streams,
 *       cross-reference stream compression, and unreferenced object removal</li>
 *   <li><strong>PDFium</strong>: metadata stripping via {@link PdfSecurity}
 *       (skipped for PDF/A documents)</li>
 *   <li><strong>Rust/zopfli</strong> (optional opt-in): lopdf reloads the output
 *       and recompresses every FlateDecode stream with zopfli. Enabled via
 *       {@link CompressOptions.Builder#useZopfliDeflate(boolean)}.</li>
 * </ol>
 *
 * <p>The result is <strong>monotonic</strong> for a document that has not been
 * modified since it was opened: the returned bytes are never larger than the
 * input. If in-memory edits make the serialized document exceed the input, the
 * larger bytes are returned and a warning is recorded. Every skipped or
 * downgraded step is reported in {@link CompressResult#warnings()}.
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

    // PDF/A conformance claim markers - an actual claim, not the generic XMP
    // schema description that every Adobe-produced XMP block embeds. Both the
    // element form (pdfaid:part>) and the attribute form (pdfaid:part=") count.
    private static final byte[][] PDFA_MARKERS = {
            "pdfaid:part>".getBytes(StandardCharsets.US_ASCII),
            "pdfaid:part=\"".getBytes(StandardCharsets.US_ASCII),
            "pdfaid:conformance>".getBytes(StandardCharsets.US_ASCII),
            "pdfaid:conformance=\"".getBytes(StandardCharsets.US_ASCII),
            "GTS_PDFA1".getBytes(StandardCharsets.US_ASCII),
    };
    // PDF/A-1 forbids object streams and cross-reference streams; later parts allow them.
    private static final byte[][] PDFA_PART1_MARKERS = {
            "pdfaid:part>1<".getBytes(StandardCharsets.US_ASCII),
            "pdfaid:part=\"1\"".getBytes(StandardCharsets.US_ASCII),
            "GTS_PDFA1".getBytes(StandardCharsets.US_ASCII),
    };
    // Catalog /Metadata reference. XMP streams are often Flate-compressed, so a
    // raw scan can miss the claim; presence of this key triggers a scan of the
    // catalog's metadata stream only.
    private static final byte[] METADATA_MARKER = "/Metadata".getBytes(StandardCharsets.US_ASCII);
    // Upper bound on an inflated metadata stream: a crafted /Metadata Flate bomb
    // must not be able to exhaust the heap.
    private static final int MAX_XMP_BYTES = 4 << 20;
    private static final byte[] FILTER_KEY = "/Filter".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FLATE_FILTER = "/FlateDecode".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STREAM_KEY = "stream".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ENDSTREAM_KEY = "endstream".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DICT_END = ">>".getBytes(StandardCharsets.US_ASCII);
    // Signature-dictionary markers. /ByteRange is exclusive to signature
    // dictionaries; PDFium's FPDF_GetSignatureCount can miss signatures that are
    // not reachable through the AcroForm (observed on signed IRS forms).
    private static final byte[] BYTE_RANGE = "/ByteRange".getBytes(StandardCharsets.US_ASCII);
    private static final byte[][] SIG_MARKERS = {
            "/Type/Sig".getBytes(StandardCharsets.US_ASCII),
            "/Type /Sig".getBytes(StandardCharsets.US_ASCII),
            "/SubFilter".getBytes(StandardCharsets.US_ASCII),
    };

    private PdfCompressor() {}

    /**
     * Compress a document using the given options. See the class documentation
     * for the pipeline; for a document that has not been modified since it was
     * opened the result is never larger than the input.
     *
     * @param doc  the source document
     * @param opts compression options
     * @return compression result with statistics, warnings, and the compressed PDF bytes
     */
    public static CompressResultWithBytes compress(PdfDocument doc, CompressOptions opts) {
        return compress(doc, opts, RustBridgeBindings::rustCompressPdf);
    }

    // Package-private seam so tests can inject a failing zopfli compressor and
    // exercise the verify/reject branch without the native backend.
    static CompressResultWithBytes compress(PdfDocument doc, CompressOptions opts,
            BiFunction<byte[], Integer, byte[]> zopfliCompressor) {
        List<String> actions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Read the true original bytes once: needed for the signed/PDF-A gates,
        // as the byte-for-byte starting point for a lossless pass, and to decide
        // whether the result grew. Prefer the open-time snapshot retained at the
        // document boundary so the bytes can never come from a path replaced
        // after the document was opened.
        Path sourcePath = doc.sourcePath();
        byte[] sourceBytes;
        boolean originalBytesKnown;
        byte[] retained = doc.sourceBytes();
        if (retained != null) {
            originalBytesKnown = true;
            sourceBytes = retained;
        } else if (sourcePath != null) {
            byte[] read = null;
            try {
                read = Files.readAllBytes(sourcePath);
            } catch (IOException _) {
                read = null;
            }
            originalBytesKnown = read != null;
            sourceBytes = read != null ? read : doc.saveBytes();
        } else {
            sourceBytes = doc.saveBytes();
            originalBytesKnown = false;
        }

        // 0. Signed documents are never rewritten: recompression would
        //    invalidate the signature's /ByteRange coverage.
        int pdfiumSignatures = signatureCount(doc);
        boolean signed = pdfiumSignatures > 0 || looksSignedByBytes(sourceBytes);
        if (signed && originalBytesKnown && doc.contentGeneration() == 0) {
            // The true original bytes are available and the document has not
            // been structurally modified since it was opened, so return them
            // byte-for-byte.
            String detail = pdfiumSignatures > 0
                    ? "%d digital signature(s)".formatted(pdfiumSignatures)
                    : "a digital signature";
            warnings.add(("Document contains %s; returned the original unchanged "
                    + "to preserve the signature(s)").formatted(detail));
            CompressResult signedResult = new CompressResult(
                    sourceBytes.length, sourceBytes.length, 0, 0, false,
                    List.of(), List.copyOf(warnings));
            return new CompressResultWithBytes(signedResult, sourceBytes);
        }
        if (signed) {
            if (originalBytesKnown) {
                // The caller modified a signed document after opening it, so the
                // open-time bytes no longer match the live document. Serialize the
                // edits rather than silently discarding them; the signature is
                // likely already invalid by then.
                warnings.add("Document contains a digital signature and was modified after it "
                        + "was opened; the live document is serialized and the signature may "
                        + "already be invalid.");
            } else {
                // Signed but the original bytes are unavailable (e.g. a stream/owned
                // document opened before byte retention). Warn and skip every
                // byte-changing pass so we do not invalidate the signature further.
                warnings.add("Document appears to contain a digital signature, but the original "
                        + "bytes are unavailable so the signature cannot be preserved; "
                        + "byte-changing compression passes are skipped.");
            }
        }

        // 1. A PDF/A conformance claim pins the file's structure and colour
        //    handling; lossy image passes and metadata removal would break it,
        //    so preserve conformance instead.
        int pdfPart = pdfaPart(sourceBytes);
        boolean pdfA = pdfPart >= 1;
        boolean pdfA1 = pdfPart == 1;
        if (pdfA) {
            warnings.add("PDF/A conformance claim detected; preserving conformance "
                    + "(image downsampling and metadata removal disabled)");
        }

        // Preserved documents (PDF/A, or a signed doc opened from memory) skip
        // every lossy/mutating pass and get structural optimization only.
        // EXACT mode forbids every lossy/mutating pass; PDF/A and signed
        // documents are preserved for correctness, not size.
        PreservationMode mode = opts.preservationMode();
        boolean preserve = pdfA || signed || mode == PreservationMode.EXACT;
        boolean wantImagePass = !preserve && mode.lossyAllowed()
                && (opts.imageQuality() > 0 || opts.maxImageDpi() > 0
                    || opts.convertPngToJpeg());
        boolean removeMetadata = opts.removeMetadata() && !preserve;
        if (wantImagePass && !PdfImageOptimizer.isSupported()) {
            warnings.add("image optimization was requested but is unavailable on this platform");
        }

        // Prefer the on-disk size: it is the true original.
        long originalSize = sourceBytes.length;

        int metadataRemoved = 0;
        int imagesOptimized = 0;
        int expectedPages = doc.pageCount();

        // 2. Remove metadata if requested, before serializing.
        if (removeMetadata) {
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

        // 3. Native image pass: downsample images above the DPI threshold.
        //    The pass is verified-and-rolled-back: for lossy modes with a finite
        //    fidelity tolerance, the before/after pages are rendered at low DPI
        //    and compared, and the pass is discarded when the mean absolute
        //    per-channel difference exceeds the mode's tolerance. The pre-image
        //    snapshot also lets a failed pass fall back to the pre-image bytes.
        byte[] imageRollbackBytes = null;
        byte[] imagePostBytes = null;
        if (wantImagePass && PdfImageOptimizer.isSupported()) {
            boolean verify = mode.lossyAllowed() && mode.maxMeanAbsDiff() < 255.0;
            // The pre-image captures every pass so far (e.g. metadata removal).
            // The image pass runs on a working copy opened from that snapshot so
            // a rejected pass can never leave the caller's live document holding
            // images the result reports as rolled back.
            byte[] preImage = doc.saveBytes();
            int n = 0;
            try (PdfDocument working = PdfDocument.open(preImage)) {
                n = PdfImageOptimizer.optimize(working, opts.maxImageDpi());
                if (n > 0) {
                    imagePostBytes = working.saveBytes();
                }
            } catch (JPDFiumException ex) {
                imageRollbackBytes = preImage;
                imagePostBytes = null;
                warnings.add("image pass failed: " + ex.getMessage());
                n = 0;
            }
            if (n > 0) {
                // Only keep rewritten images when they actually shrink the file;
                // an inflated image pass is dropped so it cannot throw away the
                // savings the structural passes earned.
                if (imagePostBytes.length >= preImage.length) {
                    imageRollbackBytes = preImage;
                    imagePostBytes = null;
                    warnings.add("image pass discarded: rewritten images were not smaller");
                } else if (!verify) {
                    imagesOptimized = n;
                    actions.add("Native: optimized %d image(s) (max DPI=%d)".formatted(
                            n, opts.maxImageDpi()));
                } else {
                    double mad = maxPreviewMeanAbsDiff(preImage, imagePostBytes, expectedPages);
                    if (mad <= mode.maxMeanAbsDiff()) {
                        imagesOptimized = n;
                        actions.add(("Native: optimized %d image(s) (max DPI=%d, "
                                + "verified mean-abs-diff=%.2f)").formatted(
                                        n, opts.maxImageDpi(), mad));
                    } else {
                        imageRollbackBytes = preImage;
                        imagePostBytes = null;
                        warnings.add(("image pass rolled back: preview mean-abs-diff %.2f "
                                + "exceeds %s tolerance %.2f").formatted(
                                        mad, mode, mode.maxMeanAbsDiff()));
                    }
                }
            }
        }

        // 4. Serialize once, then run the structural passes on the bytes.
        //    Always serialize the live document so in-memory edits are never
        //    dropped; the monotonic clamp below keeps the result <= the input.
        byte[] resultBytes;
        if (imageRollbackBytes != null) {
            resultBytes = imageRollbackBytes;
        } else if (imagePostBytes != null) {
            resultBytes = imagePostBytes;
        } else {
            resultBytes = doc.saveBytes();
        }
        boolean streamsOptimized = false;

        // 5. qpdf pass: recompress streams, generate object/xref streams.
        //    Only adopt when strictly smaller so small PDFs are never inflated.
        if (!signed && opts.optimizeStreams()) {
            // PDF/A-1 forbids object streams and cross-reference streams, so a
            // generated-xref result would break the conformance it claims.
            byte[] opt = pdfA1
                    ? PdfOptimizer.optimize(resultBytes, PdfOptimizer.DEFAULT, 9,
                            PdfOptimizer.OBJECT_STREAMS_PRESERVE,
                            PdfOptimizer.STREAM_DATA_COMPRESS,
                            PdfOptimizer.DECODE_LEVEL_GENERALIZED)
                    : PdfOptimizer.compress(resultBytes, 9, false, false);
            if (opt != null && opt.length < resultBytes.length) {
                resultBytes = opt;
                streamsOptimized = true;
                actions.add(pdfA1
                        ? "qpdf: recompressed streams (object streams preserved for PDF/A-1)"
                        : "qpdf: recompressed streams and generated object streams");
            } else {
                warnings.add("qpdf stream pass did not reduce size; kept original bytes");
            }
        } else if (!signed && opts.removeUnusedObjects()) {
            // Compact without restructuring object streams. Explicit flags are
            // required: the all-DEFAULT form measured worse than the source on
            // some inputs (inflating streams instead of recompressing them).
            byte[] opt = PdfOptimizer.optimize(resultBytes,
                    PdfOptimizer.RECOMPRESS_FLATE | PdfOptimizer.COMPRESS_STREAMS, 9,
                    PdfOptimizer.OBJECT_STREAMS_PRESERVE,
                    PdfOptimizer.STREAM_DATA_COMPRESS,
                    PdfOptimizer.DECODE_LEVEL_GENERALIZED);
            if (opt != null && opt.length < resultBytes.length) {
                resultBytes = opt;
                streamsOptimized = true;
                actions.add("qpdf: compacted (removed unreferenced objects)");
            } else {
                warnings.add("qpdf compact pass did not reduce size; kept original bytes");
            }
        }

        // 6. Rust/zopfli post-processing pass (optional opt-in).
        if (!signed && opts.useZopfliDeflate()) {
            if (opts.zopfliMaxInputBytes() <= 0
                    || resultBytes.length <= opts.zopfliMaxInputBytes()) {
                byte[] zopfliResult = zopfliCompressor.apply(
                        resultBytes, opts.zopfliIterations());
                if (zopfliResult != null && zopfliResult.length < resultBytes.length) {
                    if (valid(zopfliResult, expectedPages)) {
                        resultBytes = zopfliResult;
                        actions.add("Rust/zopfli: recompressed FlateDecode streams (%d iterations)"
                                .formatted(opts.zopfliIterations()));
                    } else {
                        warnings.add("Rust/zopfli: rejected (output failed validation)");
                    }
                } else if (zopfliResult != null) {
                    warnings.add("Rust/zopfli: skipped (output not smaller)");
                } else {
                    warnings.add("Rust/zopfli: skipped (Rust integration unavailable)");
                }
            } else {
                warnings.add("Rust/zopfli: skipped (input exceeds %d bytes)"
                        .formatted(opts.zopfliMaxInputBytes()));
            }
        }

        // 7. Monotonic guarantee: never return something larger than the current
        //    document. The current bytes (not the ones captured at open time) are
        //    serialized only when the result actually grew, so an in-memory
        //    watermark or annotation is never silently replaced by stale input.
        if (resultBytes.length > originalSize) {
            byte[] current = doc.saveBytes();
            if (resultBytes.length > current.length) {
                resultBytes = current;
                streamsOptimized = false;
                imagesOptimized = 0;
                metadataRemoved = 0;
                actions.clear();
                warnings.add("compressed output was larger than the input; "
                        + "returned the document unchanged");
            }
        }
        // Even the freshly serialized document can exceed the input size when
        // PDFium rewrites a small or already-tight file. Honour the documented
        // guarantee by falling back to the open-time snapshot when the document
        // has not been structurally edited; otherwise report the shortfall
        // instead of silently returning more bytes than the caller supplied.
        if (resultBytes.length > originalSize && sourceBytes != null) {
            if (doc.contentGeneration() == 0) {
                resultBytes = sourceBytes;
                streamsOptimized = false;
                imagesOptimized = 0;
                metadataRemoved = 0;
                actions.clear();
                warnings.add("compressed output was larger than the input; "
                        + "returned the document unchanged");
            } else {
                warnings.add("compressed output is larger than the input and the document "
                        + "was modified after opening; the size guarantee does not hold");
            }
        }

        CompressResult result = new CompressResult(
                originalSize, resultBytes.length,
                imagesOptimized, metadataRemoved, streamsOptimized,
                List.copyOf(actions), List.copyOf(warnings)
        );

        return new CompressResultWithBytes(result, resultBytes);
    }

    // Signature count using PDFium's raw document handle; 0 when unavailable.
    private static int signatureCount(PdfDocument doc) {
        try {
            MemorySegment raw = doc.rawHandle();
            if (raw == null || raw.equals(MemorySegment.NULL)) {
                return 0;
            }
            return PdfSignatures.count(raw);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return 0;
        }
    }

    // PDF/A conformance part: -1 none, 1 PDF/A-1, 2 PDF/A-2 or later. The raw
    // file is scanned first; when that misses (Flate-compressed XMP) only the
    // catalog's /Metadata stream is inflated, never the whole file, so a large
    // embedded image is not decompressed just to look for a claim.
    static int pdfaPart(byte[] pdf) {
        if (pdf == null) {
            return -1;
        }
        byte[] xmp = null;
        if (!containsAny(pdf, PDFA_MARKERS)) {
            xmp = catalogMetadata(pdf);
            if (xmp == null || !containsAny(xmp, PDFA_MARKERS)) {
                return -1;
            }
        }
        if (containsAny(pdf, PDFA_PART1_MARKERS)
                || (xmp != null && containsAny(xmp, PDFA_PART1_MARKERS))) {
            return 1;
        }
        return 2;
    }

    // Reads only the catalog's /Metadata stream: resolves the "/Metadata N 0 R"
    // reference, extracts that object's stream, and inflates it if Flate-encoded.
    // Returns null when it cannot be read (missing, encrypted, or filtered with
    // an unsupported codec), in which case the file is not treated as PDF/A.
    private static byte[] catalogMetadata(byte[] pdf) {
        // A page, image or font /Metadata entry can precede the catalog's, so scan
        // every /Metadata reference and return the first stream that carries a
        // PDF/A claim, falling back to the first decodable stream.
        byte[] firstDecoded = null;
        int at = indexOf(pdf, METADATA_MARKER);
        while (at >= 0) {
            byte[] decoded = decodeMetadata(pdf, at);
            if (decoded != null) {
                if (containsAny(decoded, PDFA_MARKERS)) {
                    return decoded;
                }
                if (firstDecoded == null) {
                    firstDecoded = decoded;
                }
            }
            at = indexOf(pdf, METADATA_MARKER, at + 1);
        }
        return firstDecoded;
    }

    private static byte[] decodeMetadata(byte[] pdf, int at) {
        int[] ref = indirectRef(pdf, at + METADATA_MARKER.length);
        if (ref == null) {
            return null;
        }
        ObjectStream s = objectStream(pdf, ref[0], ref[1]);
        if (s == null) {
            return null;
        }
        if (indexOf(s.dict(), FLATE_FILTER) >= 0) {
            return inflate(s.data());
        }
        // Any other declared filter (DCTDecode, etc.) is not decodable here.
        return indexOf(s.dict(), FILTER_KEY) >= 0 ? null : s.data();
    }

    private record ObjectStream(byte[] dict, byte[] data) {}

    // Parses "N G R" starting at or after p; returns {N, G} or null.
    private static int[] indirectRef(byte[] pdf, int p) {
        long[] a = readInt(pdf, p);
        if (a == null) {
            return null;
        }
        long[] b = readInt(pdf, (int) a[1]);
        if (b == null) {
            return null;
        }
        int q = skipWs(pdf, (int) b[1]);
        if (q < pdf.length && pdf[q] == 'R') {
            return new int[] {(int) a[0], (int) b[0]};
        }
        return null;
    }

    // Locates "num gen obj" and returns its dictionary and stream payload.
    private static ObjectStream objectStream(byte[] pdf, int num, int gen) {
        byte[] header = ("%d %d obj".formatted(num, gen)).getBytes(StandardCharsets.US_ASCII);
        int at = indexOf(pdf, header);
        while (at >= 0) {
            if (at == 0 || isWhitespace(pdf[at - 1])) {
                int dictStart = skipWs(pdf, at + header.length);
                if (dictStart + 1 < pdf.length
                        && pdf[dictStart] == '<' && pdf[dictStart + 1] == '<') {
                    int dictEnd = indexOf(pdf, DICT_END, dictStart + 2);
                    if (dictEnd >= 0) {
                        int sp = skipWs(pdf, dictEnd + 2);
                        if (startsWith(pdf, sp, STREAM_KEY)) {
                            int dataStart = sp + STREAM_KEY.length;
                            if (dataStart < pdf.length && pdf[dataStart] == '\r') {
                                dataStart++;
                            }
                            if (dataStart < pdf.length && pdf[dataStart] == '\n') {
                                dataStart++;
                            }
                            int end = indexOf(pdf, ENDSTREAM_KEY, dataStart);
                            if (end < 0) {
                                return null;
                            }
                            return new ObjectStream(
                                    Arrays.copyOfRange(pdf, dictStart, dictEnd + 2),
                                    Arrays.copyOfRange(pdf, dataStart, end));
                        }
                    }
                }
            }
            at = indexOf(pdf, header, at + 1);
        }
        return null;
    }

    // Reads a non-negative integer starting at or after p; returns {value, next}.
    private static long[] readInt(byte[] pdf, int p) {
        int q = skipWs(pdf, p);
        if (q >= pdf.length || pdf[q] < '0' || pdf[q] > '9') {
            return null;
        }
        long v = 0;
        while (q < pdf.length && pdf[q] >= '0' && pdf[q] <= '9') {
            v = v * 10 + (pdf[q] - '0');
            q++;
        }
        return new long[] {v, q};
    }

    private static int skipWs(byte[] pdf, int p) {
        while (p < pdf.length && isWhitespace(pdf[p])) {
            p++;
        }
        return p;
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == '\f' || b == 0;
    }

    private static boolean startsWith(byte[] haystack, int at, byte[] needle) {
        if (at < 0 || at + needle.length > haystack.length) {
            return false;
        }
        for (int i = 0; i < needle.length; i++) {
            if (haystack[at + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    // Inflates a FlateDecode stream; null on malformed data.
    private static byte[] inflate(byte[] data) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length));
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n > 0) {
                    if (out.size() + n > MAX_XMP_BYTES) {
                        return null;
                    }
                    out.write(buf, 0, n);
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break;
                }
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    // Signature-dictionary detection over the raw bytes (see SIG_MARKERS).
    private static boolean looksSignedByBytes(byte[] pdf) {
        return pdf != null && indexOf(pdf, BYTE_RANGE) >= 0 && containsAny(pdf, SIG_MARKERS);
    }

    private static boolean containsAny(byte[] haystack, byte[][] needles) {
        if (haystack == null) {
            return false;
        }
        for (byte[] needle : needles) {
            if (indexOf(haystack, needle) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        return indexOf(haystack, needle, 0);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        if (needle.length == 0 || haystack.length < needle.length || from < 0) {
            return -1;
        }
        int first = needle[0];
        int last = haystack.length - needle.length;
        outer:
        for (int i = from; i <= last; i++) {
            if (haystack[i] != first) {
                continue;
            }
            for (int j = 1; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // Renders two documents at a low DPI and returns the largest per-page mean
    // absolute per-channel difference. Used to verify (and roll back) the lossy
    // image pass. Returns positive infinity when there is nothing to compare or
    // rendering fails, so an unverifiable pass always fails safe (rolls back)
    // rather than silently passing every tolerance.
    private static double maxPreviewMeanAbsDiff(byte[] before, byte[] after, int expectedPages) {
        if (before == null || after == null) {
            return Double.POSITIVE_INFINITY;
        }
        try (PdfDocument db = PdfDocument.open(before);
             PdfDocument da = PdfDocument.open(after)) {
            int n = Math.min(Math.min(db.pageCount(), da.pageCount()), expectedPages);
            double max = 0.0;
            for (int i = 0; i < n; i++) {
                double d = meanAbsDiff(
                        db.renderImage(i, PREVIEW_DPI), da.renderImage(i, PREVIEW_DPI));
                if (d > max) {
                    max = d;
                }
            }
            return max;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return Double.POSITIVE_INFINITY;
        }
    }

    private static final int PREVIEW_DPI = 48;

    private static double meanAbsDiff(BufferedImage a, BufferedImage b) {
        if (a == null || b == null) {
            return Double.POSITIVE_INFINITY;
        }
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return Double.MAX_VALUE;
        }
        int w = a.getWidth();
        int h = a.getHeight();
        long sum = 0;
        long count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                sum += Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
                sum += Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF));
                sum += Math.abs((pa & 0xFF) - (pb & 0xFF));
                count += 3;
            }
        }
        return count == 0 ? 0.0 : (double) sum / count;
    }

    // Reopens the candidate and checks it still parses with the expected page count.
    private static boolean valid(byte[] candidate, int expectedPages) {
        try (PdfDocument check = PdfDocument.open(candidate)) {
            return check.pageCount() == expectedPages;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        }
    }

    /**
     * Convenience: compress for web delivery (moderate image quality).
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
     * Convenience: maximum compression (aggressive image optimization + qpdf).
     */
    public static byte[] maximum(PdfDocument doc) {
        return compress(doc, CompressOptions.builder()
                .preset(CompressPreset.MAXIMUM)
                .build()).bytes();
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
