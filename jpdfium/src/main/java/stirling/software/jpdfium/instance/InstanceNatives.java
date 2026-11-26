package stirling.software.jpdfium.instance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import stirling.software.jpdfium.panama.NativeLoader;

/**
 * Prepares one isolated instance's private copy of every bundled native library.
 *
 * <p>Copying PDFium alone is not enough. The dynamic linker shares a library between loads when
 * its name matches one already loaded (SONAME on ELF, install name on Mach-O), and libraries such
 * as Skia and fontconfig call back into PDFium: a shared Skia stays bound to the first PDFium, so
 * a second instance rendering through it touches the first instance's state and crashes. Each
 * instance therefore gets a copy of the whole bundle in which every bundled library name, wherever
 * it appears (file name, SONAME or install name, dependency entries), is renamed from
 * {@code libX} to {@code NNNX}, except the allocator, which must stay single per process. The
 * rename keeps every string the same length, so no load command moves. Mach-O copies are re-signed
 * ad hoc because the rename invalidates their signature.
 *
 * <p>Copies live in {@code instance-NNN} inside the directory the natives were loaded from, which
 * JPDFium created owner-only and keeps locked while loaded, so they share its protection and its
 * cleanup. Preparation runs under a per-instance file lock, builds in a staging directory and
 * moves it into place; a copy is reused only while its recorded SHA-256 still matches.
 */
final class InstanceNatives {

    static final int MAX_INSTANCES = 999;
    static final String DIR_PREFIX = "instance-";

    private static final String FORMAT = "3";
    private static final String MARKER = "instance.properties";
    private static final String LIB = "lib";
    private static final String VERIFY_PROPERTY = "jpdfium.native.verify";
    private static final Set<PosixFilePermission> OWNER_DIR =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_FILE =
            PosixFilePermissions.fromString("rw-------");
    private static final Map<Path, Object> IN_PROCESS_LOCKS = new ConcurrentHashMap<>();

    record Prepared(Path pdfium, Path bridge) {}

    enum Format {
        ELF,
        MACH_O
    }

    private InstanceNatives() {}

