plugins {
    `java-platform`
    `maven-publish`
    signing
}

dependencies {
    constraints {
        api(project(":jpdfium"))
        api(project(":jpdfium-spring"))
        api(project(":jpdfium-natives:jpdfium-natives-linux-x64"))
        api(project(":jpdfium-natives:jpdfium-natives-linux-arm64"))
        api(project(":jpdfium-natives:jpdfium-natives-darwin-x64"))
        api(project(":jpdfium-natives:jpdfium-natives-darwin-arm64"))
        api(project(":jpdfium-natives:jpdfium-natives-windows-x64"))
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenBom") {
            from(components["javaPlatform"])

            pom {
                name.set("JPDFium BOM")
                description.set("Bill of Materials for JPDFium modules")
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
            name = "sonatype"
            val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
            credentials {
                username = findProperty("ossrhUsername")?.toString() ?: System.getenv("OSSRH_USERNAME") ?: ""
                password = findProperty("ossrhPassword")?.toString() ?: System.getenv("OSSRH_PASSWORD") ?: ""
            }
        }
    }
}

signing {
    val signingKey = findProperty("signing.key")?.toString() ?: System.getenv("GPG_SIGNING_KEY")
    val signingPassword = findProperty("signing.password")?.toString() ?: System.getenv("GPG_SIGNING_PASSWORD")
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications["mavenBom"])
}
