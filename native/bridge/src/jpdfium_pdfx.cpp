#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <map>
#include <memory>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_rust.h"
#include "jpdfium_staging.h"

#ifdef JPDFIUM_HAS_QPDF
#include <qpdf/Constants.h>

#include <qpdf/Buffer.hh>
#include <qpdf/Pl_QPDFTokenizer.hh>
#include <qpdf/Pl_String.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFAcroFormDocumentHelper.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <qpdf/QPDFPageObjectHelper.hh>
#include <qpdf/QPDFTokenizer.hh>
#include <qpdf/QPDFWriter.hh>

#include "jpdfium_qpdf_writer_lock.h"
#endif

#ifdef JPDFIUM_HAS_TURBOJPEG
#include <turbojpeg.h>
#endif

namespace {

#ifdef JPDFIUM_HAS_QPDF

class PdfxNonconformance : public std::runtime_error {
   public:
    using std::runtime_error::runtime_error;
};

enum class Level { X1a, X3, X4, X6 };

bool ForbidsTransparency(Level level) {
    return level == Level::X1a || level == Level::X3;
}

bool ForbidsDeviceIndependent(Level level) {
    return level == Level::X1a;
}

enum class Kind { Keep, Rgb, Lab, AsGray, AsCmyk };

struct ColourSpaceInfo {
    Kind kind = Kind::Keep;
    QPDFObjectHandle replacement;
    double white[3] = {0.9505, 1.0, 1.089};
    double range[4] = {-100.0, 100.0, -100.0, 100.0};
    Kind pattern_base = Kind::Keep;
    bool is_pattern = false;
};

struct Options {
    Level level = Level::X4;
    const uint8_t* lut = nullptr;
    int32_t grid = 0;
    bool convert_colours = true;
    bool convert_images = true;
    bool neutrals_to_black = true;
    bool convert_special = true;
    bool strict_images = false;
    int32_t quality = 90;
};

double Clamp01(double v) {
    return std::clamp(v, 0.0, 1.0);
}

void LutLookup(const Options& o, double r, double g, double b, double out[4]) {
    if (!o.lut || o.grid < 2) {
        const double c = 1.0 - Clamp01(r);
        const double m = 1.0 - Clamp01(g);
        const double y = 1.0 - Clamp01(b);
        const double k = std::min(c, std::min(m, y));
        if (k >= 1.0) {
            out[0] = out[1] = out[2] = 0.0;
            out[3] = 1.0;
            return;
        }
        out[0] = (c - k) / (1.0 - k);
        out[1] = (m - k) / (1.0 - k);
        out[2] = (y - k) / (1.0 - k);
        out[3] = k;
        return;
    }
    const int32_t last = o.grid - 1;
    const double fr = Clamp01(r) * last;
    const double fg = Clamp01(g) * last;
    const double fb = Clamp01(b) * last;
    const int32_t r0 = std::min(static_cast<int32_t>(fr), last);
    const int32_t g0 = std::min(static_cast<int32_t>(fg), last);
    const int32_t b0 = std::min(static_cast<int32_t>(fb), last);
    const int32_t r1 = std::min(r0 + 1, last);
    const int32_t g1 = std::min(g0 + 1, last);
    const int32_t b1 = std::min(b0 + 1, last);
    const double dr = fr - r0;
    const double dg = fg - g0;
    const double db = fb - b0;
    const size_t grid = static_cast<size_t>(o.grid);
    auto at = [&](int32_t ri, int32_t gi, int32_t bi, int c) {
        return static_cast<double>(
            o.lut[((static_cast<size_t>(ri) * grid + static_cast<size_t>(gi)) * grid +
                   static_cast<size_t>(bi)) *
                      4 +
                  static_cast<size_t>(c)]);
    };
    for (int c = 0; c < 4; ++c) {
        const double c00 = at(r0, g0, b0, c) * (1 - dr) + at(r1, g0, b0, c) * dr;
        const double c10 = at(r0, g1, b0, c) * (1 - dr) + at(r1, g1, b0, c) * dr;
        const double c01 = at(r0, g0, b1, c) * (1 - dr) + at(r1, g0, b1, c) * dr;
        const double c11 = at(r0, g1, b1, c) * (1 - dr) + at(r1, g1, b1, c) * dr;
        const double c0 = c00 * (1 - dg) + c10 * dg;
        const double c1 = c01 * (1 - dg) + c11 * dg;
        out[c] = (c0 * (1 - db) + c1 * db) / 255.0;
    }
}

void RgbToCmyk(const Options& o, double r, double g, double b, bool vector, double out[4]) {
    if (vector && o.neutrals_to_black && std::abs(r - g) < 0.002 && std::abs(g - b) < 0.002) {
        out[0] = out[1] = out[2] = 0.0;
        out[3] = 1.0 - Clamp01((r + g + b) / 3.0);
        return;
    }
    LutLookup(o, r, g, b, out);
}

double SrgbGamma(double v) {
    v = Clamp01(v);
    return v <= 0.0031308 ? 12.92 * v : 1.055 * std::pow(v, 1.0 / 2.4) - 0.055;
}

void LabToRgb(const ColourSpaceInfo& cs, double l, double a, double b, double rgb[3]) {
    auto finv = [](double t) {
        return t > 6.0 / 29.0 ? t * t * t : 3 * 36.0 / 841.0 * (t - 4.0 / 29.0);
    };
    const double fy = (l + 16.0) / 116.0;
    const double x = cs.white[0] * finv(fy + a / 500.0);
    const double y = cs.white[1] * finv(fy);
    const double z = cs.white[2] * finv(fy - b / 200.0);
    rgb[0] = SrgbGamma(3.2406 * x - 1.5372 * y - 0.4986 * z);
    rgb[1] = SrgbGamma(-0.9689 * x + 1.8758 * y + 0.0415 * z);
    rgb[2] = SrgbGamma(0.0557 * x - 0.2040 * y + 1.0570 * z);
}

std::string FormatNumber(double v) {
    if (std::abs(v) < 0.00005) return "0";
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%.4f", v);
    std::string s(buf);
    while (!s.empty() && s.back() == '0') s.pop_back();
    if (!s.empty() && s.back() == '.') s.pop_back();
    return s;
}

struct Raster;

class Converter {
   public:
    Converter(QPDF& pdf, const Options& options) : pdf_(pdf), o_(options) {}

    void Run();
    void AuditTransparency();
    std::string Report() const;
    int64_t SkippedImages() const {
        return images_skipped_;
    }

   private:
    struct Resources {
        QPDFObjectHandle dict;
        std::map<std::string, ColourSpaceInfo> spaces;
    };

    ColourSpaceInfo Classify(QPDFObjectHandle cs, int depth = 0);
    ColourSpaceInfo ClassifyArray(QPDFObjectHandle cs, int depth);
    ColourSpaceInfo ClassifyIndexed(QPDFObjectHandle cs, int depth);
    ColourSpaceInfo ClassifySpecial(const std::string& family, QPDFObjectHandle cs, int depth);
    ColourSpaceInfo ClassifyPattern(QPDFObjectHandle cs, int depth);
    bool ConvertFunction(QPDFObjectHandle fn, int inputs, Kind kind, const ColourSpaceInfo& base,
                         int depth = 0);
    bool ConvertSampled(QPDFObjectHandle fn, Kind kind, const ColourSpaceInfo& base);
    bool ConvertPostScript(QPDFObjectHandle fn, Kind kind);
    std::vector<double> ConvertComponents(const ColourSpaceInfo& cs, const std::vector<double>& in,
                                          bool vector);
    Resources PrepareResources(QPDFObjectHandle resources);
    void ProcessContentHolder(QPDFObjectHandle stream, QPDFObjectHandle inherited, int depth);
    void ProcessResources(Resources& res, int depth);
    std::string FilterContent(const std::string& content, Resources& res, bool* changed,
                              bool* sets_font = nullptr);
    void ConvertImage(QPDFObjectHandle image);
    bool ConvertImagePixels(QPDFObjectHandle image, const ColourSpaceInfo& cs);
    std::vector<uint8_t> RasterToCmyk(const Raster& raster, const ColourSpaceInfo& cs);
    QPDFObjectHandle ColourKeyToStencil(const Raster& raster, QPDFObjectHandle mask);
    void ConvertMatte(QPDFObjectHandle smask, const ColourSpaceInfo& cs);
    void StoreCmyk(QPDFObjectHandle image, const std::vector<uint8_t>& cmyk, int64_t width,
                   int64_t height, bool as_jpeg);
    std::shared_ptr<Buffer> DecodeImage(QPDFObjectHandle image, bool was_dct);
    void ConvertGroup(QPDFObjectHandle holder);
    bool AuditResources(QPDFObjectHandle resources, std::set<QPDFObjGen>* seen, std::string* why,
                        int depth);
    void RemoveTransparencyGroups(QPDFObjectHandle resources, std::set<QPDFObjGen>* seen,
                                  int depth);

    QPDF& pdf_;
    Options o_;
    std::map<QPDFObjGen, ColourSpaceInfo> cs_memo_;
    std::set<QPDFObjGen> done_streams_;
    std::set<QPDFObjGen> done_images_;
    std::set<QPDFObjGen> done_functions_;
    std::set<QPDFObjGen> done_resources_;
    std::vector<QPDFObjectHandle> done_direct_resources_;
    std::map<QPDFObjGen, std::map<std::string, ColourSpaceInfo>> spaces_memo_;
    std::vector<std::pair<QPDFObjectHandle, std::map<std::string, ColourSpaceInfo>>>
        direct_spaces_memo_;
    int64_t vector_ops_ = 0;
    int64_t images_ = 0;
    int64_t images_skipped_ = 0;
    int64_t spaces_ = 0;

