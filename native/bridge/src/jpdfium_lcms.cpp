// jpdfium_lcms.cpp - ICC color profile validation and replacement via lcms2.
//
// Opt-in: requires JPDFIUM_HAS_LCMS2 at build time.
// Validates /ICCBased profile streams and generates standard replacements.

#include "jpdfium.h"
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <sstream>
#include <string>

#ifdef JPDFIUM_HAS_LCMS2

#include <lcms2.h>

// Suppress lcms2 stderr noise
static void lcms_error_handler(cmsContext, cmsUInt32Number, const char*) {}

static std::string json_escape_lcms(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (char c : s) {
        if (c == '"' || c == '\\') out += '\\';
        if (c == '\n') { out += "\\n"; continue; }
        if (c == '\r') continue;
        out += c;
    }
    return out;
}

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_validate_icc_profile(
    const uint8_t* profileData, int64_t profileLen,
    int32_t expectedComponents,
    char** resultJson) {

    if (!profileData || profileLen <= 0 || !resultJson)
        return JPDFIUM_ERR_INVALID;

    cmsSetLogErrorHandler(lcms_error_handler);

    cmsHPROFILE hProfile = cmsOpenProfileFromMem(
        profileData, static_cast<cmsUInt32Number>(profileLen));

    if (!hProfile) {
        *resultJson = strdup("{\"status\":\"corrupt\",\"colorspace\":\"unknown\","
                             "\"components\":0,\"expected_components\":0,"
                             "\"description\":\"Failed to parse ICC profile\"}");
        return -1;
    }

    cmsColorSpaceSignature cs = cmsGetColorSpace(hProfile);
    int actualComponents = static_cast<int>(cmsChannelsOfColorSpace(cs));

    const char* csName = "Other";
    switch (cs) {
        case cmsSigRgbData:  csName = "RGB";  break;
        case cmsSigCmykData: csName = "CMYK"; break;
        case cmsSigGrayData: csName = "Gray"; break;
        case cmsSigLabData:  csName = "Lab";  break;
        default: break;
    }

    char desc[256] = {0};
    cmsGetProfileInfoASCII(hProfile, cmsInfoDescription,
        "en", "US", desc, sizeof(desc));

    int status = 0;
    const char* statusStr = "valid";

    // Check /N mismatch
    if (actualComponents != expectedComponents) {
        status = 1;
        statusStr = "fixable";
    }

    // Create a test transform to verify the profile is functional
    if (status == 0) {
        cmsHPROFILE hSRGB = cmsCreate_sRGBProfile();
        cmsUInt32Number inFmt =
            (actualComponents == 3) ? TYPE_RGB_8 :
            (actualComponents == 4) ? TYPE_CMYK_8 :
            (actualComponents == 1) ? TYPE_GRAY_8 : TYPE_RGB_8;

        cmsHTRANSFORM hTransform = cmsCreateTransform(
            hProfile, inFmt, hSRGB, TYPE_RGB_8,
            INTENT_PERCEPTUAL, cmsFLAGS_NOOPTIMIZE);

        if (!hTransform) {
            status = -1;
            statusStr = "corrupt";
            snprintf(desc, sizeof(desc), "Profile parses but transform creation fails");
        } else {
            cmsDeleteTransform(hTransform);
        }
        cmsCloseProfile(hSRGB);
    }

    cmsCloseProfile(hProfile);

    std::ostringstream os;
    os << "{\"status\":\"" << statusStr << "\""
       << ",\"colorspace\":\"" << csName << "\""
       << ",\"components\":" << actualComponents
       << ",\"expected_components\":" << expectedComponents
       << ",\"description\":\"" << json_escape_lcms(desc) << "\"}";

    *resultJson = strdup(os.str().c_str());
    return status;
}

JPDFIUM_EXPORT int32_t jpdfium_generate_replacement_icc(
    int32_t numComponents,
    uint8_t** profileOutput, int64_t* profileLen) {

    if (!profileOutput || !profileLen) return JPDFIUM_ERR_INVALID;

    cmsSetLogErrorHandler(lcms_error_handler);
    cmsHPROFILE hProfile = nullptr;

    switch (numComponents) {
        case 1: {
            cmsToneCurve* gamma22 = cmsBuildGamma(nullptr, 2.2);
            hProfile = cmsCreateGrayProfile(cmsD50_xyY(), gamma22);
            cmsFreeToneCurve(gamma22);
            break;
        }
        case 3:
            hProfile = cmsCreate_sRGBProfile();
            break;
        case 4:
            // Minimal synthetic synthetic Lab-CMYK profile as placeholder
            hProfile = cmsCreateLab4Profile(nullptr);
            break;
        default:
            return JPDFIUM_ERR_INVALID;
    }

    if (!hProfile) return JPDFIUM_ERR_INVALID;

    cmsUInt32Number len = 0;
    cmsSaveProfileToMem(hProfile, nullptr, &len);
    *profileOutput = static_cast<uint8_t*>(malloc(len));
    cmsSaveProfileToMem(hProfile, *profileOutput, &len);
    *profileLen = static_cast<int64_t>(len);

    cmsCloseProfile(hProfile);
    return JPDFIUM_OK;
}

} // extern "C"

#else // !JPDFIUM_HAS_LCMS2

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_validate_icc_profile(
    const uint8_t*, int64_t, int32_t, char** resultJson) {
    if (resultJson) *resultJson = strdup("{\"status\":\"unavailable\"}");
    return JPDFIUM_ERR_NATIVE;
}

JPDFIUM_EXPORT int32_t jpdfium_generate_replacement_icc(
    int32_t, uint8_t**, int64_t*) {
    return JPDFIUM_ERR_NATIVE;
}

} // extern "C"

#endif // JPDFIUM_HAS_LCMS2
