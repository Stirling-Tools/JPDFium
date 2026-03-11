package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.NativeLoadException;
import stirling.software.jpdfium.exception.NativeNotFoundException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class NativeLoader {

    private static volatile boolean loaded = false;
    private static volatile Throwable loadError = null;

    private NativeLoader() {}

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        if (loadError != null) {
            throw new NativeLoadException("Native library failed to load previously", loadError);
        }
        try {
            tryLoadFromClasspath();
            loaded = true;
        } catch (NativeNotFoundException classpathMiss) {
            try {
                System.loadLibrary("jpdfium");
                loaded = true;
            } catch (UnsatisfiedLinkError e) {
                loadError = classpathMiss;
                throw new NativeNotFoundException(
                        detectPlatform() + ". Also tried System.loadLibrary(\"jpdfium\") and failed.");
            }
        } catch (Throwable t) {
            loadError = t;
            throw (t instanceof NativeLoadException nle) ? nle
                    : new NativeLoadException("Failed to load native library", t);
        }
    }

    private static void tryLoadFromClasspath() {
        String platform    = detectPlatform();
        String resourceBase = "/natives/" + platform + "/";
        String bridgeName  = nativeFilename("jpdfium");
        String pdfiumName  = nativeFilename("pdfium");
        String indexResource = resourceBase + "native-libs.txt";

        if (NativeLoader.class.getResource(resourceBase + bridgeName) == null)
            throw new NativeNotFoundException(platform);

        try {
            Path tmpDir = Files.createTempDirectory("jpdfium-");
            tmpDir.toFile().deleteOnExit();

            // Extract all libraries from the manifest to tmpDir so the dynamic
            // linker can resolve NEEDED dependencies via RUNPATH=$ORIGIN
            List<String> libs = readLibraryIndex(indexResource);
            for (String lib : libs) {
                extractToDir(resourceBase + lib, tmpDir);
            }

            // If no manifest was found, fall back to extracting just libpdfium
            if (libs.isEmpty()) {
                extractToDir(resourceBase + pdfiumName, tmpDir);
            }

            // Load pdfium first (triggers loading of its component dependencies via RUNPATH)
            Path pdfiumPath = tmpDir.resolve(pdfiumName);
            if (Files.exists(pdfiumPath)) {
                System.load(pdfiumPath.toAbsolutePath().toString());
            }

            // Then load the bridge
            Path bridge = tmpDir.resolve(bridgeName);
            if (!Files.exists(bridge)) {
                bridge = extractLib(resourceBase + bridgeName, tmpDir, bridgeName);
            }
            System.load(bridge.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new NativeLoadException("Failed to extract native library", e);
        }
    }

    private static List<String> readLibraryIndex(String resource) {
        List<String> result = new ArrayList<>();
        try (InputStream is = NativeLoader.class.getResourceAsStream(resource)) {
            if (is == null) return result;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !(!trimmed.isEmpty() && trimmed.charAt(0) == '#')) {
                        result.add(trimmed);
                    }
                }
            }
        } catch (IOException ignored) {
            // Missing index is not fatal; fall through with empty list
        }
        return result;
    }

    private static void extractToDir(String resource, Path dir) throws IOException {
        try (InputStream is = NativeLoader.class.getResourceAsStream(resource)) {
            if (is == null) return;
            Path target = dir.resolve(resource.substring(resource.lastIndexOf('/') + 1));
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
        }
    }

    private static Path extractLib(String resource, Path dir, String filename) throws IOException {
        try (InputStream is = NativeLoader.class.getResourceAsStream(resource)) {
            if (is == null) throw new NativeNotFoundException(detectPlatform());
            Path target = dir.resolve(filename);
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            return target;
        }
    }

    public static String detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        String osKey = os.contains("win") ? "windows" : os.contains("mac") ? "darwin" : "linux";
        return osKey + "-" + Architecture.detect().key();
    }

    static String nativeFilename(String lib) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return lib + ".dll";
        if (os.contains("mac")) return "lib" + lib + ".dylib";
        return "lib" + lib + ".so";
    }
}
