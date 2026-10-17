pluginManagement {
    // The kotlin-dsl plugin resolves its own classpath from these repositories, so
    // Maven Central comes first and the plugin portal is only a fallback.
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

rootProject.name = "jpdfium-build-logic"
