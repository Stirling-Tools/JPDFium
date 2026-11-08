// Generates the compact, generic CMYK output profile bundled with JPDFium.
// A2B uses a 5-point CMYK grid for size; B2A and gamut use an 11-point Lab
// grid so RGB-to-CMYK conversion is accurate.
// Build:
//   cc -O2 native/tools/gen_compact_icc.c $(pkg-config --cflags --libs lcms2) -lm \
//       -o /tmp/gen_compact_icc
//   /tmp/gen_compact_icc jpdfium/src/main/resources/icc/JPDFiumCompactCmyk.icc
// This is a fallback profile, not a certified printing-condition profile.
#include <lcms2.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

static void cmyk_to_rgb(int c, int m, int y, int k, float* rgb) {
    rgb[0] = (255 - c) * (255 - k) / 65025.0f;
    rgb[1] = (255 - m) * (255 - k) / 65025.0f;
    rgb[2] = (255 - y) * (255 - k) / 65025.0f;
}

static void rgb_to_cmyk(float r, float g, float b, int* out) {
    int c = (int)lrintf((1.0f - r) * 255.0f);
    int m = (int)lrintf((1.0f - g) * 255.0f);
    int y = (int)lrintf((1.0f - b) * 255.0f);
    if (c < 0) c = 0;
    if (m < 0) m = 0;
    if (y < 0) y = 0;
    if (c > 255) c = 255;
    if (m > 255) m = 255;
    if (y > 255) y = 255;
    // Invert cmyk_to_rgb exactly. Removing black without rescaling the
    // chromatic channels made the BToA table disagree with AToB and shifted
    // light colours; this GCR keeps the two tables consistent.
    int k = c < m ? (c < y ? c : y) : (m < y ? m : y);
    if (k > 254) k = 254;
    float inv = 255.0f - (float)k;
    out[0] = (int)lrintf(((float)c - (float)k) * 255.0f / inv);
    out[1] = (int)lrintf(((float)m - (float)k) * 255.0f / inv);
    out[2] = (int)lrintf(((float)y - (float)k) * 255.0f / inv);
    out[3] = k;
}

