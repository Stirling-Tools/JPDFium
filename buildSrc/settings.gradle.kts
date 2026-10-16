rootProject.name = "jpdfium-build-logic"

// buildSrc is a separate build: it does NOT inherit the root pluginManagement,
// so without this block the kotlin-dsl plugin classpath (the Kotlin Gradle
// Plugin and its transitive jars: kotlin-stdlib, kotlin-compiler-runner,
// fus-statistics-gradle-plugin, gson, ...) is resolved from the Gradle Plugin
// Portal alone. A single portal hiccup then fails every build, even though
// Maven Central hosts all of those artifacts.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
