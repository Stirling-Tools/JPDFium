package stirling.software.jpdfium.doc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.tukaani.xz.XZInputStream;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.QpdfLib;

/**
 * Embed font programs into a PDF in one qpdf rewrite.
 *
 * <p>{@link #embed} takes programs keyed by exact {@code /BaseFont} (subset prefix included)
 * and embeds each into every matching font, replacing a program already there. TrueType,
 * OpenType, bare CFF and Type 1 (PFB or PFA) programs are accepted; a collection is narrowed to
 * the face named by the key, else its first face.
 *
 * <p>{@code embedMissing} finds the non-embedded fonts in use, looks each up by PostScript or
 * full name in the bundled fallback set and then the given font directories (the system ones
 * when none are given, indexed once per list), falls back to a metric-compatible substitute for
 * the standard 14 and their Microsoft equivalents, and never picks a font whose {@code fsType}
 * forbids embedding. Its result lists the fonts embedded, those still missing, and one
 * {@link FontStatus} per requested name with the selected source and {@code fsType}.
 *
 * <p>A CID font with a predefined CMap or a CID-to-GID stream, and a legacy non-ASCII font name
 * without {@code /ToUnicode}, are reported instead of substituted because the original
 * code-to-glyph mapping cannot be preserved. That keeps extracted text and drawn glyphs stable.
 *
 * <p>A font without a descriptor or {@code /Widths} gains them from the program, and the
 * subtype follows the program's outlines with the code-to-glyph mapping pinned by an explicit
 * encoding and {@code /ToUnicode}; existing widths are kept, so text never moves. A simple
 * non-symbolic font backed by a TrueType or OpenType program that permits subsetting is
 * embedded as a subset of the glyphs its codes can reach; Type 1 and bare CFF programs are
 * embedded whole. Signed
 * documents are refused because the rewrite invalidates their signatures, and a CIDFont keeps
 * its CID-to-glyph mapping, so its program must use the original glyph order.
 */
public final class PdfFontEmbedder {

    /**
     * The rewritten document, the fonts embedded into it, the fonts in use that are still not
     * embedded as {@link PdfFontAuditor} reports them on the output, and one
     * {@link FontStatus} per requested name.
     */
    public record Result(
            byte[] pdf, List<String> embedded, List<String> unresolved, List<FontStatus> statuses) {
        public Result(byte[] pdf, List<String> embedded, List<String> unresolved) {
            this(pdf, embedded, unresolved, List.of());
        }
    }

    /** Diagnostic result for one requested font name. */
    public record FontStatus(
            String baseName, String source, int fsType, boolean resolved, String reason) {}

    /** Outcome of the file-backed embedding pass. */
    public record FileResult(List<String> embedded, List<String> unresolved) {}

    record Face(
            Path path,
            String resource,
            int offset,
            long size,
            long mtime,
            boolean embeddable,
            int fsType) {
        Face(Path path, int offset, long size, long mtime) {
            this(path, null, offset, size, mtime, true, -1);
        }

        Face(Path path, String resource, int offset, long size, boolean embeddable) {
            this(path, resource, offset, size, 0, embeddable, -1);
        }

        Face(
                Path path,
                String resource,
                int offset,
                long size,
                boolean embeddable,
                int fsType) {
            this(path, resource, offset, size, 0, embeddable, fsType);
        }
    }

