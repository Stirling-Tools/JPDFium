import java.nio.file.Files
import java.nio.file.Path

plugins {
    id("jpdfium.library-conventions")
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.jmh)
    application
}

application {
    mainClass.set("stirling.software.jpdfium.GraalVmSmokeApp")
}

graalvmNative {
    metadataRepository {
        enabled.set(false)
    }
    binaries {
        named("main") {
            imageName.set("jpdfium-native-smoke")
            mainClass.set("stirling.software.jpdfium.GraalVmSmokeApp")
            sharedLibrary.set(false)
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
        }
        create("cli") {
            imageName.set("jpdfium")
            mainClass.set("stirling.software.jpdfium.JpdfiumCli")
            sharedLibrary.set(false)
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
            // Bundle the platform natives jar resources so NativeLoader can
            // extract and load libjpdfium at runtime.
            buildArgs.add("-H:IncludeResources=natives/.*")
            // Native library loading and FFM symbol lookup must happen at runtime
            // on the target host, never during image build.
            buildArgs.add("--initialize-at-run-time=stirling.software.jpdfium.panama.NativeLoader")
            buildArgs.add("--initialize-at-run-time=stirling.software.jpdfium.panama.Symbols")
            // Only the C locale is needed; trims ~10 MB of locale data.
            buildArgs.add("-H:IncludeLocales=en")
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}

dependencies {
    implementation(libs.imageio.webp)
    implementation(libs.imageio.tiff)
    // Which platform's native jar lands on the classpath. Auto-detects host OS
    // and arch by default; CI overrides per matrix job with
    // -Pjpdfium.testNatives=<platform> so the smoke loads the native it built.
    val defaultPlatform = run {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val isArm = arch == "aarch64" || arch == "arm64"
        when {
            os.contains("mac") || os.contains("darwin") -> if (isArm) "darwin-arm64" else "darwin-x64"
            os.contains("win") -> if (isArm) "windows-arm64" else "windows-x64"
            else -> {
                // Linux natives are libc-specific: a musl host (Alpine) needs the
                // linux-musl-* artifact - a glibc-linked libjpdfium.so won't load
                // into a musl JVM. Mirror NativeLoader's detection here so builds
                // executed on Alpine resolve the right natives jar automatically.
                val muslLoader = Path.of("/lib/ld-musl-${if (isArm) "aarch64" else "x86_64"}.so.1")
                val musl = Files.exists(muslLoader)
                    || runCatching {
                        Files.newDirectoryStream(Path.of("/lib"), "ld-musl-*").use { it.iterator().hasNext() }
                    }.getOrDefault(false)
                when {
                    musl && isArm -> "linux-musl-arm64"
                    musl -> "linux-musl-x64"
                    isArm -> "linux-arm64"
                    else -> "linux-x64"
                }
            }
        }
    }
    val testNatives = (findProperty("jpdfium.testNatives") ?: defaultPlatform).toString()
    runtimeOnly(project(":jpdfium-natives:jpdfium-natives-$testNatives"))
    testImplementation(libs.pdfbox)
    jmhImplementation(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.annproc)
}

jmh {
    // Run each benchmark for a brief warmup + 3 measurement forks so CI
    // finishes in reasonable time. Developers can override on the command line:
    //   ./gradlew :jpdfium:jmh -Pjmh.warmupIterations=5 -Pjmh.iterations=5
    warmupIterations.set((findProperty("jmh.warmupIterations") as String? ?: "2").toInt())
    iterations.set((findProperty("jmh.iterations") as String? ?: "3").toInt())
    fork.set(1)
    timeUnit.set("ms")
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json"))
    jvmArgsAppend.add("--enable-native-access=ALL-UNNAMED")
    findProperty("jmh.profilers")?.let { prof ->
        profilers.addAll((prof as String).split(",").map { it.trim() })
    }
    // Include only benchmarks in the jpdfium bench package.
    val includePattern = findProperty("jmh.include") as String? ?: "stirling.software.jpdfium.bench.*"
    includes.add(includePattern)
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
    "jpdfium_doc_create",
    "jpdfium_doc_page_count", "jpdfium_doc_save", "jpdfium_doc_save_bytes", "jpdfium_doc_close",
    "jpdfium_page_open", "jpdfium_page_width", "jpdfium_page_height", "jpdfium_page_close",
    "jpdfium_render_page", "jpdfium_free_buffer",
    "jpdfium_text_get_chars", "jpdfium_text_find", "jpdfium_free_string",
    "jpdfium_text_get_char_positions",
    "jpdfium_redact_region", "jpdfium_redact_pattern", "jpdfium_redact_words",
    "jpdfium_redact_words_ex", "jpdfium_crop_remove_content",
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
    "jpdfium_doc_sanitize_report",
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
    "jpdfium_rust_free",
    // QPDF in-process functions
    "jpdfium_qpdf_optimize",
    "jpdfium_qpdf_sanitize",
    "jpdfium_qpdf_merge",
    "jpdfium_qpdf_extract_pages",
    "jpdfium_qpdf_encrypt",
    "jpdfium_qpdf_decrypt"
)

val generateBindings = tasks.register<Exec>("generateBindings") {
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

    val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
    if (!javaHome.isNullOrEmpty()) {
        environment("JAVA_HOME", javaHome)
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("win") && !os.contains("mac")) {
            val ldPath = System.getenv("LD_LIBRARY_PATH") ?: ""
            environment("LD_LIBRARY_PATH", "$javaHome/lib:$javaHome/lib/server:$ldPath")
        }
    }

    // Skip gracefully when jextract is not installed or fails due to EA runtime image
    isIgnoreExitValue = true
    onlyIf {
        val jextract = file(jextractBin)
        if (!jextract.exists()) {
            logger.warn("jextract not found at $jextractBin - skipping binding generation. " +
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
val patchBindingsForCrossPlatform = tasks.register("patchBindingsForCrossPlatform") {
    description = "Make JpdfiumH\$shared.C_LONG init Windows-safe (OfInt vs OfLong)"
    dependsOn(generateBindings)
    val outputDir = layout.buildDirectory.dir("generated/jextract/java")
    outputs.dir(outputDir)
    doLast {
        val genMain = outputDir.get().asFile.resolve("stirling/software/jpdfium/panama/JpdfiumH.java")
        val genShared = outputDir.get().asFile.resolve("stirling/software/jpdfium/panama/JpdfiumH\$shared.java")
        val committedDir = file("src/main/java/stirling/software/jpdfium/panama")

        if (genMain.exists() && genShared.exists()) {
            genMain.copyTo(committedDir.resolve("JpdfiumH.java"), overwrite = true)
            genShared.copyTo(committedDir.resolve("JpdfiumH\$shared.java"), overwrite = true)
            logger.lifecycle("Updated committed JpdfiumH bindings from jextract generation.")
        }

        val targetShared = committedDir.resolve("JpdfiumH\$shared.java")
        if (targetShared.exists()) {
            val original = targetShared.readText()
            if (original.contains("/* patched: jextract emits a platform-specific cast")) {
                logger.lifecycle("JpdfiumH\$shared.C_LONG already patched - skipping")
            } else {
                val pattern = Regex(
                    """public\s+static\s+final\s+(?:java\.lang\.foreign\.)?ValueLayout\.OfLong\s+C_LONG\s*=\s*[^;]+;""")
                val patched = pattern.replace(original,
                    "public static final ValueLayout.OfLong C_LONG = ValueLayout.JAVA_LONG; " +
                    "/* patched: jextract emits a platform-specific cast that crashes on Windows; " +
                    "this constant is unused by any jpdfium binding so a placeholder is fine */")
                if (patched != original) {
                    targetShared.writeText(patched)
                    logger.lifecycle("Patched JpdfiumH\$shared.C_LONG for cross-platform class init")
                }
            }
        }

        // jextract maps int64_t to C_LONG_LONG on macOS but C_LONG on Linux
        // (both are the same 8-byte layout on LP64). Normalize to C_LONG so the
        // committed bindings are byte-identical no matter which host generated
        // them - the ffm-layout CI check diffs regenerated vs committed output
        // and must not see host-dependent churn.
        val targetMain = committedDir.resolve("JpdfiumH.java")
        if (targetMain.exists()) {
            val before = targetMain.readText()
            val normalized = before.replace("JpdfiumH.C_LONG_LONG", "JpdfiumH.C_LONG")
            if (normalized != before) {
                targetMain.writeText(normalized)
                logger.lifecycle("Normalized JpdfiumH descriptors to C_LONG for cross-platform stability")
            }
        }
    }
}

tasks.named<JavaExec>("run") {
    group       = "application"
    description = "Run a main class from the test classpath"
    if (project.hasProperty("mainClass")) mainClass.set(project.property("mainClass").toString())
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Run: ./gradlew :jpdfium:generateTestPdfs
tasks.register<JavaExec>("generateTestPdfs") {
    group       = "verification"
    description = "Generate test PDFs for redaction and coordinate tests"
    dependsOn(":jpdfium:compileTestJava")
    mainClass.set("stirling.software.jpdfium.redact.RedactTestPdfGenerator")
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("pdfgen.outdir", file("src/test/resources/pdfs/redact").absolutePath)
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
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx4g")
    maxHeapSize = "4g"
    setForkEvery(20)
}

// Run: ./gradlew :jpdfium:corpusTest -Pjpdfium.testNatives=<platform>
// Corpus suite (downloaded + local + synthetic PDFs) against real PDFium.
// Correctness assertions are hard; perf/alloc metrics are reported only, so
// environment-sensitive timing can never fail the build.
tasks.register<Test>("corpusTest") {
    group       = "verification"
    description = "Run the corpus test suite against real PDFium (downloads test PDFs)"
    useJUnitPlatform {
        includeTags("corpus")
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath       = sourceSets.test.get().runtimeClasspath
    systemProperty("jpdfium.integration", "true")
    systemProperty("jpdfium.corpus", "true")
    // Forward corpus shard selection (used by the sharded CI job).
    System.getProperties().forEach { k, v ->
        val key = k.toString()
        if (key.startsWith("jpdfium.corpus.")) systemProperty(key, v.toString())
    }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = System.getProperty("jpdfium.bench.xmx", "4g")
}

// Run: ./gradlew :jpdfium:generateCorpus -Pcorpus.count=3000 -Pcorpus.seed=42
// Generates the synthetic PDF corpus with PDFBox (DiversePdfGenerator).
tasks.register<JavaExec>("generateCorpus") {
    group       = "verification"
    description = "Generate the synthetic PDFBox corpus (count/seed via -Pcorpus.count/-Pcorpus.seed)"
    dependsOn("compileTestJava")
    mainClass.set("stirling.software.jpdfium.corpus.DiversePdfGenerator")
    classpath = sourceSets.test.get().runtimeClasspath
    args(
        project.findProperty("corpus.outDir")?.toString()
            ?: layout.buildDirectory.dir("test-corpus/generated").get().asFile.absolutePath,
        project.findProperty("corpus.count")?.toString() ?: "300",
        project.findProperty("corpus.seed")?.toString() ?: "42"
    )
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
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

// Run: ./gradlew :jpdfium:graalvmNativeSmokeTest -Pjpdfium.testNatives=<platform>
tasks.register<Exec>("graalvmNativeSmokeTest") {
    group       = "verification"
    description = "Compile and execute GraalVM Native Image smoke test binary"
    dependsOn("nativeCompile")
    val binaryFile = layout.buildDirectory.file("native/nativeCompile/jpdfium-native-smoke")
    executable(binaryFile.get().asFile.absolutePath)
}

// Run: ./gradlew :jpdfium:cliTest -Pjpdfium.testNatives=<platform>
// End-to-end CLI regression + correctness gate: runs every operation through the
// JVM-testable JpdfiumCli.run() seam against real PDFs and verifies each output
// opens, keeps the expected page count, and reflects the mutation (removed text
// stays removed, rotation swaps dimensions, merges sum, splits divide, renders
// decode). Requires the real native on the classpath (-Djpdfium.smoke).
tasks.register<Test>("cliTest") {
    group       = "verification"
    description = "Run JpdfiumCli end-to-end correctness tests against the real native"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath       = sourceSets.test.get().runtimeClasspath
    systemProperty("jpdfium.smoke", "true")
    filter { includeTestsMatching("*JpdfiumCliTest") }
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}
