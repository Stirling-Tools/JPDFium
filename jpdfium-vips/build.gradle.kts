import java.nio.file.Files
import java.nio.file.Path

plugins {
    id("jpdfium.library-conventions")
}

// The module has no descriptor because it is compiled against vips-ffm's
// automatic modules, so give the jar a stable module name for the module path.
tasks.named<Jar>("jar") {
    manifest {
        attributes["Automatic-Module-Name"] = "stirling.software.jpdfium.vips"
    }
}

publishing {
    publications {
        getByName<MavenPublication>("mavenJava") {
            pom {
                name.set("JPDFium libvips support")
                description.set("Optional libvips image encoding for JPDFium: WebP, HEIC, AVIF, JXL, and JPEG 2000")
            }
        }
    }
}

// The vips natives platform for tests: -Pjpdfium.testVipsNatives=vips-<platform>,
// defaulting to the same host platform as -Pjpdfium.testNatives.
val hostPlatform = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch == "aarch64" || arch == "arm64"
    when {
        os.contains("mac") || os.contains("darwin") -> if (isArm) "darwin-arm64" else "darwin-x64"
        os.contains("freebsd") -> if (isArm) "freebsd-arm64" else "freebsd-x64"
        os.contains("win") -> if (isArm) "windows-arm64" else "windows-x64"
        else -> {
            val musl = Files.exists(Path.of("/lib/ld-musl-${if (isArm) "aarch64" else "x86_64"}.so.1"))
            when {
                musl && isArm -> "linux-musl-arm64"
                musl -> "linux-musl-x64"
                isArm -> "linux-arm64"
                else -> "linux-x64"
            }
        }
    }
}
val testNatives = (findProperty("jpdfium.testNatives") ?: hostPlatform).toString()
val testVipsNatives = (findProperty("jpdfium.testVipsNatives") ?: "vips-$testNatives").toString()

// The vips tests read a sample PDF from the classpath. jpdfium leaves its
// GraalVM smoke fixtures out of the published jar, so this module carries its own
// copy under src/test/resources instead of depending on jpdfium's artifact.
dependencies {
    api(project(":jpdfium"))
    implementation(libs.vips.ffm)
    // Bring the bundled vips natives onto the TEST runtime classpath only, so
    // VipsNatives finds /natives/vips-<platform>/ and the vipsSmokeTest (and
    // the other vips tests) exercise the exact bundled libvips being shipped.
    // Resolved lazily by Gradle; a no-op when the dist dir is empty (tests then
    // fall back to a system libvips or skip).
    findProject(":jpdfium-natives:jpdfium-natives-$testNatives")?.let {
        testRuntimeOnly(it)
    }
    findProject(":jpdfium-natives:jpdfium-natives-$testVipsNatives")?.let {
        testRuntimeOnly(it)
    }
}

// Run: ./gradlew :jpdfium-vips:vipsSmokeTest -Pjpdfium.testNatives=<platform> -Pjpdfium.testVipsNatives=vips-<platform>
// Functional smoke of the bundled vips natives: renders a real PDF through the
// production NativeLoader path and encodes it to every major format (HEIF,
// HEIC, AVIF, JXL, PNG, JPEG, WEBP, TIFF), verifying each round-trips. The
// PDFium natives come from the jpdfium api dependency (resolved via
// -Pjpdfium.testNatives); the vips natives come from the testRuntimeOnly dep
// above.
tasks.register<Test>("vipsSmokeTest") {
    group = "verification"
    description = "Smoke test the bundled vips natives: render a PDF to every major image format"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("jpdfium.vips.smoke", "true")
    filter { includeTestsMatching("*VipsSmokeTest") }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}
