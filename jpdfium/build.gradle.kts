plugins {
    id("jpdfium.library-conventions")
}

dependencies {
    implementation(libs.imageio.webp)
    implementation(libs.imageio.tiff)
    // Which platform's native jar lands on the test classpath. Defaults to
    // linux-x64 for local dev; CI overrides per matrix job with
    // -Pjpdfium.testNatives=<platform> so the smoke loads the native it built.
    val testNatives = (findProperty("jpdfium.testNatives") ?: "linux-x64").toString()
    testRuntimeOnly(project(":jpdfium-natives:jpdfium-natives-$testNatives"))
    testImplementation(libs.pdfbox)
}

// Set jpdfium.jextractHome in ~/.gradle/gradle.properties or JEXTRACT_HOME env var.
val jextractBin: String = run {
    val home = findProperty("jpdfium.jextractHome")?.toString()
        ?: System.getenv("JEXTRACT_HOME")
        ?: "${System.getProperty("user.home")}/Downloads/jextract-25"
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val ext = if (isWindows) ".bat" else ""
    "$home/bin/jextract$ext"
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
    "jpdfium_page_flatten", "jpdfium_page_to_image",
    // Advanced Pattern Engine (PCRE2 JIT)
    "jpdfium_pcre2_compile", "jpdfium_pcre2_match_all", "jpdfium_pcre2_free",
    "jpdfium_luhn_validate",
    // FlashText Dictionary NER
    "jpdfium_flashtext_create", "jpdfium_flashtext_add_keyword",
    "jpdfium_flashtext_add_keywords_json", "jpdfium_flashtext_find", "jpdfium_flashtext_free",
    // Font Normalization Pipeline
    "jpdfium_font_get_data", "jpdfium_font_classify",
    "jpdfium_font_fix_tounicode", "jpdfium_font_repair_widths",
    "jpdfium_font_normalize_page", "jpdfium_font_subset",
    // Glyph-Level Redaction
    "jpdfium_redact_glyph_aware",
    // XMP Metadata Redaction
    "jpdfium_xmp_redact_patterns", "jpdfium_metadata_strip", "jpdfium_metadata_strip_all",
    // ICU4C Text Processing
    "jpdfium_icu_normalize_nfc", "jpdfium_icu_break_sentences", "jpdfium_icu_bidi_reorder",
    // Annotation-Based Redaction (Mark → Commit pattern)
    "jpdfium_annot_create_redact", "jpdfium_redact_mark_words",
    "jpdfium_annot_count_redacts", "jpdfium_annot_get_redacts_json",
    "jpdfium_annot_remove_redact", "jpdfium_annot_clear_redacts",
    "jpdfium_redact_commit", "jpdfium_doc_save_incremental",
    // Raw handle extraction (for direct PDFium FFM bindings)
    "jpdfium_doc_raw_handle", "jpdfium_page_raw_handle", "jpdfium_page_doc_raw_handle",
    // PDF Repair Pipeline
    "jpdfium_repair_pdf", "jpdfium_repair_inspect",
    // Brotli Codec
    "jpdfium_brotli_decode", "jpdfium_brotli_to_flate",
    // PDFio Structural Repair
    "jpdfium_pdfio_try_repair",
    // lcms2 ICC Profile Validation
    "jpdfium_validate_icc_profile", "jpdfium_generate_replacement_icc",
    // OpenJPEG JPEG2000
    "jpdfium_validate_jpx_stream", "jpdfium_jpx_to_raw",
    // Image to PDF
    "jpdfium_image_to_pdf", "jpdfium_doc_add_image_page",
    // N-Up Layout
    "jpdfium_import_n_pages_to_one",
    // compression, repair, and image resize
    "jpdfium_rust_compress_pdf",
    "jpdfium_rust_repair_lopdf",
    "jpdfium_rust_resize_pixels",
    "jpdfium_rust_free"
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

    // Skip gracefully when jextract is not installed
    isIgnoreExitValue = false
    onlyIf {
        val jextract = file(jextractBin)
        if (!jextract.exists()) {
            logger.warn("jextract not found at $jextractBin — skipping binding generation. " +
                "Set JEXTRACT_HOME or jpdfium.jextractHome to the jextract installation directory.")
        }
        jextract.exists()
    }
}

