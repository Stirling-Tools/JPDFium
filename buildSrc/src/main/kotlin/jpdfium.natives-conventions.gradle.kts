// Resource-only JAR - ships the platform-specific native library.
import java.security.MessageDigest
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    `java-library`
    `maven-publish`
    signing
}

java {
    // Resource-only module: no Java sources to compile. A JVM toolchain keeps
    // the javadoc/sources tasks deterministic without forcing a specific JDK
    // on consumers. 25 matches the library module; these jars are empty.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    // Central Portal requires sources + javadoc jars for every published artifact.
    // These jars are empty for resource-only modules - the presence is what matters.
    withSourcesJar()
    withJavadocJar()
}

// Convention: CI builds the per-platform native libraries outside the build
// tree, under native/dist/<platform>/, where <platform> matches the module
// suffix (e.g. linux-x64, darwin-arm64). stageNatives copies those binaries
// plus native/licenses/ into build/staged-natives, which is registered as a
// resources source directory below and also receives the native-libs.txt
// manifest that NativeLoader reads at runtime.

val platform: String = project.name.removePrefix("jpdfium-natives-")
val distDir = rootProject.layout.projectDirectory.dir("native/dist/$platform")
val stagedRoot = layout.buildDirectory.dir("staged-natives")           // added as resources srcDir
val stagedPlatformDir = stagedRoot.map { it.dir("natives/$platform") } // real files land here
val stagedLicensesDir = stagedRoot.map { it.dir("licenses") }
val licensesDir = rootProject.layout.projectDirectory.dir("native/licenses")

// A natives jar only needs the notices for the libraries it actually ships.
// The old blanket copy put libvips/libheif/OpenEXR/LibRaw notices in the core
// jar (and PDFium/qpdf notices in the vips jar), which is pure duplication.
// Keep the lists conservative: anything a bundle may load at runtime stays.
val coreLicenses = listOf(
    "ABSEIL-APACHE-2.0.txt", "BROTLI-MIT.txt", "BZIP2.txt", "FONTCONFIG.txt",
    "FREETYPE.txt", "HARFBUZZ.txt", "ICU.txt", "LIBEXPAT-MIT.txt",
    "LIBJPEG-TURBO.txt", "LIBPNG.txt", "LIBUNIBREAK-ZLIB.txt", "NOTICE.txt",
    "PCRE2-BSD-3.txt", "PDFIUM-BSD-3.txt", "PUGIXML-MIT.txt",
    "QPDF-APACHE-2.0.txt", "ZLIB.txt"
)
val vipsLicenses = listOf(
    "AOM-BSD-2.txt", "BROTLI-MIT.txt", "DAV1D-BSD-2.txt", "DAV1D-ISC.txt",
    "GETTEXT-LGPL-2.1.txt",
    "GLIB-LGPL-2.1.txt", "HIGHWAY-APACHE-2.0.txt", "KVAZAAR-BSD-3.txt",
    "LCMS2-MIT.txt", "LIBDE265-LGPL-3.0.txt", "LIBDEFLATE-MIT.txt",
    "LIBEXIF-LGPL-2.1.txt", "LIBEXPAT-MIT.txt", "LIBFFI-MIT.txt",
    "LIBHEIF-LGPL-3.0.txt", "LIBICONV-LGPL-2.1.txt", "LIBINOTIFY-MIT.txt",
    "LIBJPEG-TURBO.txt", "LIBJXL-BSD-3.txt", "LIBLZMA-0BSD.txt",
    "LIBNSGIF-MIT.txt", "LIBPNG.txt", "LIBSELINUX-PUBLIC-DOMAIN.txt",
    "LIBSPNG-BSD-2.txt", "LIBTIFF.txt", "LIBVIPS-LGPL-2.1.txt",
    "LIBWEBP-BSD-3.txt", "NOTICE.txt",
    "OPENJPEG-BSD-2.txt", "PCRE2-BSD-3.txt", "UTIL-LINUX-LGPL-2.1.txt",
    "ZLIB.txt", "ZSTD-BSD-3.txt"
)
val bundledLicenses = when {
    platform.startsWith("vips-") -> vipsLicenses
    else -> coreLicenses
}

val stageNatives = tasks.register<Copy>("stageNatives") {
    description = "Copy pre-built native libraries and license notices into the jar resource tree"
    group = "build"
    from(distDir) {
        include(
            "*.so",        // Linux: lib*.so (incl. PDFium components, bridge)
            "*.so.*",      // Linux: versioned bundled deps (libicuuc.so.74 etc.)
            "*.dylib",     // macOS: lib*.dylib
            "*.dll"        // Windows: *.dll (incl. vcpkg runtime DLLs)
        )
        into("natives/$platform")
    }
    from(licensesDir) {
        include(bundledLicenses)
        into("licenses")
    }
    into(stagedRoot)
    // Always run so the doFirst cleanup below executes even when there are no
    // dist binaries: the licenses source keeps the task non-empty, and a
    // missing dist dir contributes nothing instead of failing the build (local
    // dev, stub builds, etc.). Skipping via onlyIf would leave a previous
    // build's staged bundle in place while writeNativeManifest regenerates
    // manifests from those stale binaries, publishing the wrong native set.
    // Copy never removes: without this, switching real<->stub locally leaves
    // the previous bundle's libraries beside the new ones, and the jar ships
    // a mixed bridge/dependency set that loads (or crashes) unpredictably.
    doFirst { stagedRoot.get().asFile.deleteRecursively() }
}

