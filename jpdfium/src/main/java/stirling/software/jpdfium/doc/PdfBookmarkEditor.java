package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;
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
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Create and set bookmarks (outlines) in a PDF document.
 *
 * <p>Constructs and appends an incremental update appendix containing the Outline hierarchy
 * and modified Catalog dictionary directly on the destination file or byte array.
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
 *     PdfBookmarkEditor.setBookmarks(doc, tree, Path.of("bookmarked.pdf"));
 * }
 * }</pre>
 */
public final class PdfBookmarkEditor {

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern OUTLINES_REF_PATTERN = Pattern.compile("/Outlines\\s+\\d+\\s+\\d+\\s+R");

    private PdfBookmarkEditor() {}

    /**
     * Set the bookmark tree for a document, writing the result to {@code output}.
     *
     * @param doc       the source document
     * @param tree      the bookmark tree to set
     * @param output    destination file (overwritten if it exists)
     */
    public static void setBookmarks(PdfDocument doc, BookmarkTree tree, Path output) throws IOException {
        setBookmarks(doc, tree.bookmarks(), output);
    }

    /**
     * Set the bookmark tree for a document from a list of bookmarks (supporting nested children),
     * writing the result to {@code output}.
     *
     * @param doc       the source document
     * @param bookmarks the list of bookmarks
     * @param output    destination file (overwritten if it exists)
     */
    public static void setBookmarks(PdfDocument doc, List<Bookmark> bookmarks, Path output) throws IOException {
        doc.save(output);

        if (bookmarks == null || bookmarks.isEmpty()) {
            return;
        }

        OutlineMetadata metadata = readOutlineMetadata(output);
        long appendOffset = Files.size(output);
        byte[] appendix = buildOutlineAppendix(bookmarks, doc.pageCount(), metadata, appendOffset);

        if (appendix.length == 0) return;

        try (OutputStream outputStream = Files.newOutputStream(output, StandardOpenOption.APPEND)) {
            outputStream.write(appendix);
        }
    }

    /**
     * Set the bookmark tree for a document and return the resulting bytes.
     *
     * @param doc  the source document
     * @param tree the bookmark tree to set
     * @return PDF bytes of the document with bookmarks
     */
    public static byte[] setBookmarks(PdfDocument doc, BookmarkTree tree) {
        return setBookmarks(doc, tree.bookmarks());
    }

    /**
     * Set the bookmark tree on raw PDF bytes and return the updated PDF bytes.
     *
     * @param pdfBytes input PDF bytes
     * @param tree     the bookmark tree to set
     * @return updated PDF bytes with bookmarks
     */
    public static byte[] setBookmarks(byte[] pdfBytes, BookmarkTree tree) {
        return setBookmarks(pdfBytes, tree.bookmarks());
    }

    /**
     * Set the bookmark tree for a document from a list of bookmarks and return the resulting bytes.
     *
     * @param doc       the source document
     * @param bookmarks the list of bookmarks
     * @return PDF bytes of the document with bookmarks
     */
    public static byte[] setBookmarks(PdfDocument doc, List<Bookmark> bookmarks) {
        Path tempPdf = null;
        try {
            tempPdf = Files.createTempFile("jpdfium-bookmarks-", ".pdf");
            setBookmarks(doc, bookmarks, tempPdf);
            return Files.readAllBytes(tempPdf);
        } catch (IOException e) {
            throw new JPDFiumException("Failed to write temporary PDF with bookmarks", e);
        } finally {
            deleteQuietly(tempPdf);
        }
    }

    /**
     * Set the bookmark tree on raw PDF bytes and return the updated PDF bytes.
     *
     * @param pdfBytes  input PDF bytes
     * @param bookmarks the list of bookmarks
     * @return updated PDF bytes with bookmarks
     */
    public static byte[] setBookmarks(byte[] pdfBytes, List<Bookmark> bookmarks) {
        if (pdfBytes == null || pdfBytes.length == 0 || bookmarks == null || bookmarks.isEmpty()) {
            return pdfBytes;
        }
        Path tempPdf = null;
        try {
            tempPdf = Files.createTempFile("jpdfium-bookmarks-bytes-", ".pdf");
            Files.write(tempPdf, pdfBytes);
            OutlineMetadata metadata = readOutlineMetadata(tempPdf);
            long appendOffset = Files.size(tempPdf);
            int pageCount;
            try (PdfDocument openDoc = PdfDocument.open(tempPdf)) {
                pageCount = openDoc.pageCount();
            }
            byte[] appendix = buildOutlineAppendix(bookmarks, pageCount, metadata, appendOffset);
            if (appendix.length == 0) return pdfBytes;
            try (OutputStream outputStream = Files.newOutputStream(tempPdf, StandardOpenOption.APPEND)) {
                outputStream.write(appendix);
            }
            return Files.readAllBytes(tempPdf);
        } catch (IOException e) {
            throw new JPDFiumException("Failed to set bookmarks on PDF bytes", e);
        } finally {
            deleteQuietly(tempPdf);
        }
    }

