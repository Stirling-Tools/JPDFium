# Render the first page

A minimal JPDFium consumer project. It resolves `com.stirling:jpdfium` from https://maven.stirlingpdf.com and renders `sample.pdf` (the JPDFium logo) to a PNG at 150 DPI.

From the repository root:

```bash
./gradlew -p examples/render-first-page run --args="sample.pdf page-1.png"
```

`page-1.png` is written next to `sample.pdf`. Output paths are relative to this directory.

The build selects the native module for the machine that runs it: `jpdfium-natives-<platform>-<arch>` for Linux, macOS, and Windows. Alpine and other musl systems must substitute `jpdfium-natives-linux-musl-<arch>`.

To use this as a starting point for your own project, copy the directory, keep the dependency block in `build.gradle.kts`, and add your own Gradle wrapper.
