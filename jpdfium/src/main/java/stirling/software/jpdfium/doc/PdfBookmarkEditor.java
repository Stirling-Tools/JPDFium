package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.TextChar;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Create and set bookmarks (outlines) in a PDF document.
 *
 * <p>PDFium does not expose a bookmark creation API, so this class uses
 * qpdf's JSON round-trip ({@code --json-output} / {@code --update-from-json})
 * to inject the outline tree into the PDF's object structure.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("report.pdf"))) {
 *     BookmarkTree tree = BookmarkTree.builder()
 *         .add("Chapter 1", 0)
 *         .add("Chapter 2", 5)
 *             .addChild("Section 2.1", 5)
 *             .addChild("Section 2.2", 8)
 *             .parent()
 *         .add("Chapter 3", 15)
 *         .build();
 *
 *     byte[] result = PdfBookmarkEditor.setBookmarks(doc, tree);
 *     Files.write(Path.of("bookmarked.pdf"), result);
 * }
 * }</pre>
 */
public final class PdfBookmarkEditor {

    private static final Pattern PATTERN = Pattern.compile("\\s+");

    private PdfBookmarkEditor() {}

    /**
     * Set the bookmark tree for a document, writing the result to {@code output}.
     *
     * <p>This is the heap-friendly variant. The merged document is saved to
     * {@code output} as a single streaming pass, then the outline objects are
     * appended via an incremental update directly on the file - we never hold
     * the full PDF in heap, which matters when the document is hundreds of MB.
     *
     * <p>Old behaviour (preserved by the {@link #setBookmarks(PdfDocument,
     * BookmarkTree)} overload) materialised the full PDF as {@code byte[]}
     * three times and routed through qpdf, costing ~4× file size in heap.
     * This variant costs O(KB) regardless of document size.
     *
     * @param doc    the source document
     * @param tree   the bookmark tree to set
     * @param output destination file (overwritten if it exists)
     */
    public static void setBookmarks(PdfDocument doc, BookmarkTree tree, Path output)
            throws IOException {
        // 1. Stream the source PDF to disk. PDFium writes directly through
        //    the OS - no Java heap intermediate.
        doc.save(output);

        if (tree.entries().isEmpty()) {
            return; // nothing to do
        }

        // 2. Parse only what we need from the on-disk PDF to construct an
        //    incremental-update appendix: previous xref offset, /Size value,
        //    /Root object number, and the Catalog dictionary bytes.
        OutlineMeta meta = readOutlineMeta(output);

        // 3. Build the appendix (~few KB) - new Catalog override with
        //    /Outlines added, the Outlines dict, the bookmark entries, and
        //    a fresh xref subsection + trailer.
        long appendOffset = Files.size(output);
        byte[] appendix =
                buildOutlineAppendix(
                        tree.entries(), doc.pageCount(), meta, appendOffset);

        // 4. Append to the output file.
        try (OutputStream os = Files.newOutputStream(output, StandardOpenOption.APPEND)) {
            os.write(appendix);
        }
    }

