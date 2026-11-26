package stirling.software.jpdfium.instance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class InstanceNativesTest {

    private static final List<String> ELF = List.of(
            "libpdfium.so", "libjpdfium.so", "libskia.so", "libicuuc.so", "libicuuc.so.74", "libc++.so");

    /** {@code |} stands for NUL, so digits after it are not read as an octal escape. */
    private static byte[] bytes(String s) {
        return s.replace('|', '\0').getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void everyBundledElfNameIsRenamedAtTheSameLength() {
        byte[] skia = bytes("|libskia.so|libpdfium.so|libicuuc.so.74|libc.so.6|libc++.so|");

        byte[] patched = InstanceNatives.patch(skia, ELF, "007");

        assertArrayEquals(bytes("|007skia.so|007pdfium.so|007icuuc.so.74|libc.so.6|007c++.so|"),
                patched);
        assertEquals(skia.length, patched.length);
    }

    @Test
    void onlyWholeNamesAreRenamed() {
        byte[] text = bytes("|libpdfium.so.bak|see libpdfium.so|xlibskia.so|libicuuc.so|");

        assertArrayEquals(bytes("|libpdfium.so.bak|see libpdfium.so|xlibskia.so|007icuuc.so|"),
                InstanceNatives.patch(text, ELF, "007"));
    }

    @Test
    void machOInstallNamesAreRenamedAfterTheirPathPrefix() {
        List<String> names = List.of("libpdfium.dylib", "libjpdfium.dylib", "libskia.dylib");
        byte[] bridge = bytes("@rpath/libjpdfium.dylib|@loader_path/libpdfium.dylib|@rpath/libskia.dylib|");

        assertArrayEquals(
                bytes("@rpath/012jpdfium.dylib|@loader_path/012pdfium.dylib|@rpath/012skia.dylib|"),
                InstanceNatives.patch(bridge, names, "012"));
    }

    @Test
    void leavesTheInputUntouched() {
        byte[] original = bytes("|libpdfium.so|");
        InstanceNatives.patch(original, ELF, "001");
        assertArrayEquals(bytes("|libpdfium.so|"), original);
    }

    @Test
    void prefixesAreThreeDigitsAndBounded() {
        assertEquals("001", InstanceNatives.prefix(1));
        assertEquals("999", InstanceNatives.prefix(999));
        assertThrows(IllegalArgumentException.class, () -> InstanceNatives.prefix(1000));
        assertEquals("042skia.so", InstanceNatives.renamed("libskia.so", "042"));
    }

    @Test
    void theAllocatorStaysSharedAndEverythingElseIsCopied() {
        assertTrue(InstanceNatives.sharedAcrossInstances(
                "libbase_allocator_partition_allocator_src_partition_alloc_allocator_core.so"));
        assertFalse(InstanceNatives.sharedAcrossInstances("libskia.so"));
        assertFalse(InstanceNatives.sharedAcrossInstances("libpdfium.dylib"));
    }
}
