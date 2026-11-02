package jpdfium

import java.nio.file.Files
import java.nio.file.Path

/** Host target name shared by the core and vips build scripts. */
object HostPlatform {
    fun detect(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val isArm = arch == "aarch64" || arch == "arm64"
        return when {
            os.contains("mac") || os.contains("darwin") -> if (isArm) "darwin-arm64" else "darwin-x64"
            os.contains("freebsd") ->
                if (isArm) error("JPDFium has no freebsd-arm64 native artifact") else "freebsd-x64"
            os.contains("win") -> if (isArm) "windows-arm64" else "windows-x64"
            else -> {
                // Any musl loader means this is a musl host. Checking only the
                // host-arch loader misses the multi-arch /lib layouts.
                val musl = runCatching {
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
}