val writeNativeManifest = tasks.register("writeNativeManifest") {
    description = "Write native-libs.txt and native-libs.sha256 for the staged natives"
    group = "build"
    dependsOn(stageNatives)
    val manifest = stagedPlatformDir.map { it.file("native-libs.txt") }
    val checksums = stagedPlatformDir.map { it.file("native-libs.sha256") }
    // Without the dist input Gradle can keep a stale manifest after a rebuild.
    inputs.files(distDir).withPropertyName("dist").optional()
    inputs.property("platform", platform)
    outputs.files(manifest, checksums)
    doLast {
        val dir = stagedPlatformDir.get().asFile
        if (!dir.isDirectory) return@doLast
        // The checksum file drives the content-addressed runtime cache, so it
        // must cover exactly the files the manifest lists.
        val entries = dir.listFiles()
            ?.filter { it.isFile && it.name != "native-libs.txt" && it.name != "native-libs.sha256" }
            ?.sortedBy { it.name }
            ?: emptyList()
        manifest.get().asFile.writeText(entries.joinToString("\n") { it.name } + if (entries.isEmpty()) "" else "\n")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        val lines = entries.joinToString("\n") { file ->
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            "$hash  ${file.name}"
        }
        checksums.get().asFile.writeText(if (lines.isEmpty()) "" else lines + "\n")
    }
}

sourceSets.named("main") {
    // native/dist is the only source of truth: CMake still drops a legacy
    // copy of the PDFium component libs into src/main/resources, and mixing
    // that with the stripped/bundled dist set could publish unstripped or
    // already-deduped-away libraries. Stub builds stage into native/dist too.
    resources.setSrcDirs(listOf(stagedRoot))
}

// Resource-only jars, so the module name would otherwise be derived from the
// file name (jpdfium.natives.linux.x64). Declare it explicitly instead.
tasks.named<Jar>("jar") {
    manifest {
        attributes["Automatic-Module-Name"] =
            "stirling.software.jpdfium.natives." + platform.replace('-', '.')
    }
}

tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("processResources") {
    dependsOn(writeNativeManifest)
}

// A platform leg can be intentionally skipped (see the release workflow
// matrix NOTE). Publishing its natives jar anyway would resolve for
// consumers while containing no binaries, so skip those publications with
// a loud warning instead of failing the whole release.
tasks.matching {
    it.name.startsWith("publish") &&
        (it.name.endsWith("Repository") || it.name.contains("MavenLocal"))
}.configureEach {
    onlyIf("natives bundle present, skipped otherwise (see release.yml matrix NOTE)") {
        val nativePattern = Regex(""".*\.(so|dylib|dll)(\..*)?$""")
        val ok = stagedPlatformDir.get().asFile.listFiles()
            ?.any { it.isFile && nativePattern.matches(it.name) } == true
        if (!ok) logger.warn("Skipping ${it.name}: no staged natives for $platform")
        ok
    }
}

tasks.named<Jar>("sourcesJar") {
    dependsOn(writeNativeManifest)
    eachFile {
        if (relativePath.pathString.startsWith("natives/")) exclude()
    }
}
tasks.named("javadocJar") {
    dependsOn(writeNativeManifest)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("JPDFium natives for ${project.name.removePrefix("jpdfium-natives-")}")
                description.set("Prebuilt JPDFium native libraries for ${project.name.removePrefix("jpdfium-natives-")}")
                url.set("https://github.com/Stirling-Tools/JPDFium")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("stirling-tools")
                        name.set("Stirling Tools")
                        url.set("https://github.com/Stirling-Tools")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/Stirling-Tools/JPDFium.git")
                    developerConnection.set("scm:git:ssh://github.com/Stirling-Tools/JPDFium.git")
                    url.set("https://github.com/Stirling-Tools/JPDFium")
                }
            }
        }
    }

    repositories {
        maven {
            name = "centralPortal"
            val releasesUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            val snapshotsUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
            credentials {
                username = findProperty("centralPortalUsername")?.toString()
                    ?: findProperty("ossrhUsername")?.toString()
                    ?: System.getenv("CENTRAL_PORTAL_USERNAME")
                    ?: System.getenv("OSSRH_USERNAME") ?: ""
                password = findProperty("centralPortalPassword")?.toString()
                    ?: findProperty("ossrhPassword")?.toString()
                    ?: System.getenv("CENTRAL_PORTAL_PASSWORD")
                    ?: System.getenv("OSSRH_PASSWORD") ?: ""
            }
        }
        maven {
            name = "githubPackages"
            val targetRepo = (findProperty("githubPackagesRepo")?.toString()
                ?: System.getenv("GITHUB_REPOSITORY")
                ?: "Stirling-Tools/JPDFium")
            url = uri("https://maven.pkg.github.com/$targetRepo")
            credentials {
                username = findProperty("githubActor")?.toString()
                    ?: System.getenv("GITHUB_ACTOR") ?: ""
                password = findProperty("githubToken")?.toString()
                    ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

signing {
    val signingKey = findProperty("signing.key")?.toString() ?: System.getenv("GPG_SIGNING_KEY")
    val signingPassword = findProperty("signing.password")?.toString() ?: System.getenv("GPG_SIGNING_PASSWORD")
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["mavenJava"])
    }
}
