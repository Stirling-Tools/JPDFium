#include <fpdf_edit.h>
#include <fpdfview.h>

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_rust.h"
#include "jpdfium_staging.h"

extern "C" int32_t jpdfium_jpx_to_raw(const uint8_t* jpxData, int64_t jpxLen, uint8_t** rawPixels,
                                      int64_t* rawLen, int32_t* width, int32_t* height,
                                      int32_t* components);

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

enum class Level : std::uint8_t { X1a, X3, X4, X6 };

bool ForbidsTransparency(Level level) {
    return level == Level::X1a || level == Level::X3;
}

bool ForbidsDeviceIndependent(Level level) {
    return level == Level::X1a;
}

enum class Kind : std::uint8_t { Keep, Rgb, Lab, AsGray, AsCmyk };

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
    bool allow_strip_oc = false;
    int32_t quality = 90;
};

double Clamp01(double v) {
    return std::isfinite(v) ? std::clamp(v, 0.0, 1.0) : 0.0;
}

constexpr size_t kMaxPdfxStreamBytes = size_t{64} * 1024U * 1024U;

class BoundedStringPipeline final : public Pipeline {
   public:
    BoundedStringPipeline(const char* identifier, size_t maxBytes)
        : Pipeline(identifier, nullptr), maxBytes_(maxBytes) {}

    void write(unsigned char const* data, size_t len) override {
        if (len > maxBytes_ - out_.size()) {
            throw std::runtime_error("PDF/X stream exceeds decompressed byte limit");
        }
        if (len > 0) {
            out_.append(reinterpret_cast<const char*>(data), len);
        }
    }

    void finish() override {}

    std::string take() {
        return std::move(out_);
    }

   private:
    size_t maxBytes_;
    std::string out_;
};

