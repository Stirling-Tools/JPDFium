// jpdfium_qpdf_dedupe.cpp - Structural resource & indirect object deduplication
// and merged-subset font tag disambiguation.
#include "jpdfium_qpdf_internal.h"

#ifdef JPDFIUM_HAS_QPDF

#include <cstdint>
#include <cstring>
#include <functional>
#include <map>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <qpdf/QPDFPageObjectHelper.hh>
#include <set>
#include <string>
#include <string_view>
#include <vector>

namespace jpdfium::qpdf_detail {

namespace {

uint64_t fnv1aUpdate(uint64_t h, const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; ++i) {
        h ^= data[i];
        h *= 1099511628211ULL;
    }
    return h;
}

uint64_t fnv1aUpdateStr(uint64_t h, std::string_view s) {
    return fnv1aUpdate(h, reinterpret_cast<const uint8_t*>(s.data()), s.size());
}

void collectProtectedGens(QPDFObjectHandle const& obj, std::set<QPDFObjGen>& protectedGens,
                          std::set<QPDFObjGen>& visited, int depth) {
    if (!obj.isInitialized() || depth > 48) return;
    if (obj.isIndirect()) {
        if (!visited.insert(obj.getObjGen()).second) return;
        protectedGens.insert(obj.getObjGen());
    }
    if (obj.isArray()) {
        int n = obj.getArrayNItems();
        for (int i = 0; i < n; ++i) {
            collectProtectedGens(obj.getArrayItem(i), protectedGens, visited, depth + 1);
        }
        return;
    }
    if (obj.isDictionary()) {
        for (auto const& [k, v] : obj.getDictAsMap()) {
            // Do not chase /Resources links from Page/Annot/Field back into shareable assets.
            if (k == "/Resources" || k == "/Parent" || k == "/P") continue;
            collectProtectedGens(v, protectedGens, visited, depth + 1);
        }
    }
}

void collectProtectedProperties(QPDFObjectHandle const& resources,
                                std::set<QPDFObjGen>& protectedGens, std::set<QPDFObjGen>& visited,
                                int depth) {
    if (!resources.isDictionary() || depth > 16) return;
    if (resources.isIndirect() && !visited.insert(resources.getObjGen()).second) return;
    auto props = resources.getKey("/Properties");
    if (props.isDictionary()) {
        for (auto const& [name, val] : props.getDictAsMap()) {
            (void)name;
            QPDFObjectHandle v = val;
            if (v.isIndirect()) protectedGens.insert(v.getObjGen());
        }
    }
    auto xobjs = resources.getKey("/XObject");
    if (xobjs.isDictionary()) {
        for (auto const& [name, val] : xobjs.getDictAsMap()) {
            (void)name;
            QPDFObjectHandle xobj = val;
            if (xobj.isStream() && xobj.getDict().getKey("/Subtype").isNameAndEquals("/Form")) {
                collectProtectedProperties(xobj.getDict().getKey("/Resources"), protectedGens,
                                           visited, depth + 1);
            }
        }
    }
}

bool isIdentitySensitiveObject(QPDFObjectHandle const& obj) {
    if (!obj.isDictionary()) return false;
    static const char* const kProtectedTypes[] = {
        "/Catalog", "/Pages", "/Page", "/StructTreeRoot", "/StructElem", "/OCG",
        "/OCMD",    "/Annot", "/Sig",  "/Filespec",       "/Outlines",
    };
    auto type = obj.getKey("/Type");
    if (type.isName()) {
        for (char const* t : kProtectedTypes) {
            if (type.isNameAndEquals(t)) return true;
        }
    }
    // Backpointers, linked-list pointers, or structure parent/kid chains are object-identity keyed.
    for (char const* k : {"/Parent", "/P", "/Prev", "/Next", "/First", "/Last", "/Kids"}) {
        if (obj.hasKey(k)) return true;
    }
    return false;
}

bool serializeCanonical(QPDFObjectHandle const& obj, std::string& out,
                        bool skipStreamLength = false, int depth = 0) {
    if (depth > 48 || !obj.isInitialized()) return false;
    if (depth > 0 && obj.isIndirect()) {
        auto id = obj.getObjGen();
        out += "R(" + std::to_string(id.getObj()) + "," + std::to_string(id.getGen()) + ")";
        return true;
    }
    if (obj.isArray()) {
        out.push_back('[');
        int n = obj.getArrayNItems();
        for (int i = 0; i < n; ++i) {
            if (i > 0) out.push_back(' ');
            if (!serializeCanonical(obj.getArrayItem(i), out, false, depth + 1)) return false;
        }
        out.push_back(']');
        return true;
    }
    if (obj.isDictionary() || obj.isStream()) {
        QPDFObjectHandle dict = obj.isStream() ? obj.getDict() : obj;
        out += "<<";
        for (auto const& [k, v] : dict.getDictAsMap()) {
            if (skipStreamLength && k == "/Length") continue;
            // QPDF exposes decoded key bytes. Length-prefix them so a decoded
            // key cannot collide with a differently encoded key sequence.
            out += std::to_string(k.size());
            out.push_back(':');
            out += k;
            out.push_back(' ');
            if (!serializeCanonical(v, out, false, depth + 1)) return false;
            out.push_back(' ');
        }
        out += ">>";
        return true;
    }
    if (obj.isNull() || obj.isBool() || obj.isInteger() || obj.isReal() || obj.isName() ||
        obj.isString()) {
        try {
            out += obj.unparse();
            return true;
        } catch (...) {
            return false;
        }
    }
    return false;
}

bool streamsEqual(QPDFObjectHandle canonical, QPDFObjectHandle candidate,
                  std::string const& canonicalDict, std::string const& candidateDict) {
    if (canonicalDict != candidateDict) return false;
    try {
        auto a = canonical.getRawStreamData();
        auto b = candidate.getRawStreamData();
        if (!a || !b) return false;
        if (a->getSize() != b->getSize()) return false;
        if (a->getSize() == 0) return true;
        return std::memcmp(a->getBuffer(), b->getBuffer(), a->getSize()) == 0;
    } catch (...) {
        return false;
    }
}

void rewriteReferences(QPDFObjectHandle obj,
                       std::map<QPDFObjGen, QPDFObjectHandle> const& redirects, int depth = 0) {
    if (depth > 48 || !obj.isInitialized()) return;
    if (obj.isArray()) {
        int n = obj.getArrayNItems();
        for (int i = 0; i < n; ++i) {
            QPDFObjectHandle item = obj.getArrayItem(i);
            if (item.isIndirect()) {
                auto it = redirects.find(item.getObjGen());
                if (it != redirects.end()) obj.setArrayItem(i, it->second);
            } else if (item.isArray() || item.isDictionary()) {
                rewriteReferences(item, redirects, depth + 1);
            }
        }
        return;
    }
    if (obj.isDictionary() || obj.isStream()) {
        QPDFObjectHandle dict = obj.isStream() ? obj.getDict() : obj;
        // Snapshot keys before mutating dictionary entries.
        std::vector<std::string> keys;
        keys.reserve(dict.getDictAsMap().size());
        for (auto const& [k, v] : dict.getDictAsMap()) {
            (void)v;
            keys.push_back(k);
        }
        for (std::string const& k : keys) {
            // Keep /Properties (MCID/OCG) and /Parent-like backpointers intact.
            if (k == "/Properties" || k == "/Parent" || k == "/P") continue;
            QPDFObjectHandle val = dict.getKey(k);
            if (val.isIndirect()) {
                auto it = redirects.find(val.getObjGen());
                if (it != redirects.end()) dict.replaceKey(k, it->second);
            } else if (val.isArray() || val.isDictionary()) {
                rewriteReferences(val, redirects, depth + 1);
            }
        }
    }
}

void collectProtectedObjectGens(QPDF& qpdf, std::set<QPDFObjGen>& protectedGens) {
    // Exclude catalog trees, pages, annots, AcroForm fields, and resource /Properties from
    // deduplication because structure IDs, widget parents, and OCG dictionaries rely on object
    // identity.
    std::set<QPDFObjGen> visitedProt;
    QPDFObjectHandle root = qpdf.getRoot();
    for (char const* k : {"/StructTreeRoot", "/OCProperties", "/Outlines"}) {
        if (root.isDictionary() && root.hasKey(k)) {
            collectProtectedGens(root.getKey(k), protectedGens, visitedProt, 0);
        }
    }
    std::set<QPDFObjGen> propVisited;
    for (auto& page : QPDFPageDocumentHelper{qpdf}.getAllPages()) {
        QPDFObjectHandle pageObj = page.getObjectHandle();
        protectedGens.insert(pageObj.getObjGen());
        if (pageObj.hasKey("/Annots")) {
            collectProtectedGens(pageObj.getKey("/Annots"), protectedGens, visitedProt, 0);
        }
        QPDFObjectHandle res = pageObj.getKey("/Resources");
        if (!res.isDictionary()) res = page.getAttribute("/Resources", false);
        collectProtectedProperties(res, protectedGens, propVisited, 0);
    }
    if (root.isDictionary() && root.hasKey("/AcroForm")) {
        QPDFObjectHandle acroForm = root.getKey("/AcroForm");
        if (acroForm.isDictionary()) {
            if (acroForm.hasKey("/Fields")) {
                collectProtectedGens(acroForm.getKey("/Fields"), protectedGens, visitedProt, 0);
            }
            collectProtectedProperties(acroForm.getKey("/DR"), protectedGens, propVisited, 0);
        }
    }
}

struct SubsetDescriptorGroup {
    std::string key;
    QPDFObjectHandle descriptor;
    std::vector<QPDFObjectHandle> topFonts;
    std::vector<QPDFObjectHandle> descendants;
    std::set<std::string> baseNames;
};

void collectSubsetDescriptorGroups(QPDF& qpdf, std::vector<SubsetDescriptorGroup>& groups,
                                   std::set<std::string>& usedTags) {
    // Gather all page, annotation appearance, and AcroForm /DR font dictionaries first.
    std::vector<QPDFObjectHandle> rawFonts;
    std::set<QPDFObjGen> seenResources;
    std::set<QPDFObjGen> seenFonts;
    std::map<QPDFObjGen, int> seenStreams;

    auto collectRes = [&](QPDFObjectHandle const& res) {
        if (res.isDictionary()) {
            collectResourceFonts(res, 0, seenResources, seenFonts, seenStreams, rawFonts);
        }
    };

    for (auto& page : QPDFPageDocumentHelper{qpdf}.getAllPages()) {
        auto pageObj = page.getObjectHandle();
        auto res = pageObj.getKey("/Resources");
        if (!res.isDictionary()) res = page.getAttribute("/Resources", false);
        collectRes(res);
        auto annots = pageObj.getKey("/Annots");
        if (annots.isArray()) {
            for (auto& annot : annots.getArrayAsVector()) {
                if (!annot.isDictionary()) continue;
                auto ap = annot.getKey("/AP");
                if (!ap.isDictionary()) continue;
                for (char const* key : {"/N", "/R", "/D"}) {
                    auto app = ap.getKey(key);
                    if (app.isStream()) {
                        collectRes(app.getDict().getKey("/Resources"));
                    } else if (app.isDictionary()) {
                        for (auto const& entry : app.getDictAsMap()) {
                            auto sub = entry.second;
                            if (sub.isStream()) collectRes(sub.getDict().getKey("/Resources"));
                        }
                    }
                }
            }
        }
    }
    auto catalog = qpdf.getRoot();
    auto acroForm = catalog.isDictionary() ? catalog.getKey("/AcroForm") : QPDFObjectHandle();
    if (acroForm.isDictionary()) collectRes(acroForm.getKey("/DR"));

    std::map<std::string, size_t> groupByKey;
    std::set<QPDFObjGen> recordedFonts;
    size_t directSeq = 0;

    // Track every existing 6-letter subset prefix so replacements never collide.
    auto noteTag = [&](std::string_view name) {
        if (jpdfium::font::hasSubsetTag(name)) usedTags.insert(std::string(name.substr(0, 6)));
    };

    for (auto& font : rawFonts) {
        if (!font.isDictionary()) continue;
        if (font.isIndirect() && !recordedFonts.insert(font.getObjGen()).second) continue;

        auto baseFontObj = font.getKey("/BaseFont");
        if (!baseFontObj.isName()) continue;
        std::string topName = jpdfium::font::stripPdfNamePrefix(baseFontObj.getName());
        noteTag(topName);

        // For Type0 composite fonts, descriptor and subset prefix live on the CIDFont descendant.
        QPDFObjectHandle descendant;
        QPDFObjectHandle targetFont = font;
        if (font.getKey("/Subtype").isNameAndEquals("/Type0")) {
            auto descArr = font.getKey("/DescendantFonts");
            if (descArr.isArray() && descArr.getArrayNItems() > 0) {
                auto df = descArr.getArrayItem(0);
                if (df.isDictionary()) {
                    descendant = df;
                    targetFont = df;
                }
            }
        }
        std::string targetName = topName;
        if (descendant.isDictionary() && descendant.getKey("/BaseFont").isName()) {
            targetName =
                jpdfium::font::stripPdfNamePrefix(descendant.getKey("/BaseFont").getName());
            noteTag(targetName);
        }
        QPDFObjectHandle descriptor = targetFont.getKey("/FontDescriptor");
        if (descriptor.isDictionary() && descriptor.getKey("/FontName").isName()) {
            noteTag(jpdfium::font::stripPdfNamePrefix(descriptor.getKey("/FontName").getName()));
        }
        if (!jpdfium::font::hasSubsetTag(targetName)) continue;

        // Group fonts sharing the same descriptor object so merged pages stay consistent.
        std::string descKey;
        if (descriptor.isIndirect()) {
            auto id = descriptor.getObjGen();
            descKey = "D:" + std::to_string(id.getObj()) + ":" + std::to_string(id.getGen());
        } else if (targetFont.isIndirect()) {
            auto id = targetFont.getObjGen();
            descKey = "F:" + std::to_string(id.getObj()) + ":" + std::to_string(id.getGen());
        } else {
            descKey = "L:" + std::to_string(++directSeq);
        }

        auto [it, inserted] = groupByKey.emplace(descKey, groups.size());
        if (inserted) groups.push_back(SubsetDescriptorGroup{descKey, descriptor, {}, {}, {}});
        SubsetDescriptorGroup& g = groups[it->second];
        g.topFonts.push_back(font);
        if (descendant.isDictionary()) g.descendants.push_back(descendant);
        g.baseNames.insert(targetName);
    }
}

}  // namespace

