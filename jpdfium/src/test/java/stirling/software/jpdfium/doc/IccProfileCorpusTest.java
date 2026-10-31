package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ICC corpus smoke test for CMYK profile validation and transforms.
 *
 * <p>Set {@code ICC_PROFILE_DIR} to a directory of ICC files. CI downloads the
 * skcms profile corpus. The corpus is not redistributed with JPDFium.
 */
@EnabledIfEnvironmentVariable(named = "ICC_PROFILE_DIR", matches = ".+")
class IccProfileCorpusTest {

    private static final int LIMIT = Integer.getInteger("jpdfium.icc.limit", 200);
    private static final ColorSpace SRGB = ColorSpace.getInstance(ColorSpace.CS_sRGB);
    private static final int INK_TOLERANCE = 16;

    @Test
    void validCmykProfilesTransformAndInvalidProfilesAreRejected() throws Exception {
        Path dir = Path.of(System.getenv("ICC_PROFILE_DIR"));
        List<Path> files;
        try (Stream<Path> paths = Files.walk(dir)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".icc"))
                    .sorted()
                    .limit(LIMIT)
                    .toList();
        }
        assertTrue(!files.isEmpty(), "no ICC profiles found under " + dir);

        int validCmyk = 0;
        int rejected = 0;
        List<String> unexpected = new ArrayList<>();
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            if (!isCmykProfile(bytes)) {
                assertThrows(JPDFiumException.class, () -> new IccCmykTransform(bytes),
                        file + " was not a CMYK profile but was accepted");
                rejected++;
                continue;
            }
            try {
                IccCmykTransform transform = new IccCmykTransform(bytes);
                int[] ink = new int[4];
                transform.toCmyk(0, 0, 0, ink);
                float[] expected = new ICC_ColorSpace(ICC_Profile.getInstance(bytes))
                        .fromCIEXYZ(SRGB.toCIEXYZ(new float[] {0f, 0f, 0f}));
                for (int i = 0; i < ink.length; i++) {
                    assertTrue(ink[i] >= 0 && ink[i] <= 255,
                            file + " produced an out-of-range ink component");
                    int expectedInk = Math.round(expected[i] * 255f);
                    assertTrue(Math.abs(ink[i] - expectedInk) <= INK_TOLERANCE,
                            file + " black ink mismatch at component " + i
                                    + ": got " + ink[i] + ", expected " + expectedInk);
                }
                validCmyk++;
            } catch (JPDFiumException e) {
                unexpected.add(file.getFileName() + ": " + e.getMessage());
            }
        }
        assertTrue(validCmyk > 0, "ICC corpus contained no usable CMYK profile");
        assertTrue(rejected > 0, "ICC corpus contained no invalid profile to reject");
        assertTrue(unexpected.isEmpty(), String.join("\n", unexpected));
    }

    private static boolean isCmykProfile(byte[] bytes) {
        try {
            ICC_Profile profile = ICC_Profile.getInstance(bytes);
            return profile.getNumComponents() == 4
                    && profile.getColorSpaceType() == ColorSpace.TYPE_CMYK;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
