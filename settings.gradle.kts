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
    "jpdfium-natives:jpdfium-natives-vips-darwin-arm64"
)

// The Windows vips natives are only published once a GPL-free prebuild is
// pinned in native/vips.version: the upstream MXE zip links GPL libimagequant.
val windowsVipsPin = file("native/vips.version").let { pin ->
    pin.exists() && pin.readLines().any { it.isNotBlank() && !it.trimStart().startsWith("#") }
}
if (windowsVipsPin) {
    include(
        "jpdfium-natives:jpdfium-natives-vips-windows-x64",
        "jpdfium-natives:jpdfium-natives-vips-windows-arm64"
    )
}
