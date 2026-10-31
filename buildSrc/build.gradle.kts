plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
}

dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.10.4")
    implementation("com.github.spotbugs.snom:spotbugs-gradle-plugin:6.0.27")
    implementation("de.thetaphi:forbiddenapis:3.11")
    // Vendored (0BSD) so buildSrc does not need Maven Central just to package
    // the runtime xz payloads; the published jars still declare the Maven xz
    // dependency for consumers.
    implementation(files("libs/xz-1.10.jar"))
}
