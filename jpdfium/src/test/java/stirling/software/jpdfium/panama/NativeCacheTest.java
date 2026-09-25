package stirling.software.jpdfium.panama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeCacheTest {

    private static final List<String> NAMES = List.of("libpdfium.dylib", "libjpdfium.dylib");

    private static Map<String, byte[]> files() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("libpdfium.dylib", "pdfium-bytes".getBytes(StandardCharsets.UTF_8));
        files.put("libjpdfium.dylib", "bridge-bytes".getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static Map<String, String> hashes(Map<String, byte[]> files) {
        Map<String, String> hashes = new LinkedHashMap<>();
        files.forEach((name, data) -> hashes.put(name, sha256(data)));
        return hashes;
    }

    private static NativeCache.Resources resources(Map<String, byte[]> files) {
        return name -> {
            byte[] data = files.get(name);
            return data == null ? null : new ByteArrayInputStream(data);
        };
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void parseChecksumsReadsSha256SumLines() {
        String first = sha256("one".getBytes(StandardCharsets.UTF_8));
        String second = sha256("two".getBytes(StandardCharsets.UTF_8));
        String text = "# comment\n"
                + first + "  libpdfium.dylib\n"
                + "not-a-hash  broken.dylib\n"
                + "\n"
                + second + " libjpdfium.dylib\n";
        Map<String, String> parsed = NativeCache.parseChecksums(text);
        assertEquals(Set.of("libpdfium.dylib", "libjpdfium.dylib"), parsed.keySet());
        assertEquals(first, parsed.get("libpdfium.dylib"));
        assertEquals(second, parsed.get("libjpdfium.dylib"));
    }

    @Test
    void cacheKeyIsContentAddressedAndOrderIndependent() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("a", "11");
        first.put("b", "22");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("b", "22");
        second.put("a", "11");
        assertEquals(NativeCache.cacheKey(first), NativeCache.cacheKey(second));
        assertFalse(NativeCache.cacheKey(first).equals(NativeCache.cacheKey(Map.of("a", "11", "b", "33"))));
    }

    @Test
    void resolveCacheRootUsesPlatformDefaults() {
        Path tmp = Path.of("/tmp/x");
        assertEquals(Path.of("/custom"), NativeCache.resolveCacheRoot(
                "/custom", "Windows 11", "C:/Users/u", "C:/Users/u/AppData/Local", null, tmp));
        assertEquals(Path.of("C:/Users/u/AppData/Local/jpdfium/native"), NativeCache.resolveCacheRoot(
                null, "Windows 11", "C:/Users/u", "C:/Users/u/AppData/Local", null, tmp));
        assertEquals(Path.of("/Users/u/Library/Caches/jpdfium/native"), NativeCache.resolveCacheRoot(
                null, "Mac OS X", "/Users/u", null, null, tmp));
        assertEquals(Path.of("/xdg/jpdfium/native"), NativeCache.resolveCacheRoot(
                null, "Linux", "/home/u", null, "/xdg", tmp));
        assertEquals(Path.of("/home/u/.cache/jpdfium/native"), NativeCache.resolveCacheRoot(
                null, "Linux", "/home/u", null, null, tmp));
        assertEquals(tmp.resolve("jpdfium-cache"), NativeCache.resolveCacheRoot(
                null, "Linux", "", null, null, tmp));
    }

    @Test
    void prepareExtractsVerifiesAndReuses(@TempDir Path tmp) throws Exception {
        Path platformDir = Files.createDirectories(tmp.resolve("darwin-arm64"));
        Map<String, byte[]> files = files();
        Map<String, String> hashes = hashes(files);
        String key = NativeCache.cacheKey(hashes);
        AtomicInteger opens = new AtomicInteger();
        NativeCache.Resources counting = name -> {
            opens.incrementAndGet();
            return resources(files).open(name);
        };

        Path entry = NativeCache.prepareIn(platformDir, NAMES, hashes, key, counting, false);
        assertNotNull(entry);
        assertEquals(2, opens.get(), "every listed file is read once");
        assertEquals("pdfium-bytes", Files.readString(entry.resolve("libpdfium.dylib")));
        assertTrue(NativeCache.isValidEntry(entry, NAMES, hashes, key, true));

        NativeCache.Resources mustNotRead = name -> {
            throw new IOException("cache hit must not read resources again");
        };
        assertEquals(entry, NativeCache.prepareIn(platformDir, NAMES, hashes, key, mustNotRead, false));
    }

    @Test
    void prepareRejectsChecksumMismatch(@TempDir Path tmp) throws Exception {
        Path platformDir = Files.createDirectories(tmp.resolve("p"));
        Map<String, byte[]> files = files();
        Map<String, String> wrong = new LinkedHashMap<>(hashes(files));
        wrong.put("libpdfium.dylib", sha256("different".getBytes(StandardCharsets.UTF_8)));
        String key = NativeCache.cacheKey(wrong);

        assertThrows(IOException.class, () -> NativeCache.prepareIn(
                platformDir, NAMES, wrong, key, resources(files), false));
        assertFalse(Files.exists(platformDir.resolve(key)), "failed extraction must not publish");
    }

    @Test
    void prepareRecoversCorruptEntry(@TempDir Path tmp) throws Exception {
        Path platformDir = Files.createDirectories(tmp.resolve("p"));
        Map<String, byte[]> files = files();
        Map<String, String> hashes = hashes(files);
        String key = NativeCache.cacheKey(hashes);
        Path entry = Files.createDirectories(platformDir.resolve(key));
        Files.writeString(entry.resolve(".verified"), "bogus");
        Files.writeString(entry.resolve("libpdfium.dylib"), "corrupt");

        Path prepared = NativeCache.prepareIn(platformDir, NAMES, hashes, key, resources(files), false);
        assertEquals(entry, prepared);
        assertTrue(NativeCache.isValidEntry(entry, NAMES, hashes, key, true));
        assertEquals("pdfium-bytes", Files.readString(entry.resolve("libpdfium.dylib")));
    }

    @Test
    void fullVerificationDetectsTamper(@TempDir Path tmp) throws Exception {
        Path platformDir = Files.createDirectories(tmp.resolve("p"));
        Map<String, byte[]> files = files();
        Map<String, String> hashes = hashes(files);
        String key = NativeCache.cacheKey(hashes);
        Path entry = NativeCache.prepareIn(platformDir, NAMES, hashes, key, resources(files), false);

        assertTrue(NativeCache.isValidEntry(entry, NAMES, hashes, key, true));
        Files.writeString(entry.resolve("libpdfium.dylib"), "tampered");
        assertFalse(NativeCache.isValidEntry(entry, NAMES, hashes, key, true));
        assertTrue(NativeCache.isValidEntry(entry, NAMES, hashes, key, false));
    }

    @Test
    void sweepDeletesStaleDirsAndKeepsFresh(@TempDir Path tmp) throws Exception {
        Path stale = Files.createDirectories(tmp.resolve("jpdfium-stale"));
        Files.writeString(stale.resolve("pdfium.dll"), "x");
        Files.setLastModifiedTime(stale,
                FileTime.fromMillis(System.currentTimeMillis() - 2L * 60L * 60L * 1000L));
        Path fresh = Files.createDirectories(tmp.resolve("jpdfium-fresh"));

        NativeCache.sweepTempDirs(tmp, NativeCache.SWEEP_MIN_AGE_MILLIS, null);

        assertFalse(Files.exists(stale), "stale extraction dir must be swept");
        assertTrue(Files.exists(fresh), "a starting JVM may still own its fresh dir");
    }

    @Test
    void obsoleteEntriesRemovedOnlyWhenStale(@TempDir Path tmp) throws Exception {
        Path platformDir = Files.createDirectories(tmp.resolve("p"));
        Path old = Files.createDirectories(platformDir.resolve("oldkey"));
        Files.setLastModifiedTime(old, FileTime.fromMillis(
                System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1000L));
        Path recent = Files.createDirectories(platformDir.resolve("recentkey"));

        NativeCache.removeObsoleteEntries(platformDir, "currentkey", NativeCache.OBSOLETE_MIN_AGE_MILLIS);

        assertFalse(Files.exists(old), "stale content hash must be removed");
        assertTrue(Files.exists(recent), "a recent entry may belong to another running JVM");
    }

    @Test
    void posixCacheEntriesAreOwnerOnly(@TempDir Path tmp) throws Exception {
        Path platformDir = Files.createDirectories(tmp.resolve("p"));
        Map<String, byte[]> files = files();
        Map<String, String> hashes = hashes(files);
        Path entry = NativeCache.prepareIn(platformDir, NAMES, hashes,
                NativeCache.cacheKey(hashes), resources(files), false);
        try {
            Set<PosixFilePermission> dirPerms = Files.getPosixFilePermissions(entry);
            assertEquals(PosixFilePermissions.fromString("rwx------"), dirPerms);
            Set<PosixFilePermission> filePerms =
                    Files.getPosixFilePermissions(entry.resolve("libpdfium.dylib"));
            assertEquals(PosixFilePermissions.fromString("rw-------"), filePerms);
        } catch (UnsupportedOperationException _) {
            // Windows uses LOCALAPPDATA ACLs instead of POSIX permissions.
        }
    }
}
