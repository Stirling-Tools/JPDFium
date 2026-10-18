package stirling.software.jpdfium.vips;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VipsNativesTest {

    @Test
    void matchesCoreVipsLibrariesAcrossPlatforms() {
        assertTrue(VipsNatives.isVipsLib("vips-42.dll"));
        assertTrue(VipsNatives.isVipsLib("VIPS-42.DLL"));
        assertTrue(VipsNatives.isVipsLib("vips.dll"));
        assertTrue(VipsNatives.isVipsLib("vips.42.dll"));
        assertTrue(VipsNatives.isVipsLib("libvips-42.dll"));
        assertTrue(VipsNatives.isVipsLib("libvips.so.42"));
        assertTrue(VipsNatives.isVipsLib("libvips.so"));
        assertTrue(VipsNatives.isVipsLib("libvips.42.dylib"));
        assertTrue(VipsNatives.isVipsLib("libvips.dylib"));
    }

    @Test
    void rejectsVipsPluginsAndWrappers() {
        assertFalse(VipsNatives.isVipsLib("vips-jxl.dll"));
        assertFalse(VipsNatives.isVipsLib("vips-heif.dll"));
        assertFalse(VipsNatives.isVipsLib("vips-cpp.dll"));
        assertFalse(VipsNatives.isVipsLib("libvips-cpp.so.42"));
        assertFalse(VipsNatives.isVipsLib("libvips-cpp.42.dylib"));
        assertFalse(VipsNatives.isVipsLib("glib-2.0-0.dll"));
        assertFalse(VipsNatives.isVipsLib("gobject-2.0-0.dll"));
        assertFalse(VipsNatives.isVipsLib("aom.dll"));
    }

    @Test
    void matchesGlibLibraries() {
        assertTrue(VipsNatives.isGlibLib("glib-2.0-0.dll"));
        assertTrue(VipsNatives.isGlibLib("libglib-2.0.so.0"));
        assertTrue(VipsNatives.isGlibLib("libglib-2.0.0.dylib"));
        assertFalse(VipsNatives.isGlibLib("gio-2.0-0.dll"));
        assertFalse(VipsNatives.isGlibLib("gmodule-2.0-0.dll"));
        assertFalse(VipsNatives.isGlibLib("vips-42.dll"));
    }

    @Test
    void matchesGobjectLibraries() {
        assertTrue(VipsNatives.isGobjectLib("gobject-2.0-0.dll"));
        assertTrue(VipsNatives.isGobjectLib("libgobject-2.0.so.0"));
        assertTrue(VipsNatives.isGobjectLib("libgobject-2.0.0.dylib"));
        assertFalse(VipsNatives.isGobjectLib("glib-2.0-0.dll"));
        assertFalse(VipsNatives.isGobjectLib("vips-42.dll"));
    }
}
