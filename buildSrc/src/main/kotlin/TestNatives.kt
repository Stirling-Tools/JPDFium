import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.Project

/**
 * The jpdfium-natives platform this build loads for its own tests, benchmarks and
 * native images: `-Pjpdfium.testNatives=<platform>` when set, which CI uses so the
 * smoke loads the native it just built, and otherwise the build host's platform.
 */
fun Project.testNativesPlatform(): String =
    (findProperty("jpdfium.testNatives") ?: hostNativesPlatform()).toString()

private fun hostNativesPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch == "aarch64" || arch == "arm64"
    return when {
        os.contains("mac") || os.contains("darwin") -> if (isArm) "darwin-arm64" else "darwin-x64"
        os.contains("win") -> if (isArm) "windows-arm64" else "windows-x64"
        else -> {
            // Linux natives are libc-specific: a musl host (Alpine) needs the
            // linux-musl-* artifact - a glibc-linked libjpdfium.so won't load
            // into a musl JVM. Mirror NativeLoader's detection here so builds
            // executed on Alpine resolve the right natives jar automatically.
            val muslLoader = Path.of("/lib/ld-musl-${if (isArm) "aarch64" else "x86_64"}.so.1")
            val musl = Files.exists(muslLoader)
                || runCatching {
                    Files.newDirectoryStream(Path.of("/lib"), "ld-musl-*").use { it.iterator().hasNext() }
                }.getOrDefault(false)
            when {
                musl && isArm -> "linux-musl-arm64"
                musl -> "linux-musl-x64"
                isArm -> "linux-arm64"
                else -> "linux-x64"
            }
        }
    }
}
