package stirling.software.jpdfium.instance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.panama.NativeLoader;

class InstanceCopiesTest {

    private static final String PDFIUM = NativeLoader.libraryFileName("pdfium");
    private static final String BRIDGE = NativeLoader.libraryFileName("jpdfium");
    private static final String SKIA = NativeLoader.libraryFileName("skia");
    private static final String ALLOCATOR =
            NativeLoader.libraryFileName("base_allocator_partition_allocator_src_partition_alloc_allocator_core");

    @TempDir
    Path temp;
    private Path base;

    @BeforeEach
    void bundle() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        base = Files.createDirectory(temp.resolve("entry"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                .toRealPath();
        lib(PDFIUM, "|" + PDFIUM + "|");
        lib(BRIDGE, "|" + BRIDGE + "|" + PDFIUM + "|" + SKIA + "|");
        lib(SKIA, "|" + SKIA + "|" + PDFIUM + "|");
        lib(ALLOCATOR, "|" + ALLOCATOR + "|");
    }

    private void lib(String name, String content) throws IOException {
        Files.write(base.resolve(name), content.replace('|', '\0').getBytes(StandardCharsets.US_ASCII));
    }

    private InstanceNatives.Prepared prepare(int index) throws IOException {
        return InstanceNatives.prepare(base, index, InstanceNatives.Format.ELF);
    }

    @Test
    void copiesAreNestedOwnerOnlyAndRenamed() throws IOException {
        InstanceNatives.Prepared prepared = prepare(4);

        Path dir = base.resolve("instance-004");
        assertEquals(dir.resolve("004" + PDFIUM.substring(3)), prepared.pdfium());
        assertEquals(dir.resolve("004" + BRIDGE.substring(3)), prepared.bridge());
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        String bridge = Files.readString(prepared.bridge(), StandardCharsets.ISO_8859_1);
        assertTrue(bridge.contains("004" + PDFIUM.substring(3)));
        assertTrue(bridge.contains("004" + SKIA.substring(3)));
        assertTrue(Files.isSymbolicLink(dir.resolve(ALLOCATOR)));
        assertTrue(Files.readString(dir.resolve("instance.properties")).contains("copy 004"));
    }

    @Test
    void validCopiesAreReusedUntouched() throws IOException {
        InstanceNatives.Prepared prepared = prepare(1);
        FileTime written = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(prepared.pdfium(), written);

        prepare(1);

        assertEquals(written, Files.getLastModifiedTime(prepared.pdfium()));
    }

    @Test
    void aTamperedCopyIsRebuilt() throws IOException {
        InstanceNatives.Prepared prepared = prepare(1);
        byte[] good = Files.readAllBytes(prepared.bridge());
        Files.write(prepared.bridge(), new byte[] {1}, StandardOpenOption.APPEND);

        prepare(1);

        assertArrayEquals(good, Files.readAllBytes(prepared.bridge()));
    }

    @Test
    void aCopyDirectoryOthersCanWriteIsRebuiltPrivate() throws IOException {
        prepare(2);
        Path dir = base.resolve("instance-002");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));

        prepare(2);

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    }

    @Test
    void concurrentPreparationsAgree() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(6);
        try {
            List<Future<InstanceNatives.Prepared>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                Callable<InstanceNatives.Prepared> task = () -> prepare(3);
                results.add(threads.submit(task));
            }
            for (Future<InstanceNatives.Prepared> result : results) {
                assertTrue(Files.isRegularFile(result.get().bridge()));
            }
        } finally {
            threads.shutdownNow();
        }
        try (var entries = Files.list(base)) {
            assertFalse(entries.anyMatch(p -> p.getFileName().toString().contains("-staging-")));
        }
    }

    @Test
    void mappingsFromTheirOwnDirectoriesPass() throws IOException {
        String maps = maps(base.resolve(PDFIUM), base.resolve(BRIDGE),
                base.resolve("instance-001/001" + PDFIUM.substring(3)),
                base.resolve("instance-001/001" + BRIDGE.substring(3)),
                base.resolve("instance-001/001" + SKIA.substring(3)),
                Path.of("/usr/lib/" + SKIA));

        InstanceNatives.verifyMappings(maps, base, List.of(1));
    }

    @Test
    void aSecondUnrenamedPdfiumFails() {
        String maps = maps(base.resolve(PDFIUM), Path.of("/tmp/jpdfium-other/" + PDFIUM),
                base.resolve("instance-001/001" + PDFIUM.substring(3)),
                base.resolve("instance-001/001" + BRIDGE.substring(3)));

        assertThrows(IOException.class, () -> InstanceNatives.verifyMappings(maps, base, List.of(1)));
    }

    @Test
    void aCopyFromAnotherInstanceDirectoryFails() {
        String maps = maps(base.resolve("instance-001/001" + PDFIUM.substring(3)),
                base.resolve("instance-001/001" + BRIDGE.substring(3)),
                base.resolve("instance-002/001" + SKIA.substring(3)));

        assertThrows(IOException.class, () -> InstanceNatives.verifyMappings(maps, base, List.of(1)));
    }

    @Test
    void anInstanceUsingTheBaseCopyOfAHelperLibraryFails() {
        String maps = maps(base.resolve(PDFIUM), base.resolve(BRIDGE), base.resolve(SKIA),
                base.resolve("instance-001/001" + PDFIUM.substring(3)),
                base.resolve("instance-001/001" + BRIDGE.substring(3)));

        assertThrows(IOException.class, () -> InstanceNatives.verifyMappings(maps, base, List.of(1)));
    }

    @Test
    void anInstanceThatMappedNothingOfItsOwnFails() {
        String maps = maps(base.resolve(PDFIUM), base.resolve("instance-001/001" + PDFIUM.substring(3)));

        assertThrows(IOException.class, () -> InstanceNatives.verifyMappings(maps, base, List.of(1)));
    }

    private static String maps(Path... files) {
        StringBuilder sb = new StringBuilder("7f0000-7f1000 r--p 00000000 00:00 0  [heap]\n");
        for (Path file : files) {
            sb.append("7f0000-7f1000 r-xp 00000000 fd:01 1234   ").append(file).append('\n');
        }
        return sb.toString();
    }
}