int dedupeResources(QPDF& qpdf) {
    if (pdfHasDigitalSignature(qpdf)) return 0;

    std::set<QPDFObjGen> protectedGens;
    try {
        collectProtectedObjectGens(qpdf, protectedGens);
    } catch (...) {
        return 0;
    }

    int totalMerged = 0;
    constexpr int kMaxRounds = 16;
    // Iterate until cascading rewrites stop merging newly identical parent dicts/arrays.
    for (int round = 0; round < kMaxRounds; ++round) {
        std::vector<QPDFObjectHandle> objects;
        try {
            objects = qpdf.getAllObjects();
        } catch (...) {
            break;
        }

        // Canonical buckets for non-stream indirect objects: canonical serialized -> first handle.
        std::map<std::string, QPDFObjectHandle> nonStreamPool;
        struct StreamEntry {
            QPDFObjectHandle handle;
            std::string dictKey;
            size_t rawSize = 0;
        };
        // Stream buckets keyed by 64-bit digest + raw size, confirmed byte-for-byte on hit.
        std::map<std::pair<uint64_t, size_t>, std::vector<StreamEntry>> streamPool;
        std::map<QPDFObjGen, QPDFObjectHandle> redirects;
        constexpr size_t kMaxDedupeStreamBytes = size_t{16} * 1024 * 1024;

        for (QPDFObjectHandle& obj : objects) {
            if (!obj.isInitialized() || !obj.isIndirect()) continue;
            QPDFObjGen gen = obj.getObjGen();
            if (protectedGens.contains(gen) || isIdentitySensitiveObject(obj)) continue;

            if (obj.isStream()) {
                std::string dictKey;
                if (!serializeCanonical(obj, dictKey, true, 0)) continue;
                auto declaredLength = obj.getDict().getKey("/Length");
                if (declaredLength.isInteger() &&
                    declaredLength.getIntValue() > static_cast<long long>(kMaxDedupeStreamBytes)) {
                    continue;
                }
                std::shared_ptr<Buffer> raw;
                try {
                    raw = obj.getRawStreamData();
                } catch (...) {
                    continue;
                }
                if (!raw) continue;
                size_t rawSize = raw->getSize();
                if (rawSize > kMaxDedupeStreamBytes) continue;
                uint64_t digest = 14695981039346656037ULL;
                digest = fnv1aUpdateStr(digest, dictKey);
                digest ^= 0xffULL;
                digest = fnv1aUpdate(digest, raw->getBuffer(), rawSize);

                auto& bucket = streamPool[{digest, rawSize}];
                QPDFObjectHandle canonical;
                for (StreamEntry const& existing : bucket) {
                    if (streamsEqual(existing.handle, obj, existing.dictKey, dictKey)) {
                        canonical = existing.handle;
                        break;
                    }
                }
                if (canonical.isInitialized()) {
                    redirects.emplace(gen, canonical);
                } else {
                    bucket.push_back(StreamEntry{obj, std::move(dictKey), rawSize});
                }
            } else {
                std::string key;
                if (!serializeCanonical(obj, key, false, 0)) continue;
                auto [it, inserted] = nonStreamPool.emplace(std::move(key), obj);
                if (!inserted) redirects.emplace(gen, it->second);
            }
        }

        if (redirects.empty()) break;

        for (QPDFObjectHandle& obj : objects) {
            if (!obj.isInitialized() || !obj.isIndirect()) continue;
            if (redirects.contains(obj.getObjGen())) continue;
            rewriteReferences(obj, redirects, 0);
        }
        totalMerged += static_cast<int>(redirects.size());
        try {
            QPDFObjectHandle trailer = qpdf.getTrailer();
            rewriteReferences(trailer, redirects, 0);
        } catch (...) {
            break;
        }
    }

    return totalMerged;
}