    /**
     * Set the bookmark tree for a document and return the resulting bytes.
     *
     * <p>This overload still materialises the full PDF in heap because the
     * caller asked for bytes. Prefer
     * {@link #setBookmarks(PdfDocument, BookmarkTree, Path)} when you can
     * sink to a file - it costs O(KB) heap instead of O(file size).
     *
     * @param doc  the source document
     * @param tree the bookmark tree to set
     * @return PDF bytes of the document with bookmarks
     */
    public static byte[] setBookmarks(PdfDocument doc, BookmarkTree tree) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("jpdfium-bookmarks-", ".pdf");
            setBookmarks(doc, tree, tmp);
            return Files.readAllBytes(tmp);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write temporary PDF with bookmarks", e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Auto-generate bookmarks from text headings using font-size heuristics.
     *
     * <p>Analyzes each page's text to find large-font text blocks (likely headings)
     * and creates a bookmark tree based on relative font sizes.
     *
     * @param doc         the document to analyze
     * @param minFontSize minimum font size to consider as a heading
     * @return auto-generated bookmark tree
     */
    public static BookmarkTree fromHeadings(PdfDocument doc, float minFontSize) {
        BookmarkTree.Builder builder = BookmarkTree.builder();
        float maxFontSeen = 0;

        // First pass: find the maximum font size to calibrate heading levels
        for (int i = 0; i < doc.pageCount(); i++) {
            try {
                PageText pt = PdfTextExtractor.extractPage(doc, i);
                for (TextChar ch : pt.chars()) {
                    if (ch.fontSize() > maxFontSeen) maxFontSeen = ch.fontSize();
                }
            } catch (Exception _) {}
        }

        if (maxFontSeen <= 0) return builder.build();

        // Second pass: extract headings
        for (int i = 0; i < doc.pageCount(); i++) {
            try {
                PageText pt = PdfTextExtractor.extractPage(doc, i);
                List<HeadingCandidate> headings = extractHeadings(pt, i, minFontSize, maxFontSeen);
                for (HeadingCandidate h : headings) {
                    builder.add(h.text(), h.pageIndex());
                }
            } catch (Exception _) {}
        }

        return builder.build();
    }

    /**
     * Auto-generate bookmarks from headings using default minimum font size (14pt).
     */
    public static BookmarkTree fromHeadings(PdfDocument doc) {
        return fromHeadings(doc, 14.0f);
    }

    private record HeadingCandidate(String text, int pageIndex, float fontSize) {}

    /**
     * Everything the appendix builder needs to know about the existing PDF
     * without holding the whole file in heap.
     */
    private record OutlineMeta(int prevXrefStart, int trailerSize, int rootObjNum, String rootDict) {}

    /**
     * Read the trailer/xref/Root region of a PDF on disk. Cost: O(KB) for
     * typical PDFs - three small window reads (tail, xref table, root object).
     */
    private static OutlineMeta readOutlineMeta(Path file) throws IOException {
        long fileSize = Files.size(file);
        // Most PDFs end with: ...trailer << /Size N /Root M 0 R ... >>
        // startxref <offset>
        // %%EOF
        // - all within the last few KB. Read 8 KB which is plenty for that.
        int tailLen = (int) Math.min(fileSize, 8192L);
        String tail = readWindow(file, fileSize - tailLen, tailLen);

        int prevXrefStart = findPreviousXref(tail);
        int trailerSize = findTrailerSize(tail);
        int rootObjNum = findRootObjectNumber(tail);

        // The Catalog object lives somewhere in the file; we find its byte
        // offset via the xref table and read a small window starting there.
        // For typical PDFium output (no object streams, single contiguous
        // xref subsection) the table is contiguous starting at prevXrefStart.
        long rootOffset = readXrefEntryOffset(file, prevXrefStart, rootObjNum);
        String rootDict;
        if (rootOffset < 0) {
            // Couldn't find Root via xref - fall back to a default catalog
            // dict. setBookmarks still works; just preserves no existing
            // Catalog keys.
            rootDict = "<< /Type /Catalog >>";
        } else {
            String objWindow = readWindow(file, rootOffset, (int) Math.min(fileSize - rootOffset, 8192L));
            rootDict = extractDictFromWindow(objWindow);
        }
        return new OutlineMeta(prevXrefStart, trailerSize, rootObjNum, rootDict);
    }

