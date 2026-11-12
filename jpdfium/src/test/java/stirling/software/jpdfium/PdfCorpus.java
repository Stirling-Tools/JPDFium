package stirling.software.jpdfium;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads and caches a large set of publicly available PDF files for corpus-level testing.
 * <p>
 * PDFs are cached under {@code build/test-corpus/} so the network is only hit on the first
 * run. Subsequent runs are fully offline. Tests that rely on this class should be tagged
 * {@code @Tag("corpus")} and guarded with {@code @EnabledIfSystemProperty} so they are skipped
 * in offline CI environments.
 * <p>
 * Sources: Mozilla pdf.js test suite (public domain test files) and pdfcpu samples
 * (Apache-2.0) - both stable, no authentication required.
 */
public final class PdfCorpus {

    /** Cache directory, relative to the Gradle project working directory. */
    public static final Path CACHE_DIR = Path.of("build/test-corpus");

    // Short timeout so tests fail fast on network unavailability rather than hanging.
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String PDFJS_BASE =
            "https://raw.githubusercontent.com/mozilla/pdf.js/master/test/pdfs/";

    private static final String PDFCPU_BASE =
            "https://raw.githubusercontent.com/pdfcpu/pdfcpu/master/pkg/samples/";

    private static final List<Entry> ENTRIES = List.of(
        entry("basicapi.pdf"),
        entry("tracemonkey.pdf"),
        entry("calrgb.pdf"),
        entry("vertical.pdf"),
        entry("160F-2019.pdf"),
        entry("S2.pdf"),
        entry("TAMReview.pdf"),
        entry("Pages-tree-refs.pdf"),
        entry("90ms_rksj_h_sample.pdf"),

        entry("ArabicCIDTrueType.pdf"),
        entry("XiaoBiaoSong.pdf"),
        entry("SimFang-variant.pdf"),
        entry("ThuluthFeatures.pdf"),
        entry("ZapfDingbats.pdf"),
        entry("TrueType_without_cmap.pdf"),
        entry("Embedded_font.pdf"),
        entry("font_ascent_descent.pdf"),
        entry("copy_paste_ligatures.pdf"),
        entry("Type3WordSpacing.pdf"),
        entry("ContentStreamCycleType3insideType3.pdf"),
        entry("ContentStreamNoCycleType3insideType3.pdf"),
        entry("IdentityToUnicodeMap_charCodeOf.pdf"),
        entry("IndexedCS_negative_and_high.pdf"),
        entry("ShowText-ShadingPattern.pdf"),
        entry("Test-plusminus.pdf"),

        entry("cmykjpeg.pdf"),
        entry("bug_jpx.pdf"),
        entry("image-rotated-black-white-ratio.pdf"),
        entry("images_1bit_grayscale.pdf"),

        entry("acroform_calculation_order.pdf"),
        entry("file_pdfjs_form.pdf"),
        entry("form_two_pages.pdf"),
        entry("bug1947248_forms.pdf"),
        entry("annotation-button-widget.pdf"),
        entry("annotation-choice-widget.pdf"),
        entry("annotation-text-widget.pdf"),

        entry("annotation-border-styles.pdf"),
        entry("annotation-caret-ink.pdf"),
        entry("annotation-fileattachment.pdf"),
        entry("annotation-freetext.pdf"),
        entry("annotation-highlight.pdf"),
        entry("annotation-highlight-without-appearance.pdf"),
        entry("annotation-ink-without-appearance.pdf"),
        entry("annotation-line.pdf"),
        entry("annotation-line-without-appearance.pdf"),
        entry("annotation-line-without-appearance-empty-Rect.pdf"),
        entry("annotation-link-text-popup.pdf"),
        entry("annotation-polyline-polygon.pdf"),
        entry("annotation-square-circle.pdf"),
        entry("annotation-squiggly.pdf"),
        entry("annotation-stamp.pdf"),
        entry("annotation-strikeout.pdf"),
        entry("annotation-text-without-popup.pdf"),
        entry("annotation-tx.pdf"),
        entry("annotation-tx2.pdf"),
        entry("annotation-tx3.pdf"),
        entry("annotation-underline.pdf"),
        entry("annotation_hidden_noview.pdf"),
        entry("annotation_hidden_print.pdf"),

        entry("GHOSTSCRIPT-698804-1-fuzzed.pdf"),
        entry("PDFBOX-3148-2-fuzzed.pdf"),
        entry("PDFBOX-4352-0.pdf"),
        entry("REDHAT-1531897-0.pdf"),

        entry("bug1001080.pdf"),
        entry("bug1011159.pdf"),
        entry("bug1019475_1.pdf"),
        entry("bug1019475_2.pdf"),
        entry("bug1020226.pdf"),
        entry("bug1020858.pdf"),
        entry("bug1027533.pdf"),
        entry("bug1028735.pdf"),
        entry("bug1046314.pdf"),
        entry("bug1050040.pdf"),
        entry("bug1057544.pdf"),
        entry("bug1065245.pdf"),
        entry("bug1068432.pdf"),
        entry("bug1108301.pdf"),
        entry("bug1132849.pdf"),
        entry("bug1146106.pdf"),
        entry("bug1151216.pdf"),
        entry("bug1157493.pdf"),
        entry("bug1175962.pdf"),
        entry("bug1186827.pdf"),
        entry("bug1200096.pdf"),
        entry("bug1245391_reduced.pdf"),
        entry("bug1250079.pdf"),
        entry("bug1252420.pdf"),
        entry("bug1308536.pdf"),
        entry("bug1337429.pdf"),
        entry("bug1365930.pdf"),
        entry("bug1392647.pdf"),
        entry("bug1473809.pdf"),
        entry("bug1513120_reduced.pdf"),
        entry("bug1851498.pdf"),
        entry("bug1865341.pdf"),
        entry("bug894572.pdf"),
        entry("bug946506.pdf"),

        entry("issue1002.pdf"),
        entry("issue10084_reduced.pdf"),
        entry("issue10301.pdf"),
        entry("issue10339_reduced.pdf"),
        entry("issue10388_reduced.pdf"),
        entry("issue10402.pdf"),
        entry("issue10438_reduced.pdf"),
        entry("issue1045.pdf"),
        entry("issue10519_reduced.pdf"),
        entry("issue10529.pdf"),
        entry("issue10542_reduced.pdf"),
        entry("issue1055r.pdf"),
        entry("issue10572.pdf"),
        entry("issue10640.pdf"),
        entry("issue10665_reduced.pdf"),
        entry("issue10900.pdf"),
        entry("issue11016_reduced.pdf"),
        entry("issue11045.pdf"),
        entry("issue11124.pdf"),
        entry("issue11131_reduced.pdf"),
        entry("issue11144_reduced.pdf"),
        entry("issue11150_reduced.pdf"),
        entry("issue11242_reduced.pdf"),
        entry("issue11279.pdf"),
        entry("issue11362.pdf"),
        entry("issue11403_reduced.pdf"),
        entry("issue11442_reduced.pdf"),
        entry("issue11473.pdf"),
        entry("issue11477_reduced.pdf"),
        entry("issue11549_reduced.pdf"),
        entry("issue11555.pdf"),
        entry("issue1155r.pdf"),
        entry("issue11578_reduced.pdf"),
        entry("issue11651.pdf"),
        entry("issue11656.pdf"),
        entry("issue11697_reduced.pdf"),
        entry("issue1171.pdf"),
        entry("issue11713.pdf"),
        entry("issue11718_reduced.pdf"),
        entry("issue11740_reduced.pdf"),
        entry("issue11768_reduced.pdf"),
        entry("issue11878_reduced.pdf"),
        entry("issue11913.pdf"),
        entry("issue11915.pdf"),
        entry("issue12007_reduced.pdf"),
        entry("issue12010_reduced.pdf"),
        entry("issue12120_reduced.pdf"),
        entry("issue12213.pdf"),
        entry("issue12295.pdf"),
        entry("issue12337.pdf"),
        entry("issue12399_reduced.pdf"),
        entry("issue12418_reduced.pdf"),
        entry("issue1249.pdf"),
        entry("issue12504.pdf"),
        entry("issue12705.pdf"),
        entry("issue12706.pdf"),
        entry("issue12750.pdf"),
        entry("issue12798_page1_reduced.pdf"),
        entry("issue12810.pdf"),
        entry("issue12823.pdf"),
        entry("issue1293r.pdf"),
        entry("issue12963.pdf"),
        entry("issue13003.pdf"),
        entry("issue13107_reduced.pdf"),
        entry("issue13147.pdf"),
        entry("issue13193.pdf"),
        entry("issue13201.pdf"),
        entry("issue13211.pdf"),
        entry("issue13226.pdf"),
        entry("issue13242.pdf"),
        entry("issue13269.pdf"),
        entry("issue13271.pdf"),
        entry("issue13316_reduced.pdf"),
        entry("issue13325_reduced.pdf"),
        entry("issue13343.pdf"),
        entry("issue13372.pdf"),
        entry("issue13447.pdf"),
        entry("issue1350.pdf"),
        entry("issue13520.pdf"),
        entry("issue13561_reduced.pdf"),
        entry("issue13916.pdf"),
        entry("issue13931.pdf"),
        entry("issue14023.pdf"),
        entry("issue16221.pdf"),
        entry("issue18117.pdf"),
        entry("issue2391-1.pdf"),
        entry("issue2840.pdf"),
        entry("issue4061.pdf"),
        entry("issue5334.pdf"),
        entry("issue6068.pdf"),
        entry("issue6108.pdf"),
        entry("issue6127.pdf"),
        entry("issue6961.pdf"),
        entry("issue7544.pdf"),
        entry("issue7665.pdf"),
        entry("issue925.pdf"),
        entry("issue9278.pdf"),

        entry("issue15590.pdf"),
        entry("bug1980958.pdf"),
        entry("extractPages_null_in_array.pdf"),
        entry("issue15150.pdf"),
        entry("issue21436.pdf"),
        entry("clippath.pdf"),
        entry("issue19176.pdf"),
        entry("issue4461.pdf"),
        entry("bug1606566.pdf"),
        entry("issue17554.pdf"),
        entry("bug2035197_2.pdf"),
        entry("bug850854.pdf"),
        entry("bug878026.pdf"),
        entry("checkbox_no_appearance.pdf"),
        entry("asciihexdecode.pdf"),
        entry("helloworld-bad.pdf"),
        entry("issue14755.pdf"),
        entry("issue4436r.pdf"),
        entry("bug2035197_1.pdf"),
        entry("bad-PageLabels.pdf"),
        entry("file_url_link.pdf"),
        entry("bug766086.pdf"),
        entry("issue4575.pdf"),
        entry("issue18986.pdf"),
        entry("issue4304.pdf"),
        entry("bug1552113.pdf"),
        entry("issue14802.pdf"),
        entry("issue20319_2.pdf"),
        entry("issue3879r.pdf"),
        entry("bitmap-template1-customat.pdf"),
        entry("bitmap-template1.pdf"),
        entry("bitmap-template2.pdf"),
        entry("bitmap-template1-customat-tpgdon.pdf"),
        entry("bitmap-randomaccess.pdf"),
        entry("bitmap-stripe-single-no-end-of-stripe.pdf"),
        entry("bitmap-initially-unknown-size.pdf"),
        entry("bitmap-template3.pdf"),
        entry("issue17848.pdf"),
        entry("bitmap-stripe-single.pdf"),
        entry("issue15594_reduced.pdf"),
        entry("bitmap-trailing-7fff-stripped.pdf"),
        entry("bitmap-customat.pdf"),
        entry("bitmap-template2-customat.pdf"),
        entry("bitmap-customat-tpgdon.pdf"),
        entry("bitmap-template2-customat-tpgdon.pdf"),
        entry("bitmap-template3-customat.pdf"),
        entry("bitmap-symbol-negative-sbdsoffset.pdf"),
        entry("bitmap-template3-customat-tpgdon.pdf"),
        entry("bitmap-symbol-textbottomlefttranspose.pdf"),
        entry("bitmap-symbol-textbottomrighttranspose.pdf"),
        entry("bitmap-symbol-texttoprighttranspose.pdf"),
        entry("bitmap-symbol-texttranspose.pdf"),
        entry("issue19800.pdf"),
        entry("bitmap-symbol-texttopright.pdf"),
        entry("bitmap-symbol.pdf"),
        entry("bitmap-refine-page-subrect.pdf"),
        entry("bitmap-symbol-textbottomleft.pdf"),
        entry("bitmap-symbol-textbottomright.pdf"),
        entry("bitmap-refine-template1.pdf"),
        entry("bitmap-mmr.pdf"),
        entry("bitmap-refine-page.pdf"),
        entry("bitmap-refine-lossless.pdf"),
        entry("bitmap-refine.pdf"),
        entry("bitmap-symbol-textrefine.pdf"),
        entry("bitmap-symbol-symbolrefineseveral.pdf"),
        entry("bitmap-symbol-textrefine-negative-delta-width.pdf"),
        entry("bitmap-symbol-textrefine-customat.pdf"),
        entry("bitmap-symbol-empty.pdf"),
        entry("issue17871_bottom_right.pdf"),
        entry("issue17871_top_right.pdf"),
        entry("issue15441.pdf"),
        entry("bitmap-refine-customat-tpgron.pdf"),
        entry("bitmap-refine-customat.pdf"),
        entry("bitmap-symbol-symbolrefineone-template1.pdf"),
        entry("bitmap-symbol-symbolrefineone.pdf"),
        entry("bitmap-symbol-symbolrefineone-customat.pdf"),
        entry("bitmap-symbol-big-segmentid.pdf"),
        entry("bitmap-template2-tpgdon.pdf"),
        entry("bitmap-symbol-refine.pdf"),
        entry("issue15443.pdf"),
        entry("bitmap-symbol-context-reuse.pdf"),
        entry("bitmap-template3-tpgdon.pdf"),
        entry("bitmap-refine-refine.pdf"),
        entry("bitmap-template1-tpgdon.pdf"),
        entry("bitmap-symbol-symhuff-texthuff.pdf"),
        entry("bitmap-symbol-symhuff-texthuffB10B13.pdf"),
        entry("bitmap-tpgdon.pdf"),
        entry("bitmap-symbol-symhuffB5B3-texthuffB7B9B12.pdf"),
        entry("bitmap-refine-template1-tpgron.pdf"),
        entry("bitmap-composite-and-xnor-text.pdf"),
        entry("bitmap-symbol-symhuffrefineseveral.pdf"),
        entry("issue18042.pdf"),
        entry("bitmap-refine-tpgron.pdf"),
        entry("bug864847.pdf"),
        entry("extgstate.pdf"),
        entry("bitmap-stripe-last-implicit.pdf"),
        entry("boundingBox_invalid.pdf"),
        entry("bitmap-halftone-template1.pdf"),
        entry("bitmap-symbol-texthuffrefine.pdf"),
        entry("bitmap-symbol-texthuffrefineB15.pdf"),
        entry("bitmap-stripe-initially-unknown-height.pdf"),
        entry("bitmap-stripe.pdf"),
        entry("bitmap-halftone-skip-dummy.pdf"),
        entry("bitmap-halftone.pdf"),
        entry("bitmap-symbol-texthuffrefinecustomsize.pdf"),
        entry("bitmap-symbol-symhuffrefineone.pdf"),
        entry("bitmap-halftone-template2.pdf"),
        entry("bitmap-halftone-template3.pdf"),
        entry("issue20439.pdf"),
        entry("bitmap-symbol-symbolrefine-textrefine.pdf"),
        entry("bitmap-halftone-10bpp.pdf"),
        entry("bitmap-composite-and-xnor.pdf"),
        entry("bitmap-symbol-texthuffrefinecustomdims.pdf"),
        entry("bitmap-symbol-texthuffrefinecustompos.pdf"),
        entry("bitmap-symbol-textcomposite.pdf"),
        entry("hello_world_rotated.pdf"),
        entry("bitmap-symbol-texthuffrefinecustomposdims.pdf"),
        entry("issue21579.pdf"),
        entry("bitmap-symbol-texthuffrefinecustom.pdf"),
        entry("bitmap-halftone-refine.pdf"),
        entry("issue4722.pdf"),
        entry("bitmap-symbol-symhuffcustom-texthuffcustom.pdf"),
        entry("bitmap-symbol-symhuffrefine-textrefine.pdf"),
        entry("gradientfill.pdf"),
        entry("close-path-bug.pdf"),
        entry("issue19695.pdf"),
        entry("issue18099_reduced.pdf"),
        entry("bitmap-p32-eof.pdf"),
        entry("issue2128r.pdf"),
        entry("bitmap-composite-and-xnor-halftone.pdf"),
        entry("bitmap-composite-or-xor-replace-text.pdf"),
        entry("bitmap-halftone-skip-grid-template2.pdf"),
        entry("bitmap-halftone-composite.pdf"),
        entry("bitmap-halftone-skip-grid-template3.pdf"),
        entry("bitmap-halftone-skip-grid-template1.pdf"),
        entry("issue15893_reduced.pdf"),
        entry("bitmap-halftone-grid.pdf"),
        entry("bitmap-halftone-skip-grid.pdf"),
        entry("bitmap-composite-or-xor-replace.pdf"),
        entry("issue15977_reduced.pdf"),
        entry("issue17904.pdf"),
        entry("bitmap-halftone-10bpp-mmr.pdf"),
        entry("issue16038.pdf"),
        entry("bug847420.pdf"),
        entry("bitmap-composite-or-xor-replace-halftone.pdf"),
        entry("bug866395.pdf"),
        entry("bitmap-trailing-7fff-stripped-harder-refine.pdf"),
        entry("bitmap-composite-and-xnor-refine.pdf"),
        entry("bug1671312_ArialNarrow.pdf"),
        entry("bug1538111.pdf"),
        entry("issue4260_reduced.pdf"),
        entry("issue15716.pdf"),
        entry("bug852992_reduced.pdf"),
        entry("issue17679.pdf"),
        entry("issue17679_2.pdf"),
        entry("bitmap-composite-or-xor-replace-refine.pdf"),
        entry("issue19474.pdf"),
        entry("issue16742.pdf"),
        entry("issue18894.pdf"),
        entry("colors.pdf"),
        entry("encrypted-attachment.pdf"),
        entry("auth-event-ef-open.pdf"),
        entry("issue2391-2.pdf"),
        entry("franz.pdf"),
        entry("franz_2.pdf"),
        entry("issue2099-1.pdf"),
        entry("decodeACSuccessive.pdf"),
        entry("issue17064_readonly.pdf"),
        entry("issue2177.pdf"),
        entry("ccitt_EndOfBlock_false.pdf"),
        entry("function_based_shading_cmyk.pdf"),
        entry("issue18548_reduced.pdf"),
        entry("coons-allflags-withfunction.pdf"),
        entry("issue18816.pdf"),
        entry("issue17065.pdf"),
        entry("issue4684.pdf"),
        entry("issue16067.pdf"),
        entry("function_based_shading.pdf"),
        entry("issue18529.pdf"),
        entry("issue4379.pdf"),
        entry("issue14267.pdf"),
        entry("issue3521.pdf"),
        entry("issue16278.pdf"),
        entry("empty#hash.pdf"),
        entry("empty.pdf"),
        entry("issue15789.pdf"),
        entry("issue19207.pdf"),
        entry("issue21346.pdf"),
        entry("issue17333.pdf"),
        entry("issue21570.pdf"),
        entry("glyph_accent.pdf"),
        entry("issue20062.pdf"),
        entry("bug1871353.pdf"),
        entry("empty_protected.pdf"),
        entry("issue2833.pdf"),
        entry("bug1671312_reduced.pdf"),
        entry("issue14953.pdf"),
        entry("issue16021.pdf"),
        entry("bug1782186.pdf"),
        entry("bug1539074.1.pdf"),
        entry("bug1539074.pdf"),
        entry("bitmap-symbol-symhuffuncompressed-texthuff.pdf"),
        entry("bug1963407.pdf"),
        entry("bug1669097.pdf"),
        entry("bug1889122.pdf"),
        entry("issue19505.pdf"),
        entry("issue16473.pdf"),
        entry("bug2026037.pdf"),
        entry("issue11931.pdf"),
        entry("issue19083.pdf"),
        entry("freetext_no_appearance.pdf"),
        entry("bug1863910.pdf"),
        entry("issue19848.pdf"),
        entry("bug1811694.pdf"),
        entry("issue14048.pdf"),
        entry("issue19389.pdf"),
        entry("bug1825002.pdf"),
        entry("issue3885.pdf"),
        entry("issue18561.pdf"),
        entry("bug1934157.pdf"),
        entry("bug1796741.pdf"),
        entry("issue4246.pdf"),
        entry("dates.pdf"),
        entry("bug1770750.pdf"),
        entry("issue18072.pdf"),
        entry("issue14502.pdf"),
        entry("issue15818.pdf"),
        entry("issue20489.pdf"),
        entry("issue17929.pdf"),
        entry("issue15815.pdf"),
        entry("issue3025.pdf"),
        entry("inks_basic.pdf"),
        entry("issue18694.pdf"),
        entry("bug1802888.pdf"),
        entry("issue21068.pdf"),
        entry("fields_order.pdf"),
        entry("bug1782564.pdf"),
        entry("issue14705.pdf"),
        entry("issue17998.pdf"),
        entry("issue14999_reduced.pdf"),
        entry("issue15597.pdf"),
        entry("issue18305.pdf"),
        entry("bug1844576.pdf"),
        entry("issue18536.pdf"),
        entry("bug903856.pdf"),
        entry("issue4573.pdf"),
        entry("issue20516.pdf"),
        entry("doc_actions.pdf"),
        entry("dates_save.pdf"),
        entry("issue19424.pdf"),
        entry("autoprint.pdf"),
        entry("bug1844583.pdf"),
        entry("issue18956.pdf"),
        entry("issue20225.pdf"),
        entry("bug1802506.pdf"),
        entry("issue18693.pdf"),
        entry("cff_bluescale_small_zones.pdf"),
        entry("issue18408_reduced.pdf"),
        entry("issue3566.pdf"),
        entry("issue1985.pdf"),
        entry("issue18360.pdf"),
        entry("bug1872721.pdf"),
        entry("issue19532.pdf"),
        entry("extract_link.pdf"),
        entry("find_all.pdf"),
        entry("french_diacritics.pdf"),
        entry("issue4706.pdf"),
        entry("bug1942064.pdf"),
        entry("issue14307.pdf"),
        entry("bug1989304.pdf"),
        entry("issue3214.pdf"),
        entry("issue20324.pdf"),
        entry("bug1919513.pdf"),
        entry("issue15753.pdf"),
        entry("bug1871353.1.pdf"),
        entry("issue15557.pdf"),
        entry("bug1811668_reduced.pdf"),
        entry("doc_1_3_pages.pdf"),
        entry("issue2761.pdf"),
        entry("highlight_popup.pdf"),
        entry("issue3371.pdf"),
        entry("highlight.pdf"),
        entry("issue18030.pdf"),
        entry("bug1724918.pdf"),
        entry("arial_unicode_ab_cidfont.pdf"),
        entry("issue17056.pdf"),
        entry("bug1918115.pdf"),
        entry("bug1922766.pdf"),
        entry("issue14862.pdf"),
        entry("PDFJS-7562-reduced.pdf"),
        entry("bug1799927.pdf"),
        entry("issue20065.pdf"),
        entry("issue20102.pdf"),
        entry("issue15340.pdf"),
        entry("issue4398.pdf"),
        entry("bug1947248_text.pdf"),
        entry("german-umlaut-r.pdf"),
        entry("issue16224.pdf"),
        entry("issue15262.pdf"),
        entry("bug1675139.pdf"),
        entry("issue15759.pdf"),
        entry("issue17846.pdf"),
        entry("issue18059.pdf"),
        entry("calgray.pdf"),
        entry("issue14256.pdf"),
        entry("bitmap-trailing-7fff-stripped-harder.pdf"),
        entry("attachment.pdf"),
        entry("annotation-underline-without-appearance.pdf"),
        entry("issue21126.pdf"),
        entry("bug1907000_reduced.pdf"),
        entry("issue19550.pdf"),
        entry("annotation-squiggly-without-appearance.pdf"),
        entry("devicen.pdf"),
        entry("annotation-strikeout-without-appearance.pdf"),
        entry("arial_unicode_en_cidfont.pdf"),
        entry("issue14462_reduced.pdf"),
        entry("bug898853.pdf"),
        entry("annotation-square-circle-without-appearance.pdf"),
        entry("issue17215.pdf"),
        entry("issue14565.pdf"),
        entry("alphatrans.pdf"),
        entry("issue4550.pdf"),
        entry("issue15092.pdf"),
        entry("issue3061.pdf"),
        entry("issue1453.pdf"),
        entry("issue2884_reduced.pdf"),
        entry("issue15690.pdf"),
        entry("endchar.pdf"),
        entry("issue20504_skia.pdf"),
        entry("issue14165.pdf"),
        entry("checkbox-bad-appearance.pdf"),
        entry("cid_cff.pdf"),
        entry("issue17671.pdf"),
        entry("bug816075.pdf"),
        entry("issue3584.pdf"),
        entry("issue16500.pdf"),
        entry("issue20453.pdf"),
        entry("issue4402_reduced.pdf"),
        entry("issue18645.pdf"),
        entry("issue2948.pdf"),
        entry("bug793632.pdf"),
        entry("bug868745.pdf"),
        entry("bug1811510.pdf"),
        entry("issue3694_reduced.pdf"),
        entry("issue2931.pdf"),
        entry("bug2025674.pdf"),
        entry("issue18823.pdf"),
        entry("issue4650.pdf"),
        entry("issue19120.pdf"),
        entry("issue2537r.pdf"),
        entry("bug893730.pdf"),
        entry("issue1512r.pdf"),
        entry("issue3928.pdf"),
        entry("issue3405r.pdf"),
        entry("issue3263r.pdf"),
        entry("issue20504.pdf"),
        entry("issue4668.pdf"),
        entry("bug860632.pdf"),
        entry("bug2014080.pdf"),
        entry("issue14497.pdf"),
        entry("issue1655r.pdf"),
        entry("fraction-highlight.pdf"),
        entry("issue2017r.pdf"),
        entry("issue15516_reduced.pdf"),
        entry("issue16633.pdf"),
        entry("bug1873345.pdf"),
        entry("issue19326.pdf"),
        entry("bug1708041.pdf"),
        entry("issue20232.pdf"),
        entry("bug1650302_reduced.pdf"),
        entry("issue3458.pdf"),
        entry("issue2074.pdf"),
        entry("doc_2_3_pages.pdf"),
        entry("issue17540.pdf"),
        entry("bug1937438_af_from_latex.pdf"),
        entry("issue3323.pdf"),
        entry("doc_3_3_pages.pdf"),
        entry("bug2009627.pdf"),
        entry("issue15367.pdf"),
        entry("issue4665.pdf"),
        entry("issue19802.pdf"),
        entry("bug859204.pdf"),
        entry("bug1529502.pdf"),
        entry("bug1937438_mml_from_latex.pdf"),
        entry("freetexts.pdf"),
        entry("bug921409.pdf"),
        entry("issue17147.pdf"),
        entry("bug1937438_from_word.pdf"),
        entry("issue19484_2.pdf"),
        entry("issue19484_1.pdf"),
        entry("bug1820909.1.pdf"),
        entry("firefox_stamp.pdf"),
        entry("bug1974436.pdf"),
        entry("issue20319_1.pdf"),
        entry("bug1953099.pdf"),
        entry("issue19634.pdf"),
        entry("blendmode.pdf"),
        entry("issue3207r.pdf"),
        entry("issue14415.pdf"),
        entry("issue269_1.pdf"),
        entry("evaljs.pdf"),
        entry("bug911034.pdf"),
        entry("issue2176.pdf"),
        entry("complex_ttf_font.pdf"),
        entry("issue16263.pdf"),
        entry("issue16063.pdf"),
        entry("bug2004951.pdf"),
        entry("issue16538.pdf"),
        entry("issue14627.pdf"),
        entry("issue16176.pdf"),
        entry("issue18036.pdf"),
        entry("issue3438.pdf"),
        entry("issue17492.pdf"),
        entry("bug1708040.pdf"),
        entry("bug1771477.pdf"),
        entry("bug2013793.pdf"),
        entry("issue4630.pdf"),
        entry("bug1868759.pdf"),
        entry("bigboundingbox.pdf"),
        entry("issue16287.pdf"),
        entry("issue14046.pdf"),
        entry("issue14130.pdf"),
        entry("bug1883609.pdf"),
        entry("issue14117.pdf"),
        entry("issue15910.pdf"),
        entry("issue215.pdf"),
        entry("issue14847.pdf"),
        entry("annotation-polyline-polygon-without-appearance.pdf"),
        entry("cidfont_cmap_overflow.pdf"),
        entry("issue14881.pdf"),
        entry("issue17730.pdf"),
        entry("issue18032.pdf"),
        entry("issue2956.pdf"),
        entry("issue16091.pdf"),
        entry("bug854315.pdf"),
        entry("issue15012.pdf"),
        entry("issue15053.pdf"),
        entry("inks.pdf"),
        entry("issue15372.pdf"),
        entry("issue16316.pdf"),
        entry("bug1885505.pdf"),
        entry("bomb_giant.pdf"),
        entry("issue3205r.pdf"),
        entry("issue14438.pdf"),
        entry("bug886717.pdf"),
        entry("bug1815476.pdf"),
        entry("chrome-text-selection-markedContent.pdf"),
        entry("issue2462.pdf"),
        entry("colorspace_cos.pdf"),
        entry("colorspace_sin.pdf"),
        entry("colorspace_atan.pdf"),
        entry("issue20930.pdf"),
        entry("file_pdfjs_test.pdf"),
        entry("canvas.pdf"),
        entry("issue15629.pdf"),
        entry("issue14618.pdf"),
        entry("colorkeymask.pdf"),
        entry("bug900822.pdf"),
        entry("issue19182.pdf"),
        entry("bug1669099.pdf"),
        entry("bug1997343.pdf"),
        entry("issue15096.pdf"),
        entry("firefox_logo.pdf"),
        entry("issue14297.pdf"),
        entry("bug1627427_reduced.pdf"),
        entry("issue269_2.pdf"),
        entry("bug1743245.pdf"),
        entry("issue14821.pdf"),
        entry("issue19633.pdf"),
        entry("bug1703683_page2_reduced.pdf"),
        entry("bug1795263.pdf"),
        entry("bug920426.pdf"),
        entry("filled-background.pdf"),
        entry("issue14814.pdf"),
        entry("bug1727053.pdf"),
        entry("bug1755507.pdf"),
        entry("issue14200.pdf"),
        entry("issue17069.pdf"),
        entry("issue16553.pdf"),
        entry("bug1721218_reduced.pdf"),
        entry("issue1905.pdf"),
        entry("highlights.pdf"),
        entry("issue17808.pdf"),
        entry("issue11922_reduced.pdf"),
        entry("bug1992868.pdf"),
        entry("issue18911.pdf"),
        entry("issue19239.pdf"),
        entry("comments.pdf"),
        entry("issue20513.pdf"),
        entry("issue19360.pdf"),
        entry("Brotli-Prototype-FileA.pdf"),
        entry("images.pdf"),
        entry("bug1978317.pdf"),
        entry("issue19971.pdf"),
        entry("freeculture.pdf"),
        entry("PDFJS-9279-reduced.pdf"),
        entry("issue12841_reduced.pdf"),
        entry("issue19517.pdf"),
        entry("22060_A1_01_Plans.pdf"),
        entry("issue3188.pdf"),

        pdfcpu("annotations/Annotations.pdf"),
        pdfcpu("annotations/CaretAnnotation.pdf"),
        pdfcpu("annotations/FreeTextAnnotation.pdf"),
        pdfcpu("annotations/HighlightAnnotation.pdf"),
        pdfcpu("annotations/InkAnnotation.pdf"),
        pdfcpu("annotations/LineAnnotation.pdf"),
        pdfcpu("annotations/LinkAnnotWithDestTopLeft.pdf"),
        pdfcpu("annotations/PolyLineAnnotation.pdf"),
        pdfcpu("annotations/PolygonAnnotation.pdf"),
        pdfcpu("annotations/PopupAnnotation.pdf"),
        pdfcpu("annotations/SquigglyAnnotation.pdf"),
        pdfcpu("annotations/StrikeOutAnnotation.pdf"),
        pdfcpu("annotations/UnderlineAnnotation.pdf"),

        pdfcpu("basic/UserFont_Arabic.pdf"),
        pdfcpu("basic/UserFont_CJKV.pdf"),
        pdfcpu("basic/UserFont_Hebrew.pdf"),
        pdfcpu("basic/UserFont_HumanRights.pdf"),
        pdfcpu("basic/UserFont_Justified.pdf"),
        pdfcpu("basic/UserFont_JustifiedRightToLeft.pdf"),
        pdfcpu("basic/UserFont_Persian.pdf"),
        pdfcpu("basic/UserFont_Urdu.pdf"),
        pdfcpu("basic/TextRotateDemo.pdf"),
        pdfcpu("basic/ResourceDictInheritanceDemo.pdf"),
        pdfcpu("basic/TestTextAlignJustifyDemo.pdf")
    );