    /**
     * Auto-generate bookmarks from text headings using font-size heuristics.
     *
     * @param doc         the document to analyze
     * @param minFontSize minimum font size to consider as a heading
     * @return auto-generated bookmark tree
     */
    public static BookmarkTree fromHeadings(PdfDocument doc, float minFontSize) {
        BookmarkTree.Builder builder = BookmarkTree.builder();
        float maxFontSeen = 0;

        for (int i = 0; i < doc.pageCount(); i++) {
            try {
                PageText pageText = PdfTextExtractor.extractPage(doc, i);
                for (TextChar textChar : pageText.chars()) {
                    if (textChar.fontSize() > maxFontSeen) {
                        maxFontSeen = textChar.fontSize();
                    }
                }
            } catch (JPDFiumException | IllegalStateException _) {
                // Skip unparseable pages during heading scan
            }
        }

        if (maxFontSeen <= 0) return builder.build();

        for (int i = 0; i < doc.pageCount(); i++) {
            try {
                PageText pageText = PdfTextExtractor.extractPage(doc, i);
                List<HeadingCandidate> headings = extractHeadings(pageText, i, minFontSize);
                for (HeadingCandidate heading : headings) {
                    builder.add(heading.text(), heading.pageIndex());
                }
            } catch (JPDFiumException | IllegalStateException _) {
                // Skip unparseable pages during heading scan
            }
        }

        return builder.build();
    }

    public static BookmarkTree fromHeadings(PdfDocument doc) {
        return fromHeadings(doc, 14.0f);
    }

    private record HeadingCandidate(String text, int pageIndex, float fontSize) {}

    private record OutlineMetadata(int prevXrefStart, int trailerSize, int rootObjNum, String rootDict) {}

    private static OutlineMetadata readOutlineMetadata(Path file) throws IOException {
        long fileSize = Files.size(file);
        int tailLen = (int) Math.min(fileSize, 8192L);
        String tail = readWindow(file, fileSize - tailLen, tailLen);

        int prevXrefStart = findPreviousXref(tail);
        int trailerSize = findTrailerSize(tail);
        int rootObjNum = findRootObjectNumber(tail);

        long rootOffset = readXrefEntryOffset(file, prevXrefStart, rootObjNum);
        String rootDict;
        if (rootOffset < 0) {
            rootDict = "<< /Type /Catalog >>";
        } else {
            String objWindow = readWindow(file, rootOffset, (int) Math.min(fileSize - rootOffset, 8192L));
            rootDict = extractDictFromWindow(objWindow);
        }
        return new OutlineMetadata(prevXrefStart, trailerSize, rootObjNum, rootDict);
    }

