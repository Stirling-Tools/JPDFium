// Minimal JPDFium consumer example. Run with:
//   ./gradlew -p examples/render-first-page run --args="sample.pdf page-1.png"

plugins {
    application
}

repositories {
    maven("https://maven.stirlingpdf.com") {
        content { includeGroup("com.stirling") }
    }
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// One native module for the platform that runs the application. Alpine and
// other musl systems must substitute jpdfium-natives-linux-musl-<arch>.
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()

val platform = when {
    osName.contains("mac") || osName.contains("darwin") -> "darwin"
    osName.contains("win") -> "windows"
    osName.contains("linux") -> "linux"
    else -> throw GradleException("Unsupported os.name '$osName'. Declare com.stirling:jpdfium-natives-<platform> explicitly.")
}

val architecture = when (osArch) {
    "aarch64", "arm64" -> "arm64"
    "x86_64", "amd64" -> "x64"
    else -> throw GradleException("Unsupported os.arch '$osArch'. Declare com.stirling:jpdfium-natives-<platform> explicitly.")
}

dependencies {
    implementation(platform("com.stirling:jpdfium-bom:1.2.0"))
    implementation("com.stirling:jpdfium")
    runtimeOnly("com.stirling:jpdfium-natives-$platform-$architecture")
}

application {
    mainClass.set("Main")
    // Java 25 requires this flag before the Foreign Function and Memory API may be called.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