    friend class ColourFilter;
};

std::string Name(QPDFObjectHandle h) {
    return h.isName() ? h.getName() : std::string();
}

// Chromium 445740322: a non-finite or unbounded tiling-pattern step overflows
// the renderer's tile raster dimensions. Clamp to the safe range that PDF
// rasterisers accept.
void ClampPatternSteps(QPDFObjectHandle pattern) {
    if (!pattern.isDictionary() && !pattern.isStream()) return;
    QPDFObjectHandle dict = pattern.isStream() ? pattern.getDict() : pattern;
    QPDFObjectHandle type = dict.getKey("/PatternType");
    if (!type.isInteger() || type.getIntValueAsInt() != 1) return;
    for (const char* key : {"/XStep", "/YStep"}) {
        QPDFObjectHandle value = dict.getKey(key);
        double step = value.isNumber() ? value.getNumericValue() : 1.0;
        if (!std::isfinite(step) || std::fabs(step) < 1e-12) {
            step = 1.0;
        } else if (std::fabs(step) < 1.0) {
            step = std::copysign(1.0, step);
        } else if (std::fabs(step) > 32767.0) {
            step = std::copysign(32767.0, step);
        }
        if (!value.isNumber() || std::fabs(value.getNumericValue() - step) > 1e-9)
            dict.replaceKey(key, QPDFObjectHandle::newReal(step, 4));
    }
}

ColourSpaceInfo Replaced(Kind kind, const char* device) {
    ColourSpaceInfo info;
    info.kind = kind;
    info.replacement = QPDFObjectHandle::newName(device);
    return info;
}

void ReadLabParameters(QPDFObjectHandle cs, ColourSpaceInfo* info) {
    if (cs.getArrayNItems() < 2 || !cs.getArrayItem(1).isDictionary()) return;
    QPDFObjectHandle d = cs.getArrayItem(1);
    QPDFObjectHandle wp = d.getKey("/WhitePoint");
    for (int i = 0; wp.isArray() && wp.getArrayNItems() == 3 && i < 3; ++i) {
        if (wp.getArrayItem(i).isNumber()) info->white[i] = wp.getArrayItem(i).getNumericValue();
    }
    QPDFObjectHandle range = d.getKey("/Range");
    for (int i = 0; range.isArray() && range.getArrayNItems() == 4 && i < 4; ++i) {
        if (range.getArrayItem(i).isNumber())
            info->range[i] = range.getArrayItem(i).getNumericValue();
    }
}

ColourSpaceInfo ClassifyDeviceIndependent(const std::string& family, QPDFObjectHandle cs) {
    if (family == "/ICCBased" && cs.getArrayNItems() > 1) {
        QPDFObjectHandle stream = cs.getArrayItem(1);
        const int n = stream.isStream() && stream.getDict().getKey("/N").isInteger()
                          ? stream.getDict().getKey("/N").getIntValueAsInt()
                          : 0;
        if (n == 3) return Replaced(Kind::Rgb, "/DeviceCMYK");
        if (n == 1) return Replaced(Kind::AsGray, "/DeviceGray");
        if (n == 4) return Replaced(Kind::AsCmyk, "/DeviceCMYK");
        return {};
    }
    if (family == "/CalRGB") return Replaced(Kind::Rgb, "/DeviceCMYK");
    if (family == "/CalGray") return Replaced(Kind::AsGray, "/DeviceGray");
    if (family == "/Lab") {
        ColourSpaceInfo info = Replaced(Kind::Lab, "/DeviceCMYK");
        ReadLabParameters(cs, &info);
        return info;
    }
    return {};
}

std::string IndexedLookupBytes(QPDFObjectHandle lookup) {
    if (lookup.isString()) return lookup.getStringValue();
    if (!lookup.isStream()) return {};
    std::shared_ptr<Buffer> b = lookup.getStreamData(qpdf_dl_all);
    return std::string(reinterpret_cast<const char*>(b->getBuffer()), b->getSize());
}

ColourSpaceInfo Converter::ClassifyIndexed(QPDFObjectHandle cs, int depth) {
    ColourSpaceInfo info;
    ColourSpaceInfo base = Classify(cs.getArrayItem(1), depth + 1);
    if (base.kind == Kind::Keep) return info;
    QPDFObjectHandle out = QPDFObjectHandle::newArray();
    out.appendItem(QPDFObjectHandle::newName("/Indexed"));
    out.appendItem(base.replacement);
    out.appendItem(cs.getArrayItem(2));
    QPDFObjectHandle lookup = cs.getArrayItem(3);
    if (base.kind != Kind::Rgb && base.kind != Kind::Lab) {
        out.appendItem(lookup);
        info.replacement = out;
        ++spaces_;
        return info;
    }
    const std::string raw = IndexedLookupBytes(lookup);
    const int hival = cs.getArrayItem(2).isInteger() ? cs.getArrayItem(2).getIntValueAsInt() : -1;
    if (hival < 0 || hival > 255 || raw.size() < static_cast<size_t>(hival + 1) * 3) return info;
    std::string converted;
    converted.reserve(static_cast<size_t>(hival + 1) * 4);
    for (int i = 0; i <= hival; ++i) {
        std::vector<double> in(3);
        for (int c = 0; c < 3; ++c) {
            in[c] = static_cast<uint8_t>(raw[static_cast<size_t>(i) * 3 + c]) / 255.0;
        }
        if (base.kind == Kind::Lab) {
            in[0] = in[0] * 100.0;
            in[1] = base.range[0] + in[1] * (base.range[1] - base.range[0]);
            in[2] = base.range[2] + in[2] * (base.range[3] - base.range[2]);
        }
        for (double v : ConvertComponents(base, in, false)) {
            converted.push_back(static_cast<char>(std::lround(Clamp01(v) * 255.0)));
        }
    }
    out.appendItem(QPDFObjectHandle::newString(converted));
    info.replacement = out;
    ++spaces_;
    return info;
}

ColourSpaceInfo Converter::ClassifySpecial(const std::string& family, QPDFObjectHandle cs,
                                           int depth) {
    ColourSpaceInfo info;
    const int alt_index = 2;
    ColourSpaceInfo alt = Classify(cs.getArrayItem(alt_index), depth + 1);
    if (alt.kind == Kind::Keep) return info;
    int inputs = 1;
    if (family == "/DeviceN" && cs.getArrayItem(1).isArray()) {
        inputs = cs.getArrayItem(1).getArrayNItems();
    }
    if (alt.kind != Kind::AsGray && alt.kind != Kind::AsCmyk &&
        !ConvertFunction(cs.getArrayItem(3), inputs, alt.kind, alt)) {
        return info;
    }
    QPDFObjectHandle out = QPDFObjectHandle::newArray();
    for (int i = 0; i < cs.getArrayNItems(); ++i) {
        out.appendItem(i == alt_index ? alt.replacement : cs.getArrayItem(i));
    }
    info.replacement = out;
    ++spaces_;
    return info;
}

ColourSpaceInfo Converter::ClassifyPattern(QPDFObjectHandle cs, int depth) {
    ColourSpaceInfo info;
    info.is_pattern = true;
    if (cs.getArrayNItems() < 2) return info;
    ColourSpaceInfo base = Classify(cs.getArrayItem(1), depth + 1);
    if (base.kind == Kind::Keep && !base.replacement.isInitialized()) return info;
    info.pattern_base = base.kind;
    QPDFObjectHandle out = QPDFObjectHandle::newArray();
    out.appendItem(QPDFObjectHandle::newName("/Pattern"));
    out.appendItem(base.replacement);
    info.replacement = out;
    std::copy(std::begin(base.white), std::end(base.white), std::begin(info.white));
    std::copy(std::begin(base.range), std::end(base.range), std::begin(info.range));
    return info;
}

ColourSpaceInfo Converter::ClassifyArray(QPDFObjectHandle cs, int depth) {
    const std::string family = Name(cs.getArrayItem(0));
    if (family == "/DeviceRGB" || family == "/RGB") return Replaced(Kind::Rgb, "/DeviceCMYK");
    if (family == "/Indexed" && cs.getArrayNItems() == 4) return ClassifyIndexed(cs, depth);
    if ((family == "/Separation" || family == "/DeviceN") && cs.getArrayNItems() >= 4 &&
        o_.convert_special) {
        return ClassifySpecial(family, cs, depth);
    }
    if (family == "/Pattern") return ClassifyPattern(cs, depth);
    if (ForbidsDeviceIndependent(o_.level)) return ClassifyDeviceIndependent(family, cs);
    return {};
}

ColourSpaceInfo Converter::Classify(QPDFObjectHandle cs, int depth) {
    if (depth > 16 || cs.isNull()) return {};
    if (cs.isName()) {
        const std::string n = cs.getName();
        if (n == "/DeviceRGB" || n == "/RGB") return Replaced(Kind::Rgb, "/DeviceCMYK");
        ColourSpaceInfo info;
        info.is_pattern = n == "/Pattern";
        return info;
    }
    if (!cs.isArray() || cs.getArrayNItems() == 0) return {};
    const QPDFObjGen og = cs.getObjGen();
    if (cs.isIndirect()) {
        auto it = cs_memo_.find(og);
        if (it != cs_memo_.end()) return it->second;
    }
    ColourSpaceInfo info = ClassifyArray(cs, depth);
    if (cs.isIndirect()) cs_memo_[og] = info;
    return info;
}

std::vector<double> Converter::ConvertComponents(const ColourSpaceInfo& cs,
                                                 const std::vector<double>& in, bool vector) {
    double out[4];
    if (cs.kind == Kind::Rgb && in.size() == 3) {
        RgbToCmyk(o_, in[0], in[1], in[2], vector, out);
        return {out[0], out[1], out[2], out[3]};
    }
    if (cs.kind == Kind::Lab && in.size() == 3) {
        double rgb[3];
        LabToRgb(cs, in[0], in[1], in[2], rgb);
        RgbToCmyk(o_, rgb[0], rgb[1], rgb[2], vector, out);
        return {out[0], out[1], out[2], out[3]};
    }
    return in;
}

bool Converter::ConvertSampled(QPDFObjectHandle fn, Kind kind, const ColourSpaceInfo& base) {
    QPDFObjectHandle dict = fn.getDict();
    QPDFObjectHandle range = dict.getKey("/Range");
    if (!range.isArray() || range.getArrayNItems() != 6) return false;
    QPDFObjectHandle size = dict.getKey("/Size");
    QPDFObjectHandle bits_obj = dict.getKey("/BitsPerSample");
    if (!size.isArray() || !bits_obj.isInteger()) return false;
    const int bits = bits_obj.getIntValueAsInt();
    if (bits != 8 && bits != 16) return false;
    int64_t samples = 1;
    for (int i = 0; i < size.getArrayNItems(); ++i) {
        if (!size.getArrayItem(i).isInteger() || size.getArrayItem(i).getIntValue() < 1)
            return false;
        samples *= size.getArrayItem(i).getIntValue();
        if (samples > (1 << 22)) return false;
    }
    double decode[6];
    QPDFObjectHandle decode_obj = dict.getKey("/Decode");
    for (int i = 0; i < 6; ++i) {
        QPDFObjectHandle src = decode_obj.isArray() && decode_obj.getArrayNItems() == 6
                                   ? decode_obj.getArrayItem(i)
                                   : range.getArrayItem(i);
        if (!src.isNumber()) return false;
        decode[i] = src.getNumericValue();
    }
    std::shared_ptr<Buffer> data = fn.getStreamData(qpdf_dl_all);
    const size_t bytes_per = bits / 8;
    if (!data || data->getSize() < static_cast<size_t>(samples) * 3 * bytes_per) return false;
    const uint8_t* p = data->getBuffer();
    const double max = bits == 8 ? 255.0 : 65535.0;
    std::string out;
    out.reserve(static_cast<size_t>(samples) * 4);
    for (int64_t s = 0; s < samples; ++s) {
        std::vector<double> in(3);
        for (int c = 0; c < 3; ++c) {
            const size_t at = (static_cast<size_t>(s) * 3 + c) * bytes_per;
            const double raw = bits == 8 ? p[at] : (p[at] << 8 | p[at + 1]);
            in[c] = decode[c * 2] + raw / max * (decode[c * 2 + 1] - decode[c * 2]);
        }
        ColourSpaceInfo cs = base;
        cs.kind = kind;
        std::vector<double> cmyk = ConvertComponents(cs, in, false);
        for (double v : cmyk) out.push_back(static_cast<char>(std::lround(Clamp01(v) * 255.0)));
    }
    QPDFObjectHandle unit = QPDFObjectHandle::newArray();
    for (int i = 0; i < 4; ++i) {
        unit.appendItem(QPDFObjectHandle::newInteger(0));
        unit.appendItem(QPDFObjectHandle::newInteger(1));
    }
    dict.replaceKey("/Range", unit);
    dict.replaceKey("/Decode", unit);
    dict.replaceKey("/BitsPerSample", QPDFObjectHandle::newInteger(8));
    dict.removeKey("/Order");
    fn.replaceStreamData(out, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
    return true;
}

bool Converter::ConvertPostScript(QPDFObjectHandle fn, Kind kind) {
    if (kind != Kind::Rgb) return false;
    std::shared_ptr<Buffer> data = fn.getStreamData(qpdf_dl_all);
    if (!data) return false;
    std::string program(reinterpret_cast<const char*>(data->getBuffer()), data->getSize());
    const size_t open = program.find('{');
    const size_t close = program.rfind('}');
    if (open == std::string::npos || close == std::string::npos || close <= open) return false;
    const std::string body = program.substr(open + 1, close - open - 1);
    const std::string converted = "{" + body +
                                  " 1 exch sub 3 1 roll 1 exch sub 3 1 roll 1 exch sub 3 1 roll"
                                  " 3 copy 2 copy gt {exch} if pop 2 copy gt {exch} if pop"
                                  " 4 1 roll 3 index sub 3 1 roll 3 index sub 3 1 roll 3 index sub"
                                  " 3 1 roll 4 -1 roll }";
    QPDFObjectHandle unit = QPDFObjectHandle::newArray();
    for (int i = 0; i < 4; ++i) {
        unit.appendItem(QPDFObjectHandle::newInteger(0));
        unit.appendItem(QPDFObjectHandle::newInteger(1));
    }
    fn.getDict().replaceKey("/Range", unit);
    fn.replaceStreamData(converted, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
    return true;
}

bool Converter::ConvertFunction(QPDFObjectHandle fn, int inputs, Kind kind,
                                const ColourSpaceInfo& base, int depth) {
    if (depth > 8) return false;
    if (fn.isArray()) return false;
    if (!fn.isDictionary() && !fn.isStream()) return false;
    if (fn.isIndirect() && done_functions_.count(fn.getObjGen())) return true;
    QPDFObjectHandle dict = fn.isStream() ? fn.getDict() : fn;
    QPDFObjectHandle type = dict.getKey("/FunctionType");
    if (!type.isInteger()) return false;
    bool ok = false;
    switch (type.getIntValue()) {
        case 0:
            ok = fn.isStream() && ConvertSampled(fn, kind, base);
            break;
        case 2: {
            QPDFObjectHandle c0 = dict.getKey("/C0");
            QPDFObjectHandle c1 = dict.getKey("/C1");
            if (!c0.isArray() || !c1.isArray() || c0.getArrayNItems() != 3 ||
                c1.getArrayNItems() != 3) {
                break;
            }
            ColourSpaceInfo cs = base;
            cs.kind = kind;
            for (QPDFObjectHandle* endpoint : {&c0, &c1}) {
                std::vector<double> in;
                for (int i = 0; i < 3; ++i)
                    in.push_back(endpoint->getArrayItem(i).getNumericValue());
                std::vector<double> cmyk = ConvertComponents(cs, in, false);
                QPDFObjectHandle arr = QPDFObjectHandle::newArray();
                for (double v : cmyk) arr.appendItem(QPDFObjectHandle::newReal(v, 4));
                dict.replaceKey(endpoint == &c0 ? "/C0" : "/C1", arr);
            }
            if (dict.hasKey("/Range")) dict.removeKey("/Range");
            ok = true;
            break;
        }
        case 3: {
            QPDFObjectHandle fns = dict.getKey("/Functions");
            if (!fns.isArray()) break;
            ok = true;
            for (int i = 0; i < fns.getArrayNItems() && ok; ++i) {
                ok = ConvertFunction(fns.getArrayItem(i), inputs, kind, base, depth + 1);
            }
            if (ok && dict.hasKey("/Range")) dict.removeKey("/Range");
            break;
        }
        case 4:
            ok = fn.isStream() && ConvertPostScript(fn, kind);
            break;
        default:
            break;
    }
    (void)inputs;
    if (ok && fn.isIndirect()) done_functions_.insert(fn.getObjGen());
    return ok;
}

Converter::Resources Converter::PrepareResources(QPDFObjectHandle resources) {
    Resources res;
    res.dict = resources;
    if (!resources.isDictionary()) return res;
    QPDFObjectHandle spaces = resources.getKey("/ColorSpace");
    if (!spaces.isDictionary()) return res;
    if (spaces.isIndirect()) {
        auto it = spaces_memo_.find(spaces.getObjGen());
        if (it != spaces_memo_.end()) {
            res.spaces = it->second;
            return res;
        }
    } else {
        for (const auto& [seen, classified] : direct_spaces_memo_) {
            if (seen.isSameObjectAs(spaces)) {
                res.spaces = classified;
                return res;
            }
        }
    }
    for (const std::string& key : spaces.getKeys()) {
        QPDFObjectHandle cs = spaces.getKey(key);
        ColourSpaceInfo info = Classify(cs);
        if (info.replacement.isInitialized() && !info.replacement.isNull()) {
            spaces.replaceKey(key, info.replacement);
        }
        res.spaces[key] = info;
    }
    if (spaces.isIndirect()) {
        spaces_memo_[spaces.getObjGen()] = res.spaces;
    } else {
        direct_spaces_memo_.emplace_back(spaces, res.spaces);
    }
    return res;
}

class ColourFilter : public QPDFObjectHandle::TokenFilter {
   public:
    ColourFilter(Converter& owner, std::map<std::string, ColourSpaceInfo>& spaces)
        : owner_(owner), spaces_(spaces) {
        stack_.push_back({});
    }

    void handleToken(QPDFTokenizer::Token const& token) override {
        using T = QPDFTokenizer;
        if (in_inline_) {
            inline_.push_back(token);
            if (token.getType() == T::tt_word && token.getValue() == "EI") {
                FlushInline();
            }
            return;
        }
        if (token.getType() == T::tt_word && token.getValue() == "BI") {
            FlushOperands();
            in_inline_ = true;
            inline_.clear();
            inline_.push_back(token);
            return;
        }
        if (token.getType() == T::tt_word && token.getValue() != "true" &&
            token.getValue() != "false" && token.getValue() != "null") {
            Operator(token);
            return;
        }
        operands_.push_back(token);
    }

    void handleEOF() override {
        FlushOperands();
        for (const auto& t : inline_) writeToken(t);
        inline_.clear();
    }

    bool changed() const {
        return changed_;
    }
    bool sets_font() const {
        return sets_font_;
    }

   private:
    struct State {
        ColourSpaceInfo fill;
        ColourSpaceInfo stroke;
    };

    void FlushOperands() {
        for (const auto& t : operands_) writeToken(t);
        operands_.clear();
    }

    std::vector<double> Numbers(bool* ok, std::string* trailing_name) {
        using T = QPDFTokenizer;
        std::vector<double> values;
        *ok = true;
        for (const auto& t : operands_) {
            switch (t.getType()) {
                case T::tt_space:
                case T::tt_comment:
                    break;
                case T::tt_integer:
                case T::tt_real:
                    values.push_back(std::strtod(t.getValue().c_str(), nullptr));
                    break;
                case T::tt_name:
                    if (trailing_name) {
                        *trailing_name = t.getValue();
                    } else {
                        *ok = false;
                    }
                    break;
                default:
                    *ok = false;
                    break;
            }
        }
        return values;
    }

    void Emit(const std::vector<double>& values, const std::string& name, const std::string& op) {
        std::string s = " ";
        for (double v : values) s += FormatNumber(v) + " ";
        if (!name.empty()) s += name + " ";
        s += op + " ";
        write(s);
        operands_.clear();
        changed_ = true;
        owner_.vector_ops_++;
    }

    ColourSpaceInfo Resolve(const std::string& name) {
        if (name == "/DeviceRGB") {
            ColourSpaceInfo info;
            info.kind = Kind::Rgb;
            info.replacement = QPDFObjectHandle::newName("/DeviceCMYK");
            return info;
        }
        if (name == "/Pattern") {
            ColourSpaceInfo info;
            info.is_pattern = true;
            return info;
        }
        auto it = spaces_.find(name);
        if (it != spaces_.end()) return it->second;
        return {};
    }

    void Operator(QPDFTokenizer::Token const& token) {
        const std::string op = token.getValue();
        if (op == "Tf") sets_font_ = true;
        State& state = stack_.back();
        if (op == "q") {
            FlushOperands();
            writeToken(token);
            stack_.push_back(state);
            return;
        }
        if (op == "Q") {
            FlushOperands();
            writeToken(token);
            if (stack_.size() > 1) stack_.pop_back();
            return;
        }
        if (op == "rg" || op == "RG") {
            bool ok;
            std::vector<double> v = Numbers(&ok, nullptr);
            ColourSpaceInfo rgb;
            rgb.kind = Kind::Rgb;
            if (ok && v.size() == 3) {
                Emit(owner_.ConvertComponents(rgb, v, true), "", op == "rg" ? "k" : "K");
                ColourSpaceInfo device;
                (op == "rg" ? state.fill : state.stroke) = device;
                return;
            }
        } else if (op == "cs" || op == "CS") {
            bool ok;
            std::string name;
            Numbers(&ok, &name);
            ColourSpaceInfo info = Resolve(name);
            (op == "cs" ? state.fill : state.stroke) = info;
            if (ok && (name == "/DeviceRGB")) {
                write(" /DeviceCMYK " + op + " ");
                operands_.clear();
                changed_ = true;
                return;
            }
        } else if (op == "sc" || op == "scn" || op == "SC" || op == "SCN") {
            const ColourSpaceInfo& cs = (op == "sc" || op == "scn") ? state.fill : state.stroke;
            ColourSpaceInfo effective = cs;
            if (cs.is_pattern) effective.kind = cs.pattern_base;
            if (effective.kind == Kind::Rgb || effective.kind == Kind::Lab) {
                bool ok;
                std::string name;
                std::vector<double> v = Numbers(&ok, &name);
                if (ok && v.size() == 3) {
                    Emit(owner_.ConvertComponents(effective, v, true), name, op);
                    return;
                }
            }
        } else if (op == "g" || op == "G" || op == "k" || op == "K") {
            ColourSpaceInfo device;
            (op == "g" || op == "k" ? state.fill : state.stroke) = device;
        }
        FlushOperands();
        writeToken(token);
    }

    void FlushInline();

    Converter& owner_;
    std::map<std::string, ColourSpaceInfo>& spaces_;
    std::vector<State> stack_;
    std::vector<QPDFTokenizer::Token> operands_;
    std::vector<QPDFTokenizer::Token> inline_;
    bool in_inline_ = false;
    bool changed_ = false;
    bool sets_font_ = false;
};

std::string ExpandInlineName(const std::string& n) {
    static const std::map<std::string, std::string> names = {
        {"/BPC", "/BitsPerComponent"}, {"/CS", "/ColorSpace"},      {"/D", "/Decode"},
        {"/DP", "/DecodeParms"},       {"/F", "/Filter"},           {"/H", "/Height"},
        {"/IM", "/ImageMask"},         {"/I", "/Interpolate"},      {"/W", "/Width"},
        {"/G", "/DeviceGray"},         {"/RGB", "/DeviceRGB"},      {"/CMYK", "/DeviceCMYK"},
        {"/AHx", "/ASCIIHexDecode"},   {"/A85", "/ASCII85Decode"},  {"/LZW", "/LZWDecode"},
        {"/Fl", "/FlateDecode"},       {"/RL", "/RunLengthDecode"}, {"/CCF", "/CCITTFaxDecode"},
        {"/DCT", "/DCTDecode"}};
    auto it = names.find(n);
    return it == names.end() ? n : it->second;
}

void ColourFilter::FlushInline() {
    using T = QPDFTokenizer;
    in_inline_ = false;
    std::vector<QPDFTokenizer::Token> tokens;
    tokens.swap(inline_);
    std::string params_text;
    const QPDFTokenizer::Token* data = nullptr;
    for (const auto& t : tokens) {
        if (t.getType() == T::tt_inline_image) data = &t;
    }
    bool convert = false;
    QPDFObjectHandle dict;
    if (data && owner_.o_.convert_images) {
        for (size_t i = 1; i < tokens.size(); ++i) {
            if (tokens[i].getType() == T::tt_word && tokens[i].getValue() == "ID") break;
            params_text += tokens[i].getRawValue();
        }
        try {
            QPDFObjectHandle raw = QPDFObjectHandle::parse("<<" + params_text + ">>");
            dict = QPDFObjectHandle::newDictionary();
            for (const std::string& k : raw.getKeys()) {
                QPDFObjectHandle v = raw.getKey(k);
                if (v.isName()) v = QPDFObjectHandle::newName(ExpandInlineName(v.getName()));
                if (v.isArray()) {
                    QPDFObjectHandle arr = QPDFObjectHandle::newArray();
                    for (int i = 0; i < v.getArrayNItems(); ++i) {
                        QPDFObjectHandle item = v.getArrayItem(i);
                        arr.appendItem(item.isName() ? QPDFObjectHandle::newName(
                                                           ExpandInlineName(item.getName()))
                                                     : item);
                    }
                    v = arr;
                }
                dict.replaceKey(ExpandInlineName(k), v);
            }
            QPDFObjectHandle cs = dict.getKey("/ColorSpace");
            ColourSpaceInfo info = cs.isName() ? Resolve(cs.getName()) : ColourSpaceInfo{};
            if (cs.isName() && (cs.getName() == "/DeviceRGB")) info.kind = Kind::Rgb;
            convert = info.kind == Kind::Rgb && !dict.getKey("/ImageMask").isBool() &&
                      dict.getKey("/BitsPerComponent").isInteger() &&
                      dict.getKey("/BitsPerComponent").getIntValue() == 8;
            QPDFObjectHandle filter = dict.getKey("/Filter");
            if (filter.isName() && filter.getName() == "/DCTDecode") convert = false;
        } catch (std::exception&) {
            convert = false;
        }
    }
    if (convert) {
        try {
            std::string bytes = data->getValue();
            while (!bytes.empty() &&
                   (bytes.back() == ' ' || bytes.back() == '\n' || bytes.back() == '\r')) {
                bytes.pop_back();
            }
            auto scratch = QPDF::create();
            scratch->emptyPDF();
            QPDFObjectHandle tmp = QPDFObjectHandle::newStream(scratch.get(), bytes);
            if (dict.hasKey("/Filter")) tmp.getDict().replaceKey("/Filter", dict.getKey("/Filter"));
            if (dict.hasKey("/DecodeParms")) {
                tmp.getDict().replaceKey("/DecodeParms", dict.getKey("/DecodeParms"));
            }
            std::shared_ptr<Buffer> decoded = tmp.getStreamData(qpdf_dl_all);
            const int w = dict.getKey("/Width").getIntValueAsInt();
            const int h = dict.getKey("/Height").getIntValueAsInt();
            if (decoded && w > 0 && h > 0 &&
                decoded->getSize() >= static_cast<size_t>(w) * static_cast<size_t>(h) * 3) {
                const uint8_t* p = decoded->getBuffer();
                std::string hex;
                static const char* digits = "0123456789ABCDEF";
                hex.reserve(static_cast<size_t>(w) * h * 8 + 2);
                for (size_t i = 0; i < static_cast<size_t>(w) * h; ++i) {
                    double out[4];
                    RgbToCmyk(owner_.o_, p[i * 3] / 255.0, p[i * 3 + 1] / 255.0,
                              p[i * 3 + 2] / 255.0, false, out);
                    for (double v : out) {
                        const int byte = static_cast<int>(std::lround(Clamp01(v) * 255.0));
                        hex.push_back(digits[byte >> 4]);
                        hex.push_back(digits[byte & 15]);
                    }
                }
                hex.push_back('>');
                std::string text = " BI /W " + std::to_string(w) + " /H " + std::to_string(h) +
                                   " /CS /CMYK /BPC 8 /F /AHx";
                if (dict.getKey("/Interpolate").isBool() &&
                    dict.getKey("/Interpolate").getBoolValue()) {
                    text += " /I true";
                }
                text += " ID\n" + hex + "\nEI ";
                write(text);
                changed_ = true;
                owner_.images_++;
                return;
            }
        } catch (std::exception&) {
        }
        owner_.images_skipped_++;
    }
    for (const auto& t : tokens) writeToken(t);
}

std::string Converter::FilterContent(const std::string& content, Resources& res, bool* changed,
                                     bool* sets_font) {
    ColourFilter filter(*this, res.spaces);
    std::string out;
    Pl_String sink("pdfx-content", nullptr, out);
    Pl_QPDFTokenizer tokenizer("pdfx-tokens", &filter, &sink);
    tokenizer.write(reinterpret_cast<const unsigned char*>(content.data()), content.size());
    tokenizer.finish();
    *changed = filter.changed();
    if (sets_font) *sets_font = filter.sets_font();
    return out;
}

void Converter::ConvertGroup(QPDFObjectHandle holder) {
    QPDFObjectHandle dict = holder.isStream() ? holder.getDict() : holder;
    QPDFObjectHandle group = dict.getKey("/Group");
    if (!group.isDictionary()) return;
    ColourSpaceInfo info = Classify(group.getKey("/CS"));
    if (info.replacement.isInitialized() && !info.replacement.isNull()) {
        group.replaceKey("/CS", info.replacement);
    }
}

void Converter::ConvertImage(QPDFObjectHandle image) {
    if (!image.isStream()) return;
    if (image.isIndirect() && !done_images_.insert(image.getObjGen()).second) return;
    QPDFObjectHandle dict = image.getDict();
    if (dict.getKey("/ImageMask").isBool() && dict.getKey("/ImageMask").getBoolValue()) return;
    dict.removeKey("/Alternates");
    dict.removeKey("/OPI");
    QPDFObjectHandle cs_obj = dict.getKey("/ColorSpace");
    ColourSpaceInfo cs = Classify(cs_obj);
    if (cs.kind == Kind::Keep) {
        if (cs.replacement.isInitialized() && !cs.replacement.isNull()) {
            dict.replaceKey("/ColorSpace", cs.replacement);
            images_++;
        }
        return;
    }
    if (cs.kind == Kind::AsGray || cs.kind == Kind::AsCmyk) {
        dict.replaceKey("/ColorSpace", cs.replacement);
        images_++;
        return;
    }
    if (!o_.convert_images) {
        images_skipped_++;
        return;
    }
    if (ConvertImagePixels(image, cs)) {
        images_++;
    } else {
        images_skipped_++;
    }
}

QPDFObjectHandle Detached(QPDFObjectHandle h) {
    return h.isNull() ? h : QPDFObjectHandle::parse(h.unparseResolved());
}

QPDFObjectHandle SanitiseDctParms(QPDFObjectHandle filter, QPDFObjectHandle parms) {
    if (Name(filter) != "/DCTDecode" || !parms.isDictionary()) return parms;
    QPDFObjectHandle kept = QPDFObjectHandle::newDictionary();
    if (parms.hasKey("/ColorTransform")) {
        kept.replaceKey("/ColorTransform", parms.getKey("/ColorTransform"));
    }
    return kept.getKeys().empty() ? QPDFObjectHandle::newNull() : kept;
}

QPDFObjectHandle SanitisedParms(QPDFObjectHandle filter, QPDFObjectHandle parms) {
    if (!filter.isArray() || !parms.isArray()) {
        return filter.isName() ? SanitiseDctParms(filter, parms) : parms;
    }
    QPDFObjectHandle clean = QPDFObjectHandle::newArray();
    for (int i = 0; i < filter.getArrayNItems(); ++i) {
        QPDFObjectHandle p =
            i < parms.getArrayNItems() ? parms.getArrayItem(i) : QPDFObjectHandle::newNull();
        clean.appendItem(SanitiseDctParms(filter.getArrayItem(i), p));
    }
    return clean;
}

std::shared_ptr<Buffer> DecodeJpegLeniently(const Buffer& jpeg, int64_t width, int64_t height) {
#ifdef JPDFIUM_HAS_TURBOJPEG
    tjhandle handle = tjInitDecompress();
    if (!handle) return nullptr;
    int frame_width = 0;
    int frame_height = 0;
    int subsampling = 0;
    int colourspace = 0;
    if (tjDecompressHeader3(handle, jpeg.getBuffer(), static_cast<unsigned long>(jpeg.getSize()),
                            &frame_width, &frame_height, &subsampling, &colourspace) != 0 ||
        frame_width != width || frame_height != height) {
        tjDestroy(handle);
        return nullptr;
    }
    auto data = std::make_shared<Buffer>(static_cast<size_t>(width * height * 3));
    const int rc = tjDecompress2(handle, jpeg.getBuffer(),
                                 static_cast<unsigned long>(jpeg.getSize()), data->getBuffer(),
                                 static_cast<int>(width), 0, static_cast<int>(height), TJPF_RGB, 0);
    const bool ok = rc == 0 || tjGetErrorCode(handle) == TJERR_WARNING;
    tjDestroy(handle);
    return ok ? data : nullptr;
#else
    (void)jpeg;
    (void)width;
    (void)height;
    return nullptr;
#endif
}

std::shared_ptr<Buffer> JpegBytes(QPDF* scratch, const std::shared_ptr<Buffer>& raw,
                                  QPDFObjectHandle filter, QPDFObjectHandle parms) {
    if (Name(filter) == "/DCTDecode") return raw;
    if (!filter.isArray() || filter.getArrayNItems() < 2 ||
        Name(filter.getArrayItem(filter.getArrayNItems() - 1)) != "/DCTDecode") {
        return nullptr;
    }
    QPDFObjectHandle before = QPDFObjectHandle::newArray();
    QPDFObjectHandle before_parms = QPDFObjectHandle::newArray();
    for (int i = 0; i + 1 < filter.getArrayNItems(); ++i) {
        before.appendItem(filter.getArrayItem(i));
        before_parms.appendItem(parms.isArray() && i < parms.getArrayNItems()
                                    ? parms.getArrayItem(i)
                                    : QPDFObjectHandle::newNull());
    }
    QPDFObjectHandle tmp = QPDFObjectHandle::newStream(
        scratch, std::string(reinterpret_cast<const char*>(raw->getBuffer()), raw->getSize()));
    tmp.getDict().replaceKey("/Filter", before);
    tmp.getDict().replaceKey("/DecodeParms", before_parms);
    try {
        return tmp.getStreamData(qpdf_dl_all);
    } catch (std::exception&) {
        return nullptr;
    }
}

std::shared_ptr<Buffer> Converter::DecodeImage(QPDFObjectHandle image, bool was_dct) {
    try {
        return image.getStreamData(qpdf_dl_all);
    } catch (std::exception&) {
    }
    QPDFObjectHandle dict = image.getDict();
    QPDFObjectHandle filter = Detached(dict.getKey("/Filter"));
    QPDFObjectHandle parms = Detached(SanitisedParms(filter, dict.getKey("/DecodeParms")));
    std::shared_ptr<Buffer> raw;
    try {
        raw = image.getRawStreamData();
    } catch (std::exception&) {
        return nullptr;
    }
    auto scratch = QPDF::create();
    scratch->emptyPDF();
    QPDFObjectHandle tmp = QPDFObjectHandle::newStream(
        scratch.get(),
        std::string(reinterpret_cast<const char*>(raw->getBuffer()), raw->getSize()));
    tmp.getDict().replaceKey("/Filter", filter);
    if (!parms.isNull()) tmp.getDict().replaceKey("/DecodeParms", parms);
    try {
        return tmp.getStreamData(qpdf_dl_all);
    } catch (std::exception&) {
    }
    std::shared_ptr<Buffer> jpeg = JpegBytes(scratch.get(), raw, filter, parms);
    if (!was_dct || !jpeg) return nullptr;
    const int64_t width =
        dict.getKey("/Width").isInteger() ? dict.getKey("/Width").getIntValue() : 0;
    const int64_t height =
        dict.getKey("/Height").isInteger() ? dict.getKey("/Height").getIntValue() : 0;
    if (width <= 0 || height <= 0) return nullptr;
    return DecodeJpegLeniently(*jpeg, width, height);
}

struct Raster {
    const uint8_t* data = nullptr;
    int64_t width = 0;
    int64_t height = 0;
    int64_t row_bytes = 0;
    int bpc = 8;
    double decode[6] = {0, 1, 0, 1, 0, 1};

    double Sample(int64_t y, int64_t index) const {
        const uint8_t* row = data + y * row_bytes;
        if (bpc == 8) return row[index];
        if (bpc == 16) return static_cast<double>(row[index * 2] << 8 | row[index * 2 + 1]);
        const int64_t bit = index * bpc;
        const int shift = 8 - bpc - static_cast<int>(bit % 8);
        return static_cast<double>((row[bit / 8] >> shift) & ((1 << bpc) - 1));
    }

    bool IsPlainRgb8() const {
        return bpc == 8 && decode[0] == 0 && decode[1] == 1 && decode[2] == 0 && decode[3] == 1 &&
               decode[4] == 0 && decode[5] == 1;
    }
};

bool ReadImageFilters(QPDFObjectHandle filter, bool* was_dct) {
    std::vector<std::string> names;
    if (filter.isName()) names.push_back(filter.getName());
    for (int i = 0; filter.isArray() && i < filter.getArrayNItems(); ++i) {
        names.push_back(Name(filter.getArrayItem(i)));
    }
    for (const std::string& n : names) {
        if (n == "/DCTDecode") *was_dct = true;
        if (n == "/JPXDecode" || n == "/JBIG2Decode") return false;
    }
    return true;
}

void ReadImageDecode(QPDFObjectHandle dict, const ColourSpaceInfo& cs, Raster* raster) {
    if (cs.kind == Kind::Lab) {
        const double lab[6] = {0, 100, cs.range[0], cs.range[1], cs.range[2], cs.range[3]};
        std::copy(std::begin(lab), std::end(lab), std::begin(raster->decode));
    }
    QPDFObjectHandle decode = dict.getKey("/Decode");
    for (int i = 0; decode.isArray() && decode.getArrayNItems() == 6 && i < 6; ++i) {
        if (decode.getArrayItem(i).isNumber())
            raster->decode[i] = decode.getArrayItem(i).getNumericValue();
    }
}

std::vector<uint8_t> Converter::RasterToCmyk(const Raster& raster, const ColourSpaceInfo& cs) {
    std::vector<uint8_t> cmyk(static_cast<size_t>(raster.width * raster.height * 4));
    const double max = static_cast<double>((1 << raster.bpc) - 1);
    const bool use_cache = cs.kind == Kind::Rgb && raster.IsPlainRgb8();
    uint32_t last_key = 0xFFFFFFFFu;
    uint8_t last[4] = {0, 0, 0, 0};
    std::vector<double> in(3);
    for (int64_t y = 0; y < raster.height; ++y) {
        for (int64_t x = 0; x < raster.width; ++x) {
            uint8_t* dst = cmyk.data() + (y * raster.width + x) * 4;
            if (use_cache) {
                const uint8_t* src = raster.data + y * raster.row_bytes + x * 3;
                const uint32_t key = static_cast<uint32_t>(src[0]) << 16 | src[1] << 8 | src[2];
                if (key != last_key) {
                    double out[4];
                    RgbToCmyk(o_, src[0] / 255.0, src[1] / 255.0, src[2] / 255.0, false, out);
                    for (int c = 0; c < 4; ++c)
                        last[c] = static_cast<uint8_t>(std::lround(Clamp01(out[c]) * 255.0));
                    last_key = key;
                }
                std::memcpy(dst, last, 4);
                continue;
            }
            for (int c = 0; c < 3; ++c) {
                in[c] =
                    raster.decode[c * 2] + raster.Sample(y, x * 3 + c) / max *
                                               (raster.decode[c * 2 + 1] - raster.decode[c * 2]);
            }
            std::vector<double> out = ConvertComponents(cs, in, false);
            for (int c = 0; c < 4; ++c)
                dst[c] = static_cast<uint8_t>(std::lround(Clamp01(out[c]) * 255.0));
        }
    }
    return cmyk;
}

QPDFObjectHandle Converter::ColourKeyToStencil(const Raster& raster, QPDFObjectHandle mask) {
    int64_t lo[3];
    int64_t hi[3];
    for (int c = 0; c < 3; ++c) {
        lo[c] = mask.getArrayItem(c * 2).getIntValue();
        hi[c] = mask.getArrayItem(c * 2 + 1).getIntValue();
    }
    const int64_t mask_row = (raster.width + 7) / 8;
    std::string bits(static_cast<size_t>(mask_row * raster.height), '\0');
    for (int64_t y = 0; y < raster.height; ++y) {
        for (int64_t x = 0; x < raster.width; ++x) {
            bool keyed = true;
            for (int c = 0; c < 3 && keyed; ++c) {
                const int64_t v = static_cast<int64_t>(raster.Sample(y, x * 3 + c));
                keyed = v >= lo[c] && v <= hi[c];
            }
            if (keyed)
                bits[static_cast<size_t>(y * mask_row + x / 8)] |=
                    static_cast<char>(0x80 >> (x % 8));
        }
    }
    QPDFObjectHandle stencil = QPDFObjectHandle::newStream(&pdf_, bits);
    QPDFObjectHandle sd = stencil.getDict();
    sd.replaceKey("/Type", QPDFObjectHandle::newName("/XObject"));
    sd.replaceKey("/Subtype", QPDFObjectHandle::newName("/Image"));
    sd.replaceKey("/Width", QPDFObjectHandle::newInteger(raster.width));
    sd.replaceKey("/Height", QPDFObjectHandle::newInteger(raster.height));
    sd.replaceKey("/ImageMask", QPDFObjectHandle::newBool(true));
    return stencil;
}

void Converter::ConvertMatte(QPDFObjectHandle smask, const ColourSpaceInfo& cs) {
    if (!smask.isStream()) return;
    QPDFObjectHandle matte = smask.getDict().getKey("/Matte");
    if (!matte.isArray() || matte.getArrayNItems() != 3) return;
    std::vector<double> mv;
    for (int i = 0; i < 3; ++i) mv.push_back(matte.getArrayItem(i).getNumericValue());
    QPDFObjectHandle arr = QPDFObjectHandle::newArray();
    for (double v : ConvertComponents(cs, mv, false))
        arr.appendItem(QPDFObjectHandle::newReal(v, 4));
    smask.getDict().replaceKey("/Matte", arr);
}

void Converter::StoreCmyk(QPDFObjectHandle image, const std::vector<uint8_t>& cmyk, int64_t width,
                          int64_t height, bool as_jpeg) {
    QPDFObjectHandle dict = image.getDict();
    dict.replaceKey("/ColorSpace", QPDFObjectHandle::newName("/DeviceCMYK"));
    dict.replaceKey("/BitsPerComponent", QPDFObjectHandle::newInteger(8));
    dict.removeKey("/Decode");
    dict.removeKey("/DecodeParms");
    if (as_jpeg) {
        uint8_t* jpeg = nullptr;
        int64_t jpeg_len = 0;
        const int32_t rc = jpdfium_rust_encode_cmyk_jpeg(
            cmyk.data(), static_cast<int64_t>(cmyk.size()), static_cast<int32_t>(width),
            static_cast<int32_t>(height), o_.quality, &jpeg, &jpeg_len);
        if (rc == JPDFIUM_OK && jpeg && jpeg_len > 0) {
            std::string bytes(reinterpret_cast<const char*>(jpeg), static_cast<size_t>(jpeg_len));
            jpdfium_rust_free(jpeg);
            QPDFObjectHandle inverted = QPDFObjectHandle::newArray();
            for (int i = 0; i < 4; ++i) {
                inverted.appendItem(QPDFObjectHandle::newInteger(1));
                inverted.appendItem(QPDFObjectHandle::newInteger(0));
            }
            dict.replaceKey("/Decode", inverted);
            image.replaceStreamData(bytes, QPDFObjectHandle::newName("/DCTDecode"),
                                    QPDFObjectHandle::newNull());
            return;
        }
        if (jpeg) jpdfium_rust_free(jpeg);
    }
    image.replaceStreamData(std::string(reinterpret_cast<const char*>(cmyk.data()), cmyk.size()),
                            QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
}

bool Converter::ConvertImagePixels(QPDFObjectHandle image, const ColourSpaceInfo& cs) {
    QPDFObjectHandle dict = image.getDict();
    const QPDFObjectHandle wo = dict.getKey("/Width");
    const QPDFObjectHandle ho = dict.getKey("/Height");
    const QPDFObjectHandle bo = dict.getKey("/BitsPerComponent");
    if (!wo.isInteger() || !ho.isInteger() || !bo.isInteger()) return false;
    Raster raster;
    raster.width = wo.getIntValue();
    raster.height = ho.getIntValue();
    raster.bpc = bo.getIntValueAsInt();
    if (raster.width <= 0 || raster.height <= 0 || raster.width > 65535 || raster.height > 65535 ||
        raster.width * raster.height > 100'000'000) {
        return false;
    }
    if (raster.bpc != 1 && raster.bpc != 2 && raster.bpc != 4 && raster.bpc != 8 &&
        raster.bpc != 16) {
        return false;
    }
    bool was_dct = false;
    if (!ReadImageFilters(dict.getKey("/Filter"), &was_dct)) return false;
    std::shared_ptr<Buffer> decoded = DecodeImage(image, was_dct);
    raster.row_bytes = (raster.width * 3 * raster.bpc + 7) / 8;
    if (!decoded || static_cast<int64_t>(decoded->getSize()) < raster.row_bytes * raster.height) {
        return false;
    }
    raster.data = decoded->getBuffer();
    ReadImageDecode(dict, cs, &raster);
    QPDFObjectHandle mask = dict.getKey("/Mask");
    if (mask.isArray() && mask.getArrayNItems() != 6) return false;
    std::vector<uint8_t> cmyk = RasterToCmyk(raster, cs);
    if (mask.isArray()) dict.replaceKey("/Mask", ColourKeyToStencil(raster, mask));
    ConvertMatte(dict.getKey("/SMask"), cs);
    StoreCmyk(image, cmyk, raster.width, raster.height, was_dct);
    return true;
}

void Converter::ProcessResources(Resources& res, int depth) {
    if (!res.dict.isDictionary() || depth > 32) return;
    if (res.dict.isIndirect()) {
        if (!done_resources_.insert(res.dict.getObjGen()).second) return;
    } else {
        for (const QPDFObjectHandle& seen : done_direct_resources_) {
            if (seen.isSameObjectAs(res.dict)) return;
        }
        done_direct_resources_.push_back(res.dict);
    }
    QPDFObjectHandle xobjects = res.dict.getKey("/XObject");
    if (xobjects.isDictionary()) {
        for (const std::string& key : xobjects.getKeys()) {
            QPDFObjectHandle x = xobjects.getKey(key);
            if (!x.isStream()) continue;
            const std::string subtype = Name(x.getDict().getKey("/Subtype"));
            if (subtype == "/PS") {
                QPDFObjectHandle empty = QPDFObjectHandle::newStream(&pdf_, "");
                QPDFObjectHandle ed = empty.getDict();
                ed.replaceKey("/Type", QPDFObjectHandle::newName("/XObject"));
                ed.replaceKey("/Subtype", QPDFObjectHandle::newName("/Form"));
                QPDFObjectHandle bbox = QPDFObjectHandle::newArray();
                for (int i = 0; i < 4; ++i) bbox.appendItem(QPDFObjectHandle::newInteger(0));
                ed.replaceKey("/BBox", bbox);
                xobjects.replaceKey(key, empty);
                continue;
            }
            x.getDict().removeKey("/PS");
            if (subtype == "/Image") {
                ConvertImage(x);
            } else if (subtype == "/Form") {
                ProcessContentHolder(x, res.dict, depth + 1);
            }
        }
    }
    QPDFObjectHandle patterns = res.dict.getKey("/Pattern");
    if (patterns.isDictionary()) {
        for (const std::string& key : patterns.getKeys()) {
            QPDFObjectHandle pat = patterns.getKey(key);
            ClampPatternSteps(pat);
            if (pat.isStream()) ProcessContentHolder(pat, res.dict, depth + 1);
        }
    }
    QPDFObjectHandle fonts = res.dict.getKey("/Font");
    if (fonts.isDictionary()) {
        for (const std::string& key : fonts.getKeys()) {
            QPDFObjectHandle font = fonts.getKey(key);
            if (!font.isDictionary() || Name(font.getKey("/Subtype")) != "/Type3") continue;
            QPDFObjectHandle procs = font.getKey("/CharProcs");
            QPDFObjectHandle font_res = font.getKey("/Resources");
            QPDFObjectHandle use = font_res.isDictionary() ? font_res : res.dict;
            if (!procs.isDictionary()) continue;
            Resources r = PrepareResources(use);
            ProcessResources(r, depth + 1);
            for (const std::string& glyph : procs.getKeys()) {
                QPDFObjectHandle proc = procs.getKey(glyph);
                if (!proc.isStream()) continue;
                if (proc.isIndirect() && !done_streams_.insert(proc.getObjGen()).second) continue;
                std::shared_ptr<Buffer> data = proc.getStreamData(qpdf_dl_all);
                std::string content(reinterpret_cast<const char*>(data->getBuffer()),
                                    data->getSize());
                bool changed = false;
                std::string out = FilterContent(content, r, &changed);
                if (changed)
                    proc.replaceStreamData(out, QPDFObjectHandle::newNull(),
                                           QPDFObjectHandle::newNull());
            }
        }
    }
    QPDFObjectHandle states = res.dict.getKey("/ExtGState");
    if (states.isDictionary()) {
        for (const std::string& key : states.getKeys()) {
            QPDFObjectHandle gs = states.getKey(key);
            if (!gs.isDictionary()) continue;
            QPDFObjectHandle smask = gs.getKey("/SMask");
            if (!smask.isDictionary()) continue;
            QPDFObjectHandle group = smask.getKey("/G");
            if (group.isStream()) {
                QPDFObjectHandle bc = smask.getKey("/BC");
                QPDFObjectHandle gdict = group.getDict().getKey("/Group");
                ColourSpaceInfo gcs =
                    gdict.isDictionary() ? Classify(gdict.getKey("/CS")) : ColourSpaceInfo{};
                if (bc.isArray() && bc.getArrayNItems() == 3 &&
                    (gcs.kind == Kind::Rgb || gcs.kind == Kind::Lab)) {
                    std::vector<double> v;
                    for (int i = 0; i < 3; ++i) v.push_back(bc.getArrayItem(i).getNumericValue());
                    std::vector<double> c = ConvertComponents(gcs, v, false);
                    QPDFObjectHandle arr = QPDFObjectHandle::newArray();
                    for (double x : c) arr.appendItem(QPDFObjectHandle::newReal(x, 4));
                    smask.replaceKey("/BC", arr);
                }
                ProcessContentHolder(group, res.dict, depth + 1);
            }
        }
    }
}

void Converter::ProcessContentHolder(QPDFObjectHandle stream, QPDFObjectHandle inherited,
                                     int depth) {
    if (!stream.isStream() || depth > 32) return;
    if (stream.isIndirect() && !done_streams_.insert(stream.getObjGen()).second) return;
    QPDFObjectHandle own = stream.getDict().getKey("/Resources");
    Resources res = PrepareResources(own.isDictionary() ? own : inherited);
    ConvertGroup(stream);
    ProcessResources(res, depth);
    std::shared_ptr<Buffer> data;
    try {
        data = stream.getStreamData(qpdf_dl_all);
    } catch (std::exception&) {
        return;
    }
    std::string content(reinterpret_cast<const char*>(data->getBuffer()), data->getSize());
    bool changed = false;
    bool sets_font = true;
    std::string out = FilterContent(content, res, &changed, &sets_font);
    if (changed)
        stream.replaceStreamData(out, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
    if (!sets_font && own.isDictionary() && !own.isIndirect() && !own.hasKey("/XObject") &&
        !own.hasKey("/Pattern")) {
        own.removeKey("/Font");
    }
}

void Converter::Run() {
    QPDFPageDocumentHelper pages(pdf_);
    for (QPDFPageObjectHelper& page : pages.getAllPages()) {
        QPDFObjectHandle dict = page.getObjectHandle();
        QPDFObjectHandle resources = page.getAttribute("/Resources", false);
        if (resources.isDictionary() && !dict.getKey("/Resources").isDictionary()) {
            resources = resources.shallowCopy();
            dict.replaceKey("/Resources", resources);
        }
        Resources res = PrepareResources(resources);
        ConvertGroup(dict);
        ProcessResources(res, 0);
        std::string content;
        Pl_String sink("pdfx-page", nullptr, content);
        page.pipeContents(&sink);
        bool changed = false;
        std::string out = FilterContent(content, res, &changed);
        if (changed) dict.replaceKey("/Contents", QPDFObjectHandle::newStream(&pdf_, out));
        QPDFObjectHandle annots = dict.getKey("/Annots");
        if (annots.isArray()) {
            for (int i = 0; i < annots.getArrayNItems(); ++i) {
                QPDFObjectHandle ap = annots.getArrayItem(i).getKey("/AP");
                if (!ap.isDictionary()) continue;
                for (const std::string& which : ap.getKeys()) {
                    QPDFObjectHandle entry = ap.getKey(which);
                    if (entry.isStream()) {
                        ProcessContentHolder(entry, resources, 1);
                    } else if (entry.isDictionary()) {
                        for (const std::string& state : entry.getKeys()) {
                            ProcessContentHolder(entry.getKey(state), resources, 1);
                        }
                    }
                }
            }
        }
    }
}

bool Converter::AuditResources(QPDFObjectHandle resources, std::set<QPDFObjGen>* seen,
                               std::string* why, int depth) {
    if (!resources.isDictionary() || depth > 32) return false;
    if (resources.isIndirect() && !seen->insert(resources.getObjGen()).second) return false;
    QPDFObjectHandle states = resources.getKey("/ExtGState");
    if (states.isDictionary()) {
        for (const std::string& key : states.getKeys()) {
            QPDFObjectHandle gs = states.getKey(key);
            if (!gs.isDictionary()) continue;
            QPDFObjectHandle smask = gs.getKey("/SMask");
            if (smask.isDictionary()) {
                *why = "a soft mask in graphics state " + key;
                return true;
            }
            for (const char* alpha : {"/CA", "/ca"}) {
                QPDFObjectHandle a = gs.getKey(alpha);
                if (a.isNumber() && a.getNumericValue() < 1.0) {
                    *why = "constant alpha in graphics state " + key;
                    return true;
                }
            }
            QPDFObjectHandle bm = gs.getKey("/BM");
            std::string mode = bm.isName()                               ? bm.getName()
                               : bm.isArray() && bm.getArrayNItems() > 0 ? Name(bm.getArrayItem(0))
                                                                         : std::string();
            if (!mode.empty() && mode != "/Normal" && mode != "/Compatible") {
                *why = "blend mode " + mode.substr(1);
                return true;
            }
        }
    }
    QPDFObjectHandle xobjects = resources.getKey("/XObject");
    if (xobjects.isDictionary()) {
        for (const std::string& key : xobjects.getKeys()) {
            QPDFObjectHandle x = xobjects.getKey(key);
            if (!x.isStream()) continue;
            QPDFObjectHandle d = x.getDict();
            if (Name(d.getKey("/Subtype")) == "/Image") {
                QPDFObjectHandle filter = d.getKey("/Filter");
                std::vector<std::string> filters;
                if (filter.isName()) filters.push_back(filter.getName());
                for (int i = 0; filter.isArray() && i < filter.getArrayNItems(); ++i) {
                    filters.push_back(Name(filter.getArrayItem(i)));
                }
                for (const std::string& f : filters) {
                    if (f == "/JBIG2Decode" || f == "/JPXDecode") {
                        *why = "a " + f.substr(1, f.size() - 7) + " image (" + key +
                               "), which needs PDF 1.4 or later";
                        return true;
                    }
                }
                if (d.getKey("/SMask").isStream()) {
                    *why = "an image with a soft mask (" + key + ")";
                    return true;
                }
                QPDFObjectHandle sid = d.getKey("/SMaskInData");
                if (sid.isInteger() && sid.getIntValue() > 0) {
                    *why = "an image with alpha in its codestream (" + key + ")";
                    return true;
                }
            } else if (AuditResources(d.getKey("/Resources"), seen, why, depth + 1)) {
                return true;
            }
        }
    }
    QPDFObjectHandle patterns = resources.getKey("/Pattern");
    if (patterns.isDictionary()) {
        for (const std::string& key : patterns.getKeys()) {
            QPDFObjectHandle pat = patterns.getKey(key);
            QPDFObjectHandle d = pat.isStream() ? pat.getDict() : pat;
            if (!d.isDictionary()) continue;
            if (AuditResources(d.getKey("/Resources"), seen, why, depth + 1)) return true;
            QPDFObjectHandle gs = d.getKey("/ExtGState");
            if (gs.isDictionary()) {
                QPDFObjectHandle wrapper = QPDFObjectHandle::newDictionary();
                QPDFObjectHandle states2 = QPDFObjectHandle::newDictionary();
                states2.replaceKey("/P", gs);
                wrapper.replaceKey("/ExtGState", states2);
                if (AuditResources(wrapper, seen, why, depth + 1)) return true;
            }
        }
    }
    QPDFObjectHandle fonts = resources.getKey("/Font");
    if (fonts.isDictionary()) {
        for (const std::string& key : fonts.getKeys()) {
            QPDFObjectHandle font = fonts.getKey(key);
            if (font.isDictionary() &&
                AuditResources(font.getKey("/Resources"), seen, why, depth + 1)) {
                return true;
            }
        }
    }
    return false;
}

void Converter::RemoveTransparencyGroups(QPDFObjectHandle resources, std::set<QPDFObjGen>* seen,
                                         int depth) {
    if (!resources.isDictionary() || depth > 32) return;
    if (resources.isIndirect() && !seen->insert(resources.getObjGen()).second) return;
    QPDFObjectHandle xobjects = resources.getKey("/XObject");
    if (!xobjects.isDictionary()) return;
    for (const std::string& key : xobjects.getKeys()) {
        QPDFObjectHandle x = xobjects.getKey(key);
        if (!x.isStream()) continue;
        QPDFObjectHandle d = x.getDict();
        if (Name(d.getKey("/Subtype")) != "/Form") continue;
        QPDFObjectHandle group = d.getKey("/Group");
        if (group.isDictionary() && Name(group.getKey("/S")) == "/Transparency")
            d.removeKey("/Group");
        RemoveTransparencyGroups(d.getKey("/Resources"), seen, depth + 1);
    }
}

void Converter::AuditTransparency() {
    QPDFPageDocumentHelper pages(pdf_);
    int index = 0;
    for (QPDFPageObjectHelper& page : pages.getAllPages()) {
        ++index;
        QPDFObjectHandle dict = page.getObjectHandle();
        QPDFObjectHandle resources = page.getAttribute("/Resources", false);
        std::set<QPDFObjGen> seen;
        std::string why;
        bool transparent = AuditResources(resources, &seen, &why, 0);
        QPDFObjectHandle annots = dict.getKey("/Annots");
        if (!transparent && annots.isArray()) {
            for (int i = 0; i < annots.getArrayNItems() && !transparent; ++i) {
                QPDFObjectHandle annot = annots.getArrayItem(i);
                QPDFObjectHandle ca = annot.getKey("/CA");
                if (ca.isNumber() && ca.getNumericValue() < 1.0) {
                    transparent = true;
                    why = "a semi-transparent annotation";
                }
                QPDFObjectHandle ap = annot.getKey("/AP");
                QPDFObjectHandle normal = ap.isDictionary() ? ap.getKey("/N") : QPDFObjectHandle();
                if (!transparent && normal.isStream() &&
                    AuditResources(normal.getDict().getKey("/Resources"), &seen, &why, 1)) {
                    transparent = true;
                }
            }
        }
        if (transparent) {
            throw PdfxNonconformance(
                "page " + std::to_string(index) + " uses " + why +
                ", which this PDF/X level forbids; flatten transparency first");
        }
        QPDFObjectHandle group = dict.getKey("/Group");
        if (group.isDictionary() && Name(group.getKey("/S")) == "/Transparency")
            dict.removeKey("/Group");
        std::set<QPDFObjGen> seen_groups;
        RemoveTransparencyGroups(resources, &seen_groups, 0);
    }
}

std::string Converter::Report() const {
    return "vector=" + std::to_string(vector_ops_) + " images=" + std::to_string(images_) +
           " images_skipped=" + std::to_string(images_skipped_) +
           " spaces=" + std::to_string(spaces_);
}

bool IsJavaScriptAction(QPDFObjectHandle action) {
    return action.isDictionary() && Name(action.getKey("/S")) == "/JavaScript";
}

void StripActions(QPDFObjectHandle dict) {
    if (!dict.isDictionary()) return;
    dict.removeKey("/AA");
    if (IsJavaScriptAction(dict.getKey("/A"))) dict.removeKey("/A");
}

void RemoveJavaScript(QPDF& pdf) {
    QPDFObjectHandle root = pdf.getRoot();
    QPDFObjectHandle names = root.getKey("/Names");
    if (names.isDictionary()) names.removeKey("/JavaScript");
    if (IsJavaScriptAction(root.getKey("/OpenAction"))) root.removeKey("/OpenAction");
    root.removeKey("/AA");
    QPDFPageDocumentHelper pages(pdf);
    for (QPDFPageObjectHelper& page : pages.getAllPages()) {
        QPDFObjectHandle dict = page.getObjectHandle();
        dict.removeKey("/AA");
        QPDFObjectHandle annots = dict.getKey("/Annots");
        if (!annots.isArray()) continue;
        for (int i = 0; i < annots.getArrayNItems(); ++i) StripActions(annots.getArrayItem(i));
    }
    QPDFObjectHandle acroform = root.getKey("/AcroForm");
    if (acroform.isDictionary()) {
        QPDFObjectHandle fields = acroform.getKey("/Fields");
        if (fields.isArray()) {
            for (int i = 0; i < fields.getArrayNItems(); ++i) StripActions(fields.getArrayItem(i));
        }
    }
}

void FlattenPrintableAnnotations(QPDF& pdf) {
    QPDFObjectHandle acroform = pdf.getRoot().getKey("/AcroForm");
    if (acroform.isDictionary()) {
        QPDFAcroFormDocumentHelper forms(pdf);
        forms.generateAppearancesIfNeeded();
        acroform.removeKey("/NeedAppearances");
    }
    QPDFPageDocumentHelper pages(pdf);
    pages.flattenAnnotations(an_print, an_invisible | an_hidden);
    for (QPDFPageObjectHelper& page : pages.getAllPages()) {
        QPDFObjectHandle annots = page.getObjectHandle().getKey("/Annots");
        if (!annots.isArray()) continue;
        for (int i = 0; i < annots.getArrayNItems(); ++i) {
            QPDFObjectHandle annot = annots.getArrayItem(i);
            if (!annot.isDictionary()) continue;
            const std::string subtype = Name(annot.getKey("/Subtype"));
            if (subtype == "/Link" || subtype == "/TrapNet" || subtype == "/PrinterMark") continue;
            QPDFObjectHandle flags = annot.getKey("/F");
            if (!flags.isInteger() || (flags.getIntValue() & an_print) == 0) continue;
            annot.replaceKey("/F", QPDFObjectHandle::newInteger(flags.getIntValue() & ~an_print));
        }
    }
}

void RemoveOptionalContent(QPDF& pdf) {
    QPDFObjectHandle root = pdf.getRoot();
    QPDFObjectHandle oc = root.getKey("/OCProperties");
    if (!oc.isDictionary()) return;
    QPDFObjectHandle config = oc.getKey("/D");
    if (config.isDictionary()) {
        QPDFObjectHandle off = config.getKey("/OFF");
        const bool base_off = Name(config.getKey("/BaseState")) == "/OFF";
        if (base_off || (off.isArray() && off.getArrayNItems() > 0)) {
            throw PdfxNonconformance(
                "the document has optional content that is hidden by default, which this PDF/X "
                "level"
                " forbids; remove or show the hidden layers first");
        }
    }
    root.removeKey("/OCProperties");
}

void FixPageBoxes(QPDF& pdf) {
    QPDFPageDocumentHelper pages(pdf);
    for (QPDFPageObjectHelper& page : pages.getAllPages()) {
        QPDFObjectHandle dict = page.getObjectHandle();
        if (dict.hasKey("/TrimBox") && dict.hasKey("/ArtBox")) dict.removeKey("/ArtBox");
    }
}

Level ParseLevel(int32_t level) {
    switch (level) {
        case 1:
            return Level::X1a;
        case 3:
            return Level::X3;
        case 4:
            return Level::X4;
        case 6:
            return Level::X6;
        default:
            throw std::invalid_argument("unknown PDF/X level");
    }
}

void Normalise(QPDF& pdf, const Options& options) {
    RemoveJavaScript(pdf);
    FlattenPrintableAnnotations(pdf);
    if (ForbidsTransparency(options.level)) RemoveOptionalContent(pdf);
    FixPageBoxes(pdf);
    Converter converter(pdf, options);
    if (options.convert_colours) converter.Run();
    if (options.strict_images && converter.SkippedImages() > 0) {
        throw PdfxNonconformance(
            std::to_string(converter.SkippedImages()) +
            " image(s) could not be converted to the output intent: " + converter.Report());
    }
    if (ForbidsTransparency(options.level)) converter.AuditTransparency();
}

void ConfigureWriter(QPDFWriter& writer) {
    writer.setPreserveEncryption(false);
    writer.setDecodeLevel(qpdf_dl_none);
    writer.setCompressStreams(true);
    writer.setObjectStreamMode(qpdf_o_preserve);
}

void CopyMessage(const std::string& text, char* message, int32_t cap) {
    if (!message || cap <= 0) return;
    const size_t n = std::min(text.size(), static_cast<size_t>(cap - 1));
    std::memcpy(message, text.data(), n);
    message[n] = '\0';
}

Options MakeOptions(int32_t level, const uint8_t* lut, int32_t grid, int32_t flags,
                    int32_t quality) {
    Options o;
    o.level = ParseLevel(level);
    o.lut = lut;
    o.grid = grid;
    o.convert_colours = (flags & JPDFIUM_PDFX_CONVERT_COLOURS) != 0;
    o.convert_images = (flags & JPDFIUM_PDFX_CONVERT_IMAGES) != 0;
    o.neutrals_to_black = (flags & JPDFIUM_PDFX_NEUTRALS_TO_BLACK) != 0;
    o.convert_special = (flags & JPDFIUM_PDFX_CONVERT_SPECIAL) != 0;
    o.strict_images = (flags & JPDFIUM_PDFX_STRICT_IMAGES) != 0;
    o.quality = quality <= 0 ? 90 : std::min(quality, 100);
    return o;
}

#endif

}  // namespace

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_pdfx_prepare_bytes(const uint8_t* input, int64_t input_len,
                                                  int32_t level, const uint8_t* lut,
                                                  int32_t lut_grid, int32_t flags, int32_t quality,
                                                  uint8_t** output, int64_t* output_len,
                                                  char* message, int32_t message_cap) {
    if (output) *output = nullptr;
    if (output_len) *output_len = 0;
#ifndef JPDFIUM_HAS_QPDF
    (void)input;
    (void)input_len;
    (void)level;
    (void)lut;
    (void)lut_grid;
    (void)flags;
    (void)quality;
    (void)message;
    (void)message_cap;
    return JPDFIUM_ERR_NOT_FOUND;
#else
    if (!input || input_len <= 0 || !output || !output_len) return JPDFIUM_ERR_INVALID;
    if (lut && (lut_grid < 2 || lut_grid > 256)) return JPDFIUM_ERR_INVALID;
    try {
        Options options = MakeOptions(level, lut, lut_grid, flags, quality);
        auto pdf = QPDF::create();
        pdf->processMemoryFile("jpdfium-pdfx-prepare", reinterpret_cast<const char*>(input),
                               static_cast<size_t>(input_len));
        Normalise(*pdf, options);
        QPDFWriter writer{*pdf};
        ConfigureWriter(writer);
        writer.setOutputMemory();
        {
            jpdfium_qpdf_internal::WriterLock lock{-1};
            writer.write();
        }
        std::shared_ptr<Buffer> buffer = writer.getBufferSharedPointer();
        if (!buffer || buffer->getSize() == 0) return JPDFIUM_ERR_NATIVE;
        auto* copy = static_cast<uint8_t*>(std::malloc(buffer->getSize()));
        if (!copy) return JPDFIUM_ERR_NATIVE;
        std::memcpy(copy, buffer->getBuffer(), buffer->getSize());
        *output = copy;
        *output_len = static_cast<int64_t>(buffer->getSize());
        return JPDFIUM_OK;
    } catch (const PdfxNonconformance& e) {
        CopyMessage(e.what(), message, message_cap);
        return JPDFIUM_ERR_PDFX_NONCONFORMANT;
    } catch (const std::invalid_argument& e) {
        CopyMessage(e.what(), message, message_cap);
        return JPDFIUM_ERR_INVALID;
    } catch (const std::exception& e) {
        CopyMessage(e.what(), message, message_cap);
        return JPDFIUM_ERR_NATIVE;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
#endif
}

JPDFIUM_EXPORT int32_t jpdfium_pdfx_prepare_file(const char* in_path, const char* out_path,
                                                 int32_t level, const uint8_t* lut,
                                                 int32_t lut_grid, int32_t flags, int32_t quality,
                                                 char* message, int32_t message_cap) {
#ifndef JPDFIUM_HAS_QPDF
    (void)in_path;
    (void)out_path;
    (void)level;
    (void)lut;
    (void)lut_grid;
    (void)flags;
    (void)quality;
    (void)message;
    (void)message_cap;
    return JPDFIUM_ERR_NOT_FOUND;
#else
    if (!in_path || !out_path) return JPDFIUM_ERR_INVALID;
    if (lut && (lut_grid < 2 || lut_grid > 256)) return JPDFIUM_ERR_INVALID;
    try {
        Options options = MakeOptions(level, lut, lut_grid, flags, quality);
        auto pdf = QPDF::create();
        pdf->processFile(in_path);
        Normalise(*pdf, options);
        std::string error;
        const bool published = jpdfium_staging::WriteAndPublish(
            out_path,
            [&](jpdfium_staging::StagedFile& staged) {
                QPDFWriter writer{*pdf};
                ConfigureWriter(writer);
                writer.setOutputFile(staged.path.c_str(), staged.file, false);
                jpdfium_qpdf_internal::WriterLock lock{-1};
                writer.write();
            },
            &error);
        if (!published) {
            CopyMessage(error, message, message_cap);
            return JPDFIUM_ERR_IO;
        }
        return JPDFIUM_OK;
    } catch (const PdfxNonconformance& e) {
        CopyMessage(e.what(), message, message_cap);
        return JPDFIUM_ERR_PDFX_NONCONFORMANT;
    } catch (const std::invalid_argument& e) {
        CopyMessage(e.what(), message, message_cap);
        return JPDFIUM_ERR_INVALID;
    } catch (const std::exception& e) {
        CopyMessage(e.what(), message, message_cap);
        return JPDFIUM_ERR_NATIVE;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
#endif
}

}  // extern "C"