    private static final int TTCF = 0x74746366;
    private static final Pattern TYPE1_NAME = Pattern.compile("/FontName\\s*/(\\S+)");
    private static final List<List<String>> METRIC_COMPATIBLE = List.of(
            List.of("liberationsans", "helvetica", "arial", "nimbussans", "nimbussanl", "arimo", "freesans"),
            List.of("liberationserif", "times", "timesnewroman", "nimbusroman", "nimbusromno9l", "tinos",
                    "freeserif"),
            List.of("liberationmono", "courier", "couriernew", "nimbusmono", "nimbusmonl", "cousine", "freemono"),
            List.of("symbol", "standardsymbols", "standardsymbolsps", "standardsyml"),
            List.of("zapfdingbats", "zapfdingbatsitc", "dingbats", "d050000l"),
            List.of("carlito", "calibri"),
            List.of("caladea", "cambria"),
            List.of("dejavusans", "verdana", "tahoma", "centurygothic"),
            List.of("dejavuserif", "bookantiqua"),
            List.of("liberationsans", "myriadpro", "franklingothic", "optima"),
            List.of("liberationserif", "minionpro"),
            List.of("notosanssc", "simsun", "stsong", "stsongtiscblack", "pmingliu",
                    "msungstdlightacro", "simsunextb", "songti"),
            List.of("notosanssc", "heiseiminw3", "ryuminlight", "kozminpr6n", "dejimamincho"),
            List.of("notosanssc", "xiaobiaosong"),
            List.of("notosanssc", "ºúìå", "ëîìå", "âëîgb2312", "âëî"),
            List.of("notosanssc", "arialunicode"),
            List.of("notosansarabic", "traditionalarabic"),
            List.of("liberationsans", "swiss742"),
            List.of("notosanssc", "gonotocurrent"));
    private static final String FONT_MANIFEST = "/fonts/manifest.tsv";
    private static final String FONT_RESOURCE_ROOT = "/fonts/";
    private static volatile Map<String, Face> BUNDLED_FACES;
    private static final List<Path> SYSTEM_FONT_DIRS = Stream.of(
                    "/usr/share/fonts", "/usr/local/share/fonts", "/Library/Fonts",
                    "/System/Library/Fonts", System.getProperty("user.home") + "/Library/Fonts",
                    System.getProperty("user.home") + "/.fonts",
                    System.getProperty("user.home") + "/.local/share/fonts",
                    System.getenv("WINDIR") == null ? null : System.getenv("WINDIR") + "\\Fonts",
                    System.getenv("LOCALAPPDATA") == null ? null
                            : System.getenv("LOCALAPPDATA") + "\\Microsoft\\Windows\\Fonts")
            .filter(Objects::nonNull).map(Path::of).toList();
    private static final int MAX_INDEXES = 16;
    private static final Map<List<Path>, Map<String, Face>> INDEXES =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(MAX_INDEXES, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<List<Path>, Map<String, Face>> eldest) {
                            return size() > MAX_INDEXES;
                        }
                    });
    private static final int MAX_PROGRAMS = 32;
    private static final long MAX_PROGRAM_BYTES = 64L * 1024 * 1024;
    private static long programBytes;
    /** Extracted face data, reused across documents instead of re-reading a TTC per call. */
    private static final Map<Face, byte[]> PROGRAMS = new LinkedHashMap<>(MAX_PROGRAMS, 0.75f, true);

    private PdfFontEmbedder() {}

    /** True when this native build can embed font programs. */
    public static boolean isAvailable() {
        return QpdfLib.isFontReplacementSupported();
    }

    /**
     * Embed font programs keyed by exact {@code /BaseFont}.
     *
     * @throws JPDFiumException when no font matches, a program is unusable, or the document
     *                          is signed or password protected
     */
    public static byte[] embed(byte[] pdf, Map<String, byte[]> fonts) {
        if (pdf == null || pdf.length == 0) throw new IllegalArgumentException("pdf must not be empty");
        if (fonts == null || fonts.isEmpty()) return pdf;
        Map<String, byte[]> normalized = new LinkedHashMap<>();
        Map<String, byte[]> originals = new HashMap<>();
        for (var entry : fonts.entrySet()) {
            String rawName = entry.getKey();
            String name = rawName == null ? "" : rawName.trim().replaceFirst("^/", "");
            byte[] data = entry.getValue();
            if (name.isBlank() || (rawName != null && rawName.indexOf('\0') >= 0)
                    || data == null || data.length == 0) {
                throw new IllegalArgumentException("font name and data must not be empty");
            }
            byte[] previous = originals.putIfAbsent(name, data);
            if (previous != null) {
                if (!Arrays.equals(previous, data)) {
                    throw new IllegalArgumentException("conflicting font data for /BaseFont " + name);
                }
                continue;
            }
            normalized.put(name, singleFace(data, name));
        }
        byte[] out = QpdfLib.embedFonts(pdf, normalized, false);
        if (out == null) throw new JPDFiumException("no font matching /BaseFont " + normalized.keySet());
        return out;
    }

    /** File-backed variant of {@link #embed(byte[], Map)}. */
    public static byte[] embed(Path input, Map<String, byte[]> fonts) throws IOException {
        return embed(Files.readAllBytes(input), fonts);
    }

    /** Alias of {@link #embed(byte[], Map)}. */
    public static byte[] replace(byte[] pdf, Map<String, byte[]> fonts) {
        return embed(pdf, fonts);
    }

    /** Alias of {@link #embed(Path, Map)}. */
    public static byte[] replace(Path input, Map<String, byte[]> fonts) throws IOException {
        return embed(input, fonts);
    }

    /**
     * Embed every non-embedded font resource. The bundled fallback set is tried first, then
     * {@code fontDirs} searched recursively and indexed once per distinct list; the system font
     * directories when none are given.
     */
    public static Result embedMissing(byte[] pdf, Path... fontDirs) throws IOException {
        List<PdfFontAuditor.FontResource> resources = PdfFontAuditor.unembeddedResources(pdf);
        Set<String> names = new LinkedHashSet<>();
        Map<String, Boolean> substitutable = new HashMap<>();
        for (PdfFontAuditor.FontResource resource : resources) {
            names.add(resource.baseName());
            substitutable.merge(resource.baseName(), safeToSubstitute(resource), Boolean::logicalAnd);
        }
        List<String> missing = List.copyOf(names);
        if (missing.isEmpty()) return new Result(pdf, List.of(), List.of(), List.of());
        Map<String, Face> bundled = fontDirs.length == 0 ? bundledFaces() : null;
        Map<String, Face> index = null;
        Map<String, byte[]> found = new LinkedHashMap<>();
        List<FontStatus> statuses = new ArrayList<>(missing.size());
        for (String name : missing) {
            if (!substitutable.getOrDefault(name, true)) {
                statuses.add(new FontStatus(name, "", -1, false,
                        "CID encoding or glyph order cannot be verified safely"));
                continue;
            }
            String key = key(name);
            Face face = bundled == null ? null : resolve(bundled, key);
            if (face == null) {
                if (index == null) index = indexFor(fontDirs);
                face = resolve(index, key);
            }
            if (face == null) {
                if (index == null) index = indexFor(fontDirs);
                Face restricted = restricted(index, key);
                statuses.add(restricted == null
                        ? new FontStatus(name, "", -1, false, "no matching font")
                        : new FontStatus(name, source(restricted), restricted.fsType(), false,
                                "embedding restricted by fsType " + restricted.fsType()));
                continue;
            }
            byte[] data = program(face);
            int fsType = programFsType(face, data);
            if (!embeddableProgram(fsType)) {
                statuses.add(new FontStatus(name, source(face), fsType, false,
                        "embedding restricted by fsType " + fsType));
                continue;
            }
            found.put(name, data);
            statuses.add(new FontStatus(name, source(face), fsType, true, "embedded"));
        }
        byte[] out = found.isEmpty() ? null : QpdfLib.embedFonts(pdf, found, true);
        if (out == null) {
            List<FontStatus> failed = new ArrayList<>(statuses.size());
            for (FontStatus status : statuses) {
                failed.add(status.resolved()
                        ? new FontStatus(status.baseName(), status.source(), status.fsType(), false,
                                "native embedder left the font unchanged (glyph order or width coverage)")
                        : status);
            }
            return new Result(pdf, List.of(), missing, failed);
        }
        List<String> unresolved = PdfFontAuditor.unembeddedNames(out);
        List<FontStatus> finalStatuses = new ArrayList<>(statuses.size());
        for (FontStatus status : statuses) {
            finalStatuses.add(status.resolved() && unresolved.contains(status.baseName())
                    ? new FontStatus(status.baseName(), status.source(), status.fsType(), false,
                            "native embedder left the font unchanged (glyph order or width coverage)")
                    : status);
        }
        return new Result(out, missing.stream().filter(n -> !unresolved.contains(n)).toList(),
                unresolved, finalStatuses);
    }

    /**
     * The native bridge can rewrite {@code /CIDToGIDMap} and {@code /ToUnicode} only for
     * Identity-encoded CID fonts. A predefined CMap or an existing CID-to-GID stream carries
     * an ordering that a substitute program does not share; embedding it would keep the
     * document renderable but change its extracted text.
     */
    private static boolean safeToSubstitute(PdfFontAuditor.FontResource resource) {
        if (!resource.hasToUnicode() && !isAscii(resource.baseName())) {
            // Legacy CJK producers put GBK/Shift-JIS bytes in /BaseFont and rely on the
            // original font's glyph order. A substitute would change both text extraction
            // and the drawn glyphs because there is no /ToUnicode to preserve.
            return false;
        }
        if (!resource.cid()) return true;
        boolean identityEncoding = "Identity-H".equals(resource.encoding())
                || "Identity-V".equals(resource.encoding());
        boolean identityMap = resource.cidToGidMap().isEmpty()
                || "Identity".equals(resource.cidToGidMap());
        return identityEncoding && identityMap;
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    /**
     * Resolve one font name against the bundled fallback set and the system font directories,
     * or the given directories, without rewriting a document.
     */
    public static FontStatus diagnose(String baseName, Path... fontDirs) {
        String key = key(baseName);
        if (fontDirs.length == 0) {
            Face bundledFace = resolve(bundledFaces(), key);
            if (bundledFace != null) {
                return new FontStatus(baseName, source(bundledFace), bundledFace.fsType(), true,
                        "available");
            }
        }
        Map<String, Face> index = indexFor(fontDirs);
        Face face = resolve(index, key);
        if (face != null) {
            return new FontStatus(baseName, source(face), face.fsType(), true, "available");
        }
        Face restricted = restricted(index, key);
        return restricted == null
                ? new FontStatus(baseName, "", -1, false, "no matching font")
                : new FontStatus(baseName, source(restricted), restricted.fsType(), false,
                        "embedding restricted by fsType " + restricted.fsType());
    }

    private static Map<String, Face> indexFor(Path... fontDirs) {
        Map<String, Face> index = INDEXES.computeIfAbsent(
                fontDirs.length == 0 ? SYSTEM_FONT_DIRS : List.of(fontDirs), PdfFontEmbedder::index);
        if (fontDirs.length == 0) return index;
        // Caller-supplied directories are authoritative; the bundled pack is only
        // a fallback when no directories were given.
        Map<String, Face> isolated = new HashMap<>(index);
        isolated.entrySet().removeIf(entry -> entry.getValue().resource() != null);
        return isolated;
    }

    private static String source(Face face) {
        return face.resource() != null ? face.resource() : face.path().toString();
    }

    /** File-backed variant that lets native qpdf read the input and write the output. */
    public static FileResult embedMissingTo(Path input, Path output, Path... fontDirs)
            throws IOException {
        List<PdfFontAuditor.FontResource> resources =
                PdfFontAuditor.unembeddedResources(input);
        Set<String> names = new LinkedHashSet<>();
        Map<String, Boolean> substitutable = new HashMap<>();
        for (PdfFontAuditor.FontResource resource : resources) {
            names.add(resource.baseName());
            substitutable.merge(resource.baseName(), safeToSubstitute(resource), Boolean::logicalAnd);
        }
        List<String> missing = List.copyOf(names);
        if (missing.isEmpty()) {
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
            return new FileResult(List.of(), List.of());
        }
        Map<String, byte[]> found = programsFor(missing, substitutable, fontDirs);
        if (found.isEmpty() || !QpdfLib.embedFontsToFile(input, output, found, true)) {
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
            return new FileResult(List.of(), missing);
        }
        List<String> unresolved = nonEmbedded(output);
        return new FileResult(
                missing.stream().filter(n -> !unresolved.contains(n)).toList(), unresolved);
    }

    private static Map<String, byte[]> programsFor(
            List<String> missing, Map<String, Boolean> substitutable, Path... fontDirs)
            throws IOException {
        Map<String, Face> index = indexFor(fontDirs);
        Map<String, byte[]> found = new LinkedHashMap<>();
        for (String name : missing) {
            if (!substitutable.getOrDefault(name, true)) continue;
            Face face = resolve(index, key(name));
            if (face == null) continue;
            byte[] data = program(face);
            if (!embeddableProgram(programFsType(face, data))) continue;
            found.put(name, data);
        }
        return found;
    }

    private static List<String> nonEmbedded(Path pdf) throws IOException {
        return PdfFontAuditor.unembeddedResources(pdf).stream()
                .map(PdfFontAuditor.FontResource::baseName).distinct().toList();
    }

    /** File-backed variant of {@link #embedMissing(byte[], Path...)}. */
    public static Result embedMissing(Path input, Path... fontDirs) throws IOException {
        return embedMissing(Files.readAllBytes(input), fontDirs);
    }

    static String key(String name) {
        int start = 0;
        if (name.length() > 7 && name.charAt(6) == '+') {
            boolean subset = true;
            for (int i = 0; i < 6; i++) {
                if (name.charAt(i) < 'A' || name.charAt(i) > 'Z') {
                    subset = false;
                    break;
                }
            }
            if (subset) start = 7;
        }
        int end = name.length();
        boolean trimmed = true;
        while (trimmed) {
            trimmed = false;
            for (String suffix : new String[] {"Regular", "Roman", "Book", "Normal"}) {
                int separator = end - suffix.length() - 1;
                if (separator >= start
                        && (name.charAt(separator) == '-' || name.charAt(separator) == ',')
                        && name.regionMatches(true, separator + 1, suffix, 0, suffix.length())) {
                    end = separator;
                    trimmed = true;
                    break;
                }
            }
        }
        StringBuilder normalized = new StringBuilder(end - start);
        for (int i = start; i < end; ) {
            char c = name.charAt(i);
            if (name.regionMatches(true, i, "PS", 0, 2)
                    && (i + 2 == end || name.charAt(i + 2) == '-'
                            || (i + 4 == end && name.regionMatches(true, i + 2, "MT", 0, 2)))) {
                i += 2;
                continue;
            }
            if (i + 2 == end && name.regionMatches(true, i, "MT", 0, 2)) break;
            if (name.regionMatches(true, i, "TM", 0, 2) && !alnumAfter(name, i + 2, end)) {
                i += 2;
                continue;
            }
            int codePoint = name.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) normalized.appendCodePoint(codePoint);
            i += Character.charCount(codePoint);
        }
        String key = normalized.toString().toLowerCase(Locale.ROOT);
        return key.contains("oblique") ? key.replace("oblique", "italic") : key;
    }

    private static boolean alnumAfter(String name, int from, int end) {
        for (int i = from; i < end; i++) {
            if (Character.isLetterOrDigit(name.charAt(i))) return true;
        }
        return false;
    }

    private static Face resolve(Map<String, Face> index, String key) {
        return lookup(index, key, true);
    }

    private static Face restricted(Map<String, Face> index, String key) {
        Face face = lookup(index, key, false);
        return face != null && !face.embeddable() ? face : null;
    }

    private static Face lookup(Map<String, Face> index, String key, boolean embeddableOnly) {
        if (key.isEmpty()) return null;
        Face exact = index.get(key);
        if (exact != null && (!embeddableOnly || exact.embeddable())) return exact;
        for (List<String> group : METRIC_COMPATIBLE) {
            String family = group.stream().filter(key::startsWith)
                    .max((a, b) -> a.length() - b.length()).orElse(null);
            if (family == null) continue;
            String style = styleOf(key.substring(family.length()));
            for (String member : group) {
                Face face = index.get(member + style);
                if (face == null && !style.isEmpty()) face = index.get(member);
                if (face != null && (!embeddableOnly || face.embeddable())) return face;
            }
        }
        return null;
    }

    private static String styleOf(String suffix) {
        boolean bold = suffix.contains("bold") || suffix.contains("medi") || suffix.contains("black")
                || suffix.contains("demi");
        boolean italic = suffix.contains("italic") || suffix.contains("ital")
                || suffix.contains("obli");
        return bold ? (italic ? "bolditalic" : "bold") : (italic ? "italic" : "");
    }

    private static Map<String, Face> index(List<Path> dirs) {
        boolean bundledFirst = dirs.isEmpty();
        Map<String, Face> index = new HashMap<>();
        if (bundledFirst) index.putAll(bundledFaces());
        for (Path dir : bundledFirst ? SYSTEM_FONT_DIRS : dirs) {
            if (!Files.isDirectory(dir)) continue;
            List<Path> files = new ArrayList<>();
            try {
                Files.walkFileTree(
                        dir,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(
                                    Path file, BasicFileAttributes attrs) {
                                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                                if ((attrs.isRegularFile()
                                                || (attrs.isSymbolicLink()
                                                    && Files.isRegularFile(file)))
                                        && name.matches(".*\\.(ttf|otf|ttc|otc|pfb|pfa|t1)")) {
                                    files.add(file);
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException | RuntimeException ignored) {
            }
            for (Path file : files.stream().sorted().toList()) indexFile(file, index);
        }
        if (!bundledFirst) bundledFaces().forEach(index::putIfAbsent);
        return index;
    }

    private static Map<String, Face> bundledFaces() {
        Map<String, Face> cached = BUNDLED_FACES;
        if (cached != null) return cached;
        synchronized (PdfFontEmbedder.class) {
            if (BUNDLED_FACES != null) return BUNDLED_FACES;
            Map<String, Face> index = new HashMap<>();
            try (InputStream manifest = PdfFontEmbedder.class.getResourceAsStream(FONT_MANIFEST)) {
                if (manifest == null) {
                    BUNDLED_FACES = Map.of();
                    return BUNDLED_FACES;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        manifest, StandardCharsets.UTF_8))) {
                    parseBundledManifest(reader, index);
                }
            } catch (IOException ignored) {
                // No bundled pack means the system directories are the only source.
            }
            BUNDLED_FACES = Map.copyOf(index);
            return BUNDLED_FACES;
        }
    }

    static Map<String, Face> parseBundledManifest(String manifest) {
        Map<String, Face> index = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(manifest))) {
            parseBundledManifest(reader, index);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return index;
    }

    private static void parseBundledManifest(BufferedReader reader, Map<String, Face> index)
            throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] columns = line.split("\t", 3);
            if (columns.length < 2) continue;
            String resource = columns[0].trim();
            String postScriptName = columns[1].trim();
            if (resource.isEmpty() || postScriptName.isEmpty()) continue;
            int fsType = -1;
            if (columns.length > 2) {
                try {
                    fsType = Integer.parseInt(columns[2].trim());
                } catch (NumberFormatException malformed) {
                    // A malformed durability hint means "unknown", not a broken pack.
                }
            }
            index.putIfAbsent(key(postScriptName),
                    new Face(null, "/fonts/" + resource, -1, 0, true, fsType));
        }
    }

    private static void indexFile(Path path, Map<String, Face> index) {
        try (FileChannel channel = FileChannel.open(path)) {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            long size = attrs.size();
            long mtime = attrs.lastModifiedTime().toMillis();
            ByteBuffer b = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            if (b.getShort(0) == (short) 0x8001 || b.get(0) == '%') {
                byte[] head = new byte[Math.min(b.capacity(), 8192)];
                b.get(0, head);
                Matcher m = TYPE1_NAME.matcher(new String(head, StandardCharsets.ISO_8859_1));
                if (m.find()) index.putIfAbsent(key(m.group(1)), new Face(path, -1, size, mtime));
                return;
            }
            boolean collection = b.getInt(0) == TTCF;
            for (int f = 0, n = collection ? b.getInt(8) : 1; f < n; f++) {
                int offset = collection ? b.getInt(12 + 4 * f) : 0;
                int fsType = fsType(b, offset);
                // Restricted-license embedding and bitmap-only embedding are both unusable
                // for a program substitution that must keep outlines, but the face stays in
                // the index so diagnose() can say why it was rejected.
                boolean embeddable = (fsType & 0xF) != 2 && (fsType & 0x0200) == 0;
                for (String name : names(b, offset)) {
                    index.putIfAbsent(
                            key(name),
                            new Face(
                                    path,
                                    null,
                                    collection ? offset : -1,
                                    size,
                                    mtime,
                                    embeddable,
                                    fsType));
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static int fsType(ByteBuffer b, int face) {
        for (int i = 0, n = b.getShort(face + 4) & 0xFFFF; i < n; i++) {
            int record = face + 12 + 16 * i;
            if (b.getInt(record) == 0x4F532F32) {
                return b.getShort(b.getInt(record + 8) + 8) & 0xFFFF;
            }
        }
        return 0;
    }

    private static List<String> names(ByteBuffer b, int face) {
        List<String> names = new ArrayList<>();
        for (int i = 0, n = b.getShort(face + 4) & 0xFFFF; i < n; i++) {
            int record = face + 12 + 16 * i;
            if (b.getInt(record) != 0x6E616D65) continue;
            int table = b.getInt(record + 8);
            int strings = table + (b.getShort(table + 4) & 0xFFFF);
            for (int j = 0, count = b.getShort(table + 2) & 0xFFFF; j < count; j++) {
                int q = table + 6 + 12 * j;
                int platform = b.getShort(q) & 0xFFFF;
                int id = b.getShort(q + 6);
                int language = b.getShort(q + 4) & 0xFFFF;
                boolean english = platform == 3 ? language == 0x409 : platform == 1 && language == 0;
                if (id != 6 && !(id == 4 && english)) continue;
                byte[] raw = new byte[b.getShort(q + 8) & 0xFFFF];
                b.get(strings + (b.getShort(q + 10) & 0xFFFF), raw);
                names.add(
                        new String(
                                raw,
                                platform == 1
                                        ? Charset.forName("x-MacRoman")
                                        : StandardCharsets.UTF_16BE));
            }
        }
        return names;
    }

    /** fsType of the bytes actually selected for embedding, not the cached index entry. */
    private static int programFsType(Face face, byte[] data) {
        if (face.resource() != null) return face.fsType();
        try {
            return data.length < 12 ? -1 : fsType(ByteBuffer.wrap(data), 0);
        } catch (RuntimeException malformed) {
            return -1;
        }
    }

    private static boolean embeddableProgram(int fsType) {
        return fsType >= 0 && (fsType & 0xF) != 2 && (fsType & 0x0200) == 0;
    }

    private static byte[] program(Face face) throws IOException {
        // A replaced font file invalidates the cached face even when the path and
        // TTC offset are unchanged; resolve the current key before looking up.
        Face key = face;
        if (face.resource() == null) {
            long size = Files.size(face.path());
            long mtime = Files.getLastModifiedTime(face.path()).toMillis();
            if (size != face.size() || mtime != face.mtime()) {
                key = new Face(
                        face.path(),
                        face.resource(),
                        face.offset(),
                        size,
                        mtime,
                        face.embeddable(),
                        face.fsType());
            }
        }
        synchronized (PROGRAMS) {
            byte[] cached = PROGRAMS.get(key);
            if (cached != null) return cached;
        }
        if (face.resource() != null) {
            byte[] data;
            try (InputStream raw = PdfFontEmbedder.class.getResourceAsStream(face.resource())) {
                if (raw == null) {
                    throw new IOException("bundled font resource is missing: " + face.resource());
                }
                try (XZInputStream xz = new XZInputStream(raw, 96 * 1024)) {
                    data = xz.readNBytes(32 * 1024 * 1024 + 1);
                    if (data.length > 32 * 1024 * 1024) {
                        throw new IOException("bundled font exceeds 32 MiB: " + face.resource());
                    }
                }
            }
            synchronized (PROGRAMS) {
                byte[] raced = PROGRAMS.get(key);
                if (raced != null) return raced;
                cacheProgram(key, data);
            }
            return data;
        }
        byte[] all = Files.readAllBytes(face.path());
        byte[] data = face.offset() < 0 ? all : extractFace(all, face.offset());
        if (data.length > MAX_PROGRAM_BYTES) {
            return data;
        }
        synchronized (PROGRAMS) {
            byte[] raced = PROGRAMS.get(key);
            if (raced != null) return raced;
            cacheProgram(key, data);
        }
        return data;
    }

    private static void cacheProgram(Face key, byte[] data) {
        if (data.length > MAX_PROGRAM_BYTES) {
            // A single oversized program stays out of the cache; returning it
            // directly must not push programBytes past the byte bound.
            return;
        }
        programBytes += data.length;
        PROGRAMS.put(key, data);
        Iterator<Map.Entry<Face, byte[]>> eldest = PROGRAMS.entrySet().iterator();
        while ((PROGRAMS.size() > MAX_PROGRAMS || programBytes > MAX_PROGRAM_BYTES)
                && eldest.hasNext()) {
            Map.Entry<Face, byte[]> entry = eldest.next();
            if (entry.getKey().equals(key)) continue;
            programBytes -= entry.getValue().length;
            eldest.remove();
        }
    }

    private static byte[] singleFace(byte[] data, String name) {
        ByteBuffer b = ByteBuffer.wrap(data);
        if (data.length < 16 || b.getInt(0) != TTCF) return data;
        String wanted = key(name);
        for (int f = 0; f < b.getInt(8); f++) {
            if (names(b, b.getInt(12 + 4 * f)).stream().map(PdfFontEmbedder::key).anyMatch(wanted::equals)) {
                return extractFace(data, b.getInt(12 + 4 * f));
            }
        }
        return extractFace(data, b.getInt(12));
    }

    private static byte[] extractFace(byte[] collection, int face) {
        ByteBuffer in = ByteBuffer.wrap(collection);
        requireRange(in, face, 12);
        int tables = in.getShort(face + 4) & 0xFFFF;
        if (tables < 1 || tables > 4096) {
            throw new IllegalArgumentException("invalid font collection table count: " + tables);
        }
        int header = 12 + 16 * tables;
        requireRange(in, face, header);
        long size = header;
        for (int i = 0; i < tables; i++) {
            int record = face + 12 + 16 * i;
            int offset = in.getInt(record + 8);
            int length = in.getInt(record + 12);
            if (length < 0) throw new IllegalArgumentException("negative font table length");
            requireRange(in, offset, length);
            size += (length + 3) & ~3L;
        }
        if (size > 64L * 1024 * 1024) {
            throw new IllegalArgumentException("font face is unreasonably large: " + size);
        }
        ByteBuffer out = ByteBuffer.allocate((int) size).put(0, collection, face, header);
        for (int i = 0, pos = header; i < tables; i++) {
            int record = 12 + 16 * i;
            int offset = in.getInt(face + record + 8);
            int length = in.getInt(face + record + 12);
            out.put(pos, collection, offset, length).putInt(record + 8, pos);
            pos += (length + 3) & ~3;
        }
        return out.array();
    }

    private static void requireRange(ByteBuffer buffer, int offset, int length) {
        if (offset < 0 || length < 0 || offset > buffer.capacity() - length) {
            throw new IllegalArgumentException("font table is outside the collection");
        }
    }
}
