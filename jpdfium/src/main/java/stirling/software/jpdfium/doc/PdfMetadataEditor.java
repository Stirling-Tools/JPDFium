package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.EmbedPdfDocumentBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PdfiumRuntime;
import stirling.software.jpdfium.panama.XmpLib;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Edits the /Info dictionary, custom entries, /Trapped status, and the catalog
 * XMP packet of an open document.
 *
 * <p>The editor changes the document in memory. Call {@link #apply()} and then
 * {@link PdfDocument#save(Path)}, or use {@link #applyAndSave(Path)}.
 *
 * <pre>{@code
 * PdfMetadataEditor.on(doc)
 *     .title("Quarterly report")
 *     .author("Finance")
 *     .custom("Department", "Finance")
 *     .xmp(xmpPacket)
 *     .applyAndSave(output);
 * }</pre>
 *
 * <p>Signature and conformance preservation are enabled by default:
 * <ul>
 *   <li>{@link #applyAndSave(Path)} uses an incremental save when the document
 *       already has a digital signature, so the original signed bytes are
 *       retained. XMP replacement on a signed document is refused because it
 *       needs a full QPDF rewrite; disable {@link #preserveSignatures(boolean)}
 *       only when invalidating the signature is intentional.</li>
 *   <li>Replacing XMP on a PDF/A or PDF/X document keeps the existing
 *       identification and extension descriptions. Removing XMP from such a
 *       document is refused. Standard /Info changes are mirrored into XMP so
 *       the two metadata views stay consistent.</li>
 * </ul>
 *
 * <p>Calling {@link #apply()} alone cannot control the later save. For signed
 * documents use {@link #applyAndSave(Path)} or {@link PdfDocument#saveIncremental(Path)};
 * a full {@link PdfDocument#save(Path)} rewrites every object and invalidates
 * the signature.
 */
public final class PdfMetadataEditor {

    private static final Set<String> RESERVED_KEYS = Set.of(
            "Title", "Author", "Subject", "Keywords", "Producer", "Creator",
            "CreationDate", "ModDate", "Trapped");

    private static final byte[] BYTE_RANGE = "/ByteRange".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONTENTS = "/Contents".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SIG_FIELD = "/FT /Sig".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SIG_FIELD_TIGHT = "/FT/Sig".getBytes(StandardCharsets.US_ASCII);

    private final PdfDocument document;
    private final Map<String, String> info = new LinkedHashMap<>();
    private final Map<String, String> custom = new LinkedHashMap<>();
    private TrappedStatus trapped;
    private boolean trappedSet;
    private byte[] xmp;
    private boolean xmpSet;
    private boolean preserveSignatures = true;
    private boolean preserveConformance = true;
    private Boolean signed;

    private PdfMetadataEditor(PdfDocument document) {
        this.document = document;
    }

    /**
     * Starts an edit for an open document.
     */
    public static PdfMetadataEditor on(PdfDocument document) {
        return new PdfMetadataEditor(Objects.requireNonNull(document, "document"));
    }

    /**
     * Preserve existing digital signatures. Enabled by default. When enabled,
     * {@link #applyAndSave(Path)} appends an incremental revision for /Info and
     * /Trapped edits and refuses XMP replacement, which needs a full rewrite.
     */
    public PdfMetadataEditor preserveSignatures(boolean preserve) {
        this.preserveSignatures = preserve;
        return this;
    }

    /**
     * Preserve PDF/A and PDF/X identification when replacing XMP. Enabled by
     * default. When enabled, /Info changes on a conformant document also update
     * the matching XMP properties, and {@link #removeXmp()} is refused.
     */
    public PdfMetadataEditor preserveConformance(boolean preserve) {
        this.preserveConformance = preserve;
        return this;
    }

    /** Sets a standard field. A null value removes the entry. */
    public PdfMetadataEditor set(MetadataTag tag, String value) {
        Objects.requireNonNull(tag, "tag");
        info.put(tag.pdfKey(), value);
        return this;
    }

    /** Removes a standard field. */
    public PdfMetadataEditor remove(MetadataTag tag) {
        return set(tag, null);
    }

    public PdfMetadataEditor title(String value) { return set(MetadataTag.TITLE, value); }
    public PdfMetadataEditor author(String value) { return set(MetadataTag.AUTHOR, value); }
    public PdfMetadataEditor subject(String value) { return set(MetadataTag.SUBJECT, value); }
    public PdfMetadataEditor keywords(String value) { return set(MetadataTag.KEYWORDS, value); }
    public PdfMetadataEditor creator(String value) { return set(MetadataTag.CREATOR, value); }
    public PdfMetadataEditor producer(String value) { return set(MetadataTag.PRODUCER, value); }
    public PdfMetadataEditor creationDate(String value) { return set(MetadataTag.CREATION_DATE, value); }
    public PdfMetadataEditor modDate(String value) { return set(MetadataTag.MOD_DATE, value); }

    /**
     * Sets a custom /Info entry. Custom keys must be non-reserved PDF name
     * tokens without whitespace or delimiters. A null value removes the entry.
     */
    public PdfMetadataEditor custom(String key, String value) {
        validateCustomKey(key);
        custom.put(key, value);
        return this;
    }

    /** Removes a custom /Info entry. */
    public PdfMetadataEditor removeCustom(String key) {
        return custom(key, null);
    }

    /** Sets or removes the /Trapped status. */
    public PdfMetadataEditor trapped(TrappedStatus status) {
        this.trapped = status;
        this.trappedSet = true;
        return this;
    }

    /**
     * Replaces the catalog XMP packet. The packet must be a complete UTF-8 XML
     * document. Use {@link #removeXmp()} to remove it.
     */
    public PdfMetadataEditor xmp(String packet) {
        Objects.requireNonNull(packet, "packet");
        if (packet.isBlank()) {
            throw new IllegalArgumentException("XMP packet must not be blank");
        }
        this.xmp = packet.getBytes(StandardCharsets.UTF_8);
        this.xmpSet = true;
        return this;
    }

    /**
     * Replaces the catalog XMP packet with raw UTF-8 XML.
     */
    public PdfMetadataEditor xmp(byte[] packet) {
        Objects.requireNonNull(packet, "packet");
        if (packet.length == 0) {
            throw new IllegalArgumentException("XMP packet must not be empty");
        }
        this.xmp = packet.clone();
        this.xmpSet = true;
        return this;
    }

    /** Removes the catalog XMP packet. */
    public PdfMetadataEditor removeXmp() {
        this.xmp = null;
        this.xmpSet = true;
        return this;
    }

    /**
     * Applies the pending changes to the document. The document is not saved.
     *
     * @return a summary of the applied changes
     */
    public MetadataUpdate apply() {
        if (info.isEmpty() && custom.isEmpty() && !trappedSet && !xmpSet) {
            return MetadataUpdate.NONE;
        }

        boolean signedDocument = preserveSignatures && hasSignature();
        if (signedDocument && xmpSet) {
            throw new IllegalStateException("cannot replace XMP on a signed document while "
                    + "signature preservation is enabled; call preserveSignatures(false) "
                    + "only when invalidating the signature is intentional");
        }

        XmpMetadata.Claim claim = XmpMetadata.Claim.NONE;
        String existingXmp = null;
        if (preserveConformance && (xmpSet || !info.isEmpty() || trappedSet)) {
            existingXmp = PdfMetadata.of(document).xmp().orElse(null);
            claim = XmpMetadata.claim(existingXmp);
        }
        if (preserveConformance && claim != XmpMetadata.Claim.NONE && xmpSet && xmp == null) {
            throw new IllegalStateException("cannot remove XMP from a PDF/A or PDF/X document "
                    + "while conformance preservation is enabled");
        }
        if (signedDocument && preserveConformance && claim != XmpMetadata.Claim.NONE
                && (!info.isEmpty() || trappedSet)) {
            throw new IllegalStateException("cannot change /Info or /Trapped on a signed PDF/A "
                    + "or PDF/X document while preserving both the signature and the "
                    + "conformance claim");
        }

        if (xmpSet && xmp != null && claim != XmpMetadata.Claim.NONE) {
            String replacement = new String(xmp, StandardCharsets.UTF_8);
            String merged = XmpMetadata.mergeConformance(existingXmp, replacement);
            if (!merged.equals(replacement)) {
                xmp = merged.getBytes(StandardCharsets.UTF_8);
            }
        }
        if (preserveConformance && claim != XmpMetadata.Claim.NONE
                && (!info.isEmpty() || trappedSet)) {
            String current = xmpSet ? new String(xmp, StandardCharsets.UTF_8) : existingXmp;
            String synchronizedXmp = XmpMetadata.synchronizeInfo(current, effectiveInfo());
            if (synchronizedXmp != null && !synchronizedXmp.equals(current)) {
                xmp = synchronizedXmp.getBytes(StandardCharsets.UTF_8);
                xmpSet = true;
            }
        }

        int infoSet = 0;
        int infoRemoved = 0;
        int customSet = 0;
        int customRemoved = 0;
        for (String value : info.values()) {
            if (value == null) infoRemoved++;
            else infoSet++;
        }
        for (String value : custom.values()) {
            if (value == null) customRemoved++;
            else customSet++;
        }

        if (!info.isEmpty() || !custom.isEmpty() || trappedSet) {
            MemorySegment raw = document.rawHandle();
            PdfiumRuntime.executeBatch(() -> {
                try (Arena arena = Arena.ofConfined()) {
                    for (Map.Entry<String, String> entry : info.entrySet()) {
                        applyMetaText(arena, raw, entry.getKey(), entry.getValue());
                    }
                    for (Map.Entry<String, String> entry : custom.entrySet()) {
                        applyMetaText(arena, raw, entry.getKey(), entry.getValue());
                    }
                    if (trappedSet) {
                        setTrapped(raw, trapped == null ? TrappedStatus.NOT_SET : trapped);
                    }
                }
            });
        }

        if (xmpSet) {
            XmpLib.setXmp(document.nativeHandle(), xmp);
            document.refreshRawHandle();
        }

        return new MetadataUpdate(infoSet, infoRemoved, customSet, customRemoved,
                trappedSet, xmpSet);
    }

    /**
     * Applies the pending changes and saves the document. Signed documents use
     * an incremental save by default, preserving the bytes covered by existing
     * signatures.
     */
    public MetadataUpdate applyAndSave(Path output) {
        boolean signedDocument = preserveSignatures && hasSignature();
        MetadataUpdate update = apply();
        if (signedDocument) {
            document.saveIncremental(output);
        } else {
            document.save(output);
        }
        return update;
    }

    private Map<String, String> effectiveInfo() {
        Map<String, String> values = new LinkedHashMap<>();
        PdfMetadata.of(document).all().forEach((key, value) -> {
            if (RESERVED_KEYS.contains(key)) values.put(key, value);
        });
        values.putAll(info);
        if (trappedSet) {
            values.put("Trapped", switch (trapped == null ? TrappedStatus.NOT_SET : trapped) {
                case TRUE -> "True";
                case FALSE -> "False";
                case UNKNOWN -> "Unknown";
                case NOT_SET -> null;
            });
        }
        return values;
    }

    private boolean hasSignature() {
        if (signed != null) return signed;

        boolean found = false;
        RuntimeException countFailure = null;
        try {
            found = document.signatureCount() > 0;
        } catch (RuntimeException e) {
            countFailure = e;
        }
        if (!found) {
            // Only scan bytes the document already retains. Reading a
            // file-backed document again here would turn every unsigned
            // metadata edit into a second full-file I/O pass; PDFium's
            // signature count is authoritative for active signatures.
            byte[] bytes = document.sourceBytes();
            if (bytes != null) {
                found = (indexOf(bytes, SIG_FIELD, 0) >= 0
                            || indexOf(bytes, SIG_FIELD_TIGHT, 0) >= 0)
                        && hasNonEmptySignatureContents(bytes);
            } else if (countFailure != null) {
                // Without retained bytes there is no safe fallback: refusing
                // the edit is better than silently taking the full-save path.
                throw countFailure;
            }
        }
        signed = found;
        return found;
    }

    /**
     * Unsigned signature fields can carry a /ByteRange placeholder with
     * zero-filled /Contents (several IRS forms do). Treat those as unsigned;
     * only a dictionary with actual signature bytes blocks a full rewrite.
     */
    private static boolean hasNonEmptySignatureContents(byte[] bytes) {
        int from = 0;
        while (from < bytes.length) {
            int range = indexOf(bytes, BYTE_RANGE, from);
            if (range < 0) return false;
            int start = Math.max(0, range - 65536);
            int end = Math.min(bytes.length, range + 4096);
            int contents = indexOf(bytes, CONTENTS, start, end);
            if (contents >= 0) {
                int open = indexOf(bytes, (byte) '<', contents, end);
                if (open >= 0) {
                    int close = indexOf(bytes, (byte) '>', open + 1, end);
                    if (close > open && hasNonZeroHex(bytes, open + 1, close)) {
                        return true;
                    }
                }
            }
            from = range + BYTE_RANGE.length;
        }
        return false;
    }

    private static boolean hasNonZeroHex(byte[] bytes, int start, int end) {
        for (int i = start; i < end; i++) {
            byte b = bytes[i];
            if (b > ' ' && b != '0') return true;
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        return indexOf(haystack, needle, from, haystack.length);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from, int to) {
        outer:
        for (int i = Math.max(0, from); i <= to - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int indexOf(byte[] bytes, byte value, int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(bytes.length, to); i++) {
            if (bytes[i] == value) return i;
        }
        return -1;
    }

    private static void applyMetaText(Arena arena, MemorySegment raw, String key, String value) {
        if (EmbedPdfDocumentBindings.EPDF_SetMetaText == null) {
            throw new JPDFiumException("EPDF_SetMetaText is not available in the loaded native library");
        }
        try {
            MemorySegment tag = arena.allocateFrom(key);
            MemorySegment text = value == null
                    ? MemorySegment.NULL
                    : FfmHelper.toWideString(arena, value);
            if ((int) EmbedPdfDocumentBindings.EPDF_SetMetaText.invokeExact(raw, tag, text) == 0) {
                throw new JPDFiumException("EPDF_SetMetaText failed for " + key);
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("EPDF_SetMetaText failed for " + key, t);
        }
    }

    private static void setTrapped(MemorySegment raw, TrappedStatus status) {
        if (EmbedPdfDocumentBindings.EPDF_SetMetaTrapped == null) {
            throw new JPDFiumException("EPDF_SetMetaTrapped is not available in the loaded native library");
        }
        try {
            if ((int) EmbedPdfDocumentBindings.EPDF_SetMetaTrapped.invokeExact(raw, status.code()) == 0) {
                throw new JPDFiumException("EPDF_SetMetaTrapped failed for " + status);
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("EPDF_SetMetaTrapped failed for " + status, t);
        }
    }

    private static void validateCustomKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty()) throw new IllegalArgumentException("custom key must not be empty");
        if (RESERVED_KEYS.contains(key)) {
            throw new IllegalArgumentException("use the typed setter for reserved key: " + key);
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 33 || c > 126 || "()<>[]{}/%#".indexOf(c) >= 0) {
                throw new IllegalArgumentException("invalid character in custom key: " + key);
            }
        }
    }
}
