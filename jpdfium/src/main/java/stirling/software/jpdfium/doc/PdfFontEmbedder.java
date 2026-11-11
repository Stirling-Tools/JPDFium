package stirling.software.jpdfium.doc;

import java.io.IOException;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import stirling.software.jpdfium.PdfDocument;
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
 * full name in the given font directories (the system ones when none are given, indexed once
 * per list), falls back to a metric-compatible substitute for the standard 14 and their
 * Microsoft equivalents, and never picks a font whose {@code fsType} forbids embedding. Its
 * result lists the fonts embedded and those still missing, audited on the output.
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
     * The rewritten document, the fonts embedded into it, and the fonts in use that are still
     * not embedded, as {@link PdfFontAuditor} reports them on the output.
     */
    public record Result(byte[] pdf, List<String> embedded, List<String> unresolved) {}

    /** Outcome of the file-backed embedding pass. */
    public record FileResult(List<String> embedded, List<String> unresolved) {}

    private record Face(Path path, int offset, long size, long mtime) {}

    private static final int TTCF = 0x74746366;
    private static final Pattern TYPE1_NAME = Pattern.compile("/FontName\\s*/(\\S+)");
    private static final List<List<String>> METRIC_COMPATIBLE = List.of(
            List.of("helvetica", "arial", "liberationsans", "nimbussans", "nimbussanl", "arimo", "freesans"),
            List.of("times", "timesnewroman", "liberationserif", "nimbusroman", "nimbusromno9l", "tinos",
                    "freeserif"),
            List.of("courier", "couriernew", "liberationmono", "nimbusmono", "nimbusmonl", "cousine", "freemono"),
            List.of("symbol", "standardsymbols", "standardsymbolsps", "standardsyml"),
            List.of("zapfdingbats", "zapfdingbatsitc", "dingbats", "d050000l"));
    private static final Map<String, List<String>> STYLE_SPELLINGS = Map.of(
            "", List.of("", "regular", "regu", "roma"),
            "bold", List.of("bold", "medi"),
            "italic", List.of("italic", "ital", "reguital", "reguobli"),
            "bolditalic", List.of("bolditalic", "boldital", "mediital", "boldobli"));
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
     * Embed every non-embedded font in use from {@code fontDirs}, searched recursively and
     * indexed once per distinct list; the system font directories when none are given.
     */
    public static Result embedMissing(byte[] pdf, Path... fontDirs) throws IOException {
        List<String> missing = PdfFontAuditor.unembeddedNames(pdf);
        Map<String, Face> index = INDEXES.computeIfAbsent(
                fontDirs.length == 0 ? SYSTEM_FONT_DIRS : List.of(fontDirs), PdfFontEmbedder::index);
        Map<String, byte[]> found = new LinkedHashMap<>();
        Map<Face, byte[]> programs = new HashMap<>();
        for (String name : missing) {
            Face face = resolve(index, key(name));
            if (face == null) continue;
            byte[] data = programs.get(face);
            if (data == null) programs.put(face, data = program(face));
            found.put(name, data);
        }
        byte[] out = found.isEmpty() ? null : QpdfLib.embedFonts(pdf, found, true);
        if (out == null) return new Result(pdf, List.of(), missing);
        List<String> unresolved = PdfFontAuditor.unembeddedNames(out);
        return new Result(out, missing.stream().filter(n -> !unresolved.contains(n)).toList(), unresolved);
    }

    /** File-backed variant that lets native qpdf read the input and write the output. */
    public static FileResult embedMissingTo(Path input, Path output, Path... fontDirs)
            throws IOException {
        List<String> missing = nonEmbedded(input);
        Map<String, byte[]> found = programsFor(missing, fontDirs);
        if (found.isEmpty() || !QpdfLib.embedFontsToFile(input, output, found, true)) {
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
            return new FileResult(List.of(), missing);
        }
        List<String> unresolved = nonEmbedded(output);
        return new FileResult(
                missing.stream().filter(n -> !unresolved.contains(n)).toList(), unresolved);
    }

    private static Map<String, byte[]> programsFor(List<String> missing, Path... fontDirs)
            throws IOException {
        Map<String, Face> index = INDEXES.computeIfAbsent(
                fontDirs.length == 0 ? SYSTEM_FONT_DIRS : List.of(fontDirs), PdfFontEmbedder::index);
        Map<String, byte[]> found = new LinkedHashMap<>();
        Map<Face, byte[]> programs = new HashMap<>();
        for (String name : missing) {
            Face face = resolve(index, key(name));
            if (face == null) continue;
            byte[] data = programs.get(face);
            if (data == null) programs.put(face, data = program(face));
            found.put(name, data);
        }
        return found;
    }

    private static List<String> nonEmbedded(Path pdf) {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            if (PdfFontAuditor.inventoryAvailable()) {
                List<PdfFontAuditor.FontResource> all = PdfFontAuditor.inventory(doc);
                if (!all.isEmpty()) {
                    List<String> names = new ArrayList<>();
                    for (PdfFontAuditor.FontResource resource : all) {
                        if (resource.baseName().isEmpty() || resource.embedded()
                                || "Type3".equals(resource.subtype())) {
                            continue;
                        }
                        names.add(resource.baseName());
                    }
                    return names.stream().distinct().toList();
                }
            }
            return PdfFontAuditor.audit(doc).nonEmbeddedFonts().stream()
                    .map(PdfFontAuditor.FontInfo::baseName).distinct().toList();
        }
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
        for (String suffix : new String[] {"Regular", "Roman", "Book", "Normal"}) {
            int separator = end - suffix.length() - 1;
            if (separator >= start && (name.charAt(separator) == '-' || name.charAt(separator) == ',')
                    && name.regionMatches(separator + 1, suffix, 0, suffix.length())) {
                end = separator;
                break;
            }
        }
        StringBuilder normalized = new StringBuilder(end - start);
        for (int i = start; i < end; ) {
            char c = name.charAt(i);
            if (c == 'P' && i + 1 < end && name.charAt(i + 1) == 'S'
                    && (i + 2 == end || name.charAt(i + 2) == '-'
                            || (i + 4 == end && name.startsWith("MT", i + 2)))) {
                i += 2;
                continue;
            }
            if (c == 'M' && i + 2 == end && name.charAt(i + 1) == 'T') break;
            int codePoint = name.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) normalized.appendCodePoint(codePoint);
            i += Character.charCount(codePoint);
        }
        String key = normalized.toString().toLowerCase(Locale.ROOT);
        return key.contains("oblique") ? key.replace("oblique", "italic") : key;
    }

    private static Face resolve(Map<String, Face> index, String key) {
        if (key.isEmpty()) return null;
        Face exact = index.get(key);
        if (exact != null) return exact;
        for (List<String> group : METRIC_COMPATIBLE) {
            String family = group.stream().filter(key::startsWith)
                    .max((a, b) -> a.length() - b.length()).orElse(null);
            if (family == null) continue;
            List<String> spellings = STYLE_SPELLINGS.get(key.substring(family.length()));
            if (spellings == null) continue;
            for (String member : group) {
                for (String style : spellings) {
                    Face face = index.get(member + style);
                    if (face != null) return face;
                }
            }
        }
        return null;
    }

    private static Map<String, Face> index(List<Path> dirs) {
        Map<String, Face> index = new HashMap<>();
        for (Path dir : dirs) {
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
        return index;
    }

    private static void indexFile(Path path, Map<String, Face> index) {
        try (FileChannel channel = FileChannel.open(path)) {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            long size = attrs.size(), mtime = attrs.lastModifiedTime().toMillis();
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
                if (restricted(b, offset)) continue;
                for (String name : names(b, offset)) {
                    index.putIfAbsent(
                            key(name), new Face(path, collection ? offset : -1, size, mtime));
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static boolean restricted(ByteBuffer b, int face) {
        for (int i = 0, n = b.getShort(face + 4) & 0xFFFF; i < n; i++) {
            int record = face + 12 + 16 * i;
            if (b.getInt(record) == 0x4F532F32) {
                int fsType = b.getShort(b.getInt(record + 8) + 8) & 0xFFFF;
                // Restricted-license embedding and bitmap-only embedding are both unusable
                // for a program substitution that must keep outlines.
                return (fsType & 0xF) == 2 || (fsType & 0x0200) != 0;
            }
        }
        return false;
    }

    private static List<String> names(ByteBuffer b, int face) {
        List<String> names = new ArrayList<>();
        for (int i = 0, n = b.getShort(face + 4) & 0xFFFF; i < n; i++) {
            int record = face + 12 + 16 * i;
            if (b.getInt(record) != 0x6E616D65) continue;
            int table = b.getInt(record + 8), strings = table + (b.getShort(table + 4) & 0xFFFF);
            for (int j = 0, count = b.getShort(table + 2) & 0xFFFF; j < count; j++) {
                int q = table + 6 + 12 * j, platform = b.getShort(q) & 0xFFFF, id = b.getShort(q + 6);
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

    private static byte[] program(Face face) throws IOException {
        synchronized (PROGRAMS) {
            byte[] cached = PROGRAMS.get(face);
            if (cached != null) return cached;
        }
        long size = Files.size(face.path());
        long mtime = Files.getLastModifiedTime(face.path()).toMillis();
        // A replaced font file invalidates the cached face even when the path and
        // TTC offset are unchanged.
        Face key = size == face.size() && mtime == face.mtime()
                ? face
                : new Face(face.path(), face.offset(), size, mtime);
        byte[] all = Files.readAllBytes(face.path());
        byte[] data = face.offset() < 0 ? all : extractFace(all, face.offset());
        if (data.length > MAX_PROGRAM_BYTES) {
            return data;
        }
        synchronized (PROGRAMS) {
            byte[] raced = PROGRAMS.get(key);
            if (raced != null) return raced;
            PROGRAMS.put(key, data);
            programBytes += data.length;
            Iterator<Map.Entry<Face, byte[]>> it = PROGRAMS.entrySet().iterator();
            while (it.hasNext()
                    && (PROGRAMS.size() > MAX_PROGRAMS || programBytes > MAX_PROGRAM_BYTES)) {
                Map.Entry<Face, byte[]> eldest = it.next();
                programBytes -= eldest.getValue().length;
                it.remove();
            }
        }
        return data;
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
