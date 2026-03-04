plugins {
    id("jpdfium.library-conventions")
}

dependencies {
    testRuntimeOnly(project(":jpdfium-natives:jpdfium-natives-linux-x64"))
    testImplementation(libs.pdfbox)
}

// Set jpdfium.jextractHome in ~/.gradle/gradle.properties or JEXTRACT_HOME env var.
val jextractBin: String = run {
    val home = findProperty("jpdfium.jextractHome")?.toString()
        ?: System.getenv("JEXTRACT_HOME")
        ?: "${System.getProperty("user.home")}/Downloads/jextract-25"
    "$home/bin/jextract"
}

val jpdfiumFunctions = listOf(
    "jpdfium_init", "jpdfium_destroy",
    "jpdfium_doc_open", "jpdfium_doc_open_bytes", "jpdfium_doc_open_protected",
    "jpdfium_doc_page_count", "jpdfium_doc_save", "jpdfium_doc_save_bytes", "jpdfium_doc_close",
    "jpdfium_page_open", "jpdfium_page_width", "jpdfium_page_height", "jpdfium_page_close",
    "jpdfium_render_page", "jpdfium_free_buffer",
    "jpdfium_text_get_chars", "jpdfium_text_find", "jpdfium_free_string",
    "jpdfium_text_get_char_positions",
    "jpdfium_redact_region", "jpdfium_redact_pattern", "jpdfium_redact_words",
    "jpdfium_redact_words_ex",
    "jpdfium_page_flatten", "jpdfium_page_to_image"
)

val generateBindings by tasks.registering(Exec::class) {
    description = "Generate FFM bindings from jpdfium.h using jextract"
    val outputDir = layout.buildDirectory.dir("generated/jextract/java")
    val headerFile = rootProject.file("native/bridge/include/jpdfium.h")

    inputs.file(headerFile)
    outputs.dir(outputDir)

    val args = mutableListOf(
        jextractBin,
        "--output", outputDir.get().asFile.absolutePath,
        "--target-package", "stirling.software.jpdfium.panama",
        "--header-class-name", "JpdfiumH"
    )
    jpdfiumFunctions.forEach { fn -> args += listOf("--include-function", fn) }
    args += headerFile.absolutePath

    commandLine(args)
}

sourceSets.main.get().java.srcDir(generateBindings.map {
    layout.buildDirectory.dir("generated/jextract/java").get()
})

// Run: ./gradlew :jpdfium:viewer [-Ppdf=/path/to/file.pdf]
tasks.register<JavaExec>("viewer") {
    group       = "verification"
    description = "Launch the Swing PDF viewer for visual testing"
    mainClass.set("stirling.software.jpdfium.PdfViewerApp")
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (project.hasProperty("pdf")) args(project.property("pdf").toString())
}

// Run: ./gradlew :jpdfium:run -PmainClass=com.example.Main
tasks.register<JavaExec>("run") {
    group       = "application"
    description = "Run a main class from the test classpath"
    if (project.hasProperty("mainClass")) mainClass.set(project.property("mainClass").toString())
    if (project.hasProperty("args")) args(project.property("args").toString().split(" "))
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// ── Integration tests (real PDFium, tagged "integration") ─────────────────────
// Run: ./gradlew :jpdfium:integrationTest
tasks.register<Test>("integrationTest") {
    group       = "verification"
    description = "Run integration tests against real PDFium"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath       = sourceSets.test.get().runtimeClasspath
    systemProperty("jpdfium.integration", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
}
