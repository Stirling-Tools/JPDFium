package stirling.software.jpdfium.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.fontbox.ttf.TTFParser;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.XZInputStream;

class BundledFontManifestTest {

    @Test
    void everyManifestRowMatchesItsFontProgram() throws Exception {
        Path root = Path.of("src/main/resources/fonts");
        List<String> rows = Files.readAllLines(root.resolve("manifest.tsv"));
        Set<String> resources = new HashSet<>();
        int count = 0;
        for (String row : rows) {
            if (row.isBlank() || row.startsWith("#")) continue;
            String[] columns = row.split("\t", 3);
            assertEquals(3, columns.length, row);
            String resource = columns[0].trim();
            String postScriptName = columns[1].trim();
            int expectedFsType = Integer.parseInt(columns[2].trim());
            resources.add(resource);

            byte[] data;
            try (XZInputStream xz = new XZInputStream(
                    Files.newInputStream(root.resolve(resource)), 96 * 1024)) {
                data = xz.readAllBytes();
            }
            TrueTypeFont font = new TTFParser().parseEmbedded(new ByteArrayInputStream(data));
            assertNotNull(font.getName(), resource);
            assertEquals(postScriptName, font.getName(), resource);
            OS2WindowsMetricsTable os2 = font.getOS2Windows();
            assertNotNull(os2, resource);
            assertEquals(expectedFsType, os2.getFsType(), resource);
            assertTrue(font.getTableMap().containsKey("glyf")
                            || font.getTableMap().containsKey("CFF "),
                    resource + " must carry an outline table");
            CmapTable cmap = font.getCmap();
            assertNotNull(cmap, resource);
            count++;
        }
        assertTrue(count >= 20, "expected the bundled fallback pack, found " + count);

        try (var files = Files.list(root)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".xz")) {
                    assertTrue(resources.contains(name), name + " is not listed in manifest.tsv");
                }
            }
        }
    }
}
