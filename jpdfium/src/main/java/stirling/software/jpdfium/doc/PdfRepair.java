package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.QpdfLib;
import stirling.software.jpdfium.panama.RepairLib;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF repair with a builder-pattern API.
 *
 * <p>
 * Multi-stage cascade pipeline:
 * <ol>
 * <li><b>Pre-repair</b>: Brotli-Flate transcoding (PDF 2.0+ compat)</li>
 * <li><b>Core</b>: PDFium tolerant open - qpdf recovery - startxref fix</li>
 * <li><b>Fallback</b>: PDFio third-opinion XRef repair</li>
 * <li><b>Rust fallback</b> (opt-in): lopdf tolerant XRef rebuild - final cascade
 * stage when all C-based repair strategies fail. Enabled via
 * {@link Builder#useLopdfFallback(boolean)} or included in {@link Builder#all()}.</li>
 * <li><b>Post-repair</b>: ICC profile validation (lcms2), JPEG2000 validation
 * (OpenJPEG)</li>
 * </ol>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * RepairResult result = PdfRepair.builder()
 *         .input(pdfBytes)
 *         .all()
 *         .build()
 *         .execute();
 *
 * if (result.isUsable()) {
 *     byte[] fixed = result.repairedPdf();
 * }
 *
 * // Inspect only (non-destructive)
 * String diagnostics = PdfRepair.inspect(pdfBytes);
 * }</pre>
 *
 * <p>
 * All underlying libraries are MIT-compatible:
 * qpdf (Apache 2.0), PDFium (BSD), Brotli (MIT), PDFio (Apache 2.0),
 * lcms2 (MIT), OpenJPEG (BSD 2-Clause), lopdf (MIT, Rust).
 */
public final class PdfRepair {

    // Core repair flags - bit values must match the JPDFIUM_REPAIR_* defines in
    // native/bridge/include/jpdfium.h. The previous permutation silently mapped
    // forceVersion14() to xref normalization and normalizeXref() to startxref
    // correction; it went unnoticed because Builder.all() sets every bit.
    private static final int FLAG_NORMALIZE_XREF = 0x0001;
    private static final int FLAG_FIX_STARTXREF = 0x0002;
    private static final int FLAG_FORCE_V14 = 0x0004;

    private final byte[] inputBytes;
    private final int flags;
    private final boolean usePdfioFallback;
    private final boolean useLopdfFallback;
    private final boolean transcodeBrotli;
    private final boolean writeDiagnostics;
    private final boolean sanitize;

    private final boolean validateIcc;
    private final boolean validateJpx;

    private PdfRepair(byte[] inputBytes, int flags,
            boolean usePdfioFallback, boolean useLopdfFallback,
            boolean transcodeBrotli,
            boolean validateIcc, boolean validateJpx,
            boolean writeDiagnostics, boolean sanitize) {
        this.inputBytes = inputBytes;
        this.flags = flags;
        this.usePdfioFallback = usePdfioFallback;
        this.useLopdfFallback = useLopdfFallback;
        this.transcodeBrotli = transcodeBrotli;
        this.validateIcc = validateIcc;
        this.validateJpx = validateJpx;
        this.writeDiagnostics = writeDiagnostics;
        this.sanitize = sanitize;
    }

    public boolean validateIcc() { return validateIcc; }
    public boolean validateJpx() { return validateJpx; }

    /** True when the core qpdf-backed repair stage is available. */
    public static boolean isSupported() {
        return PdfOptimizer.isSupported();
    }

    /** Create a new repair builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Inspect a PDF for damage without modifying it. */
    public static String inspect(byte[] pdfBytes) {
        return RepairLib.inspect(pdfBytes);
    }

    /**
     * Inspects a PDF file for damage without modifying it; prefer {@link #repairFile(Path, Path, int)}
     * for large files.
     */
    public static String inspect(Path path) throws IOException {
        return inspect(Files.readAllBytes(path));
    }

    /**
     * Repairs a PDF straight to another file without materializing it, falling back to the
     * {@code byte[]} route on natives without the file entry point. Core stage only.
     *
     * <p>
     * Mirrors {@link #execute()}: a clean signed input is published unchanged (rewriting it
     * would break its {@code /ByteRange} digest) and a damaged signed input fails instead of
     * producing output whose signature silently no longer verifies. The marker scan streams
     * the file, so the input is never held on the heap.
     */
    public static RepairResult.Status repairFile(Path input, Path output, int flags)
            throws IOException {
        // Only a structural /ByteRange array counts as a signature marker.
        // Plain "/ByteRange" text in metadata/content must not suppress repair.
        boolean marker = PdfDocument.containsByteRangeArray(input);
        if (marker) {
            try (PdfDocument doc = PdfDocument.open(input)) {
                if (doc.pageCount() > 0) {
                    publishPassThrough(input, output);
                    return RepairResult.Status.CLEAN;
                }
            } catch (Exception _) {
                // Unopenable and carrying a /ByteRange array: fail closed.
                return RepairResult.Status.FAILED;
            }
            return RepairResult.Status.FAILED;
        }
        return RepairLib.repairToFile(input, output, flags);
    }