static uint16_t lab16(float v, float lo, float hi) {
    float t = (v - lo) / (hi - lo);
    if (t < 0) t = 0;
    if (t > 1) t = 1;
    return (uint16_t)lrintf(t * 65535.0f);
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    const int GA = 5;
    const int GB = 11;
    const int grid = GA * GA * GA * GA;
    const int lab_grid = GB * GB * GB;
    cmsUInt16Number* a2b = calloc((size_t)grid * 3, sizeof(cmsUInt16Number));
    cmsUInt16Number* b2a = calloc((size_t)lab_grid * 4, sizeof(cmsUInt16Number));
    cmsUInt16Number* gamt = calloc((size_t)lab_grid, sizeof(cmsUInt16Number));
    if (!a2b || !b2a || !gamt) return 3;

    cmsHPROFILE srgb = cmsCreate_sRGBProfile();
    cmsHPROFILE lab = cmsCreateLab4Profile(NULL);
    cmsHTRANSFORM rgb_to_lab =
        cmsCreateTransform(srgb, TYPE_RGB_FLT, lab, TYPE_Lab_FLT, INTENT_RELATIVE_COLORIMETRIC, 0);
    cmsHTRANSFORM lab_to_rgb =
        cmsCreateTransform(lab, TYPE_Lab_FLT, srgb, TYPE_RGB_FLT, INTENT_RELATIVE_COLORIMETRIC, 0);
    if (!srgb || !lab || !rgb_to_lab || !lab_to_rgb) return 4;

    for (int c = 0; c < GA; c++) {
        for (int m = 0; m < GA; m++) {
            for (int y = 0; y < GA; y++) {
                for (int k = 0; k < GA; k++) {
                    float rgb[3], labv[3];
                    cmyk_to_rgb(c * 255 / (GA - 1), m * 255 / (GA - 1), y * 255 / (GA - 1),
                                k * 255 / (GA - 1), rgb);
                    cmsDoTransform(rgb_to_lab, rgb, labv, 1);
                    int o = (((c * GA + m) * GA + y) * GA + k) * 3;
                    a2b[o] = lab16(labv[0], 0, 100);
                    a2b[o + 1] = lab16(labv[1], -128, 127);
                    a2b[o + 2] = lab16(labv[2], -128, 127);
                }
            }
        }
    }
    for (int l = 0; l < GB; l++) {
        for (int a = 0; a < GB; a++) {
            for (int b = 0; b < GB; b++) {
                float labv[3] = {l * 100.0f / (GB - 1), a * 255.0f / (GB - 1) - 128,
                                 b * 255.0f / (GB - 1) - 128};
                float rgb[3];
                int cmyk[4];
                cmsDoTransform(lab_to_rgb, labv, rgb, 1);
                rgb_to_cmyk(rgb[0], rgb[1], rgb[2], cmyk);
                int o = ((l * GB + a) * GB + b) * 4;
                for (int i = 0; i < 4; i++) b2a[o + i] = (cmsUInt16Number)(cmyk[i] * 257);
            }
        }
    }

    cmsHPROFILE h = cmsCreateProfilePlaceholder(NULL);
    if (!h) return 5;
    cmsSetProfileVersion(h, 2.1);
    cmsSetDeviceClass(h, cmsSigOutputClass);
    cmsSetColorSpace(h, cmsSigCmykData);
    cmsSetPCS(h, cmsSigLabData);
    cmsSetHeaderRenderingIntent(h, INTENT_PERCEPTUAL);

    cmsPipeline* a = cmsPipelineAlloc(NULL, 4, 3);
    cmsPipeline* a1 = cmsPipelineAlloc(NULL, 4, 3);
    cmsPipeline* b = cmsPipelineAlloc(NULL, 3, 4);
    cmsPipeline* b1 = cmsPipelineAlloc(NULL, 3, 4);
    cmsPipeline* g = cmsPipelineAlloc(NULL, 3, 1);
    cmsStage* as = cmsStageAllocCLut16bit(NULL, GA, 4, 3, a2b);
    cmsStage* as1 = cmsStageAllocCLut16bit(NULL, GA, 4, 3, a2b);
    cmsStage* bs = cmsStageAllocCLut16bit(NULL, GB, 3, 4, b2a);
    cmsStage* bs1 = cmsStageAllocCLut16bit(NULL, GB, 3, 4, b2a);
    cmsStage* gs = cmsStageAllocCLut16bit(NULL, GB, 3, 1, gamt);
    cmsPipelineInsertStage(a, cmsAT_BEGIN, as);
    cmsPipelineInsertStage(a1, cmsAT_BEGIN, as1);
    cmsPipelineInsertStage(b, cmsAT_BEGIN, bs);
    cmsPipelineInsertStage(b1, cmsAT_BEGIN, bs1);
    cmsPipelineInsertStage(g, cmsAT_BEGIN, gs);
    cmsWriteTag(h, cmsSigAToB0Tag, a);
    cmsWriteTag(h, cmsSigAToB1Tag, a1);
    cmsWriteTag(h, cmsSigBToA0Tag, b);
    cmsWriteTag(h, cmsSigBToA1Tag, b1);
    cmsWriteTag(h, cmsSigGamutTag, g);
    cmsWriteTag(h, cmsSigMediaWhitePointTag, cmsD50_XYZ());

    cmsMLU* desc = cmsMLUalloc(NULL, 1);
    cmsMLU* cprt = cmsMLUalloc(NULL, 1);
    cmsMLUsetASCII(desc, "en", "US", "JPDFium Compact CMYK");
    cmsMLUsetASCII(cprt, "en", "US", "Public domain compact CMYK fallback profile");
    cmsWriteTag(h, cmsSigProfileDescriptionTag, desc);
    cmsWriteTag(h, cmsSigCopyrightTag, cprt);

    if (!cmsSaveProfileToFile(h, argv[1])) return 6;
    cmsMLUfree(desc);
    cmsMLUfree(cprt);
    cmsPipelineFree(a);
    cmsPipelineFree(a1);
    cmsPipelineFree(b);
    cmsPipelineFree(b1);
    cmsPipelineFree(g);
    cmsCloseProfile(h);
    cmsDeleteTransform(rgb_to_lab);
    cmsDeleteTransform(lab_to_rgb);
    cmsCloseProfile(srgb);
    cmsCloseProfile(lab);
    free(a2b);
    free(b2a);
    free(gamt);
    return 0;
}