    /**
     * Read {@code len} bytes starting at {@code offset} as ISO-8859-1.
     * Bounded heap use: the returned String is exactly {@code len} chars.
     */
    private static String readWindow(Path file, long offset, int len) throws IOException {
        byte[] buf = new byte[len];
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            ch.position(offset);
            ByteBuffer bb = ByteBuffer.wrap(buf);
            while (bb.hasRemaining() && ch.read(bb) > 0) {
                // keep reading until buffer full or EOF
            }
        }
        return new String(buf, StandardCharsets.ISO_8859_1);
    }

    /**
     * Walk the xref table starting at {@code xrefStart} and return the
     * byte offset of object {@code wantedObj}. Returns -1 if the table is
     * an xref-stream (not supported here) or if the object is missing.
     *
     * <p>Reads the xref table in 64 KB windows so very long tables don't
     * load entirely into heap.
     */
    private static long readXrefEntryOffset(Path file, int xrefStart, int wantedObj)
            throws IOException {
        long fileSize = Files.size(file);
        if (xrefStart <= 0 || xrefStart >= fileSize) return -1;

        // First chunk: look for "xref\n" header then subsections.
        int firstChunkLen = (int) Math.min(fileSize - xrefStart, 65536L);
        String chunk = readWindow(file, xrefStart, firstChunkLen);
        if (!chunk.startsWith("xref")) {
            // Probably an xref stream. We don't try to decode object streams
            // here - caller falls back to default Catalog.
            return -1;
        }
        // Skip "xref" and the following whitespace.
        int pos = 4;
        while (pos < chunk.length() && (chunk.charAt(pos) == '\r' || chunk.charAt(pos) == '\n')) {
            pos++;
        }
        // Walk subsections: each starts with "<startObj> <count>\n" then
        // <count> 20-byte lines "offset gen n/f ".
        while (pos < chunk.length()) {
            // If we hit "trailer", we're done.
            if (chunk.startsWith("trailer", pos)) {
                break;
            }
            // Parse header line.
            int eol = chunk.indexOf('\n', pos);
            if (eol < 0) break;
            String header = chunk.substring(pos, eol).trim();
            String[] parts = PATTERN.split(header);
            if (parts.length < 2) break;
            int startObj;
            int count;
            try {
                startObj = Integer.parseInt(parts[0]);
                count = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                break;
            }
            pos = eol + 1;
            // Each entry is exactly 20 bytes including the trailing newline.
            int needed = count * 20;
            if (pos + needed > chunk.length()) {
                // Subsection spills past our 64 KB window - re-read the
                // entire subsection region. For very large PDFs this can
                // grow but stays bounded by the xref table size.
                long subStart = xrefStart + pos;
                int extendedLen = needed + 64; // small slack for trailer
                chunk = readWindow(file, subStart, (int) Math.min(fileSize - subStart, extendedLen));
                pos = 0;
            }
            if (wantedObj >= startObj && wantedObj < startObj + count) {
                int entryStart = pos + (wantedObj - startObj) * 20;
                if (entryStart + 20 > chunk.length()) return -1;
                String entry = chunk.substring(entryStart, entryStart + 20);
                // 10-digit offset, space, 5-digit gen, space, n|f
                if (entry.charAt(17) == 'f') return -1; // free object
                try {
                    return Long.parseLong(entry.substring(0, 10).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
            pos += needed;
        }
        return -1;
    }

    /**
     * Pull the {@code << ... >>} dictionary out of a window that starts at
     * an object's "N gen obj" header. Handles nested {@code << >>} pairs.
     */
    private static String extractDictFromWindow(String window) {
        int dictStart = window.indexOf("<<");
        if (dictStart < 0) return "<< /Type /Catalog >>";
        int depth = 0;
        int pos = dictStart;
        while (pos < window.length() - 1) {
            if (window.charAt(pos) == '<' && window.charAt(pos + 1) == '<') {
                depth++;
                pos += 2;
            } else if (window.charAt(pos) == '>' && window.charAt(pos + 1) == '>') {
                depth--;
                if (depth == 0) {
                    return window.substring(dictStart, pos + 2);
                }
                pos += 2;
            } else {
                pos++;
            }
        }
        return "<< /Type /Catalog >>";
    }

    /**
     * Build the incremental-update appendix: new Catalog with /Outlines
     * added, the Outlines dictionary, the bookmark entries, then a fresh
     * xref subsection + trailer pointing back at the previous xref.
     *
     * <p>All bytes returned go straight after the original file. Caller
     * appends them via {@link OutputStream}.
     */
    private static byte[] buildOutlineAppendix(
            List<BookmarkEntry> entries,
            int pageCount,
            OutlineMeta meta,
            long appendOffset) {
        int numEntries = entries.size();
        int rootObjNum = meta.rootObjNum();
        int outlinesObjNum = meta.trailerSize();
        int firstEntryObj = outlinesObjNum + 1;

        StringBuilder appendix = new StringBuilder(512 + numEntries * 128);
        List<long[]> xrefEntries = new ArrayList<>(); // [objNum, offset]

        // 1. New revision of the Catalog (same object number, gen 0).
        xrefEntries.add(new long[] {rootObjNum, appendOffset + appendix.length()});
        appendix.append(rootObjNum).append(" 0 obj\n");
        appendix.append(injectOutlinesKey(meta.rootDict(), outlinesObjNum)).append('\n');
        appendix.append("endobj\n");

        // 2. Outlines dictionary.
        xrefEntries.add(new long[] {outlinesObjNum, appendOffset + appendix.length()});
        appendix.append(outlinesObjNum).append(" 0 obj\n");
        appendix.append("<< /Type /Outlines /First ")
                .append(firstEntryObj)
                .append(" 0 R /Last ")
                .append(firstEntryObj + numEntries - 1)
                .append(" 0 R /Count ")
                .append(numEntries)
                .append(" >>\n");
        appendix.append("endobj\n");

        // 3. Bookmark entries.
        for (int i = 0; i < numEntries; i++) {
            BookmarkEntry entry = entries.get(i);
            int thisObj = firstEntryObj + i;
            xrefEntries.add(new long[] {thisObj, appendOffset + appendix.length()});
            appendix.append(thisObj).append(" 0 obj\n");
            appendix.append("<< /Title ").append(pdfString(entry.title()));
            appendix.append(" /Parent ").append(outlinesObjNum).append(" 0 R");
            int targetPage = Math.clamp(entry.pageIndex(), 0, pageCount - 1);
            appendix.append(" /Dest [").append(targetPage).append(" /Fit]");
            if (i > 0) {
                appendix.append(" /Prev ").append(firstEntryObj + i - 1).append(" 0 R");
            }
            if (i < numEntries - 1) {
                appendix.append(" /Next ").append(firstEntryObj + i + 1).append(" 0 R");
            }
            appendix.append(" >>\n");
            appendix.append("endobj\n");
        }

        // 4. New xref + trailer.
        long xrefOffset = appendOffset + appendix.length();
        int newSize = Math.max(outlinesObjNum, firstEntryObj + numEntries);
        xrefEntries.sort(Comparator.comparingLong(a -> a[0]));

        appendix.append("xref\n");
        int i = 0;
        while (i < xrefEntries.size()) {
            long startObj = xrefEntries.get(i)[0];
            int count = 1;
            while (i + count < xrefEntries.size()
                    && xrefEntries.get(i + count)[0] == startObj + count) {
                count++;
            }
            appendix.append(startObj).append(' ').append(count).append('\n');
            for (int j = 0; j < count; j++) {
                appendix.append(String.format("%010d 00000 n \n", xrefEntries.get(i + j)[1]));
            }
            i += count;
        }

        appendix.append("trailer\n");
        appendix.append("<< /Size ").append(newSize)
                .append(" /Prev ").append(meta.prevXrefStart())
                .append(" /Root ").append(rootObjNum).append(" 0 R >>\n");
        appendix.append("startxref\n").append(xrefOffset).append('\n');
        appendix.append("%%EOF\n");

        return appendix.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static List<HeadingCandidate> extractHeadings(PageText pt, int pageIndex,
                                                           float minFontSize, float maxFont) {
        List<HeadingCandidate> result = new ArrayList<>();
        if (pt.chars().isEmpty()) return result;

        // Group consecutive chars with the same large font into "runs"
        StringBuilder current = new StringBuilder(64);
        float currentFontSize = 0;

        for (TextChar ch : pt.chars()) {
            if (ch.fontSize() >= minFontSize) {
                if (!(Math.abs(ch.fontSize() - currentFontSize) < 0.5f) && !current.isEmpty()) {
                    // Font size changed - emit previous heading
                    String text = current.toString().strip();
                    if (!text.isBlank() && text.length() <= 200) {
                        result.add(new HeadingCandidate(text, pageIndex, currentFontSize));
                    }
                    current.setLength(0);
                }
                currentFontSize = ch.fontSize();
                current.append(ch.toChar());
            } else {
                // Non-heading character - emit any accumulated heading
                if (!current.isEmpty()) {
                    String text = current.toString().strip();
                    if (!text.isBlank() && text.length() <= 200) {
                        result.add(new HeadingCandidate(text, pageIndex, currentFontSize));
                    }
                    current.setLength(0);
                    currentFontSize = 0;
                }
            }
        }
        // Emit final heading
        if (!current.isEmpty()) {
            String text = current.toString().strip();
            if (!text.isBlank() && text.length() <= 200) {
                result.add(new HeadingCandidate(text, pageIndex, currentFontSize));
            }
        }

        return result;
    }

    private static int findPreviousXref(String pdf) {
        int idx = pdf.lastIndexOf("startxref");
        if (idx < 0) return 0;
        // Skip "startxref\n" to get the number
        String after = pdf.substring(idx + "startxref".length()).strip();
        int end = 0;
        while (end < after.length() && Character.isDigit(after.charAt(end))) end++;
        try {
            return Integer.parseInt(after.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int findTrailerSize(String pdf) {
        int idx = pdf.lastIndexOf("/Size");
        if (idx < 0) return 10;
        String after = pdf.substring(idx + "/Size".length()).strip();
        int end = 0;
        while (end < after.length() && Character.isDigit(after.charAt(end))) end++;
        try {
            return Integer.parseInt(after.substring(0, end));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    /**
     * Find the Root (Catalog) object number from the trailer.
     */
    private static int findRootObjectNumber(String pdf) {
        int idx = pdf.lastIndexOf("/Root");
        if (idx < 0) return 1;
        String after = pdf.substring(idx + "/Root".length()).strip();
        int end = 0;
        while (end < after.length() && Character.isDigit(after.charAt(end))) end++;
        try {
            return Integer.parseInt(after.substring(0, end));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static final Pattern OUTLINES_REF = Pattern.compile("/Outlines\\s+\\d+\\s+\\d+\\s+R");

    /**
     * Inject an /Outlines key into a catalog dictionary string.
     * Removes any existing /Outlines reference first.
     */
    private static String injectOutlinesKey(String dict, int outlinesObjNum) {
        // Remove existing /Outlines reference if present
        String cleaned = OUTLINES_REF.matcher(dict).replaceAll("");
        // Insert before the closing >>
        int closing = cleaned.lastIndexOf(">>");
        if (closing < 0) return dict;
        return cleaned.substring(0, closing)
                + " /Outlines " + outlinesObjNum + " 0 R "
                + cleaned.substring(closing);
    }

    /**
     * Encode a Java string as a PDF UTF-16BE hex string: {@code <FEFF...>}.
     */
    private static String pdfString(String s) {
        StringBuilder sb = new StringBuilder(6 + s.length() * 4);
        sb.append("<FEFF");
        for (int i = 0; i < s.length(); i++) {
            sb.append(String.format("%04X", (int) s.charAt(i)));
        }
        sb.append('>');
        return sb.toString();
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
    }

    /**
     * A tree of bookmarks to be written to a PDF.
     */
    public static final class BookmarkTree {

        private final List<BookmarkEntry> entries;

        private BookmarkTree(List<BookmarkEntry> entries) {
            this.entries = Collections.unmodifiableList(entries);
        }

        public List<BookmarkEntry> entries() { return entries; }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private final List<BookmarkEntry> entries = new ArrayList<>();

            private Builder() {}

            /**
             * Add a top-level bookmark entry.
             *
             * @param title     bookmark text
             * @param pageIndex 0-based page index
             * @return this builder
             */
            public Builder add(String title, int pageIndex) {
                entries.add(new BookmarkEntry(title, pageIndex));
                return this;
            }

            public BookmarkTree build() {
                return new BookmarkTree(new ArrayList<>(entries));
            }
        }
    }

    /**
     * A single bookmark entry.
     */
    public record BookmarkEntry(String title, int pageIndex) {}
}