    private PdfCorpus() {}

    /** A single corpus entry pairing a local cache name with its download URL. */
    public record Entry(String name, String url) {}

    /** Convenience factory: builds an Entry from just the filename, using the standard base URL. */
    private static Entry entry(String filename) {
        // '#' in a filename (e.g. empty#hash.pdf) is a URL fragment separator -
        // it must be percent-encoded for the raw download to work.
        return new Entry(filename, PDFJS_BASE + filename.replace("#", "%23"));
    }

    /** Builds an Entry from a pdfcpu repository-relative path. */
    private static Entry pdfcpu(String repoPath) {
        return new Entry(repoPath.replace('/', '_'), PDFCPU_BASE + repoPath);
    }

    /** Returns the full list of corpus entries (for inspection or selective download). */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /**
     * Download all corpus PDFs, skipping entries that are already cached.
     *
     * @return paths to all available corpus PDFs (cached or freshly downloaded)
     * @throws IOException          if the cache directory cannot be created or a download fails
     * @throws InterruptedException if the thread is interrupted during a download
     */
    public static List<Path> download() throws IOException, InterruptedException {
        Files.createDirectories(CACHE_DIR);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        List<Path> result = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (Entry entry : ENTRIES) {
            Path dest = CACHE_DIR.resolve(entry.name());

            if (Files.exists(dest) && Files.size(dest) > 0) {
                result.add(dest);
                continue;
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(entry.url()))
                    .timeout(TIMEOUT)
                    .build();

            try {
                HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(dest));
                if (resp.statusCode() != 200) {
                    Files.deleteIfExists(dest);
                    failures.add(entry.name() + " (HTTP " + resp.statusCode() + ")");
                    continue;
                }
                result.add(dest);
            } catch (IOException e) {
                Files.deleteIfExists(dest);
                failures.add(entry.name() + " (" + e.getMessage() + ")");
            }
        }

