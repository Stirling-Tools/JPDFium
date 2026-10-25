// The kotlin-dsl plugin's own classpath for this build is resolved through
// pluginManagement repositories. Without this block Gradle consults only the
// Gradle Plugin Portal, which merely proxies Maven Central; when that proxy is
// rate-limited (HTTP 429) or briefly unavailable the classpath fails to resolve
// with "Could not find kotlin-stdlib-<version>.jar". Ask Central first - it
// serves those artifacts directly - and keep the portal as the fallback.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

rootProject.name = "jpdfium-build-logic"
