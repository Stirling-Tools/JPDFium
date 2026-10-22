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
 *     .apply();
 * doc.save(output);
 * }</pre>
 *
 * <p>Setting XMP rewrites the document through QPDF, so object-stream,
 * cross-reference-stream, and compressed-content PDFs are accepted. The rewrite
 * invalidates existing signatures and should be applied before encryption.
 */
public final class PdfMetadataEditor {

    private static final Set<String> RESERVED_KEYS = Set.of(
            "Title", "Author", "Subject", "Keywords", "Producer", "Creator",
            "CreationDate", "ModDate", "Trapped");

    private final PdfDocument document;
    private final Map<String, String> info = new LinkedHashMap<>();
    private final Map<String, String> custom = new LinkedHashMap<>();
    private TrappedStatus trapped;
    private boolean trappedSet;
    private byte[] xmp;
    private boolean xmpSet;

    private PdfMetadataEditor(PdfDocument document) {
        this.document = document;
    }

    /**
     * Starts an edit for an open document.
     */
    public static PdfMetadataEditor on(PdfDocument document) {
        return new PdfMetadataEditor(Objects.requireNonNull(document, "document"));
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
     * Applies the pending changes and saves the document.
     */
    public MetadataUpdate applyAndSave(Path output) {
        MetadataUpdate update = apply();
        document.save(output);
        return update;
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