    /**
     * Copies a clean pass-through input over the destination with the same staged
     * publication the native route uses, so a partial copy never truncates an
     * existing output.
     */
    private static void publishPassThrough(Path input, Path output) throws IOException {
        Path tmp = Files.createTempFile(output.toAbsolutePath().getParent(), ".jpdfium-repair-",
                ".tmp");
        try {
            Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING);
            // createTempFile is 0600; when replacing an existing file keep its
            // mode so a pass-through repair does not drop permissions.
            if (Files.exists(output)
                    && Files.getFileStore(tmp)
                            .supportsFileAttributeView(PosixFileAttributeView.class)) {
                try {
                    Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(output));
                } catch (UnsupportedOperationException _) {
                    // Destination is not on a POSIX filesystem; keep the temp mode.
                }
            }
            try {
                Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * QDF repair with PDFium validation and the native repair cascade on
     * failure.
     *
     * @return the first usable result, or {@link RepairResult.Status#FAILED}
     */
    public static RepairResult repairQdf(byte[] input) {
        if (input == null || input.length == 0) {
            return new RepairResult(RepairResult.Status.FAILED, null,
                    "{\"error\":\"empty input\"}");
        }
        RepairResult signed = signedDocumentResult(input, true);
        if (signed != null) return signed;
        RepairResult locked = passwordBlockedResult(input, true);
        if (locked != null) {
            byte[] decrypted = decryptWithEmptyPassword(input);
            if (decrypted == null) return locked;
            input = decrypted;
        }

        byte[] prepared = preflightPageTree(normalizeRepairTokens(input));
        byte[] qdf = null;
        try {
            qdf = PdfOptimizer.repair(prepared);
        } catch (RuntimeException _) {
            // QDF decompression can exceed byte[] capacity on very large valid
            // files; the normal cascade can still validate them.
        }
        if (qdf != null) {
            try (PdfDocument doc = PdfDocument.open(qdf)) {
                if (doc.pageCount() > 0) {
                    return new RepairResult(RepairResult.Status.FIXED, compact(qdf),
                            inspect(input));
                }
            } catch (Exception _) {
                // QDF output is not openable by PDFium; let the cascade try.
            }
        }
        return builder().input(prepared).all().build().execute();
    }

    /**
     * Execute the full repair pipeline with configured options.
     *
     * @return result containing the status, repaired bytes, and diagnostics
     */
    public RepairResult execute() {
        byte[] input = inputBytes;
        RepairResult signed = signedDocumentResult(input, writeDiagnostics);
        if (signed != null) {
            if (sanitize && signed.isUsable() && signed.status() == RepairResult.Status.CLEAN) {
                // A signed document must pass through byte-for-byte to keep its
                // digest valid, so requested sanitization cannot be performed.
                // Report the conflict instead of returning unsanitized content
                // with a successful status.
                return new RepairResult(RepairResult.Status.FAILED, null,
                        writeDiagnostics
                                ? "{\"error\":\"sanitization cannot be applied to a signed document without invalidating its signature\"}"
                                : null);
            }
            return signed;
        }
        RepairResult locked = passwordBlockedResult(input, writeDiagnostics);
        if (locked != null) {
            byte[] decrypted = decryptWithEmptyPassword(input);
            if (decrypted == null) return locked;
            input = decrypted;
        }
        byte[] current = preflightPageTree(normalizeRepairTokens(input));
        boolean tokenRepaired = !Arrays.equals(current, input);

        // Pre-repair: Brotli-Flate transcoding
        if (transcodeBrotli) {
            byte[] transcoded = RepairLib.brotliToFlate(current);
            if (transcoded != null) {
                current = transcoded;
            }
        }

        RepairResult coreResult = RepairLib.repair(current, flags);
        if (tokenRepaired && coreResult.isUsable()
                && coreResult.status() == RepairResult.Status.CLEAN) {
            coreResult = new RepairResult(RepairResult.Status.FIXED, coreResult.repairedPdf(),
                    coreResult.diagnosticJson());
        }

        if (usePdfioFallback && !coreResult.isUsable()) {
            RepairResult pdfioResult = RepairLib.pdfioRepair(current);
            if (pdfioResult.isUsable()) {
                coreResult = pdfioResult;
            }
        }

        // Final fallback: Rust/lopdf tolerant XRef rebuild.
        // Only attempted when all C-based strategies have failed and the option is enabled.
        if (useLopdfFallback && !coreResult.isUsable()) {
            RepairResult rustResult = RepairLib.rustRepair(current);
            if (rustResult.isUsable()) {
                coreResult = rustResult;
            }
        }

        // Some producers keep valid page dictionaries but lose the /Pages tree.
        // Rebuild a tree, repoint each page's /Parent, and let the cascade reopen it.
        if (!coreResult.isUsable() || !hasPages(coreResult.repairedPdf())) {
            byte[] pageTree = rebuildPageTree(current);
            if (pageTree != null) {
                RepairResult salvagedResult = RepairLib.repair(pageTree, flags);
                if (!salvagedResult.isUsable() && usePdfioFallback) {
                    salvagedResult = RepairLib.pdfioRepair(pageTree);
                }
                if (!salvagedResult.isUsable() && useLopdfFallback) {
                    salvagedResult = RepairLib.rustRepair(pageTree);
                }
                if (salvagedResult.isUsable() && hasPages(salvagedResult.repairedPdf())) {
                    coreResult = salvagedResult;
                }
            }
        }

        // Truncated PDF salvage: trim partial trailing object after last 'endobj' and append %%EOF
        if (!coreResult.isUsable()) {
            byte[] salvaged = salvageTruncatedPdf(current);
            if (salvaged != null) {
                RepairResult salvagedResult = RepairLib.repair(salvaged, flags);
                if (!salvagedResult.isUsable() && useLopdfFallback) {
                    salvagedResult = RepairLib.rustRepair(salvaged);
                }
                if (salvagedResult.isUsable() && hasPages(salvagedResult.repairedPdf())) {
                    coreResult = salvagedResult;
                }
            }
        }

        if (sanitize && coreResult.isUsable()) {
            byte[] repairedBytes = coreResult.repairedPdf();
            if (repairedBytes != null) {
                // PDFium-level hardening (fonts, comments, hidden text,
                // metadata) is best effort: a salvage repair can open with
                // PDFium yet fail to enumerate individual pages. The
                // JavaScript and embedded-file guarantees below use qpdf on
                // the serialized bytes, so they do not depend on page access.
                try (PdfDocument doc = PdfDocument.open(repairedBytes)) {
                    try {
                        PdfSecurity.sanitize(doc);
                    } catch (Exception _) {
                        // Keep the core repair; the structural pass below still
                        // removes executable content.
                    }
                    byte[] saved = doc.saveBytes();
                    if (saved != null && saved.length > 0) repairedBytes = saved;
                } catch (Exception _) {
                    // Keep the core repair bytes.
                }
                byte[] withoutJavaScript;
                try {
                    withoutJavaScript = PdfSanitizer.sanitize(
                            repairedBytes, PdfSanitizer.JAVASCRIPT | PdfSanitizer.ATTACHMENTS);
                } catch (Exception e) {
                    return sanitizationFailure(e, writeDiagnostics);
                }
                if (withoutJavaScript == null || withoutJavaScript.length == 0) {
                    return sanitizationFailure(
                            new IllegalStateException("qpdf sanitization unavailable"),
                            writeDiagnostics);
                }
                coreResult = new RepairResult(coreResult.status(), withoutJavaScript,
                        coreResult.diagnosticJson());
            }
        }

        // A repair that cannot produce a page is not a successful repair.
        if (coreResult.status() == RepairResult.Status.FIXED && coreResult.isUsable()
                && !hasPages(coreResult.repairedPdf())) {
            coreResult = new RepairResult(RepairResult.Status.FAILED, null,
                    writeDiagnostics
                            ? "{\"error\":\"repair produced no pages\"}"
                            : null);
        }

        if (coreResult.isUsable() && coreResult.status() != RepairResult.Status.CLEAN
                && (flags & (FLAG_NORMALIZE_XREF | FLAG_FORCE_V14)) == 0) {
            coreResult = new RepairResult(coreResult.status(),
                    compact(coreResult.repairedPdf()), coreResult.diagnosticJson());
        }

        if (!writeDiagnostics) {
            return new RepairResult(coreResult.status(), coreResult.repairedPdf(), null);
        }

        return coreResult;
    }

    // Repair works in QDF (uncompressed) form. Finish with a qpdf structural
    // compression pass so repaired files are practical to hand back to users.
    // Keep the QDF bytes if compression fails or loses the page tree.
    private static byte[] compact(byte[] repaired) {
        if (repaired == null || !PdfOptimizer.isSupported()) return repaired;
        try {
            byte[] compressed = PdfOptimizer.compress(repaired, PdfOptimizer.DEFAULT, false,
                    false, false, false, -1);
            if (compressed != null && compressed.length < repaired.length
                    && hasPages(compressed)) {
                return compressed;
            }
        } catch (Exception _) {
            // Retain the verified QDF output.
        }
        return repaired;
    }

    // Repair cannot verify an encrypted document that needs a user password.
    // Owner-password-only files still open with the empty user password and
    // continue through the normal cascade.
    private static RepairResult passwordBlockedResult(byte[] pdf, boolean diagnostics) {
        if (pdf == null || !contains(pdf, "/Encrypt".getBytes(StandardCharsets.ISO_8859_1))) {
            return null;
        }
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            if (doc.pageCount() > 0) return null;
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("password")) {
                return new RepairResult(RepairResult.Status.FAILED, null,
                        diagnostics ? "{\"error\":\"password-protected PDF cannot be repaired without the password\"}"
                                : null);
            }
        }
        return null;
    }

    // Some producers write a malformed /Encrypt dictionary that PDFium cannot
    // open but qpdf still decrypts with the empty user password. Prefer a
    // working, decrypted document over failing the repair outright.
    private static byte[] decryptWithEmptyPassword(byte[] pdf) {
        if (pdf == null || !contains(pdf, "/Encrypt".getBytes(StandardCharsets.ISO_8859_1))) {
            return null;
        }
        try {
            byte[] decrypted = QpdfLib.decrypt(pdf, "");
            if (decrypted != null && decrypted.length > 0
                    && !contains(decrypted, "/Encrypt".getBytes(StandardCharsets.ISO_8859_1))) {
                return decrypted;
            }
        } catch (Exception _) {
            return null;
        }
        return null;
    }

