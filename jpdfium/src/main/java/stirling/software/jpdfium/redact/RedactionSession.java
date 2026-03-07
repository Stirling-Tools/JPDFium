package stirling.software.jpdfium.redact;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.fonts.FontNormalizer;
import stirling.software.jpdfium.model.Rect;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EmbedPDF-style two-phase redaction session.
 *
 * <p>A {@code RedactionSession} holds a PDF document open for the entire
 * session lifetime.  Redactions follow a strict two-phase workflow:
 *
 * <ol>
 *   <li><b>Mark phase</b> — Preview redactions via {@link #markWords} or
 *       {@link #markRegion}.  Matches are counted and stored in memory
 *       but the content stream is NOT modified.</li>
 *   <li><b>Commit phase</b> — Call {@link #commitAll()} or
 *       {@link #commitPage(int)} to apply all pending marks using the
 *       Object Fission Algorithm.  Text and images under each mark
 *       are permanently destroyed.</li>
 * </ol>
 *
 * <p>The document handle is <b>never closed or reloaded</b> between phases.
 * After commit, the same document can receive more marks and commits
 * without any reload.  Use {@link #saveIncremental()} to write only the
 * changed objects, or {@link #save(Path)} for a full rewrite.
 *
 * <h3>Thread Safety</h3>
 * <p>Like all PDFium operations, a session must be confined to one thread.
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * try (RedactionSession session = RedactionSession.open(Path.of("input.pdf"))) {
 *     // Mark phase — zero content mutation
 *     session.markWords(new String[]{"Confidential", "\\d{3}-\\d{2}-\\d{4}"},
 *             0xFF000000, 1.5f, false, true, false);
 *
 *     // Inspect pending marks
 *     System.out.println("Pending: " + session.totalPendingRedactions());
 *
 *     // Commit phase — destructive burn via Object Fission
 *     RedactionSession.CommitResult result = session.commitAll();
 *     System.out.println("Committed: " + result.totalCommitted());
 *
 *     // Save incrementally (only changed objects)
 *     byte[] output = session.saveIncremental();
 *     Files.write(Path.of("output.pdf"), output);
 * }
 * }</pre>
 *
 * @see PdfPage#redactWordsEx
 */
public final class RedactionSession implements AutoCloseable {

    private final PdfDocument document;
    private final boolean ownsDocument;
    private volatile boolean closed = false;

    // Pending marks stored in Java memory (PDFium REDACT annotations don't
    // survive page close/reopen, so we store and replay during commit).
    private final Map<Integer, List<PendingMark>> pendingMarks = new LinkedHashMap<>();

    /** A deferred word-based or region-based redaction mark. */
    sealed interface PendingMark {
        int matchCount();
    }

    record WordMark(String[] words, int argbColor, float padding,
                    boolean wholeWord, boolean useRegex, boolean caseSensitive,
                    int matchCount) implements PendingMark {}

    record RegionMark(Rect rect, int argbColor) implements PendingMark {
        @Override public int matchCount() { return 1; }
    }

    private RedactionSession(PdfDocument document, boolean ownsDocument) {
        this.document = document;
        this.ownsDocument = ownsDocument;
    }

    /** Open a session from a file path. The session owns the document. */
    public static RedactionSession open(Path path) {
        return new RedactionSession(PdfDocument.open(path), true);
    }

    /** Open a session from raw PDF bytes. The session owns the document. */
    public static RedactionSession open(byte[] data) {
        return new RedactionSession(PdfDocument.open(data), true);
    }

    /** Open a session from a password-protected file. The session owns the document. */
    public static RedactionSession open(Path path, String password) {
        return new RedactionSession(PdfDocument.open(path, password), true);
    }

    /**
     * Wrap an existing document in a session. The caller retains ownership;
     * closing the session will NOT close the document.
     */
    public static RedactionSession wrap(PdfDocument document) {
        return new RedactionSession(document, false);
    }

    /** Returns the underlying document (stays alive for the entire session). */
    public PdfDocument document() {
        ensureOpen();
        return document;
    }

    // Font Normalization

    /**
     * Run the full font normalization pipeline on all pages.
     *
     * <p>This repairs broken /ToUnicode maps and /W glyph width tables that cause
     * text extraction failures and missed redactions. Must be called <b>before</b>
     * any mark or commit operations to ensure text extraction is correct.
     *
     * @return normalization statistics
     */
    public FontNormalizer.Result normalizeFonts() {
        ensureOpen();
        return FontNormalizer.normalizeAll(document);
    }

    /**
     * Run font normalization on a single page.
     *
     * @param pageIndex zero-based page index
     * @return normalization statistics for this page
     */
    public FontNormalizer.Result normalizeFontsOnPage(int pageIndex) {
        ensureOpen();
        return FontNormalizer.normalizePage(document, pageIndex);
    }

    // Mark Phase

    /**
     * Mark words/patterns for redaction across ALL pages.
     * Matches are counted per page but the content stream is NOT modified.
     *
     * @param words         words or regex patterns to mark
     * @param argbColor     fill color for redaction boxes (0xAARRGGBB)
     * @param padding       extra padding in PDF points around each match
     * @param wholeWord     if true, only match whole words
     * @param useRegex      if true, treat each word as a regex pattern
     * @param caseSensitive if true, match case-sensitively
     * @return total number of matches found across all pages
     */
    public int markWords(String[] words, int argbColor, float padding,
                          boolean wholeWord, boolean useRegex, boolean caseSensitive) {
        ensureOpen();
        int total = 0;
        int pageCount = document.pageCount();
        for (int i = 0; i < pageCount; i++) {
            int count = markWordsOnPage(i, words, argbColor, padding,
                    wholeWord, useRegex, caseSensitive);
            total += count;
        }
        return total;
    }

    /**
     * Mark words/patterns for redaction on a specific page.
     *
     * @return number of matches found on this page
     */
    public int markWordsOnPage(int pageIndex, String[] words, int argbColor, float padding,
                                boolean wholeWord, boolean useRegex, boolean caseSensitive) {
        ensureOpen();
        // Count matches via a preview pass (text search only, no content modification)
        int count;
        try (PdfPage page = document.page(pageIndex)) {
            count = page.markRedactWords(words, argbColor, padding,
                    wholeWord, useRegex, caseSensitive);
        }
        if (count > 0) {
            pendingMarks.computeIfAbsent(pageIndex, k -> new ArrayList<>())
                    .add(new WordMark(words.clone(), argbColor, padding,
                            wholeWord, useRegex, caseSensitive, count));
        }
        return count;
    }

    /**
     * Mark a specific rectangular region for redaction on a page.
     *
     * @return 0 (the mark index within this page's pending list)
     */
    public int markRegion(int pageIndex, Rect rect, int argbColor) {
        ensureOpen();
        List<PendingMark> marks = pendingMarks.computeIfAbsent(pageIndex, k -> new ArrayList<>());
        marks.add(new RegionMark(rect, argbColor));
        return marks.size() - 1;
    }

    // Query Phase

    /** Total pending match count across all pages. */
    public int totalPendingRedactions() {
        ensureOpen();
        int total = 0;
        for (List<PendingMark> marks : pendingMarks.values()) {
            for (PendingMark m : marks) {
                total += m.matchCount();
            }
        }
        return total;
    }

    /** Pending match count on a specific page. */
    public int pendingRedactionsOnPage(int pageIndex) {
        ensureOpen();
        List<PendingMark> marks = pendingMarks.get(pageIndex);
        if (marks == null) return 0;
        int total = 0;
        for (PendingMark m : marks) {
            total += m.matchCount();
        }
        return total;
    }

    /** Returns page indices that have pending marks. */
    public List<Integer> dirtyPageIndices() {
        ensureOpen();
        List<Integer> result = new ArrayList<>(pendingMarks.keySet());
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    // Undo Phase

    /** Remove a specific pending mark on a page by its index. */
    public void unmark(int pageIndex, int markIndex) {
        ensureOpen();
        List<PendingMark> marks = pendingMarks.get(pageIndex);
        if (marks == null || markIndex < 0 || markIndex >= marks.size()) {
            throw new IllegalStateException("No mark at index " + markIndex + " on page " + pageIndex);
        }
        marks.remove(markIndex);
        if (marks.isEmpty()) pendingMarks.remove(pageIndex);
    }

    /** Remove all pending marks on a specific page. */
    public void clearPage(int pageIndex) {
        ensureOpen();
        pendingMarks.remove(pageIndex);
    }

    /** Remove all pending marks on all pages. */
    public void clearAll() {
        ensureOpen();
        pendingMarks.clear();
    }

    // Commit Phase

    /**
     * Commit all pending marks across all pages.
     * This permanently destroys content under each marked area.
     * The document remains live — no reload required.
     *
     * @return commit statistics per page
     */
    public CommitResult commitAll() {
        return commitAll(0xFF000000, true);
    }

    /**
     * Commit all pending marks with explicit options.
     *
     * @param argbColor     fill color for burned rectangles
     * @param removeContent if true, apply Object Fission;
     *                      if false, paint visual overlay only
     * @return commit statistics per page
     */
    public CommitResult commitAll(int argbColor, boolean removeContent) {
        ensureOpen();
        List<CommitResult.PageCommit> pageCommits = new ArrayList<>();
        for (int pageIndex : new ArrayList<>(pendingMarks.keySet())) {
            int committed = commitPageInternal(pageIndex, argbColor, removeContent);
            if (committed > 0) {
                pageCommits.add(new CommitResult.PageCommit(pageIndex, committed));
            }
        }
        pendingMarks.clear();
        return new CommitResult(pageCommits);
    }

    /**
     * Commit pending marks on a single page.
     *
     * @return number of redactions applied on this page
     */
    public int commitPage(int pageIndex) {
        return commitPage(pageIndex, 0xFF000000, true);
    }

    /**
     * Commit pending marks on a single page with explicit options.
     */
    public int commitPage(int pageIndex, int argbColor, boolean removeContent) {
        ensureOpen();
        int committed = commitPageInternal(pageIndex, argbColor, removeContent);
        pendingMarks.remove(pageIndex);
        return committed;
    }

    private int commitPageInternal(int pageIndex, int argbColor, boolean removeContent) {
        List<PendingMark> marks = pendingMarks.get(pageIndex);
        if (marks == null || marks.isEmpty()) return 0;

        int totalCommitted = 0;
        try (PdfPage page = document.page(pageIndex)) {
            for (PendingMark mark : marks) {
                switch (mark) {
                    case WordMark wm -> {
                        int committed = page.redactWordsEx(wm.words, argbColor,
                                wm.padding, wm.wholeWord, wm.useRegex,
                                removeContent, wm.caseSensitive);
                        totalCommitted += committed;
                    }
                    case RegionMark rm -> {
                        page.redactRegion(rm.rect, argbColor, removeContent);
                        totalCommitted += 1;
                    }
                }
            }
            page.flatten();
        }
        return totalCommitted;
    }

    // Save

    /** Full save to file. Document remains valid. */
    public void save(Path path) {
        ensureOpen();
        document.save(path);
    }

    /** Full save to bytes. Document remains valid. */
    public byte[] saveBytes() {
        ensureOpen();
        return document.saveBytes();
    }

    /**
     * Incremental save: writes only changed objects.
     * The document remains valid for further mark/commit cycles.
     */
    public byte[] saveIncremental() {
        ensureOpen();
        return document.saveBytesIncremental();
    }

    // Lifecycle

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("RedactionSession is already closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        pendingMarks.clear();
        if (ownsDocument) document.close();
    }

    /**
     * Result of a {@link #commitAll()} operation.
     */
    public record CommitResult(List<PageCommit> pageCommits) {

        /** Total redactions applied across all pages. */
        public int totalCommitted() {
            return pageCommits.stream().mapToInt(PageCommit::committed).sum();
        }

        /** Number of pages that had redactions applied. */
        public int pagesAffected() {
            return pageCommits.size();
        }

        public record PageCommit(int pageIndex, int committed) {}
    }
}