    /** Null when this platform cannot host more than one instance per process. */
    static Format currentFormat() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return null;
        }
        if (os.contains("mac")) {
            return Format.MACH_O;
        }
        return Format.ELF;
    }

    static String prefix(int index) {
        if (index < 1 || index > MAX_INSTANCES) {
            throw new IllegalArgumentException("instance index out of range: " + index);
        }
        return String.format(Locale.ROOT, "%03d", index);
    }

    /** {@code libskia.so} becomes {@code 007skia.so} for instance 7. */
    static String renamed(String libraryName, String prefix) {
        if (!libraryName.startsWith(LIB)) {
            throw new IllegalArgumentException("not a lib* name: " + libraryName);
        }
        return prefix + libraryName.substring(LIB.length());
    }

    private static String fileName(Path path) {
        return String.valueOf(path.getFileName());
    }

    static Path instanceDir(Path baseDir, String prefix) {
        return baseDir.resolve(DIR_PREFIX + prefix);
    }

    static Prepared prepare(Path baseDir, int index, Format format) throws IOException {
        String prefix = prefix(index);
        String pdfiumName = NativeLoader.libraryFileName("pdfium");
        String bridgeName = NativeLoader.libraryFileName("jpdfium");
        requirePrivate(baseDir, baseDir);
        List<Path> libraries = bundledLibraries(baseDir);
        List<String> names = new ArrayList<>(libraries.size());
        for (Path library : libraries) {
            names.add(fileName(library));
        }
        if (!names.contains(pdfiumName) || !names.contains(bridgeName)) {
            throw new IOException("Natives in " + baseDir + " lack " + pdfiumName + " or " + bridgeName);
        }

        Path dir = instanceDir(baseDir, prefix);
        Prepared prepared = new Prepared(
                dir.resolve(renamed(pdfiumName, prefix)), dir.resolve(renamed(bridgeName, prefix)));
        String sources = sourceLines(libraries);
        if (isValid(baseDir, dir, sources, libraries, prefix)) {
            return prepared;
        }

        Path lockFile = baseDir.resolve("." + DIR_PREFIX + prefix + ".lock");
        synchronized (IN_PROCESS_LOCKS.computeIfAbsent(
                lockFile.toAbsolutePath().normalize(), _ -> new Object())) {
            createOwnerOnlyFile(lockFile);
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
                 FileLock _ = channel.lock()) {
                if (!isValid(baseDir, dir, sources, libraries, prefix)) {
                    rebuild(baseDir, dir, libraries, names, prefix, pdfiumName, bridgeName,
                            format, sources);
                }
            }
        }
        return prepared;
    }

    private static void rebuild(Path baseDir, Path dir, List<Path> libraries, List<String> names,
            String prefix, String pdfiumName, String bridgeName, Format format, String sources)
            throws IOException {
        removeLeftovers(baseDir, prefix);
        Path staging = Files.createTempDirectory(baseDir, "." + DIR_PREFIX + prefix + "-staging-");
        try {
            requirePrivate(baseDir, staging);
            build(staging, libraries, names, prefix, pdfiumName, bridgeName, format, sources);
            replace(baseDir, staging, dir, prefix);
        } finally {
            deleteRecursively(staging);
        }
        if (!isValid(baseDir, dir, sources, libraries, prefix)) {
            throw new IOException("PDFium instance copies in " + dir
                    + " failed verification right after they were written");
        }
    }

    private static void build(Path staging, List<Path> libraries, List<String> names, String prefix,
            String pdfiumName, String bridgeName, Format format, String sources) throws IOException {
        List<String> renamedNames = new ArrayList<>();
        for (String name : names) {
            if (!sharedAcrossInstances(name)) {
                renamedNames.add(name);
            }
        }
        StringBuilder copies = new StringBuilder();
        for (Path library : libraries) {
            String name = fileName(library);
            if (sharedAcrossInstances(name)) {
                Files.createSymbolicLink(staging.resolve(name), library.toAbsolutePath());
                continue;
            }
            byte[] patched = patch(Files.readAllBytes(library), renamedNames, prefix);
            if (name.equals(pdfiumName) && count(patched, renamed(pdfiumName, prefix)) == 0) {
                throw new IOException(pdfiumName + " carries no name of its own to rename");
            }
            if (name.equals(bridgeName) && count(patched, renamed(pdfiumName, prefix)) == 0) {
                throw new IOException(bridgeName + " does not name " + pdfiumName
                        + ", so its copy could not be pointed at its own PDFium");
            }
            Path target = staging.resolve(renamed(name, prefix));
            write(patched, target, format);
            copies.append("copy ").append(target.getFileName()).append('=')
                    .append(sha256(target)).append('\n');
        }
        Path marker = staging.resolve(MARKER);
        createOwnerOnlyFile(marker);
        Files.writeString(marker, "format=" + FORMAT + "\n" + sources + copies,
                StandardCharsets.UTF_8);
    }

    private static void replace(Path baseDir, Path staging, Path dir, String prefix)
            throws IOException {
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Path old = baseDir.resolve("." + DIR_PREFIX + prefix + "-old-" + System.nanoTime());
            Files.move(dir, old, StandardCopyOption.ATOMIC_MOVE);
            deleteRecursively(old);
        }
        try {
            Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException _) {
            Files.move(staging, dir);
        }
    }

    private static void removeLeftovers(Path baseDir, String prefix) throws IOException {
        try (DirectoryStream<Path> entries =
                     Files.newDirectoryStream(baseDir, "." + DIR_PREFIX + prefix + "-*")) {
            for (Path entry : entries) {
                deleteRecursively(entry);
            }
        }
    }

    static boolean isValid(Path baseDir, Path dir, String sources, List<Path> libraries,
            String prefix) {
        try {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || !isPrivate(baseDir, dir)) {
                return false;
            }
            Path marker = dir.resolve(MARKER);
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !("format=" + FORMAT).equals(lines.getFirst())) {
                return false;
            }
            Map<String, String> recorded = new HashMap<>();
            StringBuilder recordedSources = new StringBuilder();
            for (String line : lines.subList(1, lines.size())) {
                if (line.startsWith("source ")) {
                    recordedSources.append(line).append('\n');
                } else if (line.startsWith("copy ")) {
                    int eq = line.lastIndexOf('=');
                    recorded.put(line.substring("copy ".length(), eq), line.substring(eq + 1));
                }
            }
            if (!recordedSources.toString().equals(sources)) {
                return false;
            }
            boolean hash = verifyHashes();
            Set<String> expected = new HashSet<>();
            for (Path library : libraries) {
                String name = fileName(library);
                if (sharedAcrossInstances(name)) {
                    Path link = dir.resolve(name);
                    if (!Files.isSymbolicLink(link)
                            || !Files.readSymbolicLink(link).equals(library.toAbsolutePath())) {
                        return false;
                    }
                    continue;
                }
                String copyName = renamed(name, prefix);
                expected.add(copyName);
                Path copy = dir.resolve(copyName);
                if (!Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS)
                        || !recorded.containsKey(copyName)) {
                    return false;
                }
                if (hash && !sha256(copy).equals(recorded.get(copyName))) {
                    return false;
                }
            }
            return expected.equals(recorded.keySet());
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    private static boolean verifyHashes() {
        return !"marker".equalsIgnoreCase(System.getProperty(VERIFY_PROPERTY));
    }

    /**
     * Renames every reference to a bundled library: an occurrence of the exact name that ends at
     * a NUL and starts after a NUL or a {@code /}, which is how ELF string tables and Mach-O load
     * commands store them. Returns a new array; the input is untouched.
     */
    static byte[] patch(byte[] binary, List<String> libraryNames, String prefix) {
        Set<String> names = Set.copyOf(libraryNames);
        int longest = 0;
        for (String name : names) {
            longest = Math.max(longest, name.length());
        }
        byte[] tag = prefix.getBytes(StandardCharsets.US_ASCII);
        byte[] out = binary.clone();
        for (int i = 1; i + 3 < out.length; i++) {
            if (out[i] != 'l' || out[i + 1] != 'i' || out[i + 2] != 'b') {
                continue;
            }
            byte before = out[i - 1];
            if (before != 0 && before != '/') {
                continue;
            }
            int end = i;
            while (end < out.length && end - i <= longest && out[end] != 0) {
                end++;
            }
            if (end >= out.length || out[end] != 0) {
                continue;
            }
            if (names.contains(new String(out, i, end - i, StandardCharsets.US_ASCII))) {
                System.arraycopy(tag, 0, out, i, tag.length);
                i = end;
            }
        }
        return out;
    }

    private static boolean matches(byte[] data, int at, byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (data[at + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }

    static int count(byte[] data, String name) {
        byte[] needle = name.getBytes(StandardCharsets.US_ASCII);
        int hits = 0;
        for (int i = 0; i + needle.length <= data.length; i++) {
            if (matches(data, i, needle)) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * PartitionAlloc reserves process-wide address pools when it starts, so a second copy in the
     * same process faults; every instance uses the one copy, which is thread-safe.
     */
    static boolean sharedAcrossInstances(String libraryName) {
        return libraryName.contains("partition_alloc");
    }

    /**
     * Checks a {@code /proc/self/maps} listing after a pool is built. PDFium and the bridge under
     * their own names may only come from {@code baseDir}; a renamed copy {@code NNNx} may only come
     * from {@code instance-NNN}; and the renamed PDFium and bridge of every listed instance must be
     * mapped. Other bundled names may also be mapped from elsewhere, because the JVM loads system
     * copies of libraries such as fontconfig for its own use.
     *
     * @throws IOException naming the first mapping that breaks isolation
     */
    static void verifyMappings(String maps, Path baseDir, Collection<Integer> indices)
            throws IOException {
        Path base = baseDir.toRealPath();
        Set<String> bundle = new HashSet<>();
        for (Path library : bundledLibraries(baseDir)) {
            bundle.add(fileName(library));
        }
        String pdfiumName = NativeLoader.libraryFileName("pdfium");
        String bridgeName = NativeLoader.libraryFileName("jpdfium");
        Set<Path> mapped = new HashSet<>();
        for (String line : maps.split("\n")) {
            Path path = mappedPath(line);
            if (path != null) {
                mapped.add(path);
            }
        }
        for (Path path : mapped) {
            String name = fileName(path);
            Path parent = path.getParent();
            if (parent == null) {
                continue;
            }
            if (name.equals(pdfiumName) || name.equals(bridgeName)) {
                if (!parent.equals(base)) {
                    throw new IOException(name + " is mapped from " + parent
                            + " as well as from " + base);
                }
                continue;
            }
            if (name.length() > 3 && name.substring(0, 3).chars().allMatch(Character::isDigit)
                    && bundle.contains(LIB + name.substring(3))) {
                Path own = instanceDir(base, name.substring(0, 3));
                if (!parent.equals(own)) {
                    throw new IOException(name + " is mapped from " + parent + ", not " + own);
                }
            }
        }
        Set<String> loadedFromBase = new HashSet<>(List.of(pdfiumName, bridgeName));
        for (Path path : mapped) {
            String name = fileName(path);
            if (base.equals(path.getParent()) && bundle.contains(name)
                    && !sharedAcrossInstances(name)) {
                loadedFromBase.add(name);
            }
        }
        // Every library the base copy uses must also be the instance's own; one an instance
        // reaches through the shared copy instead would call back into another PDFium.
        for (int index : indices) {
            String prefix = prefix(index);
            Path own = instanceDir(base, prefix);
            for (String original : loadedFromBase) {
                String required = renamed(original, prefix);
                if (!mapped.contains(own.resolve(required))) {
                    throw new IOException("PDFium instance " + index + " did not map its own "
                            + required + " from " + own);
                }
            }
        }
    }

    private static Path mappedPath(String line) {
        String[] fields = line.trim().split("\\s+", 6);
        if (fields.length < 6 || !fields[5].startsWith("/")) {
            return null;
        }
        String path = fields[5];
        if (path.endsWith(" (deleted)")) {
            path = path.substring(0, path.length() - " (deleted)".length());
        }
        return Path.of(path).normalize();
    }

    static List<Path> bundledLibraries(Path baseDir) throws IOException {
        List<Path> libraries = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(baseDir)) {
            for (Path file : files) {
                String name = fileName(file);
                if (Files.isRegularFile(file) && name.startsWith(LIB) && isLibrary(name)) {
                    libraries.add(file);
                }
            }
        }
        libraries.sort(null);
        return libraries;
    }

    private static boolean isLibrary(String name) {
        return name.endsWith(".so") || name.contains(".so.") || name.endsWith(".dylib");
    }

    private static void write(byte[] bytes, Path target, Format format) throws IOException {
        Path tmp = Files.createTempFile(Objects.requireNonNull(target.getParent()), fileName(target), ".tmp");
        try {
            Files.write(tmp, bytes);
            if (format == Format.MACH_O) {
                signAdHoc(tmp);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void signAdHoc(Path file) throws IOException {
        Process process = new ProcessBuilder("/usr/bin/codesign", "--force", "--sign", "-",
                file.toString()).redirectErrorStream(true).start();
        try {
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("codesign timed out on " + file);
            }
            if (process.exitValue() != 0) {
                throw new IOException("codesign failed on " + file + ": "
                        + new String(output, StandardCharsets.UTF_8).trim());
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while signing " + file, e);
        }
    }

    private static String sourceLines(List<Path> libraries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path library : libraries) {
            BasicFileAttributes a = Files.readAttributes(library, BasicFileAttributes.class);
            sb.append("source ").append(library.getFileName()).append('=').append(a.size())
                    .append('@').append(a.lastModifiedTime().toMillis()).append('\n');
        }
        return sb.toString();
    }

    private static void requirePrivate(Path baseDir, Path dir) throws IOException {
        try {
            Files.setPosixFilePermissions(dir, OWNER_DIR);
        } catch (UnsupportedOperationException _) {
            throw new IOException("cannot restrict " + dir + " to its owner");
        }
        if (!isPrivate(baseDir, dir)) {
            throw new IOException("PDFium instance directory is not private: " + dir);
        }
    }

    private static boolean isPrivate(Path baseDir, Path dir) throws IOException {
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS);
        boolean ownerOnly = perms.stream()
                .noneMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"));
        return ownerOnly && Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS)
                .equals(Files.getOwner(baseDir, LinkOption.NOFOLLOW_LINKS));
    }

    private static void createOwnerOnlyFile(Path file) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_FILE));
        } catch (FileAlreadyExistsException _) {
            // Another process created it first.
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException _) {
                    // A later preparation removes what is left.
                }
            });
        } catch (IOException _) {
            // A later preparation removes what is left.
        }
    }
}