    private static boolean hasPages(byte[] pdf) {
        if (pdf == null) return false;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            return doc.pageCount() > 0;
        } catch (Exception _) {
            return false;
        }
    }

    // qpdf's writer aborts the JVM on some page trees with a broken header and a
    // null /Count. Rebuild the tree from page dictionaries before invoking the
    // native cascade so such files fail over to salvage instead of terminating.
    private static byte[] preflightPageTree(byte[] pdf) {
        if (pdf == null || hasPdfHeader(pdf)) return pdf;
        byte[] tree = rebuildPageTree(pdf);
        return tree != null ? tree : pdf;
    }

    private static boolean hasPdfHeader(byte[] pdf) {
        int limit = Math.min(pdf.length - 5, 1024);
        for (int i = 0; i <= limit; i++) {
            if (pdf[i] == '%' && pdf[i + 1] == 'P' && pdf[i + 2] == 'D'
                    && pdf[i + 3] == 'F' && pdf[i + 4] == '-') return true;
        }
        return false;
    }

    // Positions inside literal strings, hex strings, comments, or streams must
    // never be treated as object syntax by the token normalizers below.
    private static boolean[] protectedPositions(byte[] pdf) {
        boolean[] guarded = new boolean[pdf == null ? 0 : pdf.length];
        if (pdf == null) return guarded;
        int depth = 0;
        boolean escape = false;
        boolean comment = false;
        boolean hex = false;
        boolean stream = false;
        // Streams whose declared /Length leads to an exact "endstream" landing
        // end at that position: the data may legitimately contain the bytes
        // "endstream" (embedded PDFs, image streams), and an early exit would
        // leave the rest of the data unguarded so the normalizers below could
        // edit content-stream bytes. Without a resolvable /Length the scan
        // falls back to the first "endstream" at a token boundary.
        int endstreamAt = -1;
        for (int i = 0; i < pdf.length; i++) {
            byte b = pdf[i];
            if (stream) {
                guarded[i] = true;
                if (endstreamAt >= 0) {
                    if (i == endstreamAt) {
                        // Consume the closing keyword explicitly so its "stream"
                        // suffix cannot re-enter the stream state.
                        for (int k = i + 1; k < i + 9; k++) guarded[k] = true;
                        i += 8;
                        stream = false;
                        endstreamAt = -1;
                    }
                    continue;
                }
                if (matchesAscii(pdf, i, "endstream") && isBareKeywordStart(pdf, i)) {
                    for (int k = i + 1; k < i + 9; k++) guarded[k] = true;
                    i += 8;
                    stream = false;
                }
                continue;
            }
            if (comment) {
                guarded[i] = true;
                if (b == '\n' || b == '\r') comment = false;
                continue;
            }
            if (hex) {
                guarded[i] = true;
                if (b == '>') hex = false;
                continue;
            }
            if (depth > 0) {
                guarded[i] = true;
                if (escape) {
                    escape = false;
                } else if (b == '\\') {
                    escape = true;
                } else if (b == '(') {
                    depth++;
                } else if (b == ')') {
                    depth--;
                }
                continue;
            }
            if (b == '%') {
                guarded[i] = true;
                comment = true;
            } else if (b == '(') {
                guarded[i] = true;
                depth = 1;
            } else if (b == '<' && (i + 1 >= pdf.length || pdf[i + 1] != '<')
                    && (i == 0 || pdf[i - 1] != '<')) {
                guarded[i] = true;
                hex = true;
            } else if (matchesAscii(pdf, i, "stream") && isBareKeywordStart(pdf, i)
                    && (i + 6 >= pdf.length || pdf[i + 6] == '\n' || pdf[i + 6] == '\r')) {
                guarded[i] = true;
                stream = true;
                endstreamAt = resolveStreamEnd(pdf, i);
            }
        }
        return guarded;
    }

    /**
     * Resolves where the "endstream" keyword that closes the stream whose
     * "stream" token starts at {@code streamTokenStart} must sit, using the
     * dictionary's declared {@code /Length}. Returns {@code -1} when no exact
     * landing can be established (missing, indirect, or damaged /Length, a
     * length that overshoots the file, or "endstream" not precisely where the
     * length points); the caller then falls back to scanning for an anchored
     * "endstream". Self-validating: the resolved position is only returned
     * when the keyword really is there.
     */
    private static int resolveStreamEnd(byte[] pdf, int streamTokenStart) {
        int windowStart = Math.max(0, streamTokenStart - 1024);
        for (int p = streamTokenStart - 1; p >= windowStart; p--) {
            if (!matchesAscii(pdf, p, "/Length")) continue;
            int q = p + 7;
            while (q < pdf.length && isPdfSpace(pdf[q])) q++;
            int numStart = q;
            long len = 0;
            while (q < pdf.length && pdf[q] >= '0' && pdf[q] <= '9') {
                len = len * 10 + (pdf[q] - '0');
                if (len > pdf.length) return -1;
                q++;
            }
            if (q == numStart) return -1;  // indirect reference or missing value
            int j = streamTokenStart + 6;
            if (j + 1 < pdf.length && pdf[j] == '\r' && pdf[j + 1] == '\n') j += 2;
            else if (j < pdf.length && (pdf[j] == '\n' || pdf[j] == '\r')) j += 1;
            if (j + len >= pdf.length) return -1;
            int k = (int) (j + len);
            if (k + 1 < pdf.length && pdf[k] == '\r' && pdf[k + 1] == '\n') k += 2;
            else if (k < pdf.length && (pdf[k] == '\n' || pdf[k] == '\r')) k += 1;
            if (k + 9 > pdf.length) return -1;
            return matchesAscii(pdf, k, "endstream") ? k : -1;
        }
        return -1;
    }

    // A keyword occurrence is only a PDF token when preceded by whitespace or
    // a delimiter; otherwise it is part of a longer word (e.g. the "stream"
    // inside "endstream" must not re-enter the stream state).
    private static boolean isTokenStart(byte[] pdf, int i) {
        if (i == 0) return true;
        byte prev = pdf[i - 1];
        return isPdfSpace(prev) || prev == '(' || prev == ')' || prev == '<' || prev == '>'
                || prev == '[' || prev == ']' || prev == '{' || prev == '}' || prev == '/'
                || prev == '%';
    }

    // A PDF name such as /stream is data, not the stream keyword. isTokenStart
    // intentionally accepts '/' for names in general; keyword scanning must not.
    private static boolean isBareKeywordStart(byte[] pdf, int i) {
        return isTokenStart(pdf, i) && (i == 0 || pdf[i - 1] != '/');
    }

    private static byte[] normalizeRepairTokens(byte[] pdf) {
        return deduplicateResourceKeys(normalizeTruncatedObj(normalizeGluedEndobj(pdf)));
    }

    private static final Pattern STREAM_BODY = Pattern.compile(
            "(?<![A-Za-z0-9/#])stream(?:\r\n|\r|\n).*?endstream", Pattern.DOTALL);
    private static final Pattern RESOURCE_REF = Pattern.compile("(/Im\\d+)[\\t ]+\\d+[\\t ]+0[\\t ]+R");

    // Some producers repeat resource keys hundreds of times (e.g. /Im13 ... /Im13).
    // qpdf's default error budget then nulls the page object. PDF semantics keep the
    // last duplicate, so drop earlier occurrences outside streams.
    private static byte[] deduplicateResourceKeys(byte[] pdf) {
        if (pdf == null || pdf.length == 0) return pdf;
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        boolean[] guarded = protectedPositions(pdf);
        Matcher stream = STREAM_BODY.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int pos = 0;
        boolean changed = false;
        while (stream.find()) {
            String cleaned = deduplicateChunk(text.substring(pos, stream.start()), guarded, pos);
            out.append(cleaned).append(text, stream.start(), stream.end());
            changed |= cleaned.length() != stream.end() - stream.start();
            pos = stream.end();
        }
        String tail = deduplicateChunk(text.substring(pos), guarded, pos);
        out.append(tail);
        return out.length() == text.length() && !changed
                ? pdf
                : out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String deduplicateChunk(String chunk, boolean[] guarded, int base) {
        Matcher m = RESOURCE_REF.matcher(chunk);
        java.util.List<Integer> starts = new java.util.ArrayList<>();
        java.util.List<Integer> ends = new java.util.ArrayList<>();
        java.util.List<Integer> groups = new java.util.ArrayList<>();
        java.util.List<String> keys = new java.util.ArrayList<>();
        int[] stack = new int[64];
        int depth = 0;
        int scan = 0;
        while (m.find()) {
            while (scan < m.start()) {
                if (base + scan < guarded.length && guarded[base + scan]) {
                    scan++;
                    continue;
                }
                if (scan + 1 < chunk.length() && chunk.charAt(scan) == '<'
                        && chunk.charAt(scan + 1) == '<') {
                    if (depth < stack.length) stack[depth] = scan;
                    depth++;
                    scan += 2;
                } else if (scan + 1 < chunk.length() && chunk.charAt(scan) == '>'
                        && chunk.charAt(scan + 1) == '>') {
                    if (depth > 0) depth--;
                    scan += 2;
                } else {
                    scan++;
                }
            }
            if (base + m.start() < guarded.length && guarded[base + m.start()]) continue;
            starts.add(m.start());
            ends.add(m.end());
            // Hostile input can nest deeper than the tracking window; report the
            // innermost tracked level instead of throwing.
            groups.add(depth > 0 ? stack[Math.min(depth - 1, stack.length - 1)] : -1);
            keys.add(m.group(1));
        }
        Map<Integer, Map<String, Integer>> lastByGroup = new LinkedHashMap<>();
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < starts.size(); i++) {
            int group = groups.get(i);
            lastByGroup.computeIfAbsent(group, _ -> new LinkedHashMap<>())
                    .put(keys.get(i), starts.get(i));
            counts.merge(group, 1, Integer::sum);
        }
        java.util.Set<Integer> dedupGroups = new java.util.HashSet<>();
        for (var entry : counts.entrySet()) {
            int count = entry.getValue();
            int unique = lastByGroup.get(entry.getKey()).size();
            if (count >= 20 && unique <= count - 10) dedupGroups.add(entry.getKey());
        }
        if (dedupGroups.isEmpty()) return chunk;
        StringBuilder out = new StringBuilder(chunk.length());
        int pos = 0;
        for (int i = 0; i < starts.size(); i++) {
            int group = groups.get(i);
            if (!dedupGroups.contains(group)) continue;
            int start = starts.get(i);
            String key = keys.get(i);
            out.append(chunk, pos, start);
            if (lastByGroup.get(group).get(key) == start) {
                out.append(chunk, start, ends.get(i));
            }
            pos = ends.get(i);
        }
        out.append(chunk, pos, chunk.length());
        return out.toString();
    }

    // PDFBox tolerates object headers whose "obj" was truncated to "o"
    // (PDFBOX-773). Restore the token when it is clearly an object header:
    // preceding "N G" and followed by a dictionary, array, name, or scalar.
    private static byte[] normalizeTruncatedObj(byte[] pdf) {
        if (pdf == null || pdf.length < 4) return pdf;
        ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 8);
        boolean changed = false;
        // protectedPositions() is the single stream/lexical guard: bytes it
        // marks (streams, strings, comments, hex strings) must never be
        // rewritten as object syntax.
        boolean[] guarded = protectedPositions(pdf);
        for (int i = 0; i < pdf.length; i++) {
            if (!guarded[i] && pdf[i] == 'o'
                    && (i == 0 || !isPdfNameByte(pdf[i - 1]))) {
                int p = i - 1;
                while (p >= 0 && isPdfSpace(pdf[p])) p--;
                int genEnd = p;
                while (p >= 0 && pdf[p] >= '0' && pdf[p] <= '9') p--;
                boolean hasGen = p < genEnd;
                while (p >= 0 && isPdfSpace(pdf[p])) p--;
                int numEnd = p;
                while (p >= 0 && pdf[p] >= '0' && pdf[p] <= '9') p--;
                boolean hasNum = p < numEnd;
                boolean boundary = p < 0 || isPdfSpace(pdf[p]) || pdf[p] == '>';
                int q = i + 1;
                while (q < pdf.length && isPdfSpace(pdf[q])) q++;
                boolean bodyStart = q < pdf.length && (pdf[q] == '<' || pdf[q] == '[' || pdf[q] == '/'
                        || pdf[q] == '(' || pdf[q] == '+' || pdf[q] == '-'
                        || (pdf[q] >= '0' && pdf[q] <= '9')
                        || (pdf[q] >= 'A' && pdf[q] <= 'Z'));
                boolean alreadyObj = i + 3 <= pdf.length && pdf[i + 1] == 'b' && pdf[i + 2] == 'j';
                if (hasNum && hasGen && boundary && bodyStart && !alreadyObj) {
                    out.write('o');
                    out.write('b');
                    out.write('j');
                    changed = true;
                    continue;
                }
            }
            out.write(pdf[i]);
        }
        return changed ? out.toByteArray() : pdf;
    }

    private static boolean matchesAscii(byte[] data, int offset, String token) {
        if (offset < 0 || offset + token.length() > data.length) return false;
        for (int i = 0; i < token.length(); i++) {
            if (data[offset + i] != (byte) token.charAt(i)) return false;
        }
        return true;
    }

    private static boolean isPdfSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == '\f' || b == 0;
    }

    private static boolean isPdfNameByte(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                || (b >= '0' && b <= '9') || b == '_';
    }

    // Some producers emit "endobjN 0 obj" without a separator. qpdf recovers
    // the objects but can attach the next object to the previous one, losing a
    // page. Split the token before parsing; only touch occurrences that look
    // like structure (preceded by '>' or whitespace) and are followed by a
    // complete "N G obj" header.
    private static byte[] normalizeGluedEndobj(byte[] pdf) {
        if (pdf == null || pdf.length < 6) return pdf;
        ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 16);
        boolean changed = false;
        // protectedPositions() is the single stream/lexical guard: bytes it
        // marks (streams, strings, comments, hex strings) must never be
        // rewritten as object syntax.
        boolean[] guarded = protectedPositions(pdf);
        for (int i = 0; i < pdf.length; i++) {
            boolean marker = !guarded[i] && matchesAscii(pdf, i, "endobj");
            boolean boundary = i == 0 || pdf[i - 1] == '>' || pdf[i - 1] == '\n'
                    || pdf[i - 1] == '\r' || pdf[i - 1] == ' ' || pdf[i - 1] == '\t';
            if (marker && boundary && i + 7 < pdf.length && pdf[i + 6] >= '0' && pdf[i + 6] <= '9') {
                int j = i + 6;
                while (j < pdf.length && pdf[j] >= '0' && pdf[j] <= '9') j++;
                int k = j;
                while (k < pdf.length && (pdf[k] == ' ' || pdf[k] == '\t')) k++;
                int gen = k;
                while (k < pdf.length && pdf[k] >= '0' && pdf[k] <= '9') k++;
                while (k < pdf.length && (pdf[k] == ' ' || pdf[k] == '\t')) k++;
                if (gen > j && k + 3 <= pdf.length && pdf[k] == 'o' && pdf[k + 1] == 'b'
                        && pdf[k + 2] == 'j') {
                    out.write(pdf, i, 6);
                    out.write('\n');
                    i += 5;
                    changed = true;
                    continue;
                }
            }
            out.write(pdf[i]);
        }
        return changed ? out.toByteArray() : pdf;
    }

    // Rewriting a signed document always breaks its /ByteRange digest. A clean
    // signed file is returned untouched; a damaged one fails rather than producing
    // output whose signature silently no longer verifies.
    private static RepairResult signedDocumentResult(byte[] pdf, boolean diagnostics) {
        if (pdf == null) return null;
        boolean marker = PdfDocument.containsByteRangeArray(pdf, pdf.length);
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            // A structural /ByteRange array marks a signed document; bare
            // "/ByteRange" text does not.
            if (doc.pageCount() > 0 && marker) {
                return new RepairResult(RepairResult.Status.CLEAN, pdf,
                        diagnostics ? inspect(pdf) : null);
            }
            return null;
        } catch (Exception _) {
            if (!marker) return null;
        }
        return new RepairResult(RepairResult.Status.FAILED, null,
                diagnostics ? "{\"error\":\"signed document cannot be repaired without invalidating its signature\"}"
                        : null);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static RepairResult sanitizationFailure(Exception e, boolean diagnostics) {
        return new RepairResult(RepairResult.Status.FAILED, null,
                diagnostics
                        ? "{\"error\":\"post-repair sanitization failed: "
                                + escapeJson(String.valueOf(e.getMessage())) + "\"}"
                        : null);
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Builder for configuring PDF repair pipeline options.
     */
    public static final class Builder {
        private byte[] inputBytes;
        private boolean doForceVersion14;
        private boolean doNormalizeXref;
        private boolean doFixStartxref;
        private boolean doUsePdfioFallback;
        private boolean doUseLopdfFallback;
        private boolean doTranscodeBrotli;
        private boolean doValidateIcc;
        private boolean doValidateJpx;
        private boolean doWriteDiagnostics = true;
        private boolean doSanitize;

        private Builder() {
        }

        /** Set the input PDF bytes. */
        public Builder input(byte[] pdfBytes) {
            this.inputBytes = pdfBytes;
            return this;
        }

        /**
         * Set the input PDF file; read into the heap, or use {@link PdfRepair#repairFile} for a
         * flat-heap file-to-file repair.
         */
        public Builder input(Path path) throws IOException {
            this.inputBytes = Files.readAllBytes(path);
            return this;
        }

        /** Force output PDF version to 1.4. */
        public Builder forceVersion14(boolean enable) {
            this.doForceVersion14 = enable;
            return this;
        }

        /** Normalize xref format (force classic xref table). */
        public Builder normalizeXref(boolean enable) {
            this.doNormalizeXref = enable;
            return this;
        }

        /** Enable startxref offset brute-force correction. */
        public Builder fixStartxref(boolean enable) {
            this.doFixStartxref = enable;
            return this;
        }

        /** Enable PDFio third-opinion fallback (opt-in, requires libpdfio). */
        public Builder usePdfioFallback(boolean enable) {
            this.doUsePdfioFallback = enable;
            return this;
        }

        /**
         * Enable lopdf (Rust) XRef rebuild as a final fallback (opt-in).
         *
         * <p>Tried only after both the core qpdf pipeline and the PDFio fallback have
         * failed. lopdf's tolerant parser can often recover PDFs with corrupted XRef
         * tables that qpdf and PDFio cannot parse at all.
         *
         * <p>Included automatically by {@link #all()}.  If the Rust library is not
         * compiled in, this option is silently ignored (no error is thrown).
         *
         * @param enable {@code true} to enable (default {@code false})
         */
        public Builder useLopdfFallback(boolean enable) {
            this.doUseLopdfFallback = enable;
            return this;
        }

        /** Enable Brotli-Flate pre-repair transcoding (opt-in, requires libbrotli). */
        public Builder transcodeBrotli(boolean enable) {
            this.doTranscodeBrotli = enable;
            return this;
        }

        /** Enable ICC profile validation post-pass (opt-in, requires liblcms2). */
        public Builder validateIcc(boolean enable) {
            this.doValidateIcc = enable;
            return this;
        }

        /**
         * Enable JPEG2000 stream validation post-pass (opt-in, requires libopenjp2).
         */
        public Builder validateJpx(boolean enable) {
            this.doValidateJpx = enable;
            return this;
        }

        /** Include diagnostic JSON in the result (default: true). Set to false to skip diagnostics. */
        public Builder writeDiagnostics(boolean enable) {
            this.doWriteDiagnostics = enable;
            return this;
        }

        /**
         * Enable post-repair security sanitization: removes JavaScript,
         * embedded files, and action annotations from the repaired PDF.
         */
        public Builder sanitize(boolean enable) {
            this.doSanitize = enable;
            return this;
        }

        /** Enable all core + Phase 2 repair strategies (including Rust lopdf + sanitize). */
        public Builder all() {
            this.doForceVersion14 = true;
            this.doNormalizeXref = true;
            this.doFixStartxref = true;
            this.doUsePdfioFallback = true;
            this.doUseLopdfFallback = true;
            this.doTranscodeBrotli = true;
            this.doValidateIcc = true;
            this.doValidateJpx = true;
            this.doSanitize = true;
            return this;
        }

        /**
         * Build the repair instance. Call {@link PdfRepair#execute()} to run.
         *
         * @throws IllegalStateException if no input was provided
         */
        public PdfRepair build() {
            if (inputBytes == null || inputBytes.length == 0) {
                throw new IllegalStateException("Input PDF bytes must be provided via .input()");
            }

            int flags = 0;
            if (doForceVersion14)
                flags |= FLAG_FORCE_V14;
            if (doNormalizeXref)
                flags |= FLAG_NORMALIZE_XREF;
            if (doFixStartxref)
                flags |= FLAG_FIX_STARTXREF;

            return new PdfRepair(inputBytes, flags,
                    doUsePdfioFallback, doUseLopdfFallback, doTranscodeBrotli,
                    doValidateIcc, doValidateJpx, doWriteDiagnostics, doSanitize);
        }
    }

    private static String balancedDictionary(String chunk, int pageStart) {
        int start = enclosingDictionaryStart(chunk, pageStart);
        if (start < 0) return null;
        int end = dictionaryEnd(chunk, start);
        return end < 0 ? null : chunk.substring(start, end);
    }

    private static int enclosingDictionaryStart(String s, int limit) {
        int[] stack = new int[64];
        int depth = 0;
        boolean literal = false;
        boolean hex = false;
        boolean comment = false;
        boolean escape = false;
        for (int i = 0; i < limit && i < s.length(); i++) {
            char c = s.charAt(i);
            char n = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (comment) {
                if (c == '\n' || c == '\r') comment = false;
                continue;
            }
            if (literal) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == ')') literal = false;
                continue;
            }
            if (hex) {
                if (c == '>') hex = false;
                continue;
            }
            if (c == '%') comment = true;
            else if (c == '(') literal = true;
            else if (c == '<' && n == '<') {
                if (depth < stack.length) stack[depth] = i;
                depth++;
                i++;
            } else if (c == '>' && n == '>') {
                if (depth > 0) depth--;
                i++;
            } else if (c == '<') {
                hex = true;
            }
        }
        // Hostile input can nest deeper than the tracking window; report the
        // innermost tracked level instead of throwing.
        return depth > 0 ? stack[Math.min(depth - 1, stack.length - 1)] : -1;
    }

    private static int dictionaryEnd(String s, int start) {
        int depth = 0;
        boolean literal = false;
        boolean hex = false;
        boolean comment = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            char n = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (comment) {
                if (c == '\n' || c == '\r') comment = false;
                continue;
            }
            if (literal) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == ')') literal = false;
                continue;
            }
            if (hex) {
                if (c == '>') hex = false;
                continue;
            }
            if (c == '%') comment = true;
            else if (c == '(') literal = true;
            else if (c == '<' && n == '<') {
                depth++;
                i++;
            } else if (c == '>' && n == '>') {
                depth--;
                i++;
                if (depth == 0) return i + 1;
            } else if (c == '<') {
                hex = true;
            }
        }
        return -1;
    }

    /**
     * Returns the index just past the first balanced {@code << >>} dictionary in
     * {@code fragment}, or -1 when the fragment never closes one. Truncated
     * repair candidates use the balanced prefix instead of the broken tail.
     */
    // Closes an unbalanced PDF fragment by dropping an unterminated tail:
    // everything after the last complete value before the first unclosed
    // structural token. Mirrors how tolerant parsers recover objects cut
    // mid-array or mid-dictionary.
    private static String closeUnbalancedFragment(String fragment) {
        int depthDict = 0;
        int depthArray = 0;
        boolean literal = false;
        boolean literalEscape = false;
        boolean hex = false;
        boolean comment = false;
        int lastKeyStart = -1;
        for (int i = 0; i < fragment.length(); i++) {
            char c = fragment.charAt(i);
            if (comment) {
                if (c == '\n' || c == '\r') comment = false;
                continue;
            }
            if (literal) {
                if (literalEscape) literalEscape = false;
                else if (c == '\\') literalEscape = true;
                else if (c == ')') literal = false;
                continue;
            }
            if (hex) {
                if (c == '>') hex = false;
                continue;
            }
            if (c == '%') {
                comment = true;
                continue;
            }
            if (c == '(') {
                literal = true;
                continue;
            }
            if (c == '<' && i + 1 < fragment.length() && fragment.charAt(i + 1) == '<') {
                depthDict++;
                i++;
                continue;
            }
            if (c == '>' && i + 1 < fragment.length() && fragment.charAt(i + 1) == '>') {
                depthDict--;
                i++;
                if (depthDict <= 0) return null; // balanced already
                continue;
            }
            if (c == '<') {
                hex = true;
                continue;
            }
            if (c == '[') {
                depthArray++;
                continue;
            }
            if (c == ']') {
                depthArray--;
                if (depthArray < 0) return null;
                continue;
            }
            if (c == '/' && depthArray == 0 && depthDict == 1 && !literal && !hex && !comment) {
                // Remember each top-level key start: the cut point for a fragment
                // truncated inside a later value is just before the last key.
                lastKeyStart = i;
            }
        }
        if (lastKeyStart < 0) return null;
        // Drop the unterminated key and everything after it, then close the
        // containers that were open at that point (counted from the cut).
        String head = fragment.substring(0, lastKeyStart).stripTrailing();
        int openArrays = 0;
        int openDicts = 0;
        for (int i = 0; i < lastKeyStart; i++) {
            char c = fragment.charAt(i);
            if (c == '[') openArrays++;
            else if (c == ']') openArrays--;
            else if (i + 1 < lastKeyStart && c == '<' && fragment.charAt(i + 1) == '<') {
                openDicts++;
                i++;
            } else if (i + 1 < lastKeyStart && c == '>' && fragment.charAt(i + 1) == '>') {
                openDicts--;
                i++;
            }
        }
        if (openDicts <= 0 || openArrays < 0) return null;
        StringBuilder tail = new StringBuilder();
        for (int i = 0; i < openArrays; i++) tail.append(" ]");
        tail.append(" >>");
        return head + tail;
    }

    private static int closedDictionaryEnd(String fragment) {
        int depth = 0;
        boolean literal = false;
        boolean hex = false;
        boolean comment = false;
        boolean escape = false;
        for (int i = 0; i < fragment.length(); i++) {
            char c = fragment.charAt(i);
            char n = i + 1 < fragment.length() ? fragment.charAt(i + 1) : '\0';
            if (comment) {
                if (c == '\n' || c == '\r') comment = false;
                continue;
            }
            if (literal) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == ')') literal = false;
                continue;
            }
            if (hex) {
                if (c == '>') hex = false;
                continue;
            }
            if (c == '%') comment = true;
            else if (c == '(') literal = true;
            else if (c == '<' && n == '<') {
                depth++;
                i++;
            } else if (c == '>' && n == '>') {
                depth--;
                i++;
                if (depth == 0) return i + 1;
            } else if (c == '<') {
                hex = true;
            }
        }
        return -1;
    }

    private static String replaceParent(String dict, long parent) {
        java.util.regex.Matcher m = Pattern.compile("/Parent\\s+\\d+\\s+\\d+\\s+R").matcher(dict);
        if (m.find()) {
            return dict.substring(0, m.start()) + "/Parent " + parent + " 0 R" + dict.substring(m.end());
        }
        int start = dict.indexOf("<<");
        return start < 0 ? dict : dict.substring(0, start + 2) + "/Parent " + parent + " 0 R"
                + dict.substring(start + 2);
    }

    private static java.util.List<String> kidsOrder(String text) {
        java.util.List<String> order = new java.util.ArrayList<>();
        int[] catalog = catalogObjectBefore(text, text.length());
        if (catalog != null) {
            String catalogBody = objectBody(text, catalog[0], catalog[1]);
            String rootRef = catalogBody == null ? null : valueAfterKey(catalogBody, "/Pages");
            if (rootRef != null) {
                collectKidsOrder(text, rootRef.trim(), order, new java.util.HashSet<>(), 0);
            }
        }
        if (order.isEmpty()) {
            // No usable root: preserve the previous declaration-order fallback.
            Matcher kids = Pattern.compile("/Kids\\s*\\[([^\\]]*)]").matcher(text);
            while (kids.find()) {
                Matcher ref = Pattern.compile("(\\d+)\\s+(\\d+)\\s+R").matcher(kids.group(1));
                while (ref.find()) {
                    String value = ref.group(1) + " " + ref.group(2) + " R";
                    if (!order.contains(value)) order.add(value);
                }
            }
        }
        return order;
    }

    private static void collectKidsOrder(String text, String ref, java.util.List<String> order,
                                         java.util.Set<String> visited, int depth) {
        if (depth > 64 || !visited.add(ref)) return;
        Matcher r = Pattern.compile("(\\d+)\\s+(\\d+)\\s+R").matcher(ref);
        if (!r.find()) return;
        long number = parseObjectNumber(r.group(1));
        long generation = parseObjectNumber(r.group(2));
        if (number < 0 || generation < 0) return;
        String body = objectBody(text, number, generation);
        if (body == null) return;
        if (Pattern.compile("/Type\\s*/Page(?![s])").matcher(body).find()) {
            if (!order.contains(ref)) order.add(ref);
            return;
        }
        String kids = valueAfterKey(body, "/Kids");
        if (kids == null) return;
        Matcher child = Pattern.compile("(\\d+)\\s+(\\d+)\\s+R").matcher(kids);
        while (child.find()) {
            collectKidsOrder(text, child.group(1) + " " + child.group(2) + " R", order, visited,
                             depth + 1);
        }
    }

    private static String objectBody(String text, long number, long generation) {
        String marker = "\n" + number + " " + generation + " obj";
        int at = text.indexOf(marker);
        if (at < 0) at = text.indexOf(number + " " + generation + " obj");
        if (at < 0) return null;
        int end = text.indexOf("endobj", at);
        return end < 0 ? null : text.substring(at, end);
    }

    private static String valueAfterKey(String dict, String key) {
        int at = dict.indexOf(key);
        if (at < 0) return null;
        int i = at + key.length();
        while (i < dict.length() && Character.isWhitespace(dict.charAt(i))) i++;
        if (i >= dict.length()) return null;
        if (dict.startsWith("<<", i)) {
            int end = dictionaryEnd(dict, i);
            return end < 0 ? null : dict.substring(i, end);
        }
        if (dict.charAt(i) == '[') {
            int depth = 0;
            for (int j = i; j < dict.length(); j++) {
                char c = dict.charAt(j);
                if (c == '[') depth++;
                else if (c == ']' && --depth == 0) return dict.substring(i, j + 1);
            }
            return null;
        }
        int start = i;
        while (i < dict.length() && !Character.isWhitespace(dict.charAt(i))
                && dict.charAt(i) != '>' && dict.charAt(i) != ']') i++;
        if (i <= start) return null;
        int end = i;
        // Preserve indirect references (N G R) as one value.
        int j = i;
        while (j < dict.length() && Character.isWhitespace(dict.charAt(j))) j++;
        int genStart = j;
        while (j < dict.length() && Character.isDigit(dict.charAt(j))) j++;
        if (j > genStart) {
            int genEnd = j;
            while (j < dict.length() && Character.isWhitespace(dict.charAt(j))) j++;
            if (j < dict.length() && dict.charAt(j) == 'R'
                    && (j + 1 >= dict.length() || !Character.isLetterOrDigit(dict.charAt(j + 1)))) {
                return dict.substring(start, j + 1);
            }
        }
        return dict.substring(start, end);
    }

    private static String insertEntry(String dict, String key, String value) {
        if (dict.contains(key)) return dict;
        int parent = dict.indexOf("/Parent");
        int start = dict.indexOf("<<");
        if (start < 0) return dict;
        int at = parent >= 0 ? parent : start + 2;
        return dict.substring(0, at) + key + " " + value + " "
                + dict.substring(at);
    }

    private static String inheritPageAttributes(String dict, String text, long number, long generation) {
        String current = dict;
        String parent = valueAfterKey(dict, "/Parent");
        int guard = 0;
        while (parent != null && guard++ < 32) {
            Matcher ref = Pattern.compile("(\\d+)\\s+(\\d+)\\s+R").matcher(parent);
            if (!ref.find()) break;
            long pnum = parseObjectNumber(ref.group(1));
            long pgen = parseObjectNumber(ref.group(2));
            if (pnum < 0 || pgen < 0) break;
            String body = objectBody(text, pnum, pgen);
            if (body == null) break;
            String pageDict = balancedDictionary(body, Math.max(0, body.indexOf("/Type")));
            if (pageDict == null) break;
            for (String key : new String[] {"/MediaBox", "/CropBox", "/Resources", "/Rotate"}) {
                if (valueAfterKey(current, key) == null) {
                    String value = valueAfterKey(pageDict, key);
                    if (value != null) current = insertEntry(current, key, value);
                }
            }
            parent = valueAfterKey(pageDict, "/Parent");
        }
        return current;
    }

    private static String updateCatalogPages(String text, int[] catalog, long pagesObj) {
        String marker = "\n" + catalog[0] + " " + catalog[1] + " obj";
        int start = text.indexOf(marker);
        if (start < 0) {
            marker = catalog[0] + " " + catalog[1] + " obj";
            start = text.indexOf(marker);
        }
        if (start < 0) return text;
        int end = text.indexOf("endobj", start);
        if (end < 0) return text;
        String dict = text.substring(start, end);
        Matcher pages = Pattern.compile("/Pages\\s+\\d+\\s+\\d+\\s+R").matcher(dict);
        if (pages.find()) {
            dict = dict.substring(0, pages.start()) + "/Pages " + pagesObj + " 0 R"
                    + dict.substring(pages.end());
        } else {
            int at = dict.indexOf("<<");
            if (at < 0) return text;
            dict = dict.substring(0, at + 2) + " /Pages " + pagesObj + " 0 R "
                    + dict.substring(at + 2);
        }
        return text.substring(0, start) + dict + text.substring(end);
    }

    private static long parseObjectNumber(String raw) {
        try {
            long value = Long.parseLong(raw);
            return value >= 0 && value <= 9_000_000L ? value : -1;
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    // Rebuilds a /Pages tree from literal page dictionaries that lost their parent.
    // Streams are skipped so /Type /Page text inside content cannot create pages.
    static byte[] rebuildPageTree(byte[] input) {
        if (input == null || input.length < 16) return null;
        String text = new String(input, StandardCharsets.ISO_8859_1);
        Matcher stream = STREAM_BODY.matcher(text);
        java.util.List<Integer> pageStarts = new java.util.ArrayList<>();
        java.util.List<Long> pageNumbers = new java.util.ArrayList<>();
        java.util.List<Long> pageGenerations = new java.util.ArrayList<>();
        java.util.List<String> refs = new java.util.ArrayList<>();
        java.util.List<String> orphanedPages = new java.util.ArrayList<>();
        int pos = 0;
        while (true) {
            int from = pos;
            int to = stream.find() ? stream.start() : text.length();
            String chunk = text.substring(from, to);
            Matcher page = Pattern.compile("/Type\\s*/Page(?![s])").matcher(chunk);
            while (page.find()) {
                Matcher obj =
                        Pattern.compile("(\\d+)\\s+(\\d+)\\s+obj")
                                .matcher(chunk.substring(0, page.start()));
                String number = null;
                String generation = null;
                int start = -1;
                int objEnd = -1;
                while (obj.find()) {
                    number = obj.group(1);
                    generation = obj.group(2);
                    start = from + obj.start();
                    objEnd = from + obj.end();
                }
                boolean owned = number != null && generation != null
                        && page.start() - (objEnd - from) <= 64;
                String ref = owned ? number + " " + generation + " R" : null;
                if (owned && !refs.contains(ref)) {
                    refs.add(ref);
                    pageStarts.add(start);
                    pageNumbers.add(parseObjectNumber(number));
                    pageGenerations.add(parseObjectNumber(generation));
                } else if (!owned) {
                    String orphan = balancedDictionary(chunk, page.start());
                    if (orphan != null) orphanedPages.add(orphan);
                }
            }
            if (!stream.find(to)) break;
            pos = stream.end();
        }
        if (refs.isEmpty() && orphanedPages.isEmpty()) return null;

        long maxObj = 0;
        Matcher all = Pattern.compile("(?m)(\\d+)\\s+\\d+\\s+obj").matcher(text);
        while (all.find()) {
            long value = parseObjectNumber(all.group(1));
            if (value < 0) return null;
            maxObj = Math.max(maxObj, value);
        }

        java.util.List<String> kids = kidsOrder(text);
        refs.sort(java.util.Comparator.comparingInt(r -> {
            int index = kids.indexOf(r);
            return index < 0 ? Integer.MAX_VALUE : index;
        }));
        java.util.List<String> orphanObjects = new java.util.ArrayList<>();
        long pagesObj = maxObj + orphanedPages.size() + 1;
        for (String orphan : orphanedPages) {
            long object = ++maxObj;
            String dict = replaceParent(orphan, pagesObj);
            orphanObjects.add(object + " 0 obj\n" + dict + "\nendobj\n");
            refs.add(object + " 0 R");
        }

        StringBuilder out = new StringBuilder(text.length() + 512);
        if (text.indexOf("%PDF-") < 0) out.append("%PDF-1.4\n");
        int cursor = 0;
        for (int i = 0; i < pageStarts.size(); i++) {
            int start = pageStarts.get(i);
            out.append(text, cursor, start);
            int end = text.indexOf("endobj", start);
            int junk = text.indexOf("%PDF-", start + 5);
            if (junk >= 0 && (end < 0 || junk < end)) end = junk;
            if (end < 0) end = text.length();
            String dict = text.substring(start, end);
            int close = closedDictionaryEnd(dict);
            if (close >= 0 && close < dict.length()) {
                // A page dictionary truncated mid-way (e.g. by a mid-file second
                // %PDF header) keeps a usable prefix; close it and drop the tail.
                dict = dict.substring(0, close);
            } else {
                // The dictionary itself never closes: the object was cut mid-way
                // (often inside /Annots or another array). Close any open arrays
                // and dictionaries so the fragment becomes a valid object again.
                String recovered = closeUnbalancedFragment(dict);
                if (recovered != null) {
                    dict = recovered;
                    // The original object body ran into following junk; rewrite
                    // the recovered fragment as a complete object of its own and
                    // skip past the junk instead of splicing the tail back in.
                    out.append(dict).append("\nendobj\n");
                    cursor = end;
                    continue;
                }
            }
            if (pageNumbers.get(i) >= 0 && pageGenerations.get(i) >= 0) {
                dict = inheritPageAttributes(dict, text, pageNumbers.get(i), pageGenerations.get(i));
            }
            out.append(replaceParent(dict, pagesObj));
            cursor = end;
        }
        out.append(text, cursor, text.length());
        for (String orphan : orphanObjects) out.append("\n").append(orphan);
        out.append("\n").append(pagesObj).append(" 0 obj\n<< /Type /Pages /Kids [")
                .append(String.join(" ", refs)).append("] /Count ").append(refs.size())
                .append(" >>\nendobj\n");

        int[] catalog = catalogObjectBefore(text, text.length());
        String result;
        long rootObj;
        long rootGen = 0;
        if (catalog != null) {
            result = updateCatalogPages(out.toString(), catalog, pagesObj);
            rootObj = catalog[0];
            rootGen = catalog[1];
        } else {
            rootObj = maxObj + 2;
            result = out.toString() + rootObj + " 0 obj\n<< /Type /Catalog /Pages " + pagesObj
                    + " 0 R >>\nendobj\n";
        }
        // /Size is one greater than the highest object number. pagesObj is
        // maxObj + 1, and a newly created catalog is maxObj + 2.
        result += "trailer\n<< /Root " + rootObj + " " + rootGen + " R /Size "
                + Math.max(maxObj + 2, rootObj + 1) + " >>\nstartxref\n0\n%%EOF\n";
        return result.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] salvageTruncatedPdf(byte[] input) {
        if (input == null || input.length < 16) {
            return null;
        }
        // Check %PDF- header within first 1024 bytes
        int headerLimit = Math.min(input.length - 5, 1024);
        boolean hasPdfHeader = false;
        for (int i = 0; i <= headerLimit; i++) {
            if (input[i] == '%' && input[i + 1] == 'P' && input[i + 2] == 'D'
                    && input[i + 3] == 'F' && input[i + 4] == '-') {
                hasPdfHeader = true;
                break;
            }
        }
        if (!hasPdfHeader) {
            return null;
        }

        // Find last 'endobj' token
        byte[] endobj = {'e', 'n', 'd', 'o', 'b', 'j'};
        int lastEndObj = -1;
        for (int i = input.length - endobj.length; i >= 0; i--) {
            if (input[i] == 'e' && input[i + 1] == 'n' && input[i + 2] == 'd'
                    && input[i + 3] == 'o' && input[i + 4] == 'b' && input[i + 5] == 'j') {
                lastEndObj = i + endobj.length;
                break;
            }
        }
        if (lastEndObj <= 0) {
            return null;
        }

        String body = new String(input, 0, lastEndObj, StandardCharsets.ISO_8859_1);
        int[] root = catalogObjectBefore(body, body.length());
        StringBuilder trailer = new StringBuilder();
        if (root != null) {
            int maxObj = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?m)(\\d+)\\s+\\d+\\s+obj")
                    .matcher(body);
            // parseObjectNumber caps accepted object numbers at 9,000,000 everywhere
            // else; honor the same fail-closed bound here instead of letting a
            // hostile 10+ digit token throw NumberFormatException out of execute().
            while (m.find()) {
                long parsed = parseObjectNumber(m.group(1));
                if (parsed > maxObj) maxObj = (int) parsed;
            }
            trailer.append("\ntrailer\n<< /Size ").append(Math.max(maxObj + 1, root[0] + 1))
                    .append(" /Root ").append(root[0]).append(' ').append(root[1])
                    .append(" R >>\nstartxref\n0\n");
        }
        byte[] tail = (trailer + "%%EOF\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[lastEndObj + tail.length];
        System.arraycopy(input, 0, out, 0, lastEndObj);
        System.arraycopy(tail, 0, out, lastEndObj, tail.length);
        return out;
    }

    /**
     * Finds the object header that owns the last real catalog dictionary before {@code limit}.
     * String contents and nested dictionaries are ignored: an Info value such as
     * {@code /Subject (/Type /Catalog)} must not become the document root.
     */
    private static int[] catalogObjectBefore(String body, int limit) {
        Matcher headers =
                Pattern.compile("(?m)^[\\t ]*(\\d+)[\\t ]+(\\d+)[\\t ]+obj\\b").matcher(body);
        int[] found = null;
        while (headers.find() && headers.start() < limit) {
            long number = parseObjectNumber(headers.group(1));
            long generation = parseObjectNumber(headers.group(2));
            if (number < 0 || generation < 0) continue;
            int end = body.indexOf("endobj", headers.end());
            if (end < 0 || end > limit) continue;
            int dictStart = body.indexOf("<<", headers.end());
            if (dictStart < 0 || dictStart > end) continue;
            int dictEnd = dictionaryEnd(body, dictStart);
            if (dictEnd < 0 || dictEnd > end) continue;
            String dictionary = body.substring(dictStart, dictEnd);
            if (hasTopLevelCatalogType(dictionary)) {
                found = new int[] {(int) number, (int) generation};
            }
        }
        return found;
    }

    /** True when the dictionary's own (top-level) {@code /Type} is {@code /Catalog}. */
    private static boolean hasTopLevelCatalogType(String dictionary) {
        int depth = 0;
        boolean literal = false;
        boolean hex = false;
        boolean comment = false;
        int arrayDepth = 0;
        for (int i = 0; i < dictionary.length(); ) {
            char c = dictionary.charAt(i);
            if (comment) {
                if (c == '\n' || c == '\r') comment = false;
                i++;
                continue;
            }
            if (literal) {
                if (c == '\\') {
                    i += 2;
                } else {
                    if (c == ')') literal = false;
                    i++;
                }
                continue;
            }
            if (hex) {
                if (c == '>') hex = false;
                i++;
                continue;
            }
            if (c == '%') {
                comment = true;
                i++;
                continue;
            }
            if (c == '(') {
                literal = true;
                i++;
                continue;
            }
            if (c == '<') {
                if (i + 1 < dictionary.length() && dictionary.charAt(i + 1) == '<') {
                    depth++;
                    i += 2;
                } else {
                    hex = true;
                    i++;
                }
                continue;
            }
            if (c == '>' && i + 1 < dictionary.length() && dictionary.charAt(i + 1) == '>') {
                depth--;
                i += 2;
                continue;
            }
            if (c == '[') {
                arrayDepth++;
                i++;
                continue;
            }
            if (c == ']') {
                if (arrayDepth > 0) arrayDepth--;
                i++;
                continue;
            }
            if (c == '/' && depth == 1 && arrayDepth == 0) {
                int end = i + 1;
                while (end < dictionary.length() && isPdfNameByte((byte) dictionary.charAt(end))) end++;
                String name = dictionary.substring(i, end);
                if (!"/Type".equals(name)) {
                    i = end;
                    continue;
                }
                int value = end;
                while (value < dictionary.length() && Character.isWhitespace(dictionary.charAt(value)))
                    value++;
                if (value < dictionary.length() && dictionary.charAt(value) == '/') {
                    int valueEnd = value + 1;
                    while (valueEnd < dictionary.length()
                            && isPdfNameByte((byte) dictionary.charAt(valueEnd))) valueEnd++;
                    return "/Catalog".equals(dictionary.substring(value, valueEnd));
                }
                return false;
            }
            i++;
        }
        return false;
    }
}
