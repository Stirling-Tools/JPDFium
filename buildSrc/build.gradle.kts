plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.9.0")
    implementation("com.github.spotbugs.snom:spotbugs-gradle-plugin:6.0.27")
    implementation("de.thetaphi:forbiddenapis:3.10")
}
