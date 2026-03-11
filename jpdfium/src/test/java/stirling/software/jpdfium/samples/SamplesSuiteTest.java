package stirling.software.jpdfium.samples;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * JUnit 5 test suite that runs every sample class as a parameterized test.
 *
 * <p>Each sample is invoked via its {@code main(String[])} method with no arguments
 * (uses classpath PDFs). The test asserts that no exception is thrown.
 *
 * <p>Run with: {@code ./gradlew :jpdfium:integrationTest}
 * (requires {@code -Djpdfium.integration=true} system property)
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class SamplesSuiteTest {

    @BeforeAll
    static void ensureNative() {
        SampleBase.ensureNative();
    }

    static Stream<Arguments> sampleClasses() {
        return Stream.of(
                Arguments.of("S01_Render", S01_Render.class),
                Arguments.of("S02_TextExtract", S02_TextExtract.class),
                Arguments.of("S03_TextSearch", S03_TextSearch.class),
                Arguments.of("S04_Metadata", S04_Metadata.class),
                Arguments.of("S05_Bookmarks", S05_Bookmarks.class),
                Arguments.of("S06_Redact", S06_RedactWords.class),
                Arguments.of("S07_Annotations", S07_Annotations.class),
                Arguments.of("S08_FullPipeline", S08_FullPipeline.class),
                Arguments.of("S09_Flatten", S09_Flatten.class),
                Arguments.of("S10_Signatures", S10_Signatures.class),
                Arguments.of("S11_Attachments", S11_Attachments.class),
                Arguments.of("S12_Links", S12_Links.class),
                Arguments.of("S13_PageImport", S13_PageImport.class),
                Arguments.of("S14_StructureTree", S14_StructureTree.class),
                Arguments.of("S15_Thumbnails", S15_Thumbnails.class),
                Arguments.of("S16_PageEditing", S16_PageEditing.class),
                Arguments.of("S17_NUpLayout", S17_NUpLayout.class),
                Arguments.of("S18_Repair", S18_Repair.class),
                Arguments.of("S19_PdfToImages", S19_PdfToImages.class),
                Arguments.of("S20_ImagesToPdf", S20_ImagesToPdf.class),
                Arguments.of("S21_Thumbnails", S21_Thumbnails.class),
                Arguments.of("S22_MergeSplit", S22_MergeSplit.class),
                Arguments.of("S23_Watermark", S23_Watermark.class),
                Arguments.of("S24_TableExtract", S24_TableExtract.class),
                Arguments.of("S25_PageGeometry", S25_PageGeometry.class),
                Arguments.of("S26_HeaderFooter", S26_HeaderFooter.class),
                Arguments.of("S27_Security", S27_Security.class),
                Arguments.of("S28_DocInfo", S28_DocInfo.class),
                Arguments.of("S29_RenderOptions", S29_RenderOptions.class),
                Arguments.of("S30_FormReader", S30_FormReader.class),
                Arguments.of("S31_ImageExtract", S31_ImageExtract.class),
                Arguments.of("S32_PageObjects", S32_PageObjects.class),
                Arguments.of("S33_Encryption", S33_Encryption.class),
                Arguments.of("S34_Linearizer", S34_Linearizer.class),
                Arguments.of("S35_Overlay", S35_Overlay.class),
                Arguments.of("S36_AnnotationBuilder", S36_AnnotationBuilder.class),
                Arguments.of("S37_PathDrawer", S37_PathDrawer.class),
                Arguments.of("S38_JavaScriptInspector", S38_JavaScriptInspector.class),
                Arguments.of("S39_WebLinks", S39_WebLinks.class),
                Arguments.of("S40_PageBoxes", S40_PageBoxes.class),
                Arguments.of("S41_VersionConverter", S41_VersionConverter.class),
                Arguments.of("S42_BoundedText", S42_BoundedText.class),
                Arguments.of("S43_StreamOptimizer", S43_StreamOptimizer.class),
                Arguments.of("S45_PageInterleaver", S45_PageInterleaver.class),
                Arguments.of("S46_NamedDestinations", S46_NamedDestinations.class),
                Arguments.of("S47_BlankPageDetector", S47_BlankPageDetector.class),
                Arguments.of("S48_EmbedPdfAnnotations", S48_EmbedPdfAnnotations.class),
                Arguments.of("S49_NativeEncryption", S49_NativeEncryption.class),
                Arguments.of("S50_NativeRedaction", S50_NativeRedaction.class),
                Arguments.of("S51_Compress", S51_Compress.class),
                Arguments.of("S52_BookmarkEditor", S52_BookmarkEditor.class),
                Arguments.of("S53_BarcodeGenerate", S53_BarcodeGenerate.class),
                Arguments.of("S54_PageReorder", S54_PageReorder.class),
                Arguments.of("S55_ColorConvert", S55_ColorConvert.class),
                Arguments.of("S56_Booklet", S56_Booklet.class),
                Arguments.of("S58_Analytics", S58_Analytics.class),
                Arguments.of("S59_FormFill", S59_FormFill.class),
                Arguments.of("S60_AutoCrop", S60_AutoCrop.class),
                Arguments.of("S61_SearchHighlight", S61_SearchHighlight.class),
                Arguments.of("S62_PageSplit2Up", S62_PageSplit2Up.class),
                Arguments.of("S63_PageLabels", S63_PageLabels.class),
                Arguments.of("S64_LinkValidation", S64_LinkValidation.class),
                Arguments.of("S65_Posterize", S65_Posterize.class),
                Arguments.of("S66_PdfDiff", S66_PdfDiff.class),
                Arguments.of("S67_AutoDeskew", S67_AutoDeskew.class),
                Arguments.of("S68_FontAudit", S68_FontAudit.class),
                Arguments.of("S69_PdfAConversion", S69_PdfAConversion.class),
                Arguments.of("S70_PageScaling", S70_PageScaling.class),
                Arguments.of("S71_MarginAdjust", S71_MarginAdjust.class),
                Arguments.of("S72_SelectiveFlatten", S72_SelectiveFlatten.class),
                Arguments.of("S73_AnnotExport", S73_AnnotExport.class),
                Arguments.of("S74_ImageReplace", S74_ImageReplace.class),
                Arguments.of("S75_LongImage", S75_LongImage.class),
                Arguments.of("S76_DuplicateDetect", S76_DuplicateDetect.class),
                Arguments.of("S77_ColumnExtract", S77_ColumnExtract.class),
                Arguments.of("S78_ImageDpi", S78_ImageDpi.class),
                Arguments.of("S79_PageMirror", S79_PageMirror.class),
                Arguments.of("S80_Background", S80_Background.class),
                Arguments.of("S81_ReadingOrder", S81_ReadingOrder.class),
                Arguments.of("S82_ResourceDedup", S82_ResourceDedup.class),
                Arguments.of("S83_TocGenerate", S83_TocGenerate.class),
                Arguments.of("S84_SelectiveRaster", S84_SelectiveRaster.class),
                Arguments.of("S85_AnnotStats", S85_AnnotStats.class),
                Arguments.of("S86_PosterizeSizes", S86_PosterizeSizes.class),
                Arguments.of("S87_AutoCropMargins", S87_AutoCropMargins.class)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleClasses")
    void sampleRunsWithoutException(String name, Class<?> sampleClass) throws Exception {
        Method main = sampleClass.getMethod("main", String[].class);
        assertDoesNotThrow(() -> {
            try {
                main.invoke(null, (Object) new String[0]);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }, name + " threw an exception");
    }
}
