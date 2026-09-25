package stirling.software.jpdfium.panama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.security.auth.Subject;

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
        assertEquals(Path.of("/custom"), NativeCache.resolveCacheRoot(
                "/custom", "Windows 11", "C:/Users/u", "C:/Users/u/AppData/Local", null));
        assertEquals(Path.of("C:/Users/u/AppData/Local/jpdfium/native"), NativeCache.resolveCacheRoot(
                null, "Windows 11", "C:/Users/u", "C:/Users/u/AppData/Local", null));
        assertEquals(Path.of("/Users/u/Library/Caches/jpdfium/native"), NativeCache.resolveCacheRoot(
                null, "Mac OS X", "/Users/u", null, null));
        assertEquals(Path.of("/xdg/jpdfium/native"), NativeCache.resolveCacheRoot(
                null, "Linux", "/home/u", null, "/xdg"));
        assertEquals(Path.of("/home/u/.cache/jpdfium/native"), NativeCache.resolveCacheRoot(
                null, "Linux", "/home/u", null, null));
        assertNull(NativeCache.resolveCacheRoot(null, "Linux", "", null, null),
                "no user cache means temp extraction, never a shared temp cache");
    }

    @Test
    void verifyDefaultsToFull() {
        String original = System.getProperty(NativeCache.VERIFY_PROPERTY);
        try {
            System.clearProperty(NativeCache.VERIFY_PROPERTY);
            assertTrue(NativeCache.verifyFull(), "cache hits are verified unless explicitly opted out");
            System.setProperty(NativeCache.VERIFY_PROPERTY, "marker");
            assertFalse(NativeCache.verifyFull());
            System.setProperty(NativeCache.VERIFY_PROPERTY, "full");
            assertTrue(NativeCache.verifyFull());
        } finally {
            if (original == null) {
                System.clearProperty(NativeCache.VERIFY_PROPERTY);
            } else {
                System.setProperty(NativeCache.VERIFY_PROPERTY, original);
            }
        }
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

        Path entry = NativeCache.prepareIn(platformDir, NAMES, hashes, key, counting, true);
        assertNotNull(entry);
        assertEquals(2, opens.get(), "every listed file is read once");
        assertEquals("pdfium-bytes", Files.readString(entry.resolve("libpdfium.dylib")));
        assertTrue(NativeCache.isValidEntry(entry, NAMES, hashes, key, true));

        NativeCache.Resources mustNotRead = name -> {
            throw new IOException("cache hit must not read resources again");
        };
        assertEquals(entry, NativeCache.prepareIn(platformDir, NAMES, hashes, key, mustNotRead, true));
    }

    @Test
    void prepareRejectsChecksumMismatch(@TempDir Path tmp) throws Exception {
        Path platformDir = Files.createDirectories(tmp.resolve("p"));
        Map<String, byte[]> files = files();
        Map<String, String> wrong = new LinkedHashMap<>(hashes(files));
        wrong.put("libpdfium.dylib", sha256("different".getBytes(StandardCharsets.UTF_8)));
        String key = NativeCache.cacheKey(wrong);

        assertThrows(NativeCache.IntegrityException.class, () -> NativeCache.prepareIn(
                platformDir, NAMES, wrong, key, resources(files), true));
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

        Path prepared = NativeCache.prepareIn(platformDir, NAMES, hashes, key, resources(files), true);
        assertEquals(entry, prepared);
        assertTrue(NativeCache.isValidEntry(entry, NAMES, hashes, key, true));
        assertEquals("pdfium-bytes", Files.readString(entry.resolve("libpdfium.dylib")));
    }

    @Test
    void tamperedEntryIsReExtracted(@TempDir Path tmp) throws Exception {
        Path platformDir = Files.createDirectories(tmp.resolve("p"));
        Map<String, byte[]> files = files();
        Map<String, String> hashes = hashes(files);
        String key = NativeCache.cacheKey(hashes);
        Path entry = Files.createDirectories(platformDir.resolve(key));
        Files.writeString(entry.resolve(".verified"), key);
        Files.writeString(entry.resolve("libpdfium.dylib"), "tampered");
        Files.writeString(entry.resolve("libjpdfium.dylib"), "bridge-bytes");

        Path prepared = NativeCache.prepareIn(platformDir, NAMES, hashes, key, resources(files), true);

        assertEquals("pdfium-bytes", Files.readString(prepared.resolve("libpdfium.dylib")));
    }

    @Test
    void fullVerificationDetectsTamper(@TempDir Path tmp) throws Exception {
        Path platformDir = Files.createDirectories(tmp.resolve("p"));
        Map<String, byte[]> files = files();
        Map<String, String> hashes = hashes(files);
        String key = NativeCache.cacheKey(hashes);
        Path entry = NativeCache.prepareIn(platformDir, NAMES, hashes, key, resources(files), true);

        assertTrue(NativeCache.isValidEntry(entry, NAMES, hashes, key, true));
        Files.writeString(entry.resolve("libpdfium.dylib"), "tampered");
        assertFalse(NativeCache.isValidEntry(entry, NAMES, hashes, key, true));
        assertTrue(NativeCache.isValidEntry(entry, NAMES, hashes, key, false));
    }

    @Test
    void readerLockPreventsObsoleteCleanup(@TempDir Path tmp) throws Exception {
        Path platformDir = Files.createDirectories(tmp.resolve("p"));
        Map<String, byte[]> files = files();
        Map<String, String> hashes = hashes(files);
        String key = NativeCache.cacheKey(hashes);
        Path entry = NativeCache.prepareIn(platformDir, NAMES, hashes, key, resources(files), true);
        Files.setLastModifiedTime(entry, FileTime.fromMillis(
                System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1000L));

        NativeCache.removeObsoleteEntries(platformDir, "other-key", NativeCache.OBSOLETE_MIN_AGE_MILLIS);

        assertTrue(Files.exists(entry), "an entry held open by a live JVM must not be removed");
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
    void sweepSkipsLockedFallbackDir(@TempDir Path tmp) throws Exception {
        Path live = Files.createDirectories(tmp.resolve("jpdfium-live"));
        Path lockFile = Files.writeString(live.resolve(".lock"), "");
        Path stale = Files.createDirectories(tmp.resolve("jpdfium-stale"));
        long old = System.currentTimeMillis() - 2L * 60L * 60L * 1000L;
        Files.setLastModifiedTime(live, FileTime.fromMillis(old));
        Files.setLastModifiedTime(stale, FileTime.fromMillis(old));

        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock _ = channel.lock()) {
            NativeCache.sweepTempDirs(tmp, NativeCache.SWEEP_MIN_AGE_MILLIS, null);
        }

        assertTrue(Files.exists(live), "a running JVM's fallback dir must survive the sweep");
        assertFalse(Files.exists(stale), "an unlocked stale dir must be swept");
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
                NativeCache.cacheKey(hashes), resources(files), true);
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

    @Test
    void isPrivateRejectsGroupOrOthersAccess(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("shared"));
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
            assertFalse(NativeCache.isPrivate(dir));
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            assertTrue(NativeCache.isPrivate(dir));
        } catch (UnsupportedOperationException _) {
            // Windows has no POSIX permissions.
        }
    }

    @Test
    void copyVerifiedRejectsMismatch(@TempDir Path tmp) {
        Path target = tmp.resolve("lib.dll");
        assertThrows(NativeCache.IntegrityException.class, () -> NativeCache.copyVerified(
                new ByteArrayInputStream("tampered".getBytes(StandardCharsets.UTF_8)),
                target, sha256("expected".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void isSafeNameRejectsTraversal() {
        assertTrue(NativeCache.isSafeName("libpdfium.dylib"));
        assertFalse(NativeCache.isSafeName(""));
        assertFalse(NativeCache.isSafeName("."));
        assertFalse(NativeCache.isSafeName(".."));
        assertFalse(NativeCache.isSafeName("../evil"));
        assertFalse(NativeCache.isSafeName("a/b"));
        assertFalse(NativeCache.isSafeName("a\\b"));
        assertFalse(NativeCache.isSafeName("/abs"));
    }

    @Test
    void prepareRejectsUnsafeName(@TempDir Path tmp) throws Exception {
        Path platformDir = Files.createDirectories(tmp.resolve("p"));
        Map<String, String> hashes = new LinkedHashMap<>(hashes(files()));
        hashes.put("../evil", sha256("x".getBytes(StandardCharsets.UTF_8)));
        List<String> unsafe = List.of("../evil");

        assertThrows(NativeCache.IntegrityException.class, () -> NativeCache.prepareIn(
                platformDir, unsafe, hashes, NativeCache.cacheKey(hashes), resources(files()), true));
    }

    @Test
    void staleStagingIsReclaimed(@TempDir Path tmp) throws Exception {
        Path platformDir = Files.createDirectories(tmp.resolve("p"));
        Path stale = Files.createDirectories(platformDir.resolve(".staging-old"));
        Files.setLastModifiedTime(stale,
                FileTime.fromMillis(System.currentTimeMillis() - 2L * 60L * 60L * 1000L));
        Path fresh = Files.createDirectories(platformDir.resolve(".staging-fresh"));
        Map<String, byte[]> files = files();
        Map<String, String> hashes = hashes(files);

        NativeCache.prepareIn(platformDir, NAMES, hashes,
                NativeCache.cacheKey(hashes), resources(files), true);

        assertFalse(Files.exists(stale), "leftover staging dirs must be reclaimed");
        assertTrue(Files.exists(fresh), "an in-flight staging dir must be kept");
    }

    @Test
    void aclRejectsBroadWritePrincipals() {
        UserPrincipal owner = new NamePrincipal("MACHINE\\user");
        UserPrincipal system = new NamePrincipal("NT AUTHORITY\\SYSTEM");
        UserPrincipal admins = new NamePrincipal("BUILTIN\\Administrators");

        assertFalse(NativeCache.isPrivateAcl(new FakeAclView(owner,
                List.of(aclEntry("Everyone", AclEntryPermission.WRITE_DATA))), system, admins));
        assertFalse(NativeCache.isPrivateAcl(new FakeAclView(owner,
                List.of(aclEntry("BUILTIN\\Users", AclEntryPermission.DELETE_CHILD))), system, admins));
        // A name suffix must not impersonate the resolved Administrators group.
        assertFalse(NativeCache.isPrivateAcl(new FakeAclView(owner,
                List.of(aclEntry("CONTOSO\\Administrators", AclEntryPermission.WRITE_DATA))),
                system, admins));
        assertTrue(NativeCache.isPrivateAcl(new FakeAclView(owner,
                List.of(aclEntry("MACHINE\\user", AclEntryPermission.WRITE_DATA),
                        aclEntry("NT AUTHORITY\\SYSTEM", AclEntryPermission.WRITE_DATA),
                        aclEntry("BUILTIN\\Administrators", AclEntryPermission.DELETE),
                        aclEntry("CREATOR OWNER", AclEntryPermission.WRITE_DATA),
                        aclEntry("Everyone", AclEntryPermission.READ_DATA))), system, admins));
    }

    private static final class NamePrincipal implements UserPrincipal {
        private final String name;

        NamePrincipal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean implies(Subject subject) {
            return false;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NamePrincipal principal && name.equals(principal.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    private static final class FakeAclView implements AclFileAttributeView {
        private final UserPrincipal owner;
        private final List<AclEntry> acl;

        FakeAclView(UserPrincipal owner, List<AclEntry> acl) {
            this.owner = owner;
            this.acl = acl;
        }

        @Override
        public String name() {
            return "acl";
        }

        @Override
        public List<AclEntry> getAcl() {
            return acl;
        }

        @Override
        public void setAcl(List<AclEntry> acl) {
        }

        @Override
        public UserPrincipal getOwner() {
            return owner;
        }

        @Override
        public void setOwner(UserPrincipal owner) {
        }
    }

    private static AclEntry aclEntry(String principal, AclEntryPermission permission) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(new NamePrincipal(principal))
                .setPermissions(EnumSet.of(permission))
                .build();
    }
}
