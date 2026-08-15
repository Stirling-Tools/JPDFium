plugins {
    id("jpdfium.library-conventions")
}

publishing {
    publications {
        getByName<MavenPublication>("mavenJava") {
            pom {
                description.set("Optional libvips encoding for JPDFium via vips-ffm (HEIC, AVIF, JXL, WebP, PNG, JPEG)")
            }
        }
    }
}

// The vips natives platform for tests: -Pjpdfium.testVipsNatives=vips-<platform>,
// defaulting to the same platform as -Pjpdfium.testNatives (used by jpdfium's
// runtimeOnly natives dependency).
val testVipsNatives = (findProperty("jpdfium.testVipsNatives") as String?)
    ?: "vips-${findProperty("jpdfium.testNatives") ?: "linux-x64"}"

dependencies {
    api(project(":jpdfium"))
    implementation(libs.vips.ffm)
    // Bring the bundled vips natives onto the TEST runtime classpath only, so
    // VipsNatives finds /natives/vips-<platform>/ and the vipsSmokeTest (and
    // the other vips tests) exercise the exact bundled libvips being shipped.
    // Resolved lazily by Gradle; a no-op when the dist dir is empty (tests then
    // fall back to a system libvips or skip).
    testRuntimeOnly(project(":jpdfium-natives:jpdfium-natives-$testVipsNatives"))
}

tasks.withType<Test> {
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX &&
        file("/opt/homebrew/lib/libvips.42.dylib").exists()) {
        jvmArgs("-Dvipsffm.libpath.vips.override=/opt/homebrew/lib/libvips.42.dylib")
        jvmArgs("-Dvipsffm.libpath.glib.override=/opt/homebrew/lib/libglib-2.0.dylib")
        jvmArgs("-Dvipsffm.libpath.gobject.override=/opt/homebrew/lib/libgobject-2.0.dylib")
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
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}