int disambiguateSubsetFontTags(QPDF& qpdf) {
    if (pdfHasDigitalSignature(qpdf)) return 0;

    std::vector<SubsetDescriptorGroup> groups;
    std::set<std::string> usedTags;
    try {
        collectSubsetDescriptorGroups(qpdf, groups, usedTags);
    } catch (...) {
        return 0;
    }

    // Group distinct descriptor instances that share the same XXXXXX+FontName.
    std::map<std::string, std::vector<size_t>> byTaggedName;
    for (size_t i = 0; i < groups.size(); ++i) {
        if (groups[i].baseNames.size() != 1) continue;
        byTaggedName[*groups[i].baseNames.begin()].push_back(i);
    }

    int renamed = 0;
    for (auto const& [fullName, idxs] : byTaggedName) {
        if (idxs.size() < 2) continue;
        std::string family = jpdfium::font::stripSubsetTag(fullName);
        // Preserve the first instance; assign distinct 6-letter subset tags to collisions.
        for (size_t pos = 1; pos < idxs.size(); ++pos) {
            SubsetDescriptorGroup& g = groups[idxs[pos]];
            std::string newTag;
            uint64_t salt =
                std::hash<std::string>{}(g.key) ^ (uint64_t(pos) * 0x9E3779B97F4A7C15ULL);
            for (int attempt = 0; attempt < 4096; ++attempt) {
                std::string candidate =
                    jpdfium::font::buildSubsetTag(family, salt + uint64_t(attempt));
                if (!usedTags.contains(candidate)) {
                    newTag = std::move(candidate);
                    break;
                }
            }
            if (newTag.empty()) continue;
            usedTags.insert(newTag);
            std::string taggedBase;
            taggedBase.reserve(1 + newTag.size() + 1 + family.size());
            taggedBase += '/';
            taggedBase += newTag;
            taggedBase += '+';
            taggedBase += family;
            QPDFObjectHandle taggedName = QPDFObjectHandle::newName(taggedBase);
            for (auto& top : g.topFonts) {
                auto bf = top.getKey("/BaseFont");
                if (!bf.isName()) continue;
                std::string topName = jpdfium::font::stripPdfNamePrefix(bf.getName());
                if (topName == fullName) {
                    top.replaceKey("/BaseFont", taggedName);
                } else if (topName.size() > fullName.size() &&
                           topName.compare(0, fullName.size(), fullName) == 0 &&
                           topName[fullName.size()] == '-') {
                    std::string suffixedName = taggedBase;
                    suffixedName.append(topName, fullName.size(), std::string::npos);
                    top.replaceKey("/BaseFont", QPDFObjectHandle::newName(suffixedName));
                }
            }
            for (auto& df : g.descendants) df.replaceKey("/BaseFont", taggedName);
            if (g.descriptor.isDictionary()) g.descriptor.replaceKey("/FontName", taggedName);
            ++renamed;
        }
    }
    return renamed;
}

}  // namespace jpdfium::qpdf_detail

#endif  // JPDFIUM_HAS_QPDF
