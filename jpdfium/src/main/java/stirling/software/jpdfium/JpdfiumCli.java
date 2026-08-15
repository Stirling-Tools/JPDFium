package stirling.software.jpdfium;

import stirling.software.jpdfium.doc.CompressOptions;
import stirling.software.jpdfium.doc.CompressPreset;
import stirling.software.jpdfium.doc.MetadataTag;
import stirling.software.jpdfium.doc.NUpLayout;
import stirling.software.jpdfium.doc.PdfAutoCrop;
import stirling.software.jpdfium.doc.PdfBackground;
import stirling.software.jpdfium.doc.PdfColorConverter;
import stirling.software.jpdfium.doc.PdfCompressor;
import stirling.software.jpdfium.doc.PdfCompressor.CompressResultWithBytes;
import stirling.software.jpdfium.doc.PdfDeskew;
import stirling.software.jpdfium.doc.PdfPageMirror;
import stirling.software.jpdfium.doc.PdfPageReorder;
import stirling.software.jpdfium.doc.PdfPageScaler;
import stirling.software.jpdfium.doc.PdfPageScaler.FitMode;
import stirling.software.jpdfium.doc.PdfPosterizer.PaperSize;
import stirling.software.jpdfium.doc.PdfRepair;
import stirling.software.jpdfium.doc.PdfSecurity;
import stirling.software.jpdfium.doc.PdfTocGenerator;
import stirling.software.jpdfium.doc.RepairResult;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Command-line interface for JPDFium, also compiled as a standalone GraalVM
 * native binary ({@code jpdfium <operation> <args...>}).
 *
 * <p>Operations are registered in {@link #OPS} and {@link #printHelp()} is
 * generated from the same table, so help can never drift from the dispatch.
 *
 * <p>Exit codes: 0 success, 1 processing failure, 2 usage error, 70 fatal VM
 * error. {@code help} and unknown-operation diagnostics never touch the native
 * library so they are instant in the compiled binary.
 */
public final class JpdfiumCli {

    private JpdfiumCli() {}

    public static void main(String[] args) {
        boolean verbose = Arrays.stream(args).anyMatch("--verbose"::equals);
        int rc;
        try {
            rc = run(args);
        } catch (UsageError e) {
            System.err.println("jpdfium: " + e.getMessage());
            System.err.println("Run 'jpdfium help' for usage.");
            rc = 2;
        } catch (Exception e) {
            System.err.println("jpdfium: " + e.getMessage());
            if (verbose) {
                e.printStackTrace(System.err);
            }
            rc = 1;
        } catch (Throwable t) {
            System.err.println("jpdfium: fatal: " + t);
            t.printStackTrace(System.err);
            rc = 70;
        }
        System.exit(rc);
    }

    @FunctionalInterface
    interface OpHandler {
        int run(Args a) throws Exception;
    }

    @FunctionalInterface
    interface DocOp {
        void apply(PdfDocument doc) throws Exception;
    }

    @FunctionalInterface
    interface PageOp {
        void apply(PdfPage page) throws Exception;
    }

    /** Command-line usage error: invalid flags, bad values, missing args. */
    static final class UsageError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageError(String message) {
            super(message);
        }
    }

    /** Parsed command line: positional arguments plus {@code --key value} flags. */
    private record Args(List<String> positional, Map<String, String> flags, String opName) {

        String positional(int index) {
            if (index >= positional.size()) {
                throw new UsageError(opName + ": missing required argument "
                        + "(expected " + (index + 1) + " positional arguments)");
            }
            return positional.get(index);
        }

        boolean has(String name) {
            return flags.containsKey(name);
        }

        String require(String name) {
            String value = flags.get(name);
            if (value == null) {
                throw new UsageError(opName + ": missing required flag --" + name);
            }
            return value;
        }

        String str(String name, String def) {
            return flags.getOrDefault(name, def);
        }

        int intFlag(String name, int def) {
            String raw = flags.get(name);
            if (raw == null) {
                return def;
            }
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new UsageError(opName + ": invalid value for --" + name + ": '" + raw + "'");
            }
        }

        float floatFlag(String name, float def) {
            String raw = flags.get(name);
            if (raw == null) {
                return def;
            }
            try {
                return Float.parseFloat(raw);
            } catch (NumberFormatException e) {
                throw new UsageError(opName + ": invalid value for --" + name + ": '" + raw + "'");
            }
        }

        <E extends Enum<E>> E enumFlag(String name, E def, Function<String, E> valueOf) {
            String raw = flags.get(name);
            if (raw == null) {
                return def;
            }
            try {
                return valueOf.apply(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new UsageError(opName + ": invalid value for --" + name + ": '" + raw + "'");
            }
        }

        int colorFlag(String name, int def) {
            String raw = flags.get(name);
            if (raw == null) {
                return def;
            }
            return parseColor(raw);
        }
    }


    private record Operation(String usage, String blurb, OpHandler handler) {}

    private static final Map<String, Operation> OPS = buildRegistry();

    private static Map<String, Operation> buildRegistry() {
        Map<String, Operation> ops = new LinkedHashMap<>();
        ops.put("info", new Operation("info <input.pdf>",
                "Print page count, page sizes and metadata", JpdfiumCli::opInfo));
        ops.put("text", new Operation("text <input.pdf> <output.txt>",
                "Extract text to a plain-text file", JpdfiumCli::opText));
        ops.put("render", new Operation("render <input.pdf> <output-dir> [--dpi N]",
                "Render every page to a PNG", JpdfiumCli::opRender));
        ops.put("merge", new Operation("merge <output.pdf> <input1.pdf> <input2.pdf> ...",
                "Merge multiple PDFs into one", JpdfiumCli::opMerge));
        ops.put("split", new Operation("split <input.pdf> <output-dir> [--pages-per N]",
                "Split a PDF into N-page parts", JpdfiumCli::opSplit));
        ops.put("redact-region", new Operation("redact-region <in.pdf> <out.pdf> --rect x,y,w,h "
                + "[--color AARRGGBB] [--remove] [--page N]",
                "Redact a rectangular region on every page", JpdfiumCli::opRedactRegion));
        ops.put("redact-words", new Operation("redact-words <in.pdf> <out.pdf> --words a,b,c "
                + "[--color AARRGGBB] [--remove] [--page N]",
                "Redact matching words on every page", JpdfiumCli::opRedactWords));
        ops.put("redact-pattern", new Operation("redact-pattern <in.pdf> <out.pdf> --pattern REGEX "
                + "[--color AARRGGBB] [--remove] [--page N]",
                "Redact text matching a regex on every page", JpdfiumCli::opRedactPattern));
        ops.put("flatten", new Operation("flatten <in.pdf> <out.pdf>",
                "Flatten all annotations into page content", JpdfiumCli::opFlatten));
        ops.put("compress", new Operation("compress <in.pdf> <out.pdf> [--preset WEB|SCREEN|PRINT|LOSSLESS|MAXIMUM]",
                "Compress a PDF", JpdfiumCli::opCompress));
        ops.put("auto-crop", new Operation("auto-crop <in.pdf> <out.pdf> [--margin F]",
                "Trim page margins to content bounds", JpdfiumCli::opAutoCrop));
        ops.put("watermark", new Operation("watermark <in.pdf> <out.pdf> --text TEXT "
                + "[--opacity F] [--color AARRGGBB] [--size F] [--rotation F]",
                "Stamp a diagonal text watermark", JpdfiumCli::opWatermark));
        ops.put("header-footer", new Operation("header-footer <in.pdf> <out.pdf> "
                + "[--header TEXT] [--footer TEXT] [--size F]",
                "Add a header and/or footer to every page", JpdfiumCli::opHeaderFooter));
        ops.put("bates", new Operation("bates <in.pdf> <out.pdf> [--prefix P] [--start N] [--digits N]",
                "Add Bates numbering to every page", JpdfiumCli::opBates));
        ops.put("repair", new Operation("repair <in.pdf> <out.pdf>",
                "Rebuild a damaged PDF", JpdfiumCli::opRepair));
        ops.put("rotate", new Operation("rotate <in.pdf> <out.pdf> [--degrees 90|180|270]",
                "Rotate every page", JpdfiumCli::opRotate));
        ops.put("mirror-h", new Operation("mirror-h <in.pdf> <out.pdf>",
                "Mirror every page horizontally", JpdfiumCli::opMirrorH));
        ops.put("mirror-v", new Operation("mirror-v <in.pdf> <out.pdf>",
                "Mirror every page vertically", JpdfiumCli::opMirrorV));
        ops.put("grayscale", new Operation("grayscale <in.pdf> <out.pdf>",
                "Convert every page to grayscale", JpdfiumCli::opGrayscale));
        ops.put("background", new Operation("background <in.pdf> <out.pdf> --color RRGGBB",
                "Add a solid background color to every page", JpdfiumCli::opBackground));
        ops.put("deskew", new Operation("deskew <in.pdf> <out.pdf> [--max-angle F] [--accuracy F]",
                "Correct page skew", JpdfiumCli::opDeskew));
        ops.put("scale", new Operation("scale <in.pdf> <out.pdf> [--paper A4|A3|LETTER] [--fit WIDTH|HEIGHT|PAGE]",
                "Resize every page to a paper size", JpdfiumCli::opScale));
        ops.put("pages", new Operation("pages <in.pdf> <out.pdf> --range 1-3,5,7-9",
                "Extract the given page range", JpdfiumCli::opPages));
        ops.put("reverse", new Operation("reverse <in.pdf> <out.pdf>",
                "Reverse page order", JpdfiumCli::opReverse));
        ops.put("nup", new Operation("nup <in.pdf> <out.pdf> [--cols N] [--rows N]",
                "Lay pages out N-up on larger sheets", JpdfiumCli::opNup));
        ops.put("sanitize", new Operation("sanitize <in.pdf> <out.pdf>",
                "Strip hidden content and metadata", JpdfiumCli::opSanitize));
        ops.put("toc", new Operation("toc <in.pdf> <out.pdf>",
                "Generate a table of contents from headings", JpdfiumCli::opToc));
        return Map.copyOf(ops);
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0) {
            printHelp();
            return 2;
        }
        String opName = args[0];
        if (opName.equals("help") || opName.equals("--help") || opName.equals("-h")) {
            printHelp();
            return 0;
        }
        Operation op = OPS.get(opName);
        if (op == null) {
            System.err.println("jpdfium: unknown operation '" + opName + "'");
            printHelp();
            return 2;
        }
        NativeLoader.ensureLoaded();
        try {
            Args a = parseArgs(Arrays.copyOfRange(args, 1, args.length), opName);
            return op.handler().run(a);
        } catch (UsageError e) {
            System.err.println("jpdfium: " + e.getMessage());
            System.err.println("Run 'jpdfium help' for usage.");
            return 2;
        }
    }

    private static Args parseArgs(String[] args, String opName) {
        List<String> positional = new ArrayList<>();
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                positional.add(arg);
                continue;
            }
            String name = arg.substring(2);
            int eq = name.indexOf('=');
            if (eq >= 0) {
                String key = name.substring(0, eq);
                requireKnownFlag(key, opName);
                flags.put(key, name.substring(eq + 1));
                continue;
            }
            if (VALUE_FLAGS.contains(name)) {
                if (i + 1 >= args.length) {
                    throw new UsageError(opName + ": missing value for --" + name);
                }
                flags.put(name, args[++i]);
            } else if (BOOLEAN_FLAGS.contains(name)) {
                flags.put(name, "true");
            } else {
                throw new UsageError(opName + ": unrecognized flag --" + name);
            }
        }
        return new Args(positional, flags, opName);
    }

    private static void requireKnownFlag(String name, String opName) {
        if (!VALUE_FLAGS.contains(name) && !BOOLEAN_FLAGS.contains(name)) {
            throw new UsageError(opName + ": unrecognized flag --" + name);
        }
    }

    /** Flags that require a value; everything else must be a boolean switch. */
    private static final Set<String> VALUE_FLAGS = Set.of(
            "rect", "color", "words", "padding", "pattern", "preset", "margin", "text",
            "opacity", "size", "rotation", "header", "footer", "prefix", "start", "digits",
            "degrees", "max-angle", "accuracy", "min-confidence", "paper", "fit", "range",
            "cols", "rows", "pages-per", "dpi", "page");

    private static final Set<String> BOOLEAN_FLAGS = Set.of(
            "remove", "force", "quiet", "verbose");


    private static int transform(Args a, String verb, DocOp op) throws Exception {
        Path in = Path.of(a.positional(0));
        Path out = Path.of(a.positional(1));
        rejectSameFile(in, out);
        rejectOverwriteUnlessForced(out, a);
        long t0 = System.nanoTime();
        try (PdfDocument doc = PdfDocument.open(in)) {
            op.apply(doc);
            saveAtomically(doc, out);
        }
        if (!a.has("quiet")) {
            System.out.printf("%s %s -> %s (%.2f s)%n", verb, in, out, (System.nanoTime() - t0) / 1e9);
        }
        return 0;
    }

    private static void rejectSameFile(Path in, Path out) {
        if (in.toAbsolutePath().normalize().equals(out.toAbsolutePath().normalize())) {
            throw new UsageError("input and output must be different files: " + in);
        }
    }

    private static void rejectOverwriteUnlessForced(Path out, Args a) {
        if (Files.exists(out) && !a.has("force")) {
            throw new UsageError("output already exists: " + out + " (pass --force to overwrite)");
        }
    }

    private static void saveAtomically(PdfDocument doc, Path out) throws Exception {
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try {
            doc.save(tmp);
            moveSafely(tmp, out);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void writeBytesAtomically(byte[] data, Path out, Args a) throws Exception {
        rejectOverwriteUnlessForced(out, a);
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try {
            Files.write(tmp, data);
            moveSafely(tmp, out);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void moveSafely(Path tmp, Path out) throws Exception {
        try {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forEachPage(PdfDocument doc, Args a, PageOp pageOp) throws Exception {
        if (a.has("page")) {
            int index = a.intFlag("page", 1) - 1;
            if (index < 0 || index >= doc.pageCount()) {
                throw new UsageError(a.opName() + ": page " + (index + 1) + " is out of range "
                        + "(document has " + doc.pageCount() + " pages)");
            }
            try (PdfPage page = doc.page(index)) {
                pageOp.apply(page);
            }
            return;
        }
        for (int i = 0; i < doc.pageCount(); i++) {
            try (PdfPage page = doc.page(i)) {
                pageOp.apply(page);
            }
        }
    }

    private static void rejectOutOfRange(String flag, int value, int max, Args a) {
        if (value < 1 || value > max) {
            throw new UsageError(a.opName() + ": " + flag + " " + value + " is out of range (1.." + max + ")");
        }
    }


    private static int opRedactRegion(Args a) throws Exception {
        Rect rect = parseRect(a.require("rect"));
        int color = a.colorFlag("color", 0xFFFF0000);
        boolean remove = a.has("remove");
        return transform(a, "redacted", doc -> forEachPage(doc, a, page -> page.redactRegion(rect, color, remove)));
    }

    private static int opRedactWords(Args a) throws Exception {
        String[] words = a.require("words").split(",");
        int color = a.colorFlag("color", 0xFFFF0000);
        float padding = a.floatFlag("padding", 2f);
        boolean remove = a.has("remove");
        return transform(a, "redacted words in", doc -> forEachPage(doc, a,
                page -> page.redactWords(words, color, padding, false, false, remove)));
    }

    private static int opRedactPattern(Args a) throws Exception {
        String pattern = a.require("pattern");
        int color = a.colorFlag("color", 0xFFFF0000);
        boolean remove = a.has("remove");
        return transform(a, "redacted pattern in", doc -> forEachPage(doc, a,
                page -> page.redactPattern(pattern, color, remove)));
    }


    private static int opFlatten(Args a) throws Exception {
        return transform(a, "flattened", doc -> forEachPage(doc, a, PdfPage::flatten));
    }

    private static int opCompress(Args a) throws Exception {
        Path in = Path.of(a.positional(0));
        Path out = Path.of(a.positional(1));
        rejectSameFile(in, out);
        CompressPreset preset = a.enumFlag("preset", CompressPreset.WEB, CompressPreset::valueOf);
        CompressOptions options = CompressOptions.builder().preset(preset).build();
        byte[] bytes;
        try (PdfDocument doc = PdfDocument.open(in)) {
            CompressResultWithBytes result = PdfCompressor.compress(doc, options);
            bytes = result.bytes();
        }
        writeBytesAtomically(bytes, out, a);
        if (!a.has("quiet")) {
            System.out.println("compressed " + in + " -> " + out);
        }
        return 0;
    }

    private static int opAutoCrop(Args a) throws Exception {
        float margin = a.floatFlag("margin", 0f);
        return transform(a, "auto-cropped", doc -> PdfAutoCrop.cropAll(doc, margin));
    }

    private static int opWatermark(Args a) throws Exception {
        String text = a.require("text");
        Watermark watermark = Watermark.text(text)
                .size(a.floatFlag("size", 72f))
                .color(a.colorFlag("color", 0x40FF0000))
                .opacity(a.floatFlag("opacity", 0.25f))
                .rotation(a.floatFlag("rotation", 45f))
                .build();
        return transform(a, "watermarked", doc -> WatermarkApplier.apply(doc, watermark));
    }

    private static int opHeaderFooter(Args a) throws Exception {
        if (!a.has("header") && !a.has("footer")) {
            throw new UsageError("header-footer requires --header and/or --footer");
        }
        HeaderFooter.Builder builder = HeaderFooter.builder().size(a.floatFlag("size", 9f));
        if (a.has("header")) {
            builder.header(a.str("header", ""));
        }
        if (a.has("footer")) {
            builder.footer(a.str("footer", ""));
        }
        HeaderFooter hf = builder.build();
        return transform(a, "added header/footer to", doc -> HeaderFooterApplier.apply(doc, hf));
    }

    private static int opBates(Args a) throws Exception {
        String prefix = a.str("prefix", "");
        int start = a.intFlag("start", 1);
        int digits = a.intFlag("digits", 6);
        return transform(a, "bates-numbered", doc -> HeaderFooterApplier.applyBatesNumbering(doc, prefix, start, digits));
    }

    private static int opRepair(Args a) throws Exception {
        Path in = Path.of(a.positional(0));
        Path out = Path.of(a.positional(1));
        rejectSameFile(in, out);
        RepairResult result = PdfRepair.builder().input(in).build().execute();
        if (!result.isUsable()) {
            throw new IllegalStateException("repair failed: " + result.status());
        }
        writeBytesAtomically(result.repairedPdf(), out, a);
        if (!a.has("quiet")) {
            System.out.println("repaired " + in + " -> " + out + " (" + result.status() + ")");
        }
        return 0;
    }

    private static int opRotate(Args a) throws Exception {
        int degrees = a.intFlag("degrees", 90);
        if (degrees % 90 != 0) {
            throw new UsageError("--degrees must be a multiple of 90 (got " + degrees + ")");
        }
        return transform(a, "rotated", doc -> PdfPageGeometry.rotateAll(doc, degrees));
    }

    private static int opMirrorH(Args a) throws Exception {
        return transform(a, "mirrored", doc -> PdfPageMirror.mirrorHorizontalAll(doc));
    }

    private static int opMirrorV(Args a) throws Exception {
        return transform(a, "mirrored", doc -> PdfPageMirror.mirrorVerticalAll(doc));
    }

    private static int opGrayscale(Args a) throws Exception {
        return transform(a, "grayscaled", doc -> PdfColorConverter.toGrayscale(doc));
    }

    private static int opBackground(Args a) throws Exception {
        int rgb = parseColor(a.require("color")) & 0xFFFFFF;
        return transform(a, "added background to", doc -> PdfBackground.addColorAll(doc, rgb));
    }

    private static int opDeskew(Args a) throws Exception {
        float maxAngle = a.floatFlag("max-angle", 15f);
        float accuracy = a.floatFlag("accuracy", 0.5f);
        float confidence = a.floatFlag("min-confidence", 0.6f);
        return transform(a, "deskewed", doc -> PdfDeskew.deskewAll(doc, maxAngle, accuracy, confidence));
    }

    private static int opScale(Args a) throws Exception {
        PaperSize paper = switch (a.str("paper", "a4").toUpperCase()) {
            case "A3" -> PaperSize.A3;
            case "LETTER" -> PaperSize.LETTER;
            case "A4" -> PaperSize.A4;
            default -> throw new UsageError("invalid value for --paper: '" + a.str("paper", "")
                    + "' (expected A4, A3, LETTER)");
        };
        FitMode fit = a.enumFlag("fit", FitMode.FIT_PAGE, JpdfiumCli::parseFitMode);
        return transform(a, "scaled", doc -> PdfPageScaler.scaleAll(doc, paper, fit));
    }

    private static int opPages(Args a) throws Exception {
        Path in = Path.of(a.positional(0));
        Path out = Path.of(a.positional(1));
        rejectSameFile(in, out);
        rejectOverwriteUnlessForced(out, a);
        Set<Integer> pages = parsePageSet(a.require("range"), a);
        try (PdfDocument doc = PdfDocument.open(in)) {
            int count = doc.pageCount();
            if (Collections.max(pages) >= count) {
                throw new UsageError(a.opName() + ": --range references page " + (Collections.max(pages) + 1)
                        + " but the document has only " + count + " pages");
            }            try (PdfDocument extracted = PdfSplit.extractPages(doc, pages)) {
                saveAtomically(extracted, out);
            }
        }
        if (!a.has("quiet")) {
            System.out.println("extracted " + pages.size() + " pages " + in + " -> " + out);
        }
        return 0;
    }

    private static int opReverse(Args a) throws Exception {
        return transform(a, "reversed", doc -> PdfPageReorder.reverse(doc));
    }

    private static int opNup(Args a) throws Exception {
        Path in = Path.of(a.positional(0));
        Path out = Path.of(a.positional(1));
        rejectSameFile(in, out);
        rejectOverwriteUnlessForced(out, a);
        int cols = a.intFlag("cols", 2);
        int rows = a.intFlag("rows", 2);
        if (cols < 1 || rows < 1) {
            throw new UsageError("--cols and --rows must be >= 1");
        }
        byte[] bytes;
        try (PdfDocument doc = PdfDocument.open(in)) {
            bytes = NUpLayout.from(doc).grid(cols, rows).a4Landscape().build().toBytes();
        }
        writeBytesAtomically(bytes, out, a);
        if (!a.has("quiet")) {
            System.out.println("laid out " + cols + "x" + rows + " N-up " + in + " -> " + out);
        }
        return 0;
    }

    private static int opSanitize(Args a) throws Exception {
        return transform(a, "sanitized", doc -> PdfSecurity.sanitize(doc));
    }

    private static int opToc(Args a) throws Exception {
        return transform(a, "added TOC to", doc -> PdfTocGenerator.generate(doc));
    }


    private static int opMerge(Args a) throws Exception {
        if (a.positional().size() < 3) {
            throw new UsageError("merge requires: merge <output.pdf> <input1.pdf> <input2.pdf> ...");
        }
        Path out = Path.of(a.positional(0));
        List<Path> inputs = a.positional().subList(1, a.positional().size()).stream().map(Path::of).toList();
        rejectOverwriteUnlessForced(out, a);
        try (PdfDocument merged = PdfMerge.mergeFiles(inputs)) {
            saveAtomically(merged, out);
        }
        if (!a.has("quiet")) {
            System.out.println("merged " + inputs.size() + " files -> " + out);
        }
        return 0;
    }

    private static int opSplit(Args a) throws Exception {
        Path in = Path.of(a.positional(0));
        Path outDir = Path.of(a.positional(1));
        int per = a.intFlag("pages-per", 1);
        if (per < 1) {
            throw new UsageError("--pages-per must be >= 1");
        }
        Files.createDirectories(outDir);
        int parts;
        try (PdfDocument doc = PdfDocument.open(in)) {
            List<int[]> ranges = PdfSplit.SplitStrategy.everyNPages(per).computeRanges(doc);
            parts = ranges.size();
            for (int i = 0; i < ranges.size(); i++) {
                int[] range = ranges.get(i);
                try (PdfDocument part = PdfSplit.extractPageRange(doc, range[0], range[1])) {
                    Path partPath = outDir.resolve(String.format("part-%03d.pdf", i + 1));
                    part.save(partPath);
                }
            }
        }
        if (!a.has("quiet")) {
            System.out.println("split " + in + " into " + parts + " parts in " + outDir);
        }
        return 0;
    }

    private static int opText(Args a) throws Exception {
        Path in = Path.of(a.positional(0));
        Path out = Path.of(a.positional(1));
        rejectSameFile(in, out);
        StringBuilder sb = new StringBuilder();
        try (PdfDocument doc = PdfDocument.open(in)) {
            for (PageText page : PdfTextExtractor.extractAll(doc)) {
                sb.append("----- Page ").append(page.pageIndex() + 1).append(" -----\n");
                sb.append(page.plainText()).append('\n');
            }
        }
        writeBytesAtomically(sb.toString().getBytes(StandardCharsets.UTF_8), out, a);
        if (!a.has("quiet")) {
            System.out.println("extracted text from " + in + " -> " + out);
        }
        return 0;
    }

    private static int opRender(Args a) throws Exception {
        Path in = Path.of(a.positional(0));
        Path outDir = Path.of(a.positional(1));
        int dpi = a.intFlag("dpi", 150);
        if (dpi < 1) {
            throw new UsageError("--dpi must be >= 1");
        }
        Files.createDirectories(outDir);
        int pages;
        try (PdfDocument doc = PdfDocument.open(in)) {
            pages = doc.pageCount();
            for (int i = 0; i < pages; i++) {
                RenderResult result;
                try (PdfPage page = doc.page(i)) {
                    result = page.renderAt(dpi);
                }
                byte[] png = PngEncoder.encodeRgba(result.rgba(), result.width(), result.height());
                Files.write(outDir.resolve(String.format("page-%03d.png", i + 1)), png);
            }
        }
        if (!a.has("quiet")) {
            System.out.println("rendered " + pages + " pages -> " + outDir);
        }
        return 0;
    }

    private static int opInfo(Args a) throws Exception {
        Path in = Path.of(a.positional(0));
        try (PdfDocument doc = PdfDocument.open(in)) {
            System.out.println("File: " + in);
            System.out.println("Pages: " + doc.pageCount());
            for (MetadataTag tag : MetadataTag.values()) {
                doc.metadata(tag.pdfKey()).ifPresent(value -> System.out.println(tag.pdfKey() + ": " + value));
            }
            for (int i = 0; i < doc.pageCount(); i++) {
                try (PdfPage page = doc.page(i)) {
                    PageSize size = page.size();
                    System.out.printf("Page %d: %.1f x %.1f pt%n", i + 1, size.width(), size.height());
                }
            }
        }
        return 0;
    }


    private static Rect parseRect(String s) {
        String[] parts = s.split(",");
        if (parts.length != 4) {
            throw new UsageError("rect must be x,y,w,h (got '" + s + "')");
        }
        try {
            return Rect.of(Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim()),
                    Float.parseFloat(parts[2].trim()), Float.parseFloat(parts[3].trim()));
        } catch (NumberFormatException e) {
            throw new UsageError("invalid rect: '" + s + "' (expected x,y,w,h)");
        }
    }

    private static Set<Integer> parsePageSet(String range, Args a) {
        TreeSet<Integer> pages = new TreeSet<>();
        for (String token : range.split(",")) {
            token = token.trim();
            if (token.isEmpty()) {
                continue;
            }
            int dash = token.indexOf('-');
            try {
                if (dash > 0) {
                    int from = Integer.parseInt(token.substring(0, dash).trim());
                    int to = Integer.parseInt(token.substring(dash + 1).trim());
                    if (from < 1 || to < 1) {
                        throw new UsageError(a.opName() + ": page numbers must be >= 1 (got '" + token + "')");
                    }
                    if (from > to) {
                        throw new UsageError(a.opName() + ": inverted range '" + token + "'");
                    }
                    for (int p = from; p <= to; p++) {
                        pages.add(p - 1);
                    }
                } else {
                    int page = Integer.parseInt(token);
                    if (page < 1) {
                        throw new UsageError(a.opName() + ": page numbers must be >= 1 (got '" + token + "')");
                    }
                    pages.add(page - 1);
                }
            } catch (NumberFormatException e) {
                throw new UsageError(a.opName() + ": invalid page token '" + token + "'");
            }
        }
        if (pages.isEmpty()) {
            throw new UsageError(a.opName() + ": no pages in range '" + range + "'");
        }
        return pages;
    }

    private static FitMode parseFitMode(String s) {
        return switch (s) {
            case "WIDTH" -> FitMode.FIT_WIDTH;
            case "HEIGHT" -> FitMode.FIT_HEIGHT;
            case "PAGE", "FIT_PAGE" -> FitMode.FIT_PAGE;
            default -> throw new IllegalArgumentException(s);
        };
    }

    private static int parseColor(String s) {
        String hex = s;
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        } else if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() == 6) {
            hex = "FF" + hex;
        }
        if (hex.length() != 8) {
            throw new UsageError("color must be RRGGBB or AARRGGBB (got '" + s + "')");
        }
        try {
            return Integer.parseUnsignedInt(hex, 16);
        } catch (NumberFormatException e) {
            throw new UsageError("invalid color '" + s + "'");
        }
    }

    private static void printHelp() {
        System.out.println("""
                jpdfium - PDF processing CLI (GraalVM native)

                Usage: jpdfium <operation> <args...>

                Operations:
                """);
        OPS.forEach((name, op) -> System.out.printf("  %-44s %s%n", op.usage(), op.blurb()));
        System.out.println("""

                Common flags:
                  --force      overwrite an existing output file
                  --quiet      suppress success messages
                  --verbose    print stack traces on failure

                Exit codes: 0 success, 1 processing failure, 2 usage error, 70 fatal.
                """);
    }

    /** Minimal PNG writer over {@link Deflater} - keeps java.desktop out of the native image. */
    private static final class PngEncoder {

        private static final byte[] SIGNATURE =
                {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        static byte[] encodeRgba(byte[] rgba, int width, int height) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(rgba.length + 128);
            out.writeBytes(SIGNATURE);
            writeChunk(out, "IHDR", ihdr(width, height));
            writeChunk(out, "IDAT", idat(rgba, width, height));
            writeChunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        }

        private static byte[] ihdr(int width, int height) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(13);
            writeInt(b, width);
            writeInt(b, height);
            b.write(8); // bit depth
            b.write(6); // color type: truecolor with alpha
            b.write(0); // compression: deflate
            b.write(0); // filter method
            b.write(0); // no interlace
            return b.toByteArray();
        }

        private static byte[] idat(byte[] rgba, int width, int height) {
            int stride = width * 4;
            byte[] raw = new byte[(stride + 1) * height];
            for (int y = 0; y < height; y++) {
                int row = y * (stride + 1);
                raw[row] = 0; // filter: none
                System.arraycopy(rgba, y * stride, raw, row + 1, stride);
            }
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            ByteArrayOutputStream b = new ByteArrayOutputStream(raw.length / 2);
            try {
                deflater.setInput(raw);
                deflater.finish();
                byte[] buf = new byte[8192];
                while (!deflater.finished()) {
                    int n = deflater.deflate(buf);
                    b.write(buf, 0, n);
                }
            } finally {
                deflater.end();
            }
            return b.toByteArray();
        }

        private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
            writeInt(out, data.length);
            byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
            out.writeBytes(typeBytes);
            out.writeBytes(data);
            CRC32 crc = new CRC32();
            crc.update(typeBytes);
            crc.update(data);
            writeInt(out, (int) crc.getValue());
        }

        private static void writeInt(ByteArrayOutputStream out, int value) {
            out.write((value >>> 24) & 0xFF);
            out.write((value >>> 16) & 0xFF);
            out.write((value >>> 8) & 0xFF);
            out.write(value & 0xFF);
        }
    }
}
