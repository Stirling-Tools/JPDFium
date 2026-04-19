// Resource-only JAR - ships the platform-specific native library.
plugins {
    `java-library`
    `maven-publish`
    signing
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    // Central Portal requires sources + javadoc jars for every published artifact.
    // These jars are empty for resource-only modules — the presence is what matters.
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
        include(
            "libjpdfium.so", "libjpdfium.dylib", "jpdfium.dll",
            "libpdfium.so", "libpdfium.dylib", "pdfium.dll",
            "*.so.*", "*.dll", "*.dylib"
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
    // stagedRoot contains natives/<platform>/..., so jar entries become natives/<platform>/<file>
    resources.srcDir(stagedRoot)
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