        if (!failures.isEmpty()) {
            System.err.println("[PdfCorpus] WARNING: failed to download " + failures.size()
                    + " PDFs: " + failures);
        }

        return result;
    }

    /**
     * Return all already-cached corpus PDFs without attempting any network access.
     * Useful for asserting corpus coverage in offline-only CI.
     */
    public static List<Path> cached() throws IOException {
        if (!Files.exists(CACHE_DIR)) return List.of();
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(CACHE_DIR)) {
            stream.filter(p -> p.toString().endsWith(".pdf"))
                  .sorted()
                  .forEach(result::add);
        }
        return result;
    }

    /**
     * Programmatic analysis of the corpus: opens each PDF and prints page count, file size,
     * and basic stats. Useful for verifying corpus health after download.
     *
     * @param pdfs list of PDF paths to analyze
     */
    public static void analyze(List<Path> pdfs) {        System.out.printf("%n=== PdfCorpus Analysis: %d PDFs ===%n", pdfs.size());
        long totalBytes = 0;
        int totalPages = 0;

        for (Path pdf : pdfs) {
            try {
                long size = Files.size(pdf);
                totalBytes += size;
                try (PdfDocument doc = PdfDocument.open(pdf)) {
                    int pages = doc.pageCount();
                    totalPages += pages;
                    System.out.printf("  %-40s  %6d bytes  %3d page(s)%n",
                            pdf.getFileName(), size, pages);
                }
            } catch (Exception e) {
                System.out.printf("  %-40s  ERROR: %s%n", pdf.getFileName(), e.getMessage());
            }
        }

        System.out.printf("  --- TOTALS: %d PDFs, %d pages, %.1f KB ---%n%n",
                pdfs.size(), totalPages, totalBytes / 1024.0);
    }

    /**
     * CLI: downloads (or refreshes) the whole corpus and prints a summary.
     * Used by CI corpus jobs to prime the shared corpus cache before the
     * sharded test jobs run. Deliberately native-free: the corpus CI job
     * primes the cache before the native bridge is built.
     */
    public static void main(String[] args) throws Exception {
        List<Path> pdfs = download();
        long totalBytes = 0;
        for (Path p : pdfs) {
            totalBytes += Files.size(p);
        }
        System.out.printf("[PdfCorpus] primed %d PDFs, %.1f MB%n",
                pdfs.size(), totalBytes / 1024.0 / 1024.0);
    }
}
