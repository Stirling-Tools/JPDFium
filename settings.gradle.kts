rootProject.name = "JPDFium"

include(
    "jpdfium",
    "jpdfium-bom",
    "jpdfium-spring",
    "jpdfium-natives:jpdfium-natives-linux-x64",
    "jpdfium-natives:jpdfium-natives-linux-arm64",
    "jpdfium-natives:jpdfium-natives-darwin-x64",
    "jpdfium-natives:jpdfium-natives-darwin-arm64",
    "jpdfium-natives:jpdfium-natives-windows-x64"
)
