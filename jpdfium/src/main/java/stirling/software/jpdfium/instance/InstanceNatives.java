package stirling.software.jpdfium.instance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
 * {@code libX} to {@code NNNX}, except the allocator, which must stay single per process. The rename keeps every string the same length, so no load command
 * moves. Mach-O copies are re-signed ad hoc because the rename invalidates their signature.
 */
final class InstanceNatives {

    static final int MAX_INSTANCES = 999;

    private static final String FORMAT = "2";
    private static final String MARKER = "instance.properties";
    private static final String LIB = "lib";

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

    static Prepared prepare(Path baseDir, int index, Format format) throws IOException {
        String prefix = prefix(index);
        String pdfiumName = NativeLoader.libraryFileName("pdfium");
        String bridgeName = NativeLoader.libraryFileName("jpdfium");
        List<Path> libraries = bundledLibraries(baseDir);
        List<String> names = new ArrayList<>(libraries.size());
        for (Path library : libraries) {
            names.add(library.getFileName().toString());
        }
        if (!names.contains(pdfiumName) || !names.contains(bridgeName)) {
            throw new IOException("Natives in " + baseDir + " lack " + pdfiumName + " or " + bridgeName);
        }

        List<String> renamedNames = new ArrayList<>();
        for (String name : names) {
            if (!sharedAcrossInstances(name)) {
                renamedNames.add(name);
            }
        }

        Path dir = baseDir.resolveSibling(baseDir.getFileName() + "-instance-" + prefix);
        Prepared prepared = new Prepared(
                dir.resolve(renamed(pdfiumName, prefix)), dir.resolve(renamed(bridgeName, prefix)));
        String expected = marker(libraries);
        Path markerFile = dir.resolve(MARKER);
        if (Files.isRegularFile(markerFile)
                && Files.readString(markerFile, StandardCharsets.UTF_8).equals(expected)) {
            return prepared;
        }

        Files.createDirectories(dir);
        for (Path library : libraries) {
            String name = library.getFileName().toString();
            if (sharedAcrossInstances(name)) {
                linkShared(library, dir.resolve(name));
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
            write(patched, dir.resolve(renamed(name, prefix)), format);
        }
        Path tmpMarker = Files.createTempFile(dir, MARKER, ".tmp");
        Files.writeString(tmpMarker, expected, StandardCharsets.UTF_8);
        Files.move(tmpMarker, markerFile, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        return prepared;
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

    private static void linkShared(Path library, Path link) throws IOException {
        if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.createSymbolicLink(link, library.toAbsolutePath());
        } catch (java.nio.file.FileAlreadyExistsException raced) {
            // Another process prepared the same instance.
        }
    }

    private static List<Path> bundledLibraries(Path baseDir) throws IOException {
        List<Path> libraries = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(baseDir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
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
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
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

    private static String marker(List<Path> libraries) throws IOException {
        StringBuilder sb = new StringBuilder("format=").append(FORMAT).append('\n');
        for (Path library : libraries) {
            BasicFileAttributes a = Files.readAttributes(library, BasicFileAttributes.class);
            sb.append(library.getFileName()).append('=').append(a.size()).append('@')
                    .append(a.lastModifiedTime().toMillis()).append('\n');
        }
        return sb.toString();
    }
}
