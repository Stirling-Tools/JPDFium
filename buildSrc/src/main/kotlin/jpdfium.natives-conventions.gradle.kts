// Resource-only JAR - ships the platform-specific native library.
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

// ── Native binary staging ────────────────────────────────────────────────────
//
// Convention: CI builds the per-platform native libraries and drops them into
//   native/dist/<platform>/
// where <platform> matches the module suffix (e.g. linux-x64, darwin-arm64).
// `processResources` then copies them into src/main/resources/natives/<platform>/
// and writes a native-libs.txt manifest that NativeLoader reads at runtime.

val platform: String = project.name.removePrefix("jpdfium-natives-")
val distDir = rootProject.layout.projectDirectory.dir("native/dist/$platform")
val stagedRoot = layout.buildDirectory.dir("staged-natives")           // added as resources srcDir
val stagedPlatformDir = stagedRoot.map { it.dir("natives/$platform") } // real files land here

val stageNatives by tasks.registering(Copy::class) {
    description = "Copy pre-built native libraries from native/dist/$platform/ into the jar resource tree"
    group = "build"
    from(distDir) {
        // Match every shared library shape we ship: the bridge, PDFium itself,
        // every PDFium component lib (e.g. libchrome_zlib.so - no version
        // suffix), every bundled third-party dependency (versioned like
        // libicuuc.so.74 on Linux, base name on macOS), every Windows DLL.
        // Earlier this list was narrower and silently dropped Linux PDFium
        // component libs because their basename matched lib*.so but not
        // *.so.* (no version) - the consumer-side System.load on libpdfium.so
        // then failed with "libthird_party_abseil-cpp_absl.so: cannot open
        // shared object file".
        include(
            "*.so",        // Linux: lib*.so (incl. PDFium components, bridge)
            "*.so.*",      // Linux: versioned bundled deps (libicuuc.so.74 etc.)
            "*.dylib",     // macOS: lib*.dylib
            "*.dll"        // Windows: *.dll (incl. vcpkg runtime DLLs)
        )
    }
    into(stagedPlatformDir)
    // Don't fail the build when the dist dir is absent (local dev, stub builds, etc.).
    // CI is responsible for populating it before `publish`.
    onlyIf { distDir.asFile.isDirectory && distDir.asFile.listFiles()?.isNotEmpty() == true }
}

val writeNativeManifest by tasks.registering {
    description = "Write native-libs.txt listing every file in the staged natives directory"
    group = "build"
    dependsOn(stageNatives)
    val manifest = stagedPlatformDir.map { it.file("native-libs.txt") }
    outputs.file(manifest)
    doLast {
        val dir = stagedPlatformDir.get().asFile
        if (!dir.isDirectory) return@doLast
        val entries = dir.listFiles()
            ?.filter { it.isFile && it.name != "native-libs.txt" }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
        manifest.get().asFile.writeText(entries.joinToString("\n") + if (entries.isEmpty()) "" else "\n")
    }
}

sourceSets.named("main") {
    // stagedRoot contains natives/<platform>/..., so jar entries become natives/<platform>/<file>.
    // Listed FIRST deliberately: the CMake build also drops the bridge + a partial
    // native-libs.txt into src/main/resources, and with duplicatesStrategy EXCLUDE
    // the complete staged set (full dependency manifest) must win that collision -
    // a partial manifest skips dependency preloading and breaks Windows loading.
    resources.setSrcDirs(listOf(stagedRoot, "src/main/resources"))
}

// Gradle 9 sees the staged natives twice (srcDir scan + stageNatives task
// outputs) and hard-fails processResources/jar on the duplicate entries.
// They are the same physical files - keep the first occurrence.
tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("processResources") {
    dependsOn(writeNativeManifest)
}

// sourcesJar / javadocJar also walk the resources srcDirs, so they need an
// explicit dep on the manifest-generating task to keep Gradle's task-validator happy.
tasks.named("sourcesJar") {
    dependsOn(writeNativeManifest)
}
tasks.named("javadocJar") {
    dependsOn(writeNativeManifest)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set(project.name)
                description.set("JPDFium native libraries for ${project.name.removePrefix("jpdfium-natives-")}")
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