// jextract on Linux emits `JpdfiumH$shared` with `C_LONG` typed as
// `ValueLayout.OfLong` and initialized from `Linker.nativeLinker()
// .canonicalLayouts().get("long")`. On Windows that returns `OfInt`
// (LLP64: C long is 4 bytes), so the OfLong cast throws ClassCastException
// during class init the first time anything in the panama/ package is touched.
// C_LONG is not actually referenced by any binding generated for jpdfium.h
// (the C bridge uses fixed-size int/int64_t types throughout), so the safest
// fix is to neutralize the platform-specific init. Patch the generated
// source between jextract and compileJava so the published jar works on
// both LP64 (Linux/macOS) and LLP64 (Windows) hosts.
val patchBindingsForCrossPlatform by tasks.registering {
    description = "Make JpdfiumH\$shared.C_LONG init Windows-safe (OfInt vs OfLong)"
    dependsOn(generateBindings)
    val outputDir = layout.buildDirectory.dir("generated/jextract/java")
    outputs.dir(outputDir)
    doLast {
        val sharedFile = outputDir.get().asFile.resolve(
            "stirling/software/jpdfium/panama/JpdfiumH\$shared.java")
        if (!sharedFile.exists()) {
            logger.info("JpdfiumH\$shared.java not present — generateBindings skipped, nothing to patch.")
            return@doLast
        }
        val original = sharedFile.readText()
        // Match any `public static final ValueLayout.OfLong C_LONG = ...;`
        // (jextract sometimes prefixes with fully-qualified names; tolerate both).
        val pattern = Regex(
            """public\s+static\s+final\s+(?:java\.lang\.foreign\.)?ValueLayout\.OfLong\s+C_LONG\s*=\s*[^;]+;""")
        val patched = pattern.replace(original,
            "public static final ValueLayout.OfLong C_LONG = ValueLayout.JAVA_LONG; " +
            "/* patched: jextract emits a platform-specific cast that crashes on Windows; " +
            "this constant is unused by any jpdfium binding so a placeholder is fine */")
        if (patched != original) {
            sharedFile.writeText(patched)
            logger.lifecycle("Patched JpdfiumH\$shared.C_LONG for cross-platform class init")
        } else {
            logger.warn("JpdfiumH\$shared.java did not contain the expected C_LONG pattern — " +
                "jextract output format may have changed.")
        }
    }
}

sourceSets.main.get().java.srcDir(patchBindingsForCrossPlatform.map {
    layout.buildDirectory.dir("generated/jextract/java").get()
})

// Run: ./gradlew :jpdfium:run -PmainClass=com.example.Main
tasks.register<JavaExec>("run") {
    group       = "application"
    description = "Run a main class from the test classpath"
    if (project.hasProperty("mainClass")) mainClass.set(project.property("mainClass").toString())
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Run: ./gradlew :jpdfium:integrationTest
tasks.register<Test>("integrationTest") {
    group       = "verification"
    description = "Run integration tests against real PDFium"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath       = sourceSets.test.get().runtimeClasspath
    systemProperty("jpdfium.integration", "true")
    // Forward -Djpdfium.bench.* from gradle invocation to the test JVM.
    System.getProperties().forEach { k, v ->
        val key = k.toString()
        if (key.startsWith("jpdfium.bench")) systemProperty(key, v.toString())
    }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = System.getProperty("jpdfium.bench.xmx", "2g")
}

// Run: ./gradlew :jpdfium:nativeSmokeTest -Pjpdfium.testNatives=<platform>
// Fast per-platform functional check: load the bundled native via the
// production NativeLoader path and open a PDF. Used by CI to verify each
// freshly-built native actually runs (not just that a file was produced).
tasks.register<Test>("nativeSmokeTest") {
    group       = "verification"
    description = "Load the bundled native and open a PDF (per-platform CI smoke)"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath       = sourceSets.test.get().runtimeClasspath
    systemProperty("jpdfium.smoke", "true")
    filter { includeTestsMatching("*NativeSmokeTest") }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // CI has no access to the HTML report; a load failure must name the
    // missing library in the console.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}
