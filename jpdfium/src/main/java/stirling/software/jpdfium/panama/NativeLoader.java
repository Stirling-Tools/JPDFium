package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.NativeLoadException;
import stirling.software.jpdfium.exception.NativeNotFoundException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
            // Fall back to a system-installed library (e.g., installed via apt/dnf or in a Docker image).
            // This allows deployment without bundling platform-specific JARs.
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

        if (NativeLoader.class.getResource(resourceBase + bridgeName) == null)
            throw new NativeNotFoundException(platform);

        try {
            // Extract to a temp directory rather than a temp file so that $ORIGIN rpath works.
            // The OS resolves $ORIGIN to the directory containing the loaded library, so
            // libpdfium.so must be in the same directory as libjpdfium.so.
            Path tmpDir = Files.createTempDirectory("jpdfium-");
            tmpDir.toFile().deleteOnExit();

            // Extract the dependency (libpdfium.so) before the bridge (libjpdfium.so).
            // The OS dynamic linker resolves the bridge's dependencies at load time,
            // so the dependency file must already exist in the temp directory.
            extractIfPresent(resourceBase + nativeFilename("pdfium"), tmpDir);

            // Extract the bridge library last so all its dependencies are already on disk.
            Path bridge = extractLib(resourceBase + bridgeName, tmpDir, bridgeName);
            System.load(bridge.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new NativeLoadException("Failed to extract native library", e);
        }
    }

    private static void extractIfPresent(String resource, Path dir) throws IOException {
        try (InputStream is = NativeLoader.class.getResourceAsStream(resource)) {
            if (is == null) return;
            Path target = dir.resolve(resource.substring(resource.lastIndexOf('/') + 1));
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            System.load(target.toAbsolutePath().toString());
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
        String os   = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String osKey   = os.contains("win") ? "windows" : os.contains("mac") ? "darwin" : "linux";
        String archKey = (arch.equals("aarch64") || arch.equals("arm64")) ? "arm64" : "x64";
        return osKey + "-" + archKey;
    }

    static String nativeFilename(String lib) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return lib + ".dll";
        if (os.contains("mac")) return "lib" + lib + ".dylib";
        return "lib" + lib + ".so";
    }
}
