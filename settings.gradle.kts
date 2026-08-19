pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "JPDFium"

include(
    "jpdfium",
    "jpdfium-bom",
    "jpdfium-spring",
    "jpdfium-vips",
    "jpdfium-natives:jpdfium-natives-linux-x64",
    "jpdfium-natives:jpdfium-natives-linux-arm64",
    "jpdfium-natives:jpdfium-natives-linux-musl-x64",
    "jpdfium-natives:jpdfium-natives-linux-musl-arm64",
    "jpdfium-natives:jpdfium-natives-darwin-x64",
    "jpdfium-natives:jpdfium-natives-darwin-arm64",
    "jpdfium-natives:jpdfium-natives-windows-x64",
    "jpdfium-natives:jpdfium-natives-windows-arm64",
    "jpdfium-natives:jpdfium-natives-vips-linux-x64",
    "jpdfium-natives:jpdfium-natives-vips-linux-arm64",
    "jpdfium-natives:jpdfium-natives-vips-darwin-x64",
    "jpdfium-natives:jpdfium-natives-vips-darwin-arm64",
    "jpdfium-natives:jpdfium-natives-vips-windows-x64",
    "jpdfium-natives:jpdfium-natives-vips-windows-arm64"
)
