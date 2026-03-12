allprojects {
    group = "stirling.software"
    version = findProperty("jpdfium.version")?.toString() ?: "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// Task to build the real native bridge (libjpdfium.so with real PDFium)
tasks.register<Exec>("buildRealBridge") {
    group = "build"
    description = "Build the real native bridge with PDFium (run native/setup-pdfium.sh first)"
    
    val scriptPath = rootProject.file("native/build-real.sh")
    if (!scriptPath.exists()) {
        throw GradleException("build-real.sh not found at ${scriptPath.absolutePath}")
    }
    
    commandLine("bash", scriptPath.absolutePath)
}

// Task to build the stub native bridge (for unit tests without PDFium)
tasks.register<Exec>("buildStubBridge") {
    group = "build"
    description = "Build the stub native bridge (no PDFium required, for unit tests only)"
    
    val scriptPath = rootProject.file("native/build-stub.sh")
    if (!scriptPath.exists()) {
        throw GradleException("build-stub.sh not found at ${scriptPath.absolutePath}")
    }
    
    commandLine("bash", scriptPath.absolutePath)
}

// Task to build PDFium from EmbedPDF fork source
tasks.register<Exec>("buildPdfium") {
    group = "build"
    description = "Build PDFium from EmbedPDF fork source (required before building real bridge)"
    
    val scriptPath = rootProject.file("native/setup-pdfium.sh")
    if (!scriptPath.exists()) {
        throw GradleException("setup-pdfium.sh not found at ${scriptPath.absolutePath}")
    }
    
    commandLine("bash", scriptPath.absolutePath)
}

// Task to run all 50 samples (full application test)
tasks.register<JavaExec>("runAllSamples") {
    group = "application"
    description = "Run all samples to test the complete application"

    dependsOn(":jpdfium:compileTestJava", ":jpdfium:processTestResources")

    mainClass.set("stirling.software.jpdfium.samples.RunAllSamples")
    val jpdfiumCompileJava = tasks.getByPath(":jpdfium:compileJava") as JavaCompile
    val jpdfiumCompileTest = tasks.getByPath(":jpdfium:compileTestJava") as JavaCompile
    classpath = project(":jpdfium").configurations.getByName("testRuntimeClasspath") + 
                files(jpdfiumCompileJava.outputs.files, jpdfiumCompileTest.outputs.files) +
                files(project(":jpdfium").layout.buildDirectory.dir("resources/test"))
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
    workingDir = rootProject.projectDir
}

// Task to run a specific sample by number (e.g., ./gradlew runSample -Psample=01)
tasks.register<JavaExec>("runSample") {
    group = "application"
    description = "Run a specific sample by number (e.g., ./gradlew runSample -Psample=01)"

    dependsOn(":jpdfium:compileTestJava", ":jpdfium:processTestResources")

    val sampleNum = project.findProperty("sample")?.toString()?.padStart(2, '0') ?: "01"
    
    // Map sample numbers to class names
    val sampleClasses = mapOf(
        "01" to "S01_Render",
        "02" to "S02_TextExtract",
        "03" to "S03_TextSearch",
        "04" to "S04_Metadata",
        "05" to "S05_Bookmarks",
        "06" to "S06_RedactWords",
        "07" to "S07_Annotations",
        "08" to "S08_FullPipeline",
        "09" to "S09_Flatten",
        "10" to "S10_Signatures",
        "11" to "S11_Attachments",
        "12" to "S12_Links",
        "13" to "S13_PageImport",
        "14" to "S14_StructureTree",
        "15" to "S15_Thumbnails",
        "16" to "S16_PageEditing",
        "17" to "S17_NUpLayout",
        "18" to "S18_Repair",
        "19" to "S19_PdfToImages",
        "20" to "S20_ImagesToPdf",
        "21" to "S21_Thumbnails",
        "22" to "S22_MergeSplit",
        "23" to "S23_Watermark",
        "24" to "S24_TableExtract",
        "25" to "S25_PageGeometry",
        "26" to "S26_HeaderFooter",
        "27" to "S27_Security",
        "28" to "S28_DocInfo",
        "29" to "S29_RenderOptions",
        "30" to "S30_FormReader",
        "31" to "S31_ImageExtract",
        "32" to "S32_PageObjects",
        "33" to "S33_Encryption",
        "34" to "S34_Linearizer",
        "35" to "S35_Overlay",
        "36" to "S36_AnnotationBuilder",
        "37" to "S37_PathDrawer",
        "38" to "S38_JavaScriptInspector",
        "39" to "S39_WebLinks",
        "40" to "S40_PageBoxes",
        "41" to "S41_VersionConverter",
        "42" to "S42_BoundedText",
        "43" to "S43_StreamOptimizer",
        "44" to "S44_FlattenRotation",
        "45" to "S45_PageInterleaver",
        "46" to "S46_NamedDestinations",
        "47" to "S47_BlankPageDetector",
        "48" to "S48_EmbedPdfAnnotations",
        "49" to "S49_NativeEncryption",
        "50" to "S50_NativeRedaction",
        "51" to "S51_Compress",
        "52" to "S52_BookmarkEditor",
        "53" to "S53_BarcodeGenerate",
        "54" to "S54_PageReorder",
        "55" to "S55_ColorConvert",
        "56" to "S56_Booklet",
        "58" to "S58_Analytics",
        "59" to "S59_FormFill",
        "60" to "S60_AutoCrop",
        "61" to "S61_SearchHighlight",
        "62" to "S62_PageSplit2Up",
        "63" to "S63_PageLabels",
        "64" to "S64_LinkValidation",
        "65" to "S65_Posterize",
        "66" to "S66_PdfDiff",
        "67" to "S67_AutoDeskew",
        "68" to "S68_FontAudit",
        "69" to "S69_PdfAConversion",
        "70" to "S70_PageScaling",
        "71" to "S71_MarginAdjust",
        "72" to "S72_SelectiveFlatten",
        "73" to "S73_AnnotExport",
        "74" to "S74_ImageReplace",
        "75" to "S75_LongImage",
        "76" to "S76_DuplicateDetect",
        "77" to "S77_ColumnExtract",
        "78" to "S78_ImageDpi",
        "79" to "S79_PageMirror",
        "80" to "S80_Background",
        "81" to "S81_ReadingOrder",
        "82" to "S82_ResourceDedup",
        "83" to "S83_TocGenerate",
        "84" to "S84_SelectiveRaster",
        "85" to "S85_AnnotStats",
        "86" to "S86_PosterizeSizes",
        "87" to "S87_AutoCropMargins",
        "89" to "S89_StructureEditor",
        "90" to "S90_Layers",
        "91" to "S91_AnnotationExchange",
        "92" to "S92_RustCompress"
    )
    
    val sampleClass = "stirling.software.jpdfium.samples." + 
        (sampleClasses[sampleNum] ?: "S${sampleNum}_Render")

    mainClass.set(sampleClass)
    val jpdfiumCompileJava = tasks.getByPath(":jpdfium:compileJava") as JavaCompile
    val jpdfiumCompileTest = tasks.getByPath(":jpdfium:compileTestJava") as JavaCompile
    classpath = project(":jpdfium").configurations.getByName("testRuntimeClasspath") + 
                files(jpdfiumCompileJava.outputs.files, jpdfiumCompileTest.outputs.files) +
                files(project(":jpdfium").layout.buildDirectory.dir("resources/test"))
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = rootProject.projectDir

    doFirst {
        println("Running sample: $sampleClass")
    }
}

// Full end-to-end build and test task
tasks.register("fullBuildAndTest") {
    group = "build"
    description = "Full end-to-end: build PDFium, build real bridge, run unit tests, integration tests, and all samples"
    
    dependsOn(
        "buildPdfium",
        "buildRealBridge",
        ":jpdfium:test",
        ":jpdfium:integrationTest",
        "runAllSamples"
    )
    
    doLast {
        println("")
        println("========================================")
        println("  FULL BUILD AND TEST COMPLETED")
        println("========================================")
        println("  - PDFium downloaded")
        println("  - Real native bridge built")
        println("  - Unit tests passed")
        println("  - Integration tests passed")
        println("  - All 50 samples executed")
        println("  - Output: samples-output/")
        println("========================================")
    }
}

// Quick try-out task (build stub + run samples for quick testing)
tasks.register("quickTry") {
    group = "application"
    description = "Quick try-out: build stub bridge and run all samples (no PDFium required)"
    
    dependsOn(
        "buildStubBridge",
        ":jpdfium:test",
        "runAllSamples"
    )
    
    doLast {
        println("")
        println("========================================")
        println("  QUICK TRY COMPLETED")
        println("========================================")
        println("  - Stub bridge built (no PDFium)")
        println("  - Unit tests passed")
        println("  - Samples executed (stub mode)")
        println("  Note: Some features require real PDFium")
        println("  For full testing, run: ./gradlew fullBuildAndTest")
        println("========================================")
    }
}

// ── Maven Central Publishing (Central Portal via OSSRH Staging API) ──────────
//
// After running:  ./gradlew publishAllPublicationsToCentralPortalRepository
// the artifacts sit in the OSSRH staging area.  You must then POST to finalize
// the deployment so it appears in the Central Portal for review / auto-release.
//
// Usage:
//   ./gradlew publishAllToCentralPortal            # publish + finalize (user_managed)
//   ./gradlew publishAllToCentralPortal -PautoRelease=true  # publish + auto-release
//
// Prerequisites (set in ~/.gradle/gradle.properties or env):
//   centralPortalUsername / CENTRAL_PORTAL_USERNAME
//   centralPortalPassword / CENTRAL_PORTAL_PASSWORD
//   signing.key / GPG_SIGNING_KEY
//   signing.password / GPG_SIGNING_PASSWORD

tasks.register("finalizePortalDeployment") {
    group = "publishing"
    description = "POST to the OSSRH Staging API to push the deployment to the Central Portal"

    doLast {
        val namespace = project.group.toString()  // e.g. "stirling.software"
        val autoRelease = (findProperty("autoRelease")?.toString() ?: "false").toBoolean()
        val publishingType = if (autoRelease) "automatic" else "user_managed"
        val url = "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/$namespace?publishing_type=$publishingType"

        val tokenUser = findProperty("centralPortalUsername")?.toString()
            ?: findProperty("ossrhUsername")?.toString()
            ?: System.getenv("CENTRAL_PORTAL_USERNAME")
            ?: System.getenv("OSSRH_USERNAME")
            ?: error("No Central Portal username configured")
        val tokenPass = findProperty("centralPortalPassword")?.toString()
            ?: findProperty("ossrhPassword")?.toString()
            ?: System.getenv("CENTRAL_PORTAL_PASSWORD")
            ?: System.getenv("OSSRH_PASSWORD")
            ?: error("No Central Portal password configured")

        val bearer = java.util.Base64.getEncoder().encodeToString("$tokenUser:$tokenPass".toByteArray())

        val client = java.net.http.HttpClient.newHttpClient()
        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .header("Authorization", "Bearer $bearer")
            .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
            .build()

        println("Finalizing deployment to Central Portal (publishingType=$publishingType) ...")
        val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() in 200..299) {
            println("✓ Deployment finalized successfully (HTTP ${response.statusCode()})")
            if (autoRelease) {
                println("  Auto-release enabled — artifacts will appear on Maven Central after validation (~10-30 min)")
            } else {
                println("  Go to https://central.sonatype.com/publishing/deployments to review and publish")
            }
        } else {
            error("✗ Failed to finalize deployment: HTTP ${response.statusCode()}\n${response.body()}")
        }
    }
}

tasks.register("publishAllToCentralPortal") {
    group = "publishing"
    description = "Publish all modules to the Central Portal (upload + finalize)"

    // Publish all subprojects that apply maven-publish
    dependsOn(subprojects.filter { it.plugins.hasPlugin("maven-publish") }.map { "${it.path}:publishAllPublicationsToCentralPortalRepository" })
    finalizedBy("finalizePortalDeployment")
}
