rootProject.name = "jpdfium-build-logic"

// buildSrc is a separate build: it does NOT inherit the root pluginManagement,
// so without this block the kotlin-dsl plugin classpath (the Kotlin Gradle
// Plugin and its transitive jars: kotlin-stdlib, kotlin-compiler-runner,
// fus-statistics-gradle-plugin, gson, ...) is resolved from the Gradle Plugin
// Portal alone. Maven Central is listed first on purpose: Gradle binds an
// artifact to the repository that served its metadata and then never looks for
// the jar anywhere else, so a portal-only list has no fallback at all.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}