bool ReadStreamBounded(QPDFObjectHandle stream, std::string& out,
                       qpdf_stream_decode_level_e dl = qpdf_dl_all,
                       size_t maxBytes = kMaxPdfxStreamBytes) {
    if (!stream.isStream()) return false;
    try {
        QPDFObjectHandle filter = stream.getDict().getKey("/Filter");
        const bool hasFilter = filter.isName() || (filter.isArray() && filter.getArrayNItems() > 0);
        BoundedStringPipeline sink("pdfx-bounded", maxBytes);
        bool filtered = false;
        if (!stream.pipeStreamData(&sink, &filtered, 0, dl, true)) {
            return false;
        }
        if (dl != qpdf_dl_none && hasFilter && !filtered) {
            return false;
        }
        out = sink.take();
        return true;
    } catch (std::exception&) {
        return false;
    }
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
    if (vector && std::abs(r - g) < 0.002 && std::abs(g - b) < 0.002) {
        const double k = 1.0 - Clamp01((r + g + b) / 3.0);
        if (o.neutrals_to_black) {
            out[0] = out[1] = out[2] = 0.0;
            out[3] = k;
        } else {
            // With neutral-to-black disabled, use a conventional rich black.
            // The bundled compact profile deliberately uses strong GCR and
            // would otherwise collapse this back to K-only, making the option
            // unobservable. 60/40/40 under 100% K keeps solid-black TAC at
            // 240%, below the profile's 245% cap.
            out[0] = 0.6 * k;
            out[1] = 0.4 * k;
            out[2] = 0.4 * k;
            out[3] = k;
        }
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

    ColourSpaceInfo Classify(QPDFObjectHandle const& cs, int depth = 0);
    ColourSpaceInfo ClassifyArray(QPDFObjectHandle const& cs, int depth);
    ColourSpaceInfo ClassifyIndexed(QPDFObjectHandle const& cs, int depth);
    ColourSpaceInfo ClassifySpecial(const std::string& family, QPDFObjectHandle const& cs,
                                    int depth);
    ColourSpaceInfo ClassifyPattern(QPDFObjectHandle const& cs, int depth);
    bool ConvertFunction(QPDFObjectHandle const& fn, int inputs, Kind kind,
                         const ColourSpaceInfo& base, int depth = 0);
    bool ConvertSampled(QPDFObjectHandle fn, Kind kind, const ColourSpaceInfo& base);
    bool ConvertPostScript(QPDFObjectHandle fn, Kind kind);
    std::vector<double> ConvertComponents(const ColourSpaceInfo& cs, const std::vector<double>& in,
                                          bool vector);
    Resources PrepareResources(QPDFObjectHandle const& resources);
    void ProcessContentHolder(QPDFObjectHandle stream, QPDFObjectHandle const& inherited,
                              int depth);
    void ProcessResources(Resources& res, int depth);
    std::string FilterContent(const std::string& content, Resources& res, bool* changed,
                              bool* sets_font = nullptr);
    void ConvertImage(QPDFObjectHandle const& image);
    bool ConvertImagePixels(QPDFObjectHandle const& image, const ColourSpaceInfo& cs);
    std::vector<uint8_t> RasterToCmyk(const Raster& raster, const ColourSpaceInfo& cs);
    QPDFObjectHandle ColourKeyToStencil(const Raster& raster, QPDFObjectHandle const& mask);
    void ConvertMatte(QPDFObjectHandle const& smask, const ColourSpaceInfo& cs);
    void StoreCmyk(QPDFObjectHandle image, const std::vector<uint8_t>& cmyk, int64_t width,
                   int64_t height, bool as_jpeg);
    std::shared_ptr<Buffer> DecodeImage(QPDFObjectHandle image, bool was_dct);
    void ConvertGroup(QPDFObjectHandle const& holder);
    void ConvertShading(QPDFObjectHandle const& shading);
    bool AuditAnnotationEntry(QPDFObjectHandle const& entry, std::set<QPDFObjGen>* seen,
                              std::string* why, int depth);
    bool AuditResources(QPDFObjectHandle const& resources, std::set<QPDFObjGen>* seen,
                        std::string* why, int depth);
    void RemoveTransparencyGroups(QPDFObjectHandle const& resources, std::set<QPDFObjGen>* seen,
                                  int depth);
    QPDF* ScratchQpdf() {
        if (!scratch_qpdf_) {
            scratch_qpdf_ = QPDF::create();
            scratch_qpdf_->emptyPDF();
        }
        return scratch_qpdf_.get();
    }

    QPDF& pdf_;
    Options o_;
    std::shared_ptr<QPDF> scratch_qpdf_;
    std::map<QPDFObjGen, ColourSpaceInfo> cs_memo_;
    std::set<QPDFObjGen> done_streams_;
    std::set<QPDFObjGen> done_images_;
    std::set<QPDFObjGen> done_shadings_;
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

std::string Name(QPDFObjectHandle const& h) {
    return h.isName() ? h.getName() : std::string();
}

// Component count of a process/alternate colour space. ICCBased stores it in
// /N; DeviceN stores it as the length of the names array; Separation and
// DeviceGray are one component.
int ColourSpaceComponents(QPDFObjectHandle const& cs) {
    if (!cs.isName() && !cs.isArray()) return 0;
    if (cs.isName()) {
        const std::string n = Name(cs);
        if (n == "/DeviceGray" || n == "/CalGray") return 1;
        if (n == "/DeviceRGB" || n == "/CalRGB" || n == "/Lab") return 3;
        if (n == "/DeviceCMYK") return 4;
        return 0;
    }
    if (cs.getArrayNItems() == 0) return 0;
    const std::string family = Name(cs.getArrayItem(0));
    if (family == "/ICCBased" && cs.getArrayNItems() >= 2) {
        QPDFObjectHandle stream = cs.getArrayItem(1);
        if (stream.isStream()) {
            QPDFObjectHandle n = stream.getDict().getKey("/N");
            if (n.isInteger() && n.getIntValue() > 0) return n.getIntValueAsInt();
        }
    }
    if ((family == "/DeviceN" || family == "/Separation") && cs.getArrayNItems() >= 2) {
        if (family == "/Separation") return 1;
        QPDFObjectHandle names = cs.getArrayItem(1);
        if (names.isArray()) return names.getArrayNItems();
    }
    if (family == "/Indexed" || family == "/CalGray") return 1;
    if (family == "/CalRGB" || family == "/Lab") return 3;
    return 0;
}

// Replace only missing/non-finite/zero tiling-pattern steps. ISO 32000-1
// 8.7.3.1 permits finite nonzero fractional and large steps; changing those
// would alter the rendered tile spacing.
void ClampPatternSteps(QPDFObjectHandle const& pattern) {
    if (!pattern.isDictionary() && !pattern.isStream()) return;
    QPDFObjectHandle dict = pattern.isStream() ? pattern.getDict() : pattern;
    QPDFObjectHandle type = dict.getKey("/PatternType");
    if (!type.isInteger() || type.getIntValueAsInt() != 1) return;
    for (const char* key : {"/XStep", "/YStep"}) {
        QPDFObjectHandle value = dict.getKey(key);
        bool replace = !value.isNumber();
        double step = value.isNumber() ? value.getNumericValue() : 1.0;
        // ISO 32000-1 8.7.3.1 permits any nonzero finite step, including
        // fractional and large values. Only replace values that cannot be
        // rendered meaningfully: missing, non-finite, or zero.
        if (replace || !std::isfinite(step) || step == 0.0) {
            step = 1.0;
            replace = true;
        }
        if (replace) dict.replaceKey(key, QPDFObjectHandle::newReal(step, 4));
    }
}

ColourSpaceInfo Replaced(Kind kind, const char* device) {
    ColourSpaceInfo info;
    info.kind = kind;
    info.replacement = QPDFObjectHandle::newName(device);
    return info;
}

void ReadLabParameters(QPDFObjectHandle const& cs, ColourSpaceInfo* info) {
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

ColourSpaceInfo ClassifyDeviceIndependent(const std::string& family, QPDFObjectHandle const& cs) {
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

std::string IndexedLookupBytes(QPDFObjectHandle const& lookup) {
    if (lookup.isString()) return lookup.getStringValue();
    if (!lookup.isStream()) return {};
    std::string out;
    if (!ReadStreamBounded(lookup, out, qpdf_dl_all, size_t{1024} * 1024U)) return {};
    return out;
}

ColourSpaceInfo Converter::ClassifyIndexed(QPDFObjectHandle const& cs, int depth) {
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
        info.replacement = std::move(out);
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
    info.replacement = std::move(out);
    ++spaces_;
    return info;
}

ColourSpaceInfo Converter::ClassifySpecial(const std::string& family, QPDFObjectHandle const& cs,
                                           int depth) {
    ColourSpaceInfo info;
    if (family == "/DeviceN" && cs.getArrayNItems() >= 5 && cs.getArrayItem(4).isDictionary()) {
        QPDFObjectHandle attrs = cs.getArrayItem(4);
        QPDFObjectHandle colorants = attrs.getKey("/Colorants");
        if (colorants.isDictionary()) {
            for (const std::string& key : colorants.getKeys()) {
                ColourSpaceInfo cinfo = Classify(colorants.getKey(key), depth + 1);
                if (cinfo.replacement.isInitialized() && !cinfo.replacement.isNull()) {
                    colorants.replaceKey(key, cinfo.replacement);
                }
            }
        }
        QPDFObjectHandle proc = attrs.getKey("/Process");
        if (proc.isDictionary() && proc.hasKey("/ColorSpace")) {
            ColourSpaceInfo pinfo = Classify(proc.getKey("/ColorSpace"), depth + 1);
            if (pinfo.replacement.isInitialized() && !pinfo.replacement.isNull()) {
                // ISO 32000-1 Table 72: /Components names the process space's
                // components in order. Rebinding Process to a space with a
                // different component count while retaining the names would
                // break the DeviceN contract; preserve the RGB mapping instead.
                const int targetComponents = ColourSpaceComponents(pinfo.replacement);
                QPDFObjectHandle components = proc.getKey("/Components");
                if (targetComponents == 0 || !components.isArray() ||
                    components.getArrayNItems() == targetComponents) {
                    proc.replaceKey("/ColorSpace", pinfo.replacement);
                }
            }
        }
    }
    const int alt_index = 2;
    QPDFObjectHandle alt_obj = cs.getArrayItem(alt_index);
    ColourSpaceInfo alt = Classify(alt_obj, depth + 1);
    if (alt.kind == Kind::Keep && alt_obj.isArray() && alt_obj.getArrayNItems() >= 2) {
        alt = ClassifyDeviceIndependent(Name(alt_obj.getArrayItem(0)), alt_obj);
    }
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
    info.replacement = std::move(out);
    ++spaces_;
    return info;
}

ColourSpaceInfo Converter::ClassifyPattern(QPDFObjectHandle const& cs, int depth) {
    ColourSpaceInfo info;
    info.is_pattern = true;
    if (cs.getArrayNItems() < 2) return info;
    ColourSpaceInfo base = Classify(cs.getArrayItem(1), depth + 1);
    if (base.kind == Kind::Keep && !base.replacement.isInitialized()) return info;
    info.pattern_base = base.kind;
    QPDFObjectHandle out = QPDFObjectHandle::newArray();
    out.appendItem(QPDFObjectHandle::newName("/Pattern"));
    out.appendItem(base.replacement);
    info.replacement = std::move(out);
    std::copy(std::begin(base.white), std::end(base.white), std::begin(info.white));
    std::copy(std::begin(base.range), std::end(base.range), std::begin(info.range));
    return info;
}

ColourSpaceInfo Converter::ClassifyArray(QPDFObjectHandle const& cs, int depth) {
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

ColourSpaceInfo Converter::Classify(QPDFObjectHandle const& cs, int depth) {
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
    constexpr int64_t kMaxSamples = 1LL << 22;
    int64_t samples = 1;
    for (int i = 0; i < size.getArrayNItems(); ++i) {
        if (!size.getArrayItem(i).isInteger()) return false;
        const int64_t dim = size.getArrayItem(i).getIntValue();
        if (dim < 1 || dim > kMaxSamples / samples) return false;
        samples *= dim;
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
    std::string data;
    if (!ReadStreamBounded(fn, data, qpdf_dl_all, kMaxPdfxStreamBytes)) return false;
    const size_t bytes_per = static_cast<size_t>(bits / 8);
    if (data.size() < static_cast<size_t>(samples) * 3U * bytes_per) return false;
    const auto* p = reinterpret_cast<const uint8_t*>(data.data());
    const double max = bits == 8 ? 255.0 : 65535.0;
    std::string out;
    out.reserve(static_cast<size_t>(samples) * 4U);
    for (int64_t s = 0; s < samples; ++s) {
        std::vector<double> in(3);
        for (int c = 0; c < 3; ++c) {
            const size_t idx = static_cast<size_t>(c);
            const size_t at = (static_cast<size_t>(s) * 3U + idx) * bytes_per;
            const double raw = bits == 8 ? p[at] : (p[at] << 8 | p[at + 1]);
            in[idx] = decode[idx * 2] + raw / max * (decode[idx * 2 + 1] - decode[idx * 2]);
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
    std::string program;
    if (!ReadStreamBounded(fn, program, qpdf_dl_all, kMaxPdfxStreamBytes)) return false;
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

bool Converter::ConvertFunction(QPDFObjectHandle const& fn, int inputs, Kind kind,
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
            bool validEndpoints = true;
            for (QPDFObjectHandle* endpoint : {&c0, &c1}) {
                for (int i = 0; i < 3; ++i) {
                    if (!endpoint->getArrayItem(i).isNumber()) {
                        validEndpoints = false;
                        break;
                    }
                }
                if (!validEndpoints) break;
            }
            if (!validEndpoints) break;
            for (QPDFObjectHandle* endpoint : {&c0, &c1}) {
                std::vector<double> in;
                in.reserve(3);
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

Converter::Resources Converter::PrepareResources(QPDFObjectHandle const& resources) {
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
    // Never leave a /DeviceCMYK replacement bound under /DefaultRGB (which must
    // be 3-component per ISO 32000-1 §8.6.5.6) after converting RGB colors.
    if (spaces.hasKey("/DefaultRGB") &&
        (ForbidsDeviceIndependent(o_.level) || o_.convert_colours)) {
        spaces.removeKey("/DefaultRGB");
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
        const std::string& op = token.getValue();
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
                (op == "rg" ? state.fill : state.stroke) = std::move(device);
                return;
            }
        } else if (op == "cs" || op == "CS") {
            bool ok;
            std::string name;
            Numbers(&ok, &name);
            ColourSpaceInfo info = Resolve(name);
            (op == "cs" ? state.fill : state.stroke) = std::move(info);
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
            (op == "g" || op == "k" ? state.fill : state.stroke) = std::move(device);
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
    // Inline images bundle abbreviated keys between BI..ID..EI; normalize before inspecting color.
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
            bool is_mask =
                dict.getKey("/ImageMask").isBool() && dict.getKey("/ImageMask").getBoolValue();
            bool is_rgb_image = info.kind == Kind::Rgb && !is_mask;
            convert = is_rgb_image && owner_.o_.convert_images &&
                      dict.getKey("/BitsPerComponent").isInteger() &&
                      dict.getKey("/BitsPerComponent").getIntValue() == 8;
            QPDFObjectHandle filter = dict.getKey("/Filter");
            // Skip DCT-encoded inline images whose JPEG payload cannot be losslessly re-encoded as
            // CMYK without user transcoding flags.
            bool has_dct = filter.isName() && filter.getName() == "/DCTDecode";
            if (!has_dct && filter.isArray()) {
                for (int fi = 0; fi < filter.getArrayNItems(); ++fi) {
                    if (Name(filter.getArrayItem(fi)) == "/DCTDecode") {
                        has_dct = true;
                        break;
                    }
                }
            }
            if (has_dct) convert = false;
            if (is_rgb_image && !convert) owner_.images_skipped_++;
        } catch (std::exception&) {
            convert = false;
        }
    }
    if (convert) {
        try {
            // Strip QPDF tokenizer trailing whitespace before re-wrapping raw bytes in a temp
            // stream for CMYK conversion.
            std::string bytes = data->getValue();
            while (!bytes.empty() &&
                   (bytes.back() == ' ' || bytes.back() == '\n' || bytes.back() == '\r')) {
                bytes.pop_back();
            }
            QPDFObjectHandle tmp = QPDFObjectHandle::newStream(owner_.ScratchQpdf(), bytes);
            if (dict.hasKey("/Filter")) tmp.getDict().replaceKey("/Filter", dict.getKey("/Filter"));
            if (dict.hasKey("/DecodeParms")) {
                tmp.getDict().replaceKey("/DecodeParms", dict.getKey("/DecodeParms"));
            }
            std::string decoded;
            const int64_t w =
                dict.getKey("/Width").isInteger() ? dict.getKey("/Width").getIntValue() : 0;
            const int64_t h =
                dict.getKey("/Height").isInteger() ? dict.getKey("/Height").getIntValue() : 0;
            constexpr int64_t kMaxInlineDim = 16384;
            constexpr uint64_t kMaxInlinePixels = uint64_t{16} * 1024U * 1024U;
            if (w > 0 && h > 0 && w <= kMaxInlineDim && h <= kMaxInlineDim &&
                ReadStreamBounded(tmp, decoded, qpdf_dl_all, kMaxPdfxStreamBytes)) {
                const uint64_t pixels = static_cast<uint64_t>(w) * static_cast<uint64_t>(h);
                if (pixels <= kMaxInlinePixels &&
                    decoded.size() >= static_cast<size_t>(pixels) * 3U) {
                    const auto* p = reinterpret_cast<const uint8_t*>(decoded.data());
                    std::string hex;
                    static const char* digits = "0123456789ABCDEF";
                    hex.reserve(static_cast<size_t>(pixels) * 8U + 2U);
                    for (size_t i = 0; i < static_cast<size_t>(pixels); ++i) {
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
            }
        } catch (std::exception&) {
            convert = false;
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

void Converter::ConvertGroup(QPDFObjectHandle const& holder) {
    QPDFObjectHandle dict = holder.isStream() ? holder.getDict() : holder;
    QPDFObjectHandle group = dict.getKey("/Group");
    if (!group.isDictionary()) return;
    ColourSpaceInfo info = Classify(group.getKey("/CS"));
    if (info.replacement.isInitialized() && !info.replacement.isNull()) {
        group.replaceKey("/CS", info.replacement);
    }
}

void Converter::ConvertShading(QPDFObjectHandle const& shading) {
    if (!shading.isDictionary() && !shading.isStream()) return;
    if (shading.isIndirect() && !done_shadings_.insert(shading.getObjGen()).second) return;
    QPDFObjectHandle dict = shading.isStream() ? shading.getDict() : shading;
    QPDFObjectHandle type_obj = dict.getKey("/ShadingType");
    const int shading_type = type_obj.isInteger() ? type_obj.getIntValueAsInt() : 0;
    ColourSpaceInfo cs = Classify(dict.getKey("/ColorSpace"));
    if (cs.kind == Kind::Keep) {
        if (cs.replacement.isInitialized() && !cs.replacement.isNull()) {
            dict.replaceKey("/ColorSpace", cs.replacement);
        }
        return;
    }
    if (cs.kind == Kind::AsGray || cs.kind == Kind::AsCmyk) {
        dict.replaceKey("/ColorSpace", cs.replacement);
        spaces_++;
        return;
    }
    QPDFObjectHandle fn = dict.getKey("/Function");
    const int inputs = shading_type == 1 ? 2 : 1;
    if (fn.isNull() || !ConvertFunction(fn, inputs, cs.kind, cs)) return;
    QPDFObjectHandle bg = dict.getKey("/Background");
    if (bg.isArray() && bg.getArrayNItems() == 3) {
        bool valid_bg = true;
        std::vector<double> in;
        for (int i = 0; i < 3; ++i) {
            if (!bg.getArrayItem(i).isNumber()) {
                valid_bg = false;
                break;
            }
            in.push_back(bg.getArrayItem(i).getNumericValue());
        }
        if (valid_bg) {
            std::vector<double> cmyk = ConvertComponents(cs, in, false);
            QPDFObjectHandle arr = QPDFObjectHandle::newArray();
            for (double v : cmyk) arr.appendItem(QPDFObjectHandle::newReal(v, 4));
            dict.replaceKey("/Background", arr);
        }
    }
    dict.replaceKey("/ColorSpace", cs.replacement);
    spaces_++;
}

void Converter::ConvertImage(QPDFObjectHandle const& image) {
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

QPDFObjectHandle Detached(QPDFObjectHandle const& h) {
    return h.isNull() ? h : QPDFObjectHandle::parse(h.unparseResolved());
}

QPDFObjectHandle SanitiseDctParms(QPDFObjectHandle const& filter, QPDFObjectHandle const& parms) {
    if (Name(filter) != "/DCTDecode" || !parms.isDictionary()) return parms;
    QPDFObjectHandle kept = QPDFObjectHandle::newDictionary();
    if (parms.hasKey("/ColorTransform")) {
        kept.replaceKey("/ColorTransform", parms.getKey("/ColorTransform"));
    }
    return kept.getKeys().empty() ? QPDFObjectHandle::newNull() : kept;
}

QPDFObjectHandle SanitisedParms(QPDFObjectHandle const& filter, QPDFObjectHandle const& parms) {
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
                                  QPDFObjectHandle const& filter, QPDFObjectHandle const& parms) {
    if (Name(filter) == "/DCTDecode" || (filter.isArray() && filter.getArrayNItems() == 1 &&
                                         Name(filter.getArrayItem(0)) == "/DCTDecode")) {
        return raw;
    }
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
    std::string bounded;
    if (!ReadStreamBounded(tmp, bounded, qpdf_dl_all, kMaxPdfxStreamBytes)) return nullptr;
    auto buf = std::make_shared<Buffer>(bounded.size());
    if (!bounded.empty()) std::memcpy(buf->getBuffer(), bounded.data(), bounded.size());
    return buf;
}

std::shared_ptr<Buffer> Converter::DecodeImage(QPDFObjectHandle image, bool was_dct) {
    QPDFObjectHandle dict = image.getDict();
    QPDFObjectHandle filter = Detached(dict.getKey("/Filter"));
    QPDFObjectHandle parms = Detached(SanitisedParms(filter, dict.getKey("/DecodeParms")));
    const int64_t width =
        dict.getKey("/Width").isInteger() ? dict.getKey("/Width").getIntValue() : 0;
    const int64_t height =
        dict.getKey("/Height").isInteger() ? dict.getKey("/Height").getIntValue() : 0;
    if (was_dct && width > 0 && height > 0) {
        std::shared_ptr<Buffer> rawDct;
        try {
            rawDct = image.getRawStreamData();
        } catch (std::exception&) {
            rawDct = nullptr;
        }
        if (rawDct && rawDct->getSize() <= kMaxPdfxStreamBytes) {
            std::shared_ptr<Buffer> jpeg = JpegBytes(ScratchQpdf(), rawDct, filter, parms);
            if (jpeg) {
                std::shared_ptr<Buffer> decodedJpeg = DecodeJpegLeniently(*jpeg, width, height);
                if (decodedJpeg) return decodedJpeg;
            }
        }
    }
    std::string bounded;
    if (!was_dct && ReadStreamBounded(image, bounded, qpdf_dl_all, kMaxPdfxStreamBytes)) {
        auto buf = std::make_shared<Buffer>(bounded.size());
        if (!bounded.empty()) std::memcpy(buf->getBuffer(), bounded.data(), bounded.size());
        return buf;
    }
    std::shared_ptr<Buffer> raw;
    try {
        raw = image.getRawStreamData();
        if (!raw || raw->getSize() > kMaxPdfxStreamBytes) return nullptr;
    } catch (std::exception&) {
        return nullptr;
    }
    QPDF* scratch = ScratchQpdf();
    QPDFObjectHandle tmp = QPDFObjectHandle::newStream(
        scratch, std::string(reinterpret_cast<const char*>(raw->getBuffer()), raw->getSize()));
    tmp.getDict().replaceKey("/Filter", filter);
    if (!parms.isNull()) tmp.getDict().replaceKey("/DecodeParms", parms);
    if (!was_dct && ReadStreamBounded(tmp, bounded, qpdf_dl_all, kMaxPdfxStreamBytes)) {
        auto buf = std::make_shared<Buffer>(bounded.size());
        if (!bounded.empty()) std::memcpy(buf->getBuffer(), bounded.data(), bounded.size());
        return buf;
    }
    std::shared_ptr<Buffer> jpeg = JpegBytes(scratch, raw, filter, parms);
    if (!was_dct || !jpeg || width <= 0 || height <= 0) return nullptr;
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

bool HasImageFilter(QPDFObjectHandle const& filter, const char* target) {
    if (filter.isName()) return filter.getName() == target;
    if (filter.isArray()) {
        for (int i = 0; i < filter.getArrayNItems(); ++i) {
            if (Name(filter.getArrayItem(i)) == target) return true;
        }
    }
    return false;
}

bool HasSingleNamedFilter(QPDFObjectHandle const& filter, const char* target) {
    if (filter.isName()) return filter.getName() == target;
    if (filter.isArray() && filter.getArrayNItems() == 1) {
        return Name(filter.getArrayItem(0)) == target;
    }
    return false;
}

std::mutex& PdfiumTranscodeMutex() {
    static std::mutex mu;
    return mu;
}

bool DecodeSingleImageWithPdfium(const QPDFObjectHandle& image, bool forceGrayRaw, int* outW,
                                 int* outH, int* outFmt, std::vector<uint8_t>* outPixels) {
    QPDFObjectHandle dict = image.getDict();
    QPDF mini;
    mini.emptyPDF();
    QPDFObjectHandle savedSmask = dict.getKey("/SMask");
    bool hadSmask = savedSmask.isInitialized() && !savedSmask.isNull();
    if (hadSmask) dict.removeKey("/SMask");
    QPDFObjectHandle miniImg;
    try {
        miniImg = mini.copyForeignObject(image);
    } catch (...) {
        if (hadSmask) dict.replaceKey("/SMask", savedSmask);
        return false;
    }
    if (hadSmask) dict.replaceKey("/SMask", savedSmask);

    QPDFObjectHandle md = miniImg.getDict();
    md.removeKey("/Alternates");
    md.removeKey("/OPI");
    md.removeKey("/OC");
    md.removeKey("/Metadata");
    if (forceGrayRaw) {
        md.replaceKey("/ColorSpace", QPDFObjectHandle::newName("/DeviceGray"));
        md.replaceKey("/BitsPerComponent", QPDFObjectHandle::newInteger(1));
        md.removeKey("/ImageMask");
        md.removeKey("/Decode");
    }

    QPDFObjectHandle pagesDict = mini.getRoot().getKey("/Pages");
    QPDFObjectHandle page = mini.makeIndirectObject(
        QPDFObjectHandle::parse("<< /Type /Page /MediaBox [0 0 100 100] >>"));
    QPDFObjectHandle xobj = QPDFObjectHandle::newDictionary();
    xobj.replaceKey("/Im0", miniImg);
    QPDFObjectHandle res = QPDFObjectHandle::newDictionary();
    res.replaceKey("/XObject", xobj);
    page.replaceKey("/Resources", res);
    page.replaceKey("/Contents",
                    QPDFObjectHandle::newStream(&mini, "q 100 0 0 100 0 0 cm /Im0 Do Q\n"));
    page.replaceKey("/Parent", pagesDict);
    pagesDict.getKey("/Kids").appendItem(page);
    pagesDict.replaceKey("/Count", QPDFObjectHandle::newInteger(1));

    QPDFWriter w(mini);
    w.setPreserveEncryption(false);
    w.setDecodeLevel(qpdf_dl_none);
    w.setCompressStreams(false);
    w.setOutputMemory();
    w.write();
    std::shared_ptr<Buffer> pdfBuf = w.getBufferSharedPointer();
    if (!pdfBuf || pdfBuf->getSize() == 0 ||
        pdfBuf->getSize() > static_cast<size_t>(std::numeric_limits<int>::max())) {
        return false;
    }

    jpdfium_init();
    std::lock_guard<std::mutex> lock(PdfiumTranscodeMutex());
    FPDF_DOCUMENT doc =
        FPDF_LoadMemDocument(pdfBuf->getBuffer(), static_cast<int>(pdfBuf->getSize()), nullptr);
    if (!doc) return false;
    FPDF_PAGE pg = FPDF_LoadPage(doc, 0);
    if (!pg) {
        FPDF_CloseDocument(doc);
        return false;
    }
    bool ok = false;
    const int objCount = FPDFPage_CountObjects(pg);
    for (int i = 0; i < objCount; ++i) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(pg, i);
        if (!obj || FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_IMAGE) continue;
        FPDF_BITMAP bmp = FPDFImageObj_GetBitmap(obj);
        if (!bmp) continue;
        const int bw = FPDFBitmap_GetWidth(bmp);
        const int bh = FPDFBitmap_GetHeight(bmp);
        const int fmt = FPDFBitmap_GetFormat(bmp);
        const int stride = FPDFBitmap_GetStride(bmp);
        const auto* rawBuf = static_cast<const uint8_t*>(FPDFBitmap_GetBuffer(bmp));
        if (bw > 0 && bh > 0 && stride > 0 && rawBuf &&
            static_cast<uint64_t>(bw) * static_cast<uint64_t>(bh) <= 100'000'000ULL) {
            int bpp = 0;
            if (fmt == FPDFBitmap_Gray)
                bpp = 1;
            else if (fmt == FPDFBitmap_BGR)
                bpp = 3;
            else if (fmt == FPDFBitmap_BGRx || fmt == FPDFBitmap_BGRA)
                bpp = 4;
            if (bpp > 0 && stride >= bw * bpp) {
                outPixels->resize(static_cast<size_t>(bw) * static_cast<size_t>(bh) *
                                  static_cast<size_t>(bpp));
                const size_t rowBytes = static_cast<size_t>(bw) * static_cast<size_t>(bpp);
                for (int y = 0; y < bh; ++y) {
                    std::memcpy(outPixels->data() + static_cast<size_t>(y) * rowBytes,
                                rawBuf + static_cast<size_t>(y) * static_cast<size_t>(stride),
                                rowBytes);
                }
                *outW = bw;
                *outH = bh;
                *outFmt = fmt;
                ok = true;
            }
        }
        FPDFBitmap_Destroy(bmp);
        break;
    }
    FPDF_ClosePage(pg);
    FPDF_CloseDocument(doc);
    return ok;
}

bool TranscodeSinglePdf14Image(QPDF& pdf, QPDFObjectHandle image) {
    if (!image.isStream()) return false;
    QPDFObjectHandle dict = image.getDict();
    QPDFObjectHandle filter = dict.getKey("/Filter");
    const bool isJbig2 = HasImageFilter(filter, "/JBIG2Decode");
    const bool isJpx = HasImageFilter(filter, "/JPXDecode");
    if (!isJbig2 && !isJpx) return false;

    if (isJbig2) {
        const QPDFObjectHandle wo = dict.getKey("/Width");
        const QPDFObjectHandle ho = dict.getKey("/Height");
        if (!wo.isInteger() || !ho.isInteger()) return false;
        const int64_t w = wo.getIntValue();
        const int64_t h = ho.getIntValue();
        if (w <= 0 || h <= 0 || w > 65535 || h > 65535 || w * h > 100'000'000) return false;

        int bw = 0;
        int bh = 0;
        int fmt = 0;
        std::vector<uint8_t> pixels;
        if (!DecodeSingleImageWithPdfium(image, true, &bw, &bh, &fmt, &pixels)) return false;
        if (bw != w || bh != h) return false;

        const size_t rowBytes = static_cast<size_t>((bw + 7) / 8);
        std::string packed(rowBytes * static_cast<size_t>(bh), '\0');
        const int bpp = (fmt == FPDFBitmap_Gray) ? 1 : (fmt == FPDFBitmap_BGR ? 3 : 4);
        const int chOffset = (bpp == 1) ? 0 : 1;
        for (int y = 0; y < bh; ++y) {
            uint8_t* dstRow =
                reinterpret_cast<uint8_t*>(packed.data()) + static_cast<size_t>(y) * rowBytes;
            const uint8_t* srcRow =
                pixels.data() + static_cast<size_t>(y) * static_cast<size_t>(bw) * bpp;
            for (int x = 0; x < bw; ++x) {
                if (srcRow[static_cast<size_t>(x) * bpp + chOffset] >= 128U) {
                    dstRow[x >> 3] |= static_cast<uint8_t>(0x80U >> (x & 7));
                }
            }
        }
        dict.replaceKey("/BitsPerComponent", QPDFObjectHandle::newInteger(1));
        dict.removeKey("/DecodeParms");
        image.replaceStreamData(packed, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
        return true;
    }

    // JPXDecode path: preserve native /DeviceCMYK if OpenJPEG delivers 4 components cleanly.
    if (Name(dict.getKey("/ColorSpace")) == "/DeviceCMYK" &&
        HasSingleNamedFilter(filter, "/JPXDecode")) {
        std::shared_ptr<Buffer> raw;
        try {
            raw = image.getRawStreamData();
        } catch (...) {
            raw.reset();
        }
        if (raw && raw->getSize() > 0 && raw->getSize() <= kMaxPdfxStreamBytes) {
            uint8_t* rawPixels = nullptr;
            int64_t rawLen = 0;
            int32_t jw = 0;
            int32_t jh = 0;
            int32_t comps = 0;
            if (jpdfium_jpx_to_raw(raw->getBuffer(), static_cast<int64_t>(raw->getSize()),
                                   &rawPixels, &rawLen, &jw, &jh, &comps) == JPDFIUM_OK) {
                if (rawPixels && comps == 4 && jw > 0 && jh > 0 &&
                    rawLen == static_cast<int64_t>(jw) * jh * 4) {
                    std::string cmyk(reinterpret_cast<const char*>(rawPixels),
                                     static_cast<size_t>(rawLen));
                    std::free(rawPixels);
                    dict.replaceKey("/Width", QPDFObjectHandle::newInteger(jw));
                    dict.replaceKey("/Height", QPDFObjectHandle::newInteger(jh));
                    dict.replaceKey("/BitsPerComponent", QPDFObjectHandle::newInteger(8));
                    dict.removeKey("/Decode");
                    dict.removeKey("/DecodeParms");
                    dict.removeKey("/SMaskInData");
                    image.replaceStreamData(cmyk, QPDFObjectHandle::newNull(),
                                            QPDFObjectHandle::newNull());
                    return true;
                }
                std::free(rawPixels);
            }
        }
    }

    int bw = 0;
    int bh = 0;
    int fmt = 0;
    std::vector<uint8_t> pixels;
    if (DecodeSingleImageWithPdfium(image, false, &bw, &bh, &fmt, &pixels)) {
        const size_t nPix = static_cast<size_t>(bw) * static_cast<size_t>(bh);
        if (fmt == FPDFBitmap_Gray) {
            std::string gray(reinterpret_cast<const char*>(pixels.data()), nPix);
            dict.replaceKey("/Width", QPDFObjectHandle::newInteger(bw));
            dict.replaceKey("/Height", QPDFObjectHandle::newInteger(bh));
            dict.replaceKey("/ColorSpace", QPDFObjectHandle::newName("/DeviceGray"));
            dict.replaceKey("/BitsPerComponent", QPDFObjectHandle::newInteger(8));
            dict.removeKey("/Decode");
            dict.removeKey("/DecodeParms");
            dict.removeKey("/SMaskInData");
            image.replaceStreamData(gray, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
            return true;
        }
        std::string rgb(nPix * 3, '\0');
        const int bpp = (fmt == FPDFBitmap_BGR) ? 3 : 4;
        bool hasAlpha = (fmt == FPDFBitmap_BGRA);
        bool anyTransparent = false;
        std::string alpha;
        if (hasAlpha) alpha.resize(nPix);
        for (size_t i = 0; i < nPix; ++i) {
            const uint8_t* p = pixels.data() + i * static_cast<size_t>(bpp);
            rgb[i * 3 + 0] = static_cast<char>(p[2]);
            rgb[i * 3 + 1] = static_cast<char>(p[1]);
            rgb[i * 3 + 2] = static_cast<char>(p[0]);
            if (hasAlpha) {
                alpha[i] = static_cast<char>(p[3]);
                if (p[3] != 0xFFU) anyTransparent = true;
            }
        }
        dict.replaceKey("/Width", QPDFObjectHandle::newInteger(bw));
        dict.replaceKey("/Height", QPDFObjectHandle::newInteger(bh));
        dict.replaceKey("/ColorSpace", QPDFObjectHandle::newName("/DeviceRGB"));
        dict.replaceKey("/BitsPerComponent", QPDFObjectHandle::newInteger(8));
        dict.removeKey("/Decode");
        dict.removeKey("/DecodeParms");
        dict.removeKey("/SMaskInData");
        if (anyTransparent && !dict.getKey("/SMask").isStream()) {
            QPDFObjectHandle smask = QPDFObjectHandle::newStream(&pdf, alpha);
            QPDFObjectHandle sd = smask.getDict();
            sd.replaceKey("/Type", QPDFObjectHandle::newName("/XObject"));
            sd.replaceKey("/Subtype", QPDFObjectHandle::newName("/Image"));
            sd.replaceKey("/Width", QPDFObjectHandle::newInteger(bw));
            sd.replaceKey("/Height", QPDFObjectHandle::newInteger(bh));
            sd.replaceKey("/ColorSpace", QPDFObjectHandle::newName("/DeviceGray"));
            sd.replaceKey("/BitsPerComponent", QPDFObjectHandle::newInteger(8));
            dict.replaceKey("/SMask", smask);
        }
        image.replaceStreamData(rgb, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
        return true;
    }

    // Fallback to OpenJPEG when JP2/J2K uses direct codestream without full PDF wrapper.
    if (HasSingleNamedFilter(filter, "/JPXDecode")) {
        std::shared_ptr<Buffer> raw;
        try {
            raw = image.getRawStreamData();
        } catch (...) {
            raw.reset();
        }
        if (raw && raw->getSize() > 0 && raw->getSize() <= kMaxPdfxStreamBytes) {
            uint8_t* rawPixels = nullptr;
            int64_t rawLen = 0;
            int32_t jw = 0;
            int32_t jh = 0;
            int32_t comps = 0;
            if (jpdfium_jpx_to_raw(raw->getBuffer(), static_cast<int64_t>(raw->getSize()),
                                   &rawPixels, &rawLen, &jw, &jh, &comps) == JPDFIUM_OK) {
                bool ok = false;
                if (rawPixels && jw > 0 && jh > 0 && (comps == 1 || comps == 3 || comps == 4) &&
                    rawLen == static_cast<int64_t>(jw) * jh * comps) {
                    const char* csName =
                        (comps == 1) ? "/DeviceGray" : (comps == 3 ? "/DeviceRGB" : "/DeviceCMYK");
                    std::string bytes(reinterpret_cast<const char*>(rawPixels),
                                      static_cast<size_t>(rawLen));
                    dict.replaceKey("/Width", QPDFObjectHandle::newInteger(jw));
                    dict.replaceKey("/Height", QPDFObjectHandle::newInteger(jh));
                    dict.replaceKey("/ColorSpace", QPDFObjectHandle::newName(csName));
                    dict.replaceKey("/BitsPerComponent", QPDFObjectHandle::newInteger(8));
                    dict.removeKey("/Decode");
                    dict.removeKey("/DecodeParms");
                    dict.removeKey("/SMaskInData");
                    image.replaceStreamData(bytes, QPDFObjectHandle::newNull(),
                                            QPDFObjectHandle::newNull());
                    ok = true;
                }
                std::free(rawPixels);
                return ok;
            }
        }
    }
    return false;
}

void TranscodeLegacyPdf14Images(QPDF& pdf) {
    std::vector<QPDFObjectHandle> imgs;
    for (auto& obj : pdf.getAllObjects()) {
        if (!obj.isStream()) continue;
        QPDFObjectHandle d = obj.getDict();
        if (Name(d.getKey("/Subtype")) != "/Image") continue;
        QPDFObjectHandle filter = d.getKey("/Filter");
        if (HasImageFilter(filter, "/JBIG2Decode") || HasImageFilter(filter, "/JPXDecode")) {
            imgs.push_back(obj);
        }
    }
    for (QPDFObjectHandle& img : imgs) {
        TranscodeSinglePdf14Image(pdf, img);
    }
}

void SplitOversizedCidWidthArrays(QPDF& pdf) {
    constexpr int kMaxSubArray = 4096;
    constexpr int kMaxPdf13Array = 8191;
    for (auto& obj : pdf.getAllObjects()) {
        if (!obj.isDictionary()) continue;
        if (Name(obj.getKey("/Type")) != "/Font") continue;
        const std::string sub = Name(obj.getKey("/Subtype"));
        if (sub != "/CIDFontType0" && sub != "/CIDFontType2") continue;

        QPDFObjectHandle w = obj.getKey("/W");
        if (w.isArray()) {
            const int wn = w.getArrayNItems();
            bool oversized = wn > kMaxPdf13Array;
            if (!oversized) {
                for (int i = 0; i < wn; ++i) {
                    QPDFObjectHandle item = w.getArrayItem(i);
                    if (item.isArray() && item.getArrayNItems() > kMaxSubArray) {
                        oversized = true;
                        break;
                    }
                }
            }
            if (oversized) {
                struct WEntry {
                    bool isRun = false;
                    int64_t c1 = 0;
                    int64_t c2 = 0;
                    double runW = 0.0;
                    std::vector<double> ws;
                };
                std::vector<WEntry> entries;
                for (int i = 0; i < wn;) {
                    QPDFObjectHandle a = w.getArrayItem(i);
                    if (!a.isInteger() || i + 1 >= wn) {
                        ++i;
                        continue;
                    }
                    const int64_t c1 = a.getIntValue();
                    QPDFObjectHandle b = w.getArrayItem(i + 1);
                    if (b.isArray()) {
                        std::vector<double> ws;
                        const int bn = b.getArrayNItems();
                        ws.reserve(static_cast<size_t>(bn));
                        for (int k = 0; k < bn; ++k) {
                            QPDFObjectHandle v = b.getArrayItem(k);
                            ws.push_back(v.isNumber() ? v.getNumericValue() : 0.0);
                        }
                        if (!ws.empty()) {
                            entries.push_back({false, c1, c1 + static_cast<int64_t>(ws.size()) - 1,
                                               0.0, std::move(ws)});
                        }
                        i += 2;
                    } else if (i + 2 < wn && b.isInteger() && w.getArrayItem(i + 2).isNumber()) {
                        entries.push_back({true,
                                           c1,
                                           b.getIntValue(),
                                           w.getArrayItem(i + 2).getNumericValue(),
                                           {}});
                        i += 3;
                    } else {
                        ++i;
                    }
                }
                // Merge consecutive single-range entries to keep outer array compact,
                // and emit runs of >= 3 equal widths as [c1 c2 w] and sub-arrays <= 4096.
                QPDFObjectHandle out = QPDFObjectHandle::newArray();
                auto emitRange = [&](int64_t startCid, const std::vector<double>& ws) {
                    size_t pos = 0;
                    while (pos < ws.size()) {
                        size_t runEnd = pos + 1;
                        while (runEnd < ws.size() && std::abs(ws[runEnd] - ws[pos]) < 1e-6) {
                            ++runEnd;
                        }
                        if (runEnd - pos >= 3) {
                            out.appendItem(
                                QPDFObjectHandle::newInteger(startCid + static_cast<int64_t>(pos)));
                            out.appendItem(QPDFObjectHandle::newInteger(
                                startCid + static_cast<int64_t>(runEnd - 1)));
                            out.appendItem(QPDFObjectHandle::newReal(ws[pos], 4));
                            pos = runEnd;
                            continue;
                        }
                        size_t arrEnd = pos;
                        while (arrEnd < ws.size() &&
                               arrEnd - pos < static_cast<size_t>(kMaxSubArray)) {
                            size_t r = arrEnd + 1;
                            while (r < ws.size() && std::abs(ws[r] - ws[arrEnd]) < 1e-6) ++r;
                            if (r - arrEnd >= 3) break;
                            arrEnd = r;
                        }
                        if (arrEnd == pos) {
                            arrEnd = std::min(ws.size(), pos + static_cast<size_t>(kMaxSubArray));
                        }
                        out.appendItem(
                            QPDFObjectHandle::newInteger(startCid + static_cast<int64_t>(pos)));
                        QPDFObjectHandle subArr = QPDFObjectHandle::newArray();
                        for (size_t k = pos; k < arrEnd; ++k) {
                            subArr.appendItem(QPDFObjectHandle::newReal(ws[k], 4));
                        }
                        out.appendItem(subArr);
                        pos = arrEnd;
                    }
                };
                for (size_t ei = 0; ei < entries.size();) {
                    if (entries[ei].isRun) {
                        out.appendItem(QPDFObjectHandle::newInteger(entries[ei].c1));
                        out.appendItem(QPDFObjectHandle::newInteger(entries[ei].c2));
                        out.appendItem(QPDFObjectHandle::newReal(entries[ei].runW, 4));
                        ++ei;
                    } else {
                        int64_t startCid = entries[ei].c1;
                        std::vector<double> merged = std::move(entries[ei].ws);
                        ++ei;
                        while (ei < entries.size() && !entries[ei].isRun &&
                               entries[ei].c1 == startCid + static_cast<int64_t>(merged.size()) &&
                               merged.size() + entries[ei].ws.size() <= 65536) {
                            merged.insert(merged.end(), entries[ei].ws.begin(),
                                          entries[ei].ws.end());
                            ++ei;
                        }
                        emitRange(startCid, merged);
                    }
                }
                obj.replaceKey("/W", out);
            }
        }

        QPDFObjectHandle w2 = obj.getKey("/W2");
        if (w2.isArray()) {
            const int wn2 = w2.getArrayNItems();
            bool oversized2 = false;
            for (int i = 0; i < wn2; ++i) {
                QPDFObjectHandle item = w2.getArrayItem(i);
                if (item.isArray() && item.getArrayNItems() > 4095) {
                    oversized2 = true;
                    break;
                }
            }
            if (oversized2) {
                QPDFObjectHandle out2 = QPDFObjectHandle::newArray();
                for (int i = 0; i < wn2;) {
                    QPDFObjectHandle a = w2.getArrayItem(i);
                    if (a.isInteger() && i + 1 < wn2 && w2.getArrayItem(i + 1).isArray()) {
                        const int64_t c1 = a.getIntValue();
                        QPDFObjectHandle b = w2.getArrayItem(i + 1);
                        const int bn = b.getArrayNItems();
                        for (int pos = 0; pos < bn; pos += 4095) {
                            const int end = std::min(bn, pos + 4095);
                            out2.appendItem(QPDFObjectHandle::newInteger(c1 + pos / 3));
                            QPDFObjectHandle subArr = QPDFObjectHandle::newArray();
                            for (int k = pos; k < end; ++k) subArr.appendItem(b.getArrayItem(k));
                            out2.appendItem(subArr);
                        }
                        i += 2;
                    } else {
                        out2.appendItem(a);
                        ++i;
                    }
                }
                obj.replaceKey("/W2", out2);
            }
        }
    }
}

bool ReadImageFilters(QPDFObjectHandle const& filter, bool* was_dct) {
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

void ReadImageDecode(QPDFObjectHandle const& dict, const ColourSpaceInfo& cs, Raster* raster) {
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
                const size_t idx = static_cast<size_t>(c);
                in[idx] = raster.decode[idx * 2] +
                          raster.Sample(y, x * 3 + c) / max *
                              (raster.decode[idx * 2 + 1] - raster.decode[idx * 2]);
            }
            std::vector<double> out = ConvertComponents(cs, in, false);
            for (int c = 0; c < 4; ++c)
                dst[c] = static_cast<uint8_t>(std::lround(Clamp01(out[c]) * 255.0));
        }
    }
    return cmyk;
}

QPDFObjectHandle Converter::ColourKeyToStencil(const Raster& raster, QPDFObjectHandle const& mask) {
    int64_t lo[3];
    int64_t hi[3];
    for (int c = 0; c < 3; ++c) {
        QPDFObjectHandle lo_obj = mask.getArrayItem(c * 2);
        QPDFObjectHandle hi_obj = mask.getArrayItem(c * 2 + 1);
        if (!lo_obj.isInteger() || !hi_obj.isInteger()) return {};
        lo[c] = lo_obj.getIntValue();
        hi[c] = hi_obj.getIntValue();
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
            if (keyed) {
                const size_t bi = static_cast<size_t>(y * mask_row + x / 8);
                const auto current = std::bit_cast<unsigned char>(bits[bi]);
                bits[bi] =
                    std::bit_cast<char>(static_cast<unsigned char>(current | (0x80u >> (x % 8))));
            }
        }
    }
    QPDFObjectHandle stencil = QPDFObjectHandle::newStream(&pdf_, bits);
    QPDFObjectHandle sd = stencil.getDict();
    sd.replaceKey("/Type", QPDFObjectHandle::newName("/XObject"));
    sd.replaceKey("/Subtype", QPDFObjectHandle::newName("/Image"));
    sd.replaceKey("/Width", QPDFObjectHandle::newInteger(raster.width));
    sd.replaceKey("/Height", QPDFObjectHandle::newInteger(raster.height));
    sd.replaceKey("/BitsPerComponent", QPDFObjectHandle::newInteger(1));
    sd.replaceKey("/ImageMask", QPDFObjectHandle::newBool(true));
    return stencil;
}

void Converter::ConvertMatte(QPDFObjectHandle const& smask, const ColourSpaceInfo& cs) {
    if (!smask.isStream()) return;
    QPDFObjectHandle matte = smask.getDict().getKey("/Matte");
    if (!matte.isArray() || matte.getArrayNItems() != 3) return;
    std::vector<double> mv;
    for (int i = 0; i < 3; ++i) {
        if (!matte.getArrayItem(i).isNumber()) return;
        mv.push_back(matte.getArrayItem(i).getNumericValue());
    }
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

bool Converter::ConvertImagePixels(QPDFObjectHandle const& image, const ColourSpaceInfo& cs) {
    QPDFObjectHandle dict = image.getDict();
    if (HasImageFilter(dict.getKey("/Filter"), "/JPXDecode") ||
        HasImageFilter(dict.getKey("/Filter"), "/JBIG2Decode")) {
        if (!TranscodeSinglePdf14Image(pdf_, image)) return false;
        const std::string newCs = Name(dict.getKey("/ColorSpace"));
        if (newCs == "/DeviceGray" || newCs == "/DeviceCMYK") return true;
        ColourSpaceInfo rgbCs;
        rgbCs.kind = Kind::Rgb;
        rgbCs.replacement = QPDFObjectHandle::newName("/DeviceCMYK");
        return ConvertImagePixels(image, rgbCs);
    }
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
    if (mask.isArray()) {
        QPDFObjectHandle stencil = ColourKeyToStencil(raster, mask);
        if (!stencil.isInitialized()) return false;
        dict.replaceKey("/Mask", stencil);
    }
    ConvertMatte(dict.getKey("/SMask"), cs);
    StoreCmyk(image, cmyk, raster.width, raster.height, was_dct);
    return true;
}

void Converter::ProcessResources(Resources& res, int depth) {
    // Guard against cyclic /Resources graphs; ISO 32000 nesting rarely exceeds a handful of levels.
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
            // PostScript XObjects (/PS) and /OPI proxies are forbidden in PDF/X; replace with empty
            // zero-BBox form placeholders.
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
            x.getDict().removeKey("/OPI");
            if (subtype == "/Image") {
                ConvertImage(x);
            } else if (subtype == "/Form") {
                ProcessContentHolder(x, res.dict, depth + 1);
            }
        }
    }
    QPDFObjectHandle shadings = res.dict.getKey("/Shading");
    if (shadings.isDictionary()) {
        for (const std::string& key : shadings.getKeys()) {
            ConvertShading(shadings.getKey(key));
        }
    }
    QPDFObjectHandle patterns = res.dict.getKey("/Pattern");
    if (patterns.isDictionary()) {
        // Clamp pattern /XStep / /YStep and sanitize Type 2 shading dictionaries recursively.
        for (const std::string& key : patterns.getKeys()) {
            QPDFObjectHandle pat = patterns.getKey(key);
            QPDFObjectHandle pat_dict = pat.isStream() ? pat.getDict() : pat;
            if (pat_dict.isDictionary()) {
                ClampPatternSteps(pat);
                if (pat_dict.getKey("/PatternType").isInteger() &&
                    pat_dict.getKey("/PatternType").getIntValueAsInt() == 2) {
                    ConvertShading(pat_dict.getKey("/Shading"));
                }
            }
            if (pat.isStream()) ProcessContentHolder(pat, res.dict, depth + 1);
        }
    }
    QPDFObjectHandle fonts = res.dict.getKey("/Font");
    if (fonts.isDictionary()) {
        // Rewrite Type 3 /CharProcs content streams too, since their strokes inherit graphics
        // color.
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
                std::string content;
                if (!ReadStreamBounded(proc, content, qpdf_dl_all, kMaxPdfxStreamBytes)) continue;
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
            gs.removeKey("/TR");
            QPDFObjectHandle tr2 = gs.getKey("/TR2");
            if (!tr2.isNull() && Name(tr2) != "/Default") gs.removeKey("/TR2");
            QPDFObjectHandle opm = gs.getKey("/OPM");
            if (opm.isInteger() && opm.getIntValue() != 0) {
                gs.replaceKey("/OPM", QPDFObjectHandle::newInteger(0));
            }
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
                    bool valid_bc = true;
                    std::vector<double> v;
                    for (int i = 0; i < 3; ++i) {
                        if (!bc.getArrayItem(i).isNumber()) {
                            valid_bc = false;
                            break;
                        }
                        v.push_back(bc.getArrayItem(i).getNumericValue());
                    }
                    if (valid_bc) {
                        std::vector<double> c = ConvertComponents(gcs, v, false);
                        QPDFObjectHandle arr = QPDFObjectHandle::newArray();
                        for (double x : c) arr.appendItem(QPDFObjectHandle::newReal(x, 4));
                        smask.replaceKey("/BC", arr);
                    }
                }
                ProcessContentHolder(group, res.dict, depth + 1);
            }
        }
    }
}

void Converter::ProcessContentHolder(QPDFObjectHandle stream, QPDFObjectHandle const& inherited,
                                     int depth) {
    if (!stream.isStream() || depth > 32) return;
    if (stream.isIndirect() && !done_streams_.insert(stream.getObjGen()).second) return;
    QPDFObjectHandle own = stream.getDict().getKey("/Resources");
    Resources res = PrepareResources(own.isDictionary() ? own : inherited);
    ConvertGroup(stream);
    ProcessResources(res, depth);
    std::string content;
    if (!ReadStreamBounded(stream, content, qpdf_dl_all, kMaxPdfxStreamBytes)) return;
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
        try {
            BoundedStringPipeline sink("pdfx-page", kMaxPdfxStreamBytes);
            page.pipeContents(&sink);
            std::string content = sink.take();
            bool changed = false;
            std::string out = FilterContent(content, res, &changed);
            if (changed) dict.replaceKey("/Contents", QPDFObjectHandle::newStream(&pdf_, out));
        } catch (std::exception& e) {
            std::fprintf(stderr, "jpdfium pdfx page content: %s\n", e.what());
        }
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

bool Converter::AuditResources(QPDFObjectHandle const& resources, std::set<QPDFObjGen>* seen,
                               std::string* why, int depth) {
    // Reject transparency, non-Normal blend modes, and PDF 1.4+ filters forbidden in PDF/X-1a/3.
    if (!resources.isDictionary() || depth > 32 || seen->size() > 20000) return false;
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
            // JBIG2Decode / JPXDecode and image /SMask /SMaskInData require PDF 1.4+
            // transparency/filters.
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
                    bool all_opaque = false;
                    std::string sm;
                    if (ReadStreamBounded(d.getKey("/SMask"), sm, qpdf_dl_all,
                                          kMaxPdfxStreamBytes) &&
                        !sm.empty()) {
                        all_opaque = true;
                        for (unsigned char b : sm) {
                            if (b != 0xFF) {
                                all_opaque = false;
                                break;
                            }
                        }
                    }
                    if (all_opaque) {
                        d.removeKey("/SMask");
                    } else {
                        *why = "an image with a soft mask (" + key + ")";
                        return true;
                    }
                }
                QPDFObjectHandle sid = d.getKey("/SMaskInData");
                if (sid.isInteger() && sid.getIntValue() > 0) {
                    *why = "an image with alpha in its codestream (" + key + ")";
                    return true;
                }
            } else if ((!x.isIndirect() || seen->insert(x.getObjGen()).second) &&
                       AuditResources(d.getKey("/Resources"), seen, why, depth + 1)) {
                return true;
            }
        }
    }
    QPDFObjectHandle patterns = resources.getKey("/Pattern");
    if (patterns.isDictionary()) {
        // Recurse into tiling pattern resource trees and local ExtGState dictionaries.
        for (const std::string& key : patterns.getKeys()) {
            QPDFObjectHandle pat = patterns.getKey(key);
            if (pat.isIndirect() && !seen->insert(pat.getObjGen()).second) continue;
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
            if (font.isIndirect() && !seen->insert(font.getObjGen()).second) continue;
            if (font.isDictionary() &&
                AuditResources(font.getKey("/Resources"), seen, why, depth + 1)) {
                return true;
            }
        }
    }
    return false;
}

void Converter::RemoveTransparencyGroups(QPDFObjectHandle const& resources,
                                         std::set<QPDFObjGen>* seen, int depth) {
    if (!resources.isDictionary() || depth > 32 || seen->size() > 20000) return;
    if (resources.isIndirect() && !seen->insert(resources.getObjGen()).second) return;
    QPDFObjectHandle xobjects = resources.getKey("/XObject");
    if (!xobjects.isDictionary()) return;
    for (const std::string& key : xobjects.getKeys()) {
        QPDFObjectHandle x = xobjects.getKey(key);
        if (!x.isStream()) continue;
        if (x.isIndirect() && !seen->insert(x.getObjGen()).second) continue;
        QPDFObjectHandle d = x.getDict();
        if (Name(d.getKey("/Subtype")) != "/Form") continue;
        QPDFObjectHandle group = d.getKey("/Group");
        if (group.isDictionary() && Name(group.getKey("/S")) == "/Transparency")
            d.removeKey("/Group");
        RemoveTransparencyGroups(d.getKey("/Resources"), seen, depth + 1);
    }
}

bool Converter::AuditAnnotationEntry(QPDFObjectHandle const& entry, std::set<QPDFObjGen>* seen,
                                     std::string* why, int depth) {
    if (depth > 8) return false;
    if (entry.isStream()) {
        return AuditResources(entry.getDict().getKey("/Resources"), seen, why, depth + 1);
    }
    if (entry.isDictionary()) {
        for (const std::string& state : entry.getKeys()) {
            if (AuditAnnotationEntry(entry.getKey(state), seen, why, depth + 1)) return true;
        }
    }
    return false;
}

void Converter::AuditTransparency() {
    QPDFPageDocumentHelper pages(pdf_);
    int index = 0;
    int first_page = 0;
    std::string first_why;
    std::vector<int> bad_pages;
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
                if (!transparent && ap.isDictionary()) {
                    for (const char* sub : {"/N", "/R", "/D"}) {
                        if (AuditAnnotationEntry(ap.getKey(sub), &seen, &why, 0)) {
                            transparent = true;
                            break;
                        }
                    }
                }
            }
        }
        if (transparent) {
            if (first_page == 0) {
                first_page = index;
                first_why = why;
            }
            bad_pages.push_back(index);
            continue;
        }
        QPDFObjectHandle group = dict.getKey("/Group");
        if (group.isDictionary() && Name(group.getKey("/S")) == "/Transparency")
            dict.removeKey("/Group");
        std::set<QPDFObjGen> seen_groups;
        RemoveTransparencyGroups(resources, &seen_groups, 0);
    }
    if (!bad_pages.empty()) {
        std::string csv;
        for (size_t i = 0; i < bad_pages.size();) {
            size_t j = i;
            while (j + 1 < bad_pages.size() && bad_pages[j + 1] == bad_pages[j] + 1) ++j;
            if (!csv.empty()) csv.push_back(',');
            if (j == i) {
                csv += std::to_string(bad_pages[i]);
            } else {
                csv += std::to_string(bad_pages[i]) + "-" + std::to_string(bad_pages[j]);
            }
            i = j + 1;
        }
        throw PdfxNonconformance(
            "page " + std::to_string(first_page) + " uses " + first_why +
            ", which this PDF/X level forbids; flatten transparency first [pages:" + csv + "]");
    }
}

std::string Converter::Report() const {
    return "vector=" + std::to_string(vector_ops_) + " images=" + std::to_string(images_) +
           " images_skipped=" + std::to_string(images_skipped_) +
           " spaces=" + std::to_string(spaces_);
}

constexpr size_t kMaxActionTraversalNodes = 16384;

bool IsForbiddenActionImpl(QPDFObjectHandle const& action, std::set<QPDFObjGen>& visited,
                           size_t& nodeCount, int depth) {
    if (depth > 16 || nodeCount >= kMaxActionTraversalNodes) return false;
    if (action.isIndirect() && !visited.insert(action.getObjGen()).second) return false;
    ++nodeCount;
    if (action.isArray()) {
        const int nItems = std::min(action.getArrayNItems(), 4096);
        for (int i = 0; i < nItems && nodeCount < kMaxActionTraversalNodes; ++i) {
            if (IsForbiddenActionImpl(action.getArrayItem(i), visited, nodeCount, depth + 1)) {
                return true;
            }
        }
        return false;
    }
    if (!action.isDictionary()) return false;
    const std::string s = Name(action.getKey("/S"));
    if (s == "/JavaScript" || s == "/Launch" || s == "/Sound" || s == "/Movie" ||
        s == "/ResetForm" || s == "/ImportData" || s == "/Rendition" || s == "/Trans" ||
        s == "/GoTo3DView") {
        return true;
    }
    return IsForbiddenActionImpl(action.getKey("/Next"), visited, nodeCount, depth + 1);
}

bool IsForbiddenAction(QPDFObjectHandle const& action) {
    std::set<QPDFObjGen> visited;
    size_t nodeCount = 0;
    return IsForbiddenActionImpl(action, visited, nodeCount, 0);
}

void StripActionsImpl(QPDFObjectHandle dict, std::set<QPDFObjGen>& visited, size_t& nodeCount,
                      int depth) {
    if (!dict.isDictionary() || depth > 16 || nodeCount >= kMaxActionTraversalNodes) return;
    if (dict.isIndirect() && !visited.insert(dict.getObjGen()).second) return;
    ++nodeCount;
    dict.removeKey("/AA");
    if (IsForbiddenAction(dict.getKey("/A"))) dict.removeKey("/A");
    QPDFObjectHandle kids = dict.getKey("/Kids");
    if (kids.isArray()) {
        const int nKids = std::min(kids.getArrayNItems(), 4096);
        for (int i = 0; i < nKids && nodeCount < kMaxActionTraversalNodes; ++i) {
            StripActionsImpl(kids.getArrayItem(i), visited, nodeCount, depth + 1);
        }
    }
}

void StripActions(QPDFObjectHandle const& dict) {
    std::set<QPDFObjGen> visited;
    size_t nodeCount = 0;
    StripActionsImpl(dict, visited, nodeCount, 0);
}

void RemoveForbiddenActionsAndFiles(QPDF& pdf, Level level) {
    QPDFObjectHandle root = pdf.getRoot();
    QPDFObjectHandle names = root.getKey("/Names");
    if (names.isDictionary()) {
        names.removeKey("/JavaScript");
        if (level == Level::X1a || level == Level::X3 || level == Level::X4) {
            names.removeKey("/EmbeddedFiles");
        }
    }
    if (level == Level::X1a || level == Level::X3 || level == Level::X4) {
        root.removeKey("/AF");
    }
    if (IsForbiddenAction(root.getKey("/OpenAction"))) root.removeKey("/OpenAction");
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
        QPDFObjectHandle kept = QPDFObjectHandle::newArray();
        for (int i = 0; i < annots.getArrayNItems(); ++i) {
            QPDFObjectHandle annot = annots.getArrayItem(i);
            if (!annot.isDictionary()) continue;
            const std::string subtype = Name(annot.getKey("/Subtype"));
            // ISO 15930 forbids interactive/link/action annotations inside the print area;
            // only PrinterMark and TrapNet annotations are safe to retain after flattening.
            if (subtype == "/TrapNet" || subtype == "/PrinterMark") {
                if (annot.hasKey("/A")) annot.removeKey("/A");
                if (annot.hasKey("/AA")) annot.removeKey("/AA");
                kept.appendItem(annot);
            }
        }
        if (kept.getArrayNItems() > 0) {
            page.getObjectHandle().replaceKey("/Annots", kept);
        } else {
            page.getObjectHandle().removeKey("/Annots");
        }
    }
    // Strip /A and /AA from any orphaned /Link or annotation objects still reachable
    // via /StructTreeRoot /OBJR, and strip forbidden transfer functions (/TR, /TR2)
    // from ExtGState dicts, in a single pass over pdf.getAllObjects().
    for (QPDFObjectHandle& obj : pdf.getAllObjects()) {
        if (obj.isDictionary()) {
            const std::string sub = Name(obj.getKey("/Subtype"));
            if (sub == "/Link" || sub == "/Widget" || sub == "/Screen" || sub == "/Sound" ||
                sub == "/Movie" || sub == "/3D" || sub == "/FileAttachment") {
                if (obj.hasKey("/A")) obj.removeKey("/A");
                if (obj.hasKey("/AA")) obj.removeKey("/AA");
            }
        }
        QPDFObjectHandle d = obj.isStream()
                                 ? obj.getDict()
                                 : (obj.isDictionary() ? obj : QPDFObjectHandle::newNull());
        if (d.isNull()) continue;
        if (d.hasKey("/TR")) d.removeKey("/TR");
        if (d.hasKey("/TR2")) d.removeKey("/TR2");
    }
    QPDFObjectHandle oc = pdf.getRoot().getKey("/OCProperties");
    if (oc.isDictionary()) {
        std::vector<QPDFObjectHandle> allOcgs;
        if (oc.hasKey("/OCGs") && oc.getKey("/OCGs").isArray()) {
            QPDFObjectHandle ocgs = oc.getKey("/OCGs");
            for (int i = 0; i < ocgs.getArrayNItems(); ++i) allOcgs.push_back(ocgs.getArrayItem(i));
        }
        auto fixOrder = [&](QPDFObjectHandle const& cfg) {
            if (!cfg.isDictionary() || !cfg.hasKey("/Order") || !cfg.getKey("/Order").isArray() ||
                allOcgs.empty())
                return;
            QPDFObjectHandle order = cfg.getKey("/Order");
            std::set<QPDFObjGen> inOrder;
            std::set<QPDFObjGen> visitedArrs;
            size_t orderNodes = 0;
            std::function<void(QPDFObjectHandle const&, int)> collect =
                [&](QPDFObjectHandle const& arr, int depth) {
                    if (!arr.isArray() || depth > 32 || orderNodes >= 65536) return;
                    if (arr.isIndirect() && !visitedArrs.insert(arr.getObjGen()).second) return;
                    ++orderNodes;
                    const int n = std::min(arr.getArrayNItems(), 8192);
                    for (int i = 0; i < n && orderNodes < 65536; ++i) {
                        QPDFObjectHandle it = arr.getArrayItem(i);
                        if (it.isIndirect()) inOrder.insert(it.getObjGen());
                        if (it.isArray()) collect(it, depth + 1);
                    }
                };
            collect(order, 0);
            for (auto& ocg : allOcgs) {
                if (ocg.isIndirect() && inOrder.insert(ocg.getObjGen()).second) {
                    order.appendItem(ocg);
                }
            }
        };
        QPDFObjectHandle d = oc.getKey("/D");
        if (d.isDictionary()) {
            if (!d.hasKey("/Name") || !d.getKey("/Name").isString() ||
                d.getKey("/Name").getStringValue().empty()) {
                d.replaceKey("/Name", QPDFObjectHandle::newString("Default"));
            }
            if (d.hasKey("/AS")) d.removeKey("/AS");
            fixOrder(d);
        }
        if (oc.hasKey("/Configs") && oc.getKey("/Configs").isArray()) {
            QPDFObjectHandle cfgs = oc.getKey("/Configs");
            for (int i = 0; i < cfgs.getArrayNItems(); ++i) {
                QPDFObjectHandle c = cfgs.getArrayItem(i);
                if (c.isDictionary()) {
                    if (!c.hasKey("/Name") || !c.getKey("/Name").isString() ||
                        c.getKey("/Name").getStringValue().empty()) {
                        c.replaceKey("/Name",
                                     QPDFObjectHandle::newString("Config" + std::to_string(i + 1)));
                    }
                    if (c.hasKey("/AS")) c.removeKey("/AS");
                    fixOrder(c);
                }
            }
        }
    }
}

void RemoveOptionalContent(QPDF& pdf, bool allow_strip_hidden) {
    QPDFObjectHandle root = pdf.getRoot();
    QPDFObjectHandle oc = root.getKey("/OCProperties");
    if (!oc.isDictionary()) return;
    if (!allow_strip_hidden) {
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
    }
    root.removeKey("/OCProperties");
}

void FixPageBoxes(QPDF& pdf) {
    QPDFPageDocumentHelper pages(pdf);
    for (QPDFPageObjectHelper& page : pages.getAllPages()) {
        QPDFObjectHandle dict = page.getObjectHandle();
        if (dict.hasKey("/TrimBox") && dict.hasKey("/ArtBox")) dict.removeKey("/ArtBox");
        QPDFObjectHandle media = page.getAttribute("/MediaBox", true);
        if (!media.isArray() || media.getArrayNItems() != 4) continue;
        double mx[4]{};
        bool valid_media = true;
        for (int i = 0; i < 4; ++i) {
            if (!media.getArrayItem(i).isNumber()) {
                valid_media = false;
                break;
            }
            mx[i] = media.getArrayItem(i).getNumericValue();
        }
        if (!valid_media) continue;
        const double mlx = std::min(mx[0], mx[2]);
        const double mly = std::min(mx[1], mx[3]);
        const double mux = std::max(mx[0], mx[2]);
        const double muy = std::max(mx[1], mx[3]);
        for (const char* key : {"/CropBox", "/BleedBox", "/TrimBox", "/ArtBox"}) {
            QPDFObjectHandle box = dict.getKey(key);
            if (!box.isArray() || box.getArrayNItems() != 4) continue;
            double bx[4]{};
            bool valid_box = true;
            for (int i = 0; i < 4; ++i) {
                if (!box.getArrayItem(i).isNumber()) {
                    valid_box = false;
                    break;
                }
                bx[i] = box.getArrayItem(i).getNumericValue();
            }
            if (!valid_box) continue;
            double nx0 = std::clamp(std::min(bx[0], bx[2]), mlx, mux);
            double ny0 = std::clamp(std::min(bx[1], bx[3]), mly, muy);
            double nx1 = std::clamp(std::max(bx[0], bx[2]), mlx, mux);
            double ny1 = std::clamp(std::max(bx[1], bx[3]), mly, muy);
            if (nx1 <= nx0 || ny1 <= ny0) {
                nx0 = mlx;
                ny0 = mly;
                nx1 = mux;
                ny1 = muy;
            }
            if (std::fabs(nx0 - bx[0]) > 1e-6 || std::fabs(ny0 - bx[1]) > 1e-6 ||
                std::fabs(nx1 - bx[2]) > 1e-6 || std::fabs(ny1 - bx[3]) > 1e-6) {
                QPDFObjectHandle clamped = QPDFObjectHandle::newArray();
                for (double v : {nx0, ny0, nx1, ny1}) {
                    clamped.appendItem(QPDFObjectHandle::newReal(v, 4));
                }
                dict.replaceKey(key, clamped);
            }
        }
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

void SanitizeOutlines(QPDF& pdf) {
    QPDFObjectHandle outlines = pdf.getRoot().getKey("/Outlines");
    if (!outlines.isDictionary()) return;
    std::set<QPDFObjGen> visited;
    if (outlines.isIndirect()) visited.insert(outlines.getObjGen());
    auto walk = [&](auto&& self, QPDFObjectHandle parent, int depth) -> void {
        if (!parent.isDictionary() || depth > 64) return;
        QPDFObjectHandle cur = parent.getKey("/First");
        QPDFObjectHandle prev;
        while (cur.isDictionary()) {
            if (cur.isIndirect() && !visited.insert(cur.getObjGen()).second) {
                if (prev.isDictionary())
                    prev.removeKey("/Next");
                else
                    parent.removeKey("/First");
                break;
            }
            QPDFObjectHandle title = cur.getKey("/Title");
            if (!title.isString() || title.getUTF8Value().empty()) {
                cur.replaceKey("/Title", QPDFObjectHandle::newUnicodeString("Section"));
            }
            self(self, cur, depth + 1);
            prev = cur;
            cur = cur.getKey("/Next");
        }
    };
    walk(walk, outlines, 0);
}

bool IsValidUtf8Str(const std::string& s) {
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        if (c < 0x80) {
            ++i;
            continue;
        }
        size_t need = 0;
        if ((c & 0xE0) == 0xC0)
            need = 1;
        else if ((c & 0xF0) == 0xE0)
            need = 2;
        else if ((c & 0xF8) == 0xF0)
            need = 3;
        else
            return false;
        if (i + need >= n) return false;
        for (size_t k = 1; k <= need; ++k) {
            if ((static_cast<unsigned char>(s[i + k]) & 0xC0) != 0x80) return false;
        }
        i += need + 1;
    }
    return true;
}

void SanitizeAndUnifySeparations(QPDF& pdf) {
    std::map<std::string, QPDFObjectHandle> canonicalSep;
    int renamedIdx = 0;
    for (auto& obj : pdf.getAllObjects()) {
        if (!obj.isArray() || obj.getArrayNItems() < 4) continue;
        const std::string fam = Name(obj.getArrayItem(0));
        if (fam == "/Separation") {
            QPDFObjectHandle nameObj = obj.getArrayItem(1);
            if (!nameObj.isName()) continue;
            std::string cname = nameObj.getName();
            if (!IsValidUtf8Str(cname)) {
                cname = "/SpotColor_" + std::to_string(renamedIdx++);
                obj.setArrayItem(1, QPDFObjectHandle::newName(cname));
            }
            if (cname != "/All" && cname != "/None") {
                auto it = canonicalSep.find(cname);
                if (it == canonicalSep.end()) {
                    canonicalSep[cname] = obj;
                } else {
                    // Unify alternate space and tint transform with canonical definition.
                    obj.setArrayItem(2, it->second.getArrayItem(2));
                    obj.setArrayItem(3, it->second.getArrayItem(3));
                }
            }
        } else if (fam == "/DeviceN" && obj.getArrayItem(1).isArray()) {
            QPDFObjectHandle names = obj.getArrayItem(1);
            for (int i = 0; i < names.getArrayNItems(); ++i) {
                QPDFObjectHandle n = names.getArrayItem(i);
                if (n.isName() && !IsValidUtf8Str(n.getName())) {
                    names.setArrayItem(
                        i, QPDFObjectHandle::newName("/SpotColor_" + std::to_string(renamedIdx++)));
                }
            }
        }
    }
}

void Normalise(QPDF& pdf, const Options& options) {
    try {
        pdf.pushInheritedAttributesToPage();
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium pdfx pushInheritedAttributesToPage: %s\n", e.what());
    }
    RemoveForbiddenActionsAndFiles(pdf, options.level);
    SanitizeOutlines(pdf);
    FlattenPrintableAnnotations(pdf);
    TranscodeLegacyPdf14Images(pdf);
    if (ForbidsTransparency(options.level)) {
        RemoveOptionalContent(pdf, options.allow_strip_oc);
        SplitOversizedCidWidthArrays(pdf);
        // Drop /StructTreeRoot when oversized arrays (> 8191) violate PDF 1.3/1.4 limits.
        if (pdf.getRoot().hasKey("/StructTreeRoot")) {
            bool oversized = false;
            for (auto& obj : pdf.getAllObjects()) {
                if (obj.isArray() && obj.getArrayNItems() > 8191) {
                    oversized = true;
                    break;
                }
            }
            if (oversized) {
                pdf.getRoot().removeKey("/StructTreeRoot");
                pdf.getRoot().removeKey("/MarkInfo");
            }
        }
    }
    FixPageBoxes(pdf);
    Converter converter(pdf, options);
    if (options.convert_colours) converter.Run();
    SanitizeAndUnifySeparations(pdf);
    if (options.strict_images && converter.SkippedImages() > 0) {
        throw PdfxNonconformance(
            std::to_string(converter.SkippedImages()) +
            " image(s) could not be converted to the output intent: " + converter.Report());
    }
    if (ForbidsTransparency(options.level)) converter.AuditTransparency();
}

void ConfigureWriter(QPDFWriter& writer, const Options& options) {
    writer.setPreserveEncryption(false);
    writer.setDecodeLevel(qpdf_dl_none);
    writer.setCompressStreams(true);
    writer.setObjectStreamMode((options.level == Level::X1a || options.level == Level::X3)
                                   ? qpdf_o_disable
                                   : qpdf_o_preserve);
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
    o.allow_strip_oc = (flags & JPDFIUM_PDFX_ALLOW_STRIP_OC) != 0;
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
        ConfigureWriter(writer, options);
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
                ConfigureWriter(writer, options);
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