    private static String readWindow(Path file, long offset, int len) throws IOException {
        byte[] buf = new byte[len];
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(offset);
            ByteBuffer byteBuffer = ByteBuffer.wrap(buf);
            while (byteBuffer.hasRemaining() && channel.read(byteBuffer) > 0) {
                // Drain until buffer filled or EOF reached
            }
        }
        return new String(buf, StandardCharsets.ISO_8859_1);
    }

    private static long readXrefEntryOffset(Path file, int xrefStart, int wantedObj) throws IOException {
        long fileSize = Files.size(file);
        if (xrefStart <= 0 || xrefStart >= fileSize) return -1;

        int firstChunkLen = (int) Math.min(fileSize - xrefStart, 65536L);
        String chunk = readWindow(file, xrefStart, firstChunkLen);
        if (!chunk.startsWith("xref")) {
            return -1;
        }

        int pos = 4;
        while (pos < chunk.length() && (chunk.charAt(pos) == '\r' || chunk.charAt(pos) == '\n')) {
            pos++;
        }

        while (pos < chunk.length()) {
            if (chunk.startsWith("trailer", pos)) {
                break;
            }
            int eol = chunk.indexOf('\n', pos);
            if (eol < 0) break;
            String header = chunk.substring(pos, eol).trim();
            String[] parts = WHITESPACE_PATTERN.split(header);
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
            int needed = count * 20;
            if (pos + needed > chunk.length()) {
                long subStart = xrefStart + pos;
                int extendedLen = needed + 64;
                chunk = readWindow(file, subStart, (int) Math.min(fileSize - subStart, extendedLen));
                pos = 0;
            }
            if (wantedObj >= startObj && wantedObj < startObj + count) {
                int entryStart = pos + (wantedObj - startObj) * 20;
                if (entryStart + 20 > chunk.length()) return -1;
                String entry = chunk.substring(entryStart, entryStart + 20);
                if (entry.charAt(17) == 'f') return -1;
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

    private static final class OutlineNode {
        final String title;
        final int pageIndex;
        final int objNum;
        int parentObjNum;
        int prevObjNum = -1;
        int nextObjNum = -1;
        int firstChildObjNum = -1;
        int lastChildObjNum = -1;
        int count = 0;
        final ActionType actionType;
        final String uri;

        OutlineNode(String title, int pageIndex, int objNum, ActionType actionType, String uri) {
            this.title = title;
            this.pageIndex = pageIndex;
            this.objNum = objNum;
            this.actionType = actionType != null ? actionType : ActionType.GOTO;
            this.uri = uri;
        }
    }

    private static byte[] buildOutlineAppendix(
            List<Bookmark> bookmarks,
            int pageCount,
            OutlineMetadata metadata,
            long appendOffset) {
        if (bookmarks == null || bookmarks.isEmpty()) {
            return new byte[0];
        }

        int rootObjNum = metadata.rootObjNum();
        int outlinesObjNum = metadata.trailerSize();
        int firstEntryObj = outlinesObjNum + 1;

        List<OutlineNode> allNodes = new ArrayList<>();
        assignOutlineNodes(bookmarks, firstEntryObj, allNodes, outlinesObjNum);

        if (allNodes.isEmpty()) return new byte[0];

        List<OutlineNode> topLevel = new ArrayList<>();
        for (OutlineNode node : allNodes) {
            if (node.parentObjNum == outlinesObjNum) {
                topLevel.add(node);
            }
        }

        if (topLevel.isEmpty()) return new byte[0];

        StringBuilder appendix = new StringBuilder(512 + allNodes.size() * 160);
        List<long[]> xrefEntries = new ArrayList<>();

        writeCatalogRevision(appendix, xrefEntries, rootObjNum, metadata.rootDict(), outlinesObjNum, appendOffset);
        writeOutlinesDictionary(appendix, xrefEntries, outlinesObjNum, topLevel, allNodes.size(), appendOffset);
        writeOutlineNodes(appendix, xrefEntries, allNodes, pageCount, appendOffset);
        writeXrefAndTrailer(appendix, xrefEntries, metadata, outlinesObjNum, allNodes, appendOffset);

        return appendix.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void writeCatalogRevision(
            StringBuilder appendix,
            List<long[]> xrefEntries,
            int rootObjNum,
            String rootDict,
            int outlinesObjNum,
            long appendOffset) {
        xrefEntries.add(new long[] {rootObjNum, appendOffset + appendix.length()});
        appendix.append(rootObjNum).append(" 0 obj\n");
        appendix.append(injectOutlinesKey(rootDict, outlinesObjNum)).append('\n');
        appendix.append("endobj\n");
    }

    private static void writeOutlinesDictionary(
            StringBuilder appendix,
            List<long[]> xrefEntries,
            int outlinesObjNum,
            List<OutlineNode> topLevel,
            int totalCount,
            long appendOffset) {
        int firstTop = topLevel.get(0).objNum;
        int lastTop = topLevel.get(topLevel.size() - 1).objNum;

        xrefEntries.add(new long[] {outlinesObjNum, appendOffset + appendix.length()});
        appendix.append(outlinesObjNum).append(" 0 obj\n");
        appendix.append("<< /Type /Outlines /First ")
                .append(firstTop)
                .append(" 0 R /Last ")
                .append(lastTop)
                .append(" 0 R /Count ")
                .append(totalCount)
                .append(" >>\n");
        appendix.append("endobj\n");
    }

    private static void writeOutlineNodes(
            StringBuilder appendix,
            List<long[]> xrefEntries,
            List<OutlineNode> allNodes,
            int pageCount,
            long appendOffset) {
        for (OutlineNode node : allNodes) {
            xrefEntries.add(new long[] {node.objNum, appendOffset + appendix.length()});
            appendix.append(node.objNum).append(" 0 obj\n");
            appendix.append("<< /Title ").append(pdfString(node.title));
            appendix.append(" /Parent ").append(node.parentObjNum).append(" 0 R");
            if (node.prevObjNum > 0) {
                appendix.append(" /Prev ").append(node.prevObjNum).append(" 0 R");
            }
            if (node.nextObjNum > 0) {
                appendix.append(" /Next ").append(node.nextObjNum).append(" 0 R");
            }
            if (node.firstChildObjNum > 0) {
                appendix.append(" /First ").append(node.firstChildObjNum).append(" 0 R");
                appendix.append(" /Last ").append(node.lastChildObjNum).append(" 0 R");
                appendix.append(" /Count ").append(node.count);
            }
            if (node.actionType == ActionType.URI && node.uri != null) {
                appendix.append(" /A << /Type /Action /S /URI /URI ").append(pdfString(node.uri)).append(" >>");
            } else if (node.pageIndex >= 0) {
                int targetPage = Math.clamp(node.pageIndex, 0, pageCount - 1);
                appendix.append(" /Dest [").append(targetPage).append(" /Fit]");
            }
            appendix.append(" >>\n");
            appendix.append("endobj\n");
        }
    }

    private static void writeXrefAndTrailer(
            StringBuilder appendix,
            List<long[]> xrefEntries,
            OutlineMetadata metadata,
            int outlinesObjNum,
            List<OutlineNode> allNodes,
            long appendOffset) {
        long xrefOffset = appendOffset + appendix.length();
        int maxObjNum = outlinesObjNum;
        for (OutlineNode node : allNodes) {
            if (node.objNum > maxObjNum) maxObjNum = node.objNum;
        }
        int newSize = maxObjNum + 1;
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
                .append(" /Prev ").append(metadata.prevXrefStart())
                .append(" /Root ").append(metadata.rootObjNum()).append(" 0 R >>\n");
        appendix.append("startxref\n").append(xrefOffset).append('\n');
        appendix.append("%%EOF\n");
    }

    private static int assignOutlineNodes(
            List<Bookmark> bookmarks,
            int startObjNum,
            List<OutlineNode> allNodes,
            int parentObjNum) {
        int currentObjNum = startObjNum;
        List<OutlineNode> currentLevel = new ArrayList<>(bookmarks.size());
        for (Bookmark bookmark : bookmarks) {
            OutlineNode node = new OutlineNode(
                    bookmark.title(),
                    bookmark.pageIndex(),
                    currentObjNum++,
                    bookmark.actionType(),
                    bookmark.uri().orElse(null)
            );
            node.parentObjNum = parentObjNum;
            currentLevel.add(node);
            allNodes.add(node);
        }

        for (int i = 0; i < currentLevel.size(); i++) {
            OutlineNode node = currentLevel.get(i);
            if (i > 0) {
                node.prevObjNum = currentLevel.get(i - 1).objNum;
            }
            if (i < currentLevel.size() - 1) {
                node.nextObjNum = currentLevel.get(i + 1).objNum;
            }
        }

        for (int i = 0; i < bookmarks.size(); i++) {
            Bookmark bookmark = bookmarks.get(i);
            OutlineNode node = currentLevel.get(i);
            if (bookmark.hasChildren()) {
                int childStartObj = currentObjNum;
                currentObjNum = assignOutlineNodes(bookmark.children(), childStartObj, allNodes, node.objNum);

                List<OutlineNode> directChildren = new ArrayList<>();
                for (OutlineNode candidate : allNodes) {
                    if (candidate.parentObjNum == node.objNum) {
                        directChildren.add(candidate);
                    }
                }
                if (!directChildren.isEmpty()) {
                    node.firstChildObjNum = directChildren.get(0).objNum;
                    node.lastChildObjNum = directChildren.get(directChildren.size() - 1).objNum;
                    node.count = countDescendants(bookmark.children());
                }
            }
        }
        return currentObjNum;
    }

    private static int countDescendants(List<Bookmark> list) {
        int count = list.size();
        for (Bookmark bookmark : list) {
            if (bookmark.hasChildren()) {
                count += countDescendants(bookmark.children());
            }
        }
        return count;
    }

    private static List<HeadingCandidate> extractHeadings(PageText pageText, int pageIndex, float minFontSize) {
        List<HeadingCandidate> result = new ArrayList<>();
        if (pageText.chars().isEmpty()) return result;

        StringBuilder currentRun = new StringBuilder(64);
        float currentFontSize = 0;

        for (TextChar textChar : pageText.chars()) {
            if (textChar.fontSize() >= minFontSize) {
                if (!(Math.abs(textChar.fontSize() - currentFontSize) < 0.5f) && !currentRun.isEmpty()) {
                    String text = currentRun.toString().strip();
                    if (!text.isBlank() && text.length() <= 200) {
                        result.add(new HeadingCandidate(text, pageIndex, currentFontSize));
                    }
                    currentRun.setLength(0);
                }
                currentFontSize = textChar.fontSize();
                currentRun.append(textChar.toChar());
            } else {
                if (!currentRun.isEmpty()) {
                    String text = currentRun.toString().strip();
                    if (!text.isBlank() && text.length() <= 200) {
                        result.add(new HeadingCandidate(text, pageIndex, currentFontSize));
                    }
                    currentRun.setLength(0);
                    currentFontSize = 0;
                }
            }
        }
        if (!currentRun.isEmpty()) {
            String text = currentRun.toString().strip();
            if (!text.isBlank() && text.length() <= 200) {
                result.add(new HeadingCandidate(text, pageIndex, currentFontSize));
            }
        }

        return result;
    }

    private static int findPreviousXref(String pdf) {
        int idx = pdf.lastIndexOf("startxref");
        if (idx < 0) return 0;
        String after = pdf.substring(idx + "startxref".length()).strip();
        int end = 0;
        while (end < after.length() && Character.isDigit(after.charAt(end))) end++;
        try {
            return Integer.parseInt(after.substring(0, end));
        } catch (NumberFormatException _) {
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
        } catch (NumberFormatException _) {
            return 10;
        }
    }

    private static int findRootObjectNumber(String pdf) {
        int idx = pdf.lastIndexOf("/Root");
        if (idx < 0) return 1;
        String after = pdf.substring(idx + "/Root".length()).strip();
        int end = 0;
        while (end < after.length() && Character.isDigit(after.charAt(end))) end++;
        try {
            return Integer.parseInt(after.substring(0, end));
        } catch (NumberFormatException _) {
            return 1;
        }
    }

    private static String injectOutlinesKey(String catalogDict, int outlinesObjNum) {
        String cleaned = OUTLINES_REF_PATTERN.matcher(catalogDict).replaceAll("");
        int closing = cleaned.lastIndexOf(">>");
        if (closing < 0) return catalogDict;
        return cleaned.substring(0, closing)
                + " /Outlines " + outlinesObjNum + " 0 R "
                + cleaned.substring(closing);
    }

    private static String pdfString(String str) {
        StringBuilder sb = new StringBuilder(6 + str.length() * 4);
        sb.append("<FEFF");
        for (int i = 0; i < str.length(); i++) {
            sb.append(String.format("%04X", (int) str.charAt(i)));
        }
        sb.append('>');
        return sb.toString();
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) {}
        }
    }

    /**
     * An entry in a bookmark tree.
     *
     * @param title     bookmark title
     * @param pageIndex target page index (0-based)
     */
    public record BookmarkEntry(String title, int pageIndex) {}

    /**
     * A tree of bookmarks to be written to a PDF.
     */
    public static final class BookmarkTree {

        private final List<Bookmark> bookmarks;
        private final List<BookmarkEntry> entries;

        private BookmarkTree(List<Bookmark> bookmarks) {
            this.bookmarks = Collections.unmodifiableList(bookmarks);
            List<BookmarkEntry> flat = new ArrayList<>();
            for (Bookmark b : bookmarks) {
                flat.add(new BookmarkEntry(b.title(), b.pageIndex()));
            }
            this.entries = Collections.unmodifiableList(flat);
        }

        public List<BookmarkEntry> entries() { return entries; }

        public List<Bookmark> bookmarks() { return bookmarks; }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private final List<Bookmark> roots = new ArrayList<>();
            private final List<List<Bookmark>> stack = new ArrayList<>();

            private Builder() {
                stack.add(roots);
            }

            public Builder add(String title, int pageIndex) {
                Bookmark bookmark = new Bookmark(title, pageIndex, new ArrayList<>(), ActionType.GOTO, Optional.empty(), Optional.empty());
                roots.add(bookmark);
                stack.clear();
                stack.add(roots);
                return this;
            }

            public Builder addChild(String title, int pageIndex) {
                List<Bookmark> currentLevel = stack.get(stack.size() - 1);
                if (currentLevel.isEmpty()) {
                    return add(title, pageIndex);
                }
                Bookmark parent = currentLevel.get(currentLevel.size() - 1);
                Bookmark child = new Bookmark(title, pageIndex, new ArrayList<>(), ActionType.GOTO, Optional.empty(), Optional.empty());
                parent.children().add(child);
                stack.add(parent.children());
                return this;
            }

            public Builder parent() {
                if (stack.size() > 1) {
                    stack.remove(stack.size() - 1);
                }
                return this;
            }

            public BookmarkTree build() {
                return new BookmarkTree(new ArrayList<>(roots));
            }
        }
    }
}
