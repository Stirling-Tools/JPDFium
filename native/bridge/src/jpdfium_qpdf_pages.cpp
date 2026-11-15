// jpdfium_qpdf_pages.cpp - Page assembly (merge/extract), encryption, inspection,
// DocInfo editing, and version forcing backed by libqpdf.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <map>
#include <memory>
#include <set>
#include <span>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "jpdfium_font_internal.h"
#include "jpdfium_qpdf_internal.h"
#include "jpdfium_qpdf_writer_lock.h"

#ifdef JPDFIUM_HAS_QPDF

#include <qpdf/Constants.h>

#include <qpdf/QPDFAcroFormDocumentHelper.hh>
#include <qpdf/QPDFEmbeddedFileDocumentHelper.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFLogger.hh>
#include <qpdf/QPDFOutlineDocumentHelper.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <qpdf/QPDFPageLabelDocumentHelper.hh>
#include <qpdf/QPDFPageObjectHelper.hh>
#include <qpdf/QPDFWriter.hh>
#include <qpdf/QUtil.hh>

namespace {

using jpdfium::font::jsonEscape;
using jpdfium::qpdf_detail::boolJson;
using jpdfium::qpdf_detail::createOutputFile;
using jpdfium::qpdf_detail::disambiguateSubsetFontTags;
using jpdfium::qpdf_detail::QpdfResult;
using jpdfium::qpdf_detail::warningsJson;
using jpdfium::qpdf_detail::writeBufferToC;
using jpdfium_qpdf_internal::WriterLock;

// Pages taken from one input, in output order. Out-of-range indices are skipped.
struct PageSelection {
    QPDF* source = nullptr;
    std::vector<int32_t> pages;
};

std::vector<int32_t> allPageIndices(QPDF& qpdf) {
    std::vector<int32_t> indices(qpdf.getAllPages().size());
    for (size_t i = 0; i < indices.size(); ++i) indices[i] = static_cast<int32_t>(i);
    return indices;
}

// Outline item captured from a source before its pages are moved, so the destination can be
// resolved to the output page index the item ends up on.
struct OutlineNode {
    std::string title;
    QPDF* source = nullptr;
    QPDFObjGen page;
    bool hasPage = false;
    bool hasCount = false;
    int count = 0;
    std::vector<OutlineNode> children;
};

using OutputPageMap = std::map<QPDF*, std::map<QPDFObjGen, int32_t>>;

constexpr int kMaxOutlineDepth = 100;

QPDFObjectHandle outlineDestPage(QPDFObjectHandle const& item, QPDFOutlineDocumentHelper& helper) {
    QPDFObjectHandle dest = item.getKey("/Dest");
    if (dest.isNull()) {
        QPDFObjectHandle action = item.getKey("/A");
        if (action.isDictionary() && action.getKey("/S").isNameAndEquals("/GoTo")) {
            dest = action.getKey("/D");
        }
    }
    if (dest.isName() || dest.isString()) dest = helper.resolveNamedDest(dest);
    if (dest.isArray() && dest.getArrayNItems() > 0 && dest.getArrayItem(0).isIndirect()) {
        return dest.getArrayItem(0);
    }
    return QPDFObjectHandle::newNull();
}

OutlineNode collectOutlineNode(QPDFObjectHandle const& item, QPDF* source,
                               QPDFOutlineDocumentHelper& helper, int depth,
                               std::set<QPDFObjGen>& seen) {
    OutlineNode node;
    node.source = source;
    QPDFObjectHandle title = item.getKey("/Title");
    if (title.isString()) node.title = title.getUTF8Value();
    QPDFObjectHandle destPage = outlineDestPage(item, helper);
    if (!destPage.isNull()) {
        node.page = destPage.getObjGen();
        node.hasPage = true;
    }
    QPDFObjectHandle count = item.getKey("/Count");
    if (count.isInteger()) {
        node.hasCount = true;
        node.count = static_cast<int>(count.getIntValue());
    }
    if (depth >= kMaxOutlineDepth) return node;

    QPDFObjectHandle child = item.getKey("/First");
    while (!child.isNull() && child.isDictionary()) {
        bool indirect = child.isIndirect();
        if (indirect && !seen.insert(child.getObjGen()).second) break;
        node.children.push_back(collectOutlineNode(child, source, helper, depth + 1, seen));
        child = child.getKey("/Next");
    }
    return node;
}

int rebuildOutlineCounts(OutlineNode& node) {
    int visibleDescendants = 0;
    for (auto& child : node.children) {
        int childVisible = rebuildOutlineCounts(child);
        bool childOpen = !child.hasCount || child.count >= 0;
        if (childOpen)
            visibleDescendants += 1 + childVisible;
        else
            visibleDescendants += 1;
    }
    if (!node.children.empty()) {
        bool open = !node.hasCount || node.count >= 0;
        node.hasCount = true;
        node.count = open ? visibleDescendants : -visibleDescendants;
    }
    return visibleDescendants;
}

// Best-effort: a malformed outline tree must not fail the structural merge, which is useful even
// when the navigation data cannot be recovered.
std::vector<OutlineNode> collectOutlines(std::vector<PageSelection> const& selections) {
    std::vector<OutlineNode> outlines;
    std::set<QPDF*> collected;
    for (auto const& selection : selections) {
        QPDF* source = selection.source;
        if (!source || !collected.insert(source).second) continue;
        try {
            QPDFOutlineDocumentHelper& helper = QPDFOutlineDocumentHelper::get(*source);
            if (!helper.hasOutlines()) continue;
            QPDFObjectHandle root = source->getRoot().getKey("/Outlines");
            QPDFObjectHandle item = root.getKey("/First");
            std::set<QPDFObjGen> seen;
            while (!item.isNull() && item.isDictionary()) {
                bool indirect = item.isIndirect();
                if (indirect && !seen.insert(item.getObjGen()).second) break;
                OutlineNode node = collectOutlineNode(item, source, helper, 0, seen);
                rebuildOutlineCounts(node);
                outlines.push_back(std::move(node));
                item = item.getKey("/Next");
            }
        } catch (std::exception const& e) {
            std::fprintf(stderr, "jpdfium qpdf: outlines from merge source dropped: %s\n",
                         e.what());
        }
    }
    return outlines;
}

// Rebuilds one sibling chain and returns its first item, with `last` set for the parent's /Last.
// Only GoTo page destinations are restored; URI and file actions keep their title and hierarchy
// but lose the action.
QPDFObjectHandle buildOutlineList(QPDF& doc, std::vector<OutlineNode> const& nodes,
                                  QPDFObjectHandle const& parent, OutputPageMap const& outputPages,
                                  QPDFObjectHandle& last) {
    if (nodes.empty()) return QPDFObjectHandle::newNull();

    std::vector<QPDFObjectHandle> items;
    items.reserve(nodes.size());
    for (size_t i = 0; i < nodes.size(); ++i) {
        items.push_back(doc.makeIndirectObject(QPDFObjectHandle::newDictionary()));
    }

    for (size_t i = 0; i < nodes.size(); ++i) {
        OutlineNode const& node = nodes[i];
        QPDFObjectHandle item = items[i];
        item.replaceKey("/Title", QPDFObjectHandle::newUnicodeString(node.title));
        if (node.hasPage) {
            auto pagesBySource = outputPages.find(node.source);
            if (pagesBySource != outputPages.end()) {
                auto found = pagesBySource->second.find(node.page);
                if (found != pagesBySource->second.end()) {
                    QPDFObjectHandle page = doc.getAllPages()[static_cast<size_t>(found->second)];
                    item.replaceKey("/Dest", QPDFObjectHandle::newArray(
                                                 {page, QPDFObjectHandle::newName("/Fit")}));
                }
            }
        }
        item.replaceKey("/Parent", parent);
        if (i > 0) item.replaceKey("/Prev", items[i - 1]);
        if (i + 1 < items.size()) item.replaceKey("/Next", items[i + 1]);

        QPDFObjectHandle lastChild;
        QPDFObjectHandle firstChild =
            buildOutlineList(doc, node.children, item, outputPages, lastChild);
        if (!firstChild.isNull()) {
            item.replaceKey("/First", firstChild);
            item.replaceKey("/Last", lastChild);
        }
        if (node.hasCount) item.replaceKey("/Count", QPDFObjectHandle::newInteger(node.count));
    }
    last = items.back();
    return items.front();
}

long long visibleOutlineItems(OutlineNode const& node) {
    long long count = 1;
    if (!node.hasCount || node.count >= 0) {
        for (auto const& child : node.children) count += visibleOutlineItems(child);
    }
    return count;
}

void installOutlines(QPDF& doc, std::vector<OutlineNode> const& outlines,
                     OutputPageMap const& outputPages) {
    if (outlines.empty()) return;

    QPDFObjectHandle outlineRoot = doc.makeIndirectObject(QPDFObjectHandle::newDictionary());
    outlineRoot.replaceKey("/Type", QPDFObjectHandle::newName("/Outlines"));
    QPDFObjectHandle last;
    QPDFObjectHandle first = buildOutlineList(doc, outlines, outlineRoot, outputPages, last);
    outlineRoot.replaceKey("/First", first);
    outlineRoot.replaceKey("/Last", last);
    long long visible = 0;
    for (auto const& node : outlines) visible += visibleOutlineItems(node);
    outlineRoot.replaceKey("/Count", QPDFObjectHandle::newInteger(visible));
    doc.getRoot().replaceKey("/Outlines", outlineRoot);
}

// A page with no label in its source is labelled with its physical number in the output, which
// is what a viewer shows for a file without labels. qpdf's own fabricated label has no /S, which
// renders as a blank label.
QPDFObjectHandle labelFor(QPDFPageLabelDocumentHelper& labels, long long sourceIndex,
                          long long outputIndex) {
    QPDFObjectHandle label = labels.getLabelForPage(sourceIndex);
    if (!label.isDictionary()) {
        label = QPDFObjectHandle::newDictionary();
        label.replaceKey("/S", QPDFObjectHandle::newName("/D"));
        label.replaceKey("/St", QPDFObjectHandle::newInteger(outputIndex + 1));
    }
    return label;
}

void appendLabel(std::vector<QPDFObjectHandle>& nums, long long outputIndex,
                 QPDFObjectHandle const& label) {
    if (nums.size() >= 2) {
        QPDFObjectHandle const& previousIndex = nums[nums.size() - 2];
        QPDFObjectHandle const& previous = nums.back();
        bool continuesPrevious =
            previous.getKey("/S").unparse() == label.getKey("/S").unparse() &&
            previous.getKey("/P").unparseResolved() == label.getKey("/P").unparseResolved() &&
            previous.getKey("/St").isInteger() && label.getKey("/St").isInteger() &&
            label.getKey("/St").getIntValue() - previous.getKey("/St").getIntValue() ==
                outputIndex - previousIndex.getIntValue();
        if (continuesPrevious) return;
    }
    nums.push_back(QPDFObjectHandle::newInteger(outputIndex));
    nums.push_back(label);
}

void nullPagesOutsideTree(QPDF& qpdf) {
    std::set<QPDFObjGen> placed;
    for (auto const& page : qpdf.getAllPages()) placed.insert(page.getObjGen());
    for (auto& object : qpdf.getAllObjects()) {
        if (object.isDictionary() && object.getKey("/Type").isNameAndEquals("/Page") &&
            !placed.count(object.getObjGen())) {
            qpdf.replaceObject(object.getObjGen(), QPDFObjectHandle::newNull());
        }
    }
}

void keepOnlyFields(QPDF& qpdf, std::set<QPDFObjGen> const& keptFields) {
    QPDFObjectHandle root = qpdf.getRoot();
    QPDFObjectHandle acroForm = root.getKey("/AcroForm");
    QPDFObjectHandle fields =
        acroForm.isDictionary() ? acroForm.getKey("/Fields") : QPDFObjectHandle::newNull();
    if (fields.isArray()) {
        std::vector<QPDFObjectHandle> kept;
        for (auto const& field : fields.getArrayAsVector()) {
            if (keptFields.count(field.getObjGen())) kept.push_back(field);
        }
        if (kept.empty()) {
            root.removeKey("/AcroForm");
        } else {
            QPDFObjectHandle keptArray = QPDFObjectHandle::newArray(kept);
            acroForm.replaceKey(
                "/Fields", fields.isIndirect() ? qpdf.makeIndirectObject(keptArray) : keptArray);
        }
    }
    QPDFObjectHandle remainingForm = root.getKey("/AcroForm");
    if (remainingForm.isDictionary()) remainingForm.removeKey("/XFA");
}

// Lays the selected pages out in `primary` the way `qpdf <primary> --pages ... --` does, so the
// primary keeps its document-level data (named destinations, viewer preferences, XMP, language,
// optional content, document info) instead of ending up with a bare catalog. Form fields from
// every input survive, page labels are rebuilt for the new page order, every page object left
// outside the output's page tree is nulled, and colliding XXXXXX+FontName subset tags across
// merged inputs are disambiguated.
//
// With `preserveOutlines`, each source's outline tree is captured before pages move and rebuilt
// against the output page indices. Extraction leaves outlines out: a tree whose destinations
// point at dropped pages is not worth carrying.
//
// Dropped, unlike qpdf: the structure tree, which describes the source's page order and would
// carry every removed page's elements; the XFA form, which viewers would show in place of the
// assembled fields; and usage-rights signatures (/Perms), which any change invalidates.
void assemblePages(QPDF& primary, std::vector<PageSelection> const& selections,
                   bool preserveOutlines = false) {
    std::vector<OutlineNode> outlines =
        preserveOutlines ? collectOutlines(selections) : std::vector<OutlineNode>{};
    std::vector<QPDFObjectHandle> primaryPages = primary.getAllPages();
    for (auto const& page : primaryPages) primary.removePage(page);

    bool anyLabels = false;
    for (auto const& selection : selections) {
        if (QPDFPageLabelDocumentHelper{*selection.source}.hasPageLabels()) anyLabels = true;
    }

    QPDFAcroFormDocumentHelper primaryForms{primary};
    std::map<QPDF*, std::vector<bool>> copied;
    copied[&primary].assign(primaryPages.size(), false);
    std::set<QPDFObjGen> keptFields;
    std::vector<QPDFObjectHandle> labelNums;
    OutputPageMap outputPages;
    long long outputIndex = 0;

    for (auto const& selection : selections) {
        QPDF& source = *selection.source;
        bool fromPrimary = &source == &primary;
        std::vector<QPDFObjectHandle> sourcePages =
            fromPrimary ? primaryPages : source.getAllPages();
        std::vector<bool>& seen =
            copied.try_emplace(&source, sourcePages.size(), false).first->second;
        QPDFPageLabelDocumentHelper sourceLabels{source};
        std::unique_ptr<QPDFAcroFormDocumentHelper> foreignForms;
        if (!fromPrimary) foreignForms = std::make_unique<QPDFAcroFormDocumentHelper>(source);
        QPDFAcroFormDocumentHelper& sourceForms = fromPrimary ? primaryForms : *foreignForms;

        for (int32_t index : selection.pages) {
            if (index < 0 || static_cast<size_t>(index) >= sourcePages.size()) continue;
            QPDFPageObjectHelper page{sourcePages[static_cast<size_t>(index)]};
            bool repeat = seen[static_cast<size_t>(index)];
            if (repeat) {
                page = page.shallowCopyPage();
            } else {
                seen[static_cast<size_t>(index)] = true;
            }
            primary.addPage(page.getObjectHandle(), false);
            QPDFObjectHandle added = primary.getAllPages().back();
            if (preserveOutlines) {
                // First occurrence wins: an outline that points at a repeated page navigates to
                // the copy that appears first in the output.
                outputPages[&source].emplace(sourcePages[static_cast<size_t>(index)].getObjGen(),
                                             static_cast<int32_t>(outputIndex));
            }
            // A repeated page would otherwise share its widgets with the first copy.
            if (repeat || (!fromPrimary && sourceForms.hasAcroForm())) {
                try {
                    primaryForms.fixCopiedAnnotations(added, page.getObjectHandle(), sourceForms,
                                                      &keptFields);
                } catch (const std::exception& e) {
                    std::fprintf(stderr, "jpdfium qpdf: form fields on a copied page lost: %s\n",
                                 e.what());
                }
            }
            if (anyLabels) {
                appendLabel(labelNums, outputIndex, labelFor(sourceLabels, index, outputIndex));
            }
            ++outputIndex;
        }
    }

    QPDFObjectHandle root = primary.getRoot();
    if (anyLabels) {
        root.replaceKey("/PageLabels", QPDFObjectHandle::newDictionary(
                                           {{"/Nums", QPDFObjectHandle::newArray(labelNums)}}));
    }

    std::vector<bool> const& primaryKept = copied[&primary];
    for (size_t i = 0; i < primaryPages.size(); ++i) {
        if (!primaryKept[i]) continue;
        for (auto& field :
             primaryForms.getFormFieldsForPage(QPDFPageObjectHelper{primaryPages[i]})) {
            keptFields.insert(field.getObjectHandle().getObjGen());
        }
    }

    nullPagesOutsideTree(primary);

    keepOnlyFields(primary, keptFields);

    for (char const* key :
         {"/Outlines", "/StructTreeRoot", "/MarkInfo", "/NeedsRendering", "/Perms"}) {
        root.removeKey(key);
    }

    disambiguateSubsetFontTags(primary);

    if (preserveOutlines) {
        installOutlines(primary, outlines, outputPages);
    }
}

// Fast structural settings, not an optimization pass: optimize_file owns size tuning. The
// primary's encryption is not carried over, matching the unencrypted output merge and extract
// have always produced.
void configureAssembledWriter(QPDFWriter& w) {
    w.setPreserveEncryption(false);
    w.setObjectStreamMode(qpdf_o_generate);
    w.setCompressStreams(false);
    w.setDecodeLevel(qpdf_dl_none);
}

QpdfResult mergePdfs(const uint8_t* const* inputs, const int64_t* inputLens, int32_t count) {
    try {
        if (!inputs || !inputLens || count <= 0) {
            return {nullptr, "invalid merge inputs"};
        }
        std::vector<std::shared_ptr<QPDF>> sources;
        sources.reserve(static_cast<size_t>(count));
        std::vector<PageSelection> selections;

        for (int32_t i = 0; i < count; ++i) {
            const uint8_t* in = inputs[i];
            int64_t len = inputLens[i];
            if (!in || len <= 0) continue;

            auto src = QPDF::create();
            src->processMemoryFile("merge-src", reinterpret_cast<const char*>(in),
                                   static_cast<size_t>(len));
            sources.push_back(src);
            selections.push_back({src.get(), allPageIndices(*src)});
        }
        if (sources.empty()) {
            return {nullptr, "no readable merge inputs"};
        }
        assemblePages(*sources.front(), selections, true);

        QPDFWriter w{*sources.front()};
        w.setOutputMemory();
        configureAssembledWriter(w);
        WriterLock lock{-1};
        w.write();
        return {w.getBufferSharedPointer(), ""};
    } catch (const std::exception& e) {
        return {nullptr, e.what()};
    }
}

struct RangeSource {
    RangeSource(QPDF& p, std::vector<QPDFObjectHandle> const& pg)
        : pdf(p), pages(pg), forms(p), labels(p) {}
    QPDF& pdf;
    std::vector<QPDFObjectHandle> const& pages;
    QPDFAcroFormDocumentHelper forms;
    QPDFPageLabelDocumentHelper labels;
};

QPDFObjectHandle copyInto(QPDF& dest, QPDF& source, QPDFObjectHandle const& value) {
    if (value.isIndirect()) return dest.copyForeignObject(value);
    return dest.copyForeignObject(source.makeIndirectObject(value));
}

void copyFieldsForPages(QPDF& dest, QPDF& source, std::set<QPDFObjGen> const& pageFields) {
    QPDFObjectHandle acroForm = source.getRoot().getKey("/AcroForm");
    if (!acroForm.isDictionary()) return;
    QPDFObjectHandle fields = acroForm.getKey("/Fields");
    if (!fields.isArray()) return;
    std::vector<QPDFObjectHandle> kept;
    for (auto const& field : fields.getArrayAsVector()) {
        if (field.isIndirect() && pageFields.count(field.getObjGen())) {
            kept.push_back(dest.copyForeignObject(field));
        }
    }
    if (kept.empty()) return;
    QPDFObjectHandle form = QPDFObjectHandle::newDictionary();
    for (auto const& key : acroForm.getKeys()) {
        if (key == "/Fields" || key == "/XFA") continue;
        form.replaceKey(key, copyInto(dest, source, acroForm.getKey(key)));
    }
    form.replaceKey("/Fields", QPDFObjectHandle::newArray(kept));
    dest.getRoot().replaceKey("/AcroForm", dest.makeIndirectObject(form));
}

QPDFObjectHandle ownedLabel(QPDF& dest, QPDFObjectHandle const& label) {
    QPDFObjectHandle owned = QPDFObjectHandle::newDictionary();
    for (auto const& key : label.getKeys()) {
        QPDFObjectHandle value = label.getKey(key);
        owned.replaceKey(key, value.isIndirect() ? dest.copyForeignObject(value) : value);
    }
    return owned;
}

int writeRange(RangeSource& source, int32_t first, int32_t last, const char* out_path) {
    if (first < 0 || last < first || static_cast<size_t>(last) >= source.pages.size()) return -1;
    auto dest = QPDF::create();
    dest->emptyPDF();
    QPDFObjectHandle srcRoot = source.pdf.getRoot();
    QPDFObjectHandle destRoot = dest->getRoot();
    for (auto const& key : srcRoot.getKeys()) {
        if (key == "/Type" || key == "/Pages" || key == "/AcroForm" || key == "/PageLabels" ||
            key == "/Outlines" || key == "/StructTreeRoot" || key == "/MarkInfo" ||
            key == "/NeedsRendering" || key == "/Perms") {
            continue;
        }
        destRoot.replaceKey(key, copyInto(*dest, source.pdf, srcRoot.getKey(key)));
    }
    QPDFObjectHandle info = source.pdf.getTrailer().getKey("/Info");
    if (info.isDictionary()) {
        dest->getTrailer().replaceKey("/Info", copyInto(*dest, source.pdf, info));
    }

    bool labelled = source.labels.hasPageLabels();
    std::set<QPDFObjGen> pageFields;
    std::vector<QPDFObjectHandle> labelNums;
    for (int32_t index = first; index <= last; ++index) {
        QPDFObjectHandle page = source.pages[static_cast<size_t>(index)];
        dest->addPage(page, false);
        for (auto& field : source.forms.getFormFieldsForPage(QPDFPageObjectHelper{page})) {
            pageFields.insert(field.getObjectHandle().getObjGen());
        }
        if (labelled) {
            long long outputIndex = index - first;
            appendLabel(labelNums, outputIndex,
                        ownedLabel(*dest, labelFor(source.labels, index, outputIndex)));
        }
    }
    if (labelled) {
        destRoot.replaceKey("/PageLabels", QPDFObjectHandle::newDictionary(
                                               {{"/Nums", QPDFObjectHandle::newArray(labelNums)}}));
    }
    copyFieldsForPages(*dest, source.pdf, pageFields);
    nullPagesOutsideTree(*dest);

    FILE* out = createOutputFile(out_path);
    if (!out) {
        std::fprintf(stderr, "jpdfium qpdf file: cannot open output %s\n", out_path);
        return -1;
    }
    bool writerOwnsFile = false;
    try {
        QPDFWriter w{*dest};
        w.setOutputFile("jpdfium-out", out, true);
        writerOwnsFile = true;
        configureAssembledWriter(w);
        w.setMinimumPDFVersion(source.pdf.getPDFVersion(), source.pdf.getExtensionLevel());
        WriterLock lock{-1};
        w.write();
        return 0;
    } catch (...) {
        if (!writerOwnsFile) std::fclose(out);
        throw;
    }
}

QpdfResult extractPages(std::span<const uint8_t> input, const int32_t* pageIndices,
                        int32_t pageCount) {
    try {
        if (!pageIndices || pageCount <= 0) {
            return {nullptr, "invalid page indices"};
        }
        auto src = QPDF::create();
        src->processMemoryFile("extract-src", reinterpret_cast<const char*>(input.data()),
                               input.size());
        assemblePages(*src,
                      {{src.get(), std::vector<int32_t>(pageIndices, pageIndices + pageCount)}});

        QPDFWriter w{*src};
        w.setOutputMemory();
        configureAssembledWriter(w);
        WriterLock lock{-1};
        w.write();
        return {w.getBufferSharedPointer(), ""};
    } catch (const std::exception& e) {
        return {nullptr, e.what()};
    }
}

void applyEncryption(QPDFWriter& w, const char* userPassword, const char* ownerPassword,
                     int32_t permissions, int32_t keyLength) {
    std::string userPass = userPassword ? userPassword : "";
    std::string ownerPass = ownerPassword ? ownerPassword : userPass;
    bool allowPrint =
        (permissions & JPDFIUM_PERM_PRINT_HIGH) || (permissions & JPDFIUM_PERM_PRINT_LOW);
    bool allowExtract = (permissions & JPDFIUM_PERM_EXTRACT) != 0;
    bool allowModify = (permissions & JPDFIUM_PERM_MODIFY) != 0;
    bool allowAccessibility = (permissions & JPDFIUM_PERM_ACCESSIBILITY) != 0;
    bool allowAssemble = (permissions & JPDFIUM_PERM_ASSEMBLE) != 0;
    bool allowAnnotate = (permissions & JPDFIUM_PERM_ANNOTATE) != 0;
    bool allowFillForms = (permissions & JPDFIUM_PERM_FILL_FORMS) != 0;
    qpdf_r3_print_e printMode = allowPrint ? qpdf_r3p_full : qpdf_r3p_none;
    if (keyLength == 256) {
        w.setR6EncryptionParameters(userPass.c_str(), ownerPass.c_str(), allowAccessibility,
                                    allowExtract, allowAssemble, allowAnnotate, allowFillForms,
                                    allowModify, printMode, true);
    } else {
        w.setR5EncryptionParameters(userPass.c_str(), ownerPass.c_str(), allowAccessibility,
                                    allowExtract, allowAssemble, allowAnnotate, allowFillForms,
                                    allowModify, printMode, true);
    }
}

QpdfResult encryptPdf(std::span<const uint8_t> input, const char* userPassword,
                      const char* ownerPassword, int32_t permissions, int32_t keyLength) {
    try {
        auto qpdf = QPDF::create();
        qpdf->processMemoryFile("encrypt-in", reinterpret_cast<const char*>(input.data()),
                                input.size());

        QPDFWriter w{*qpdf};
        w.setOutputMemory();
        applyEncryption(w, userPassword, ownerPassword, permissions, keyLength);
        WriterLock lock{-1};
        w.write();
        return {w.getBufferSharedPointer(), ""};
    } catch (const std::exception& e) {
        return {nullptr, e.what()};
    }
}

std::string inspectLoadedPdf(QPDF& pdf) {
    std::ostringstream os;

    int pages = -1;
    bool pageTreeValid = true;
    try {
        pages = static_cast<int>(QPDFPageDocumentHelper{pdf}.getAllPages().size());
    } catch (...) {
        pageTreeValid = false;
    }

    int formFields = -1;
    int signatures = -1;
    try {
        auto fields = QPDFAcroFormDocumentHelper{pdf}.getFormFields();
        formFields = static_cast<int>(fields.size());
        signatures = 0;
        for (auto& field : fields) {
            if (field.getFieldType() == "/Sig") ++signatures;
        }
    } catch (...) {
        // A damaged form tree should not hide the rest of the inspection.
        formFields = -1;
        signatures = -1;
    }

    int attachments = -1;
    try {
        attachments =
            static_cast<int>(QPDFEmbeddedFileDocumentHelper{pdf}.getEmbeddedFiles().size());
    } catch (...) {
        // Ignore malformed embedded-file trees.
        attachments = -1;
    }

    bool pageLabels = false;
    try {
        pageLabels = QPDFPageLabelDocumentHelper::get(pdf).hasPageLabels();
    } catch (...) {
        // Ignore malformed PageLabels trees.
        pageLabels = false;
    }

    bool linearized = pdf.isLinearized();
    bool linearizationValid = true;
    if (linearized) {
        try {
            auto logger = QPDFLogger::create();
            auto discard = logger->discard();
            logger->setInfo(discard);
            logger->setWarn(discard);
            logger->setError(discard);
            pdf.setLogger(logger);
            linearizationValid = pdf.checkLinearization();
        } catch (...) {
            linearizationValid = false;
        }
    }

    int revision = 0;
    int permissions = 0;
    int encryptionVersion = 0;
    bool encrypted = false;
    bool ownerPasswordMatched = false;
    bool userPasswordMatched = false;
    QPDF::encryption_method_e streamMethod = QPDF::e_none;
    QPDF::encryption_method_e stringMethod = QPDF::e_none;
    QPDF::encryption_method_e fileMethod = QPDF::e_none;
    try {
        encrypted = pdf.isEncrypted(revision, permissions, encryptionVersion, streamMethod,
                                    stringMethod, fileMethod);
        if (encrypted) {
            ownerPasswordMatched = pdf.ownerPasswordMatched();
            userPasswordMatched = pdf.userPasswordMatched();
        }
    } catch (...) {
        // Report the rest of the document even when the encryption dict is damaged.
        encrypted = false;
    }

    auto warnings = pdf.getWarnings();
    const size_t warningCount = warnings.size();

    os << "{";
    os << "\"status\":\"ok\",";
    os << "\"qpdf_version\":\"" << jsonEscape(QPDF_VERSION) << "\",";
    os << "\"pdf_version\":\"" << jsonEscape(pdf.getPDFVersion()) << "\",";
    os << "\"pages\":" << pages << ",";
    os << "\"page_tree_valid\":" << boolJson(pageTreeValid) << ",";
    os << "\"object_count\":" << pdf.getObjectCount() << ",";
    os << "\"linearized\":" << boolJson(linearized) << ",";
    os << "\"linearization_valid\":" << boolJson(linearizationValid) << ",";
    os << "\"encrypted\":" << boolJson(encrypted) << ",";
    os << "\"requires_password\":false,";
    os << "\"encryption_revision\":" << revision << ",";
    os << "\"encryption_version\":" << encryptionVersion << ",";
    os << "\"permissions\":" << permissions << ",";
    os << "\"owner_password_matched\":" << boolJson(ownerPasswordMatched) << ",";
    os << "\"user_password_matched\":" << boolJson(userPasswordMatched) << ",";
    os << "\"page_labels\":" << boolJson(pageLabels) << ",";
    os << "\"attachments\":" << attachments << ",";
    os << "\"form_fields\":" << formFields << ",";
    os << "\"signatures\":" << signatures << ",";
    os << "\"warning_count\":" << warningCount << ",";
    os << "\"warnings\":" << warningsJson(warnings);
    os << "}";
    return os.str();
}

std::string inspectErrorJson(std::exception const& e) {
    std::ostringstream os;
    auto* qpdfError = dynamic_cast<QPDFExc const*>(&e);
    const bool passwordRequired = qpdfError && qpdfError->getErrorCode() == qpdf_e_password;
    os << "{";
    os << "\"status\":\"" << (passwordRequired ? "password_required" : "error") << "\",";
    os << "\"qpdf_version\":\"" << jsonEscape(QPDF_VERSION) << "\",";
    os << "\"encrypted\":" << boolJson(passwordRequired) << ",";
    os << "\"requires_password\":" << boolJson(passwordRequired) << ",";
    if (qpdfError) {
        os << "\"error_code\":" << static_cast<int>(qpdfError->getErrorCode()) << ",";
    }
    os << "\"error\":\"" << jsonEscape(e.what()) << "\",";
    os << "\"warnings\":[]";
    os << "}";
    return os.str();
}

std::string inspectPdf(const uint8_t* input, int64_t inputLen, const char* password) {
    try {
        QPDF pdf;
        pdf.setSuppressWarnings(false);
        pdf.processMemoryFile("inspect", reinterpret_cast<const char*>(input),
                              static_cast<size_t>(inputLen), password ? password : "");
        return inspectLoadedPdf(pdf);
    } catch (std::exception const& e) {
        return inspectErrorJson(e);
    }
}

std::string inspectPdfFile(const char* path, const char* password) {
    try {
        QPDF pdf;
        pdf.setSuppressWarnings(false);
        if (password && *password) {
            pdf.processFile(path, password);
        } else {
            pdf.processFile(path);
        }
        return inspectLoadedPdf(pdf);
    } catch (std::exception const& e) {
        return inspectErrorJson(e);
    }
}

bool applyDocInfo(QPDF& pdf, const char* const* keys, const char* const* values, int32_t count,
                  std::string& error) {
    if (count < 0 || (count > 0 && (!keys || !values))) {
        error = "invalid metadata arguments";
        return false;
    }

    QPDFObjectHandle info = pdf.getTrailer().getKey("/Info");
    if (!info.isDictionary()) info = QPDFObjectHandle::newDictionary();
    if (!info.isIndirect()) info = pdf.makeIndirectObject(info);
    pdf.getTrailer().replaceKey("/Info", info);

    for (int32_t i = 0; i < count; ++i) {
        if (!keys[i] || !*keys[i]) {
            error = "metadata key must not be empty";
            return false;
        }
        std::string key = keys[i];
        if (key.front() != '/') key.insert(key.begin(), '/');
        if (values[i]) {
            if (key == "/Trapped") {
                std::string val = values[i];
                if (val.empty() || val.front() != '/') {
                    val.insert(val.begin(), '/');
                }
                info.replaceKey(key, QPDFObjectHandle::newName(val));
            } else {
                info.replaceKey(key, QPDFObjectHandle::newUnicodeString(values[i]));
            }
        } else {
            info.removeKey(key);
        }
    }
    return true;
}

QpdfResult setDocInfo(std::span<const uint8_t> input, const char* const* keys,
                      const char* const* values, int32_t count, const char* password) {
    try {
        auto pdf = QPDF::create();
        pdf->processMemoryFile("jpdfium-docinfo", reinterpret_cast<const char*>(input.data()),
                               input.size(), password ? password : "");
        std::string error;
        if (!applyDocInfo(*pdf, keys, values, count, error)) {
            return {nullptr, error};
        }

        QPDFWriter writer{*pdf};
        writer.setOutputMemory();
        WriterLock lock{-1};
        writer.write();
        return {writer.getBufferSharedPointer(), ""};
    } catch (std::exception const& e) {
        return {nullptr, e.what()};
    }
}

int setDocInfoFile(const char* in_path, const char* out_path, const char* const* keys,
                   const char* const* values, int32_t count, const char* password) {
    if (!in_path || !out_path) return -1;
    try {
        auto pdf = QPDF::create();
        if (password && *password) {
            pdf->processFile(in_path, password);
        } else {
            pdf->processFile(in_path);
        }
        std::string error;
        if (!applyDocInfo(*pdf, keys, values, count, error)) {
            std::fprintf(stderr, "jpdfium qpdf docinfo file: %s\n", error.c_str());
            return -1;
        }

        FILE* out = createOutputFile(out_path);
        if (!out) return -1;
        bool writerOwnsFile = false;
        try {
            QPDFWriter writer{*pdf};
            writer.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            WriterLock lock{-1};
            writer.write();
            return 0;
        } catch (std::exception const& e) {
            std::fprintf(stderr, "jpdfium qpdf docinfo file: %s\n", e.what());
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        } catch (...) {
            std::fprintf(stderr, "jpdfium qpdf docinfo file: unknown error\n");
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        }
    } catch (std::exception const& e) {
        std::fprintf(stderr, "jpdfium qpdf docinfo file: %s\n", e.what());
        return -1;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int forceVersionFile(const char* in_path, const char* out_path, const char* version) {
    if (!in_path || !out_path || !version || !*version) return -1;
    try {
        auto pdf = QPDF::create();
        pdf->processFile(in_path);
        pdf->getRoot().removeKey("/Version");

        FILE* out = createOutputFile(out_path);
        if (!out) return -1;
        bool writerOwnsFile = false;
        try {
            QPDFWriter writer{*pdf};
            writer.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            writer.forcePDFVersion(version);
            WriterLock lock{-1};
            writer.write();
            return 0;
        } catch (std::exception const& e) {
            std::fprintf(stderr, "jpdfium qpdf force version file: %s\n", e.what());
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        } catch (...) {
            if (!writerOwnsFile) std::fclose(out);
            return JPDFIUM_ERR_NATIVE;
        }
    } catch (std::exception const& e) {
        std::fprintf(stderr, "jpdfium qpdf force version file: %s\n", e.what());
        return -1;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

QpdfResult decryptPdf(std::span<const uint8_t> input, const char* password) {
    try {
        auto qpdf = QPDF::create();
        if (password && *password) {
            qpdf->processMemoryFile("decrypt-in", reinterpret_cast<const char*>(input.data()),
                                    input.size(), password);
        } else {
            qpdf->processMemoryFile("decrypt-in", reinterpret_cast<const char*>(input.data()),
                                    input.size());
        }

        QPDFWriter w{*qpdf};
        // QPDFWriter preserves the source document's encryption by default, so
        // without this the output would still carry /Encrypt and the caller
        // would be told the decryption succeeded.
        w.setPreserveEncryption(false);
        w.setOutputMemory();
        WriterLock lock{-1};
        w.write();
        return {w.getBufferSharedPointer(), ""};
    } catch (const std::exception& e) {
        return {nullptr, e.what()};
    }
}

}  // namespace

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_qpdf_force_version_file(const char* in_path, const char* out_path,
                                                       const char* version) {
    try {
        return forceVersionFile(in_path, out_path, version);
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_force_version(const uint8_t* input, int64_t inputLen,
                                                  const char* version, uint8_t** output,
                                                  int64_t* outputLen) {
    if (!input || inputLen <= 0 || !version || !*version || !output || !outputLen) return -1;
    *output = nullptr;
    *outputLen = 0;
    try {
        auto pdf = QPDF::create();
        pdf->processMemoryFile("jpdfium-mem", reinterpret_cast<const char*>(input),
                               static_cast<size_t>(inputLen));
        pdf->getRoot().removeKey("/Version");
        QPDFWriter writer{*pdf};
        writer.setOutputMemory();
        writer.forcePDFVersion(version);
        WriterLock lock{-1};
        writer.write();
        return writeBufferToC(writer.getBufferSharedPointer(), output, outputLen);
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_inspect(const uint8_t* input, int64_t inputLen,
                                            const char* password, char** json) {
    if (!input || inputLen <= 0 || !json) return -1;
    *json = nullptr;
    try {
        std::string result = inspectPdf(input, inputLen, password);
        *json = strdup(result.c_str());
        return *json ? 0 : -1;
    } catch (...) {
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_inspect_file(const char* in_path, const char* password,
                                                 char** json) {
    if (!in_path || !*in_path || !json) return -1;
    *json = nullptr;
    try {
        std::string result = inspectPdfFile(in_path, password);
        *json = strdup(result.c_str());
        return *json ? 0 : -1;
    } catch (...) {
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_set_docinfo(const uint8_t* input, int64_t inputLen,
                                                const char* const* keys, const char* const* values,
                                                int32_t count, const char* password,
                                                uint8_t** output, int64_t* outputLen) {
    if (!input || inputLen <= 0 || !output || !outputLen) return -1;
    *output = nullptr;
    *outputLen = 0;
    try {
        auto result =
            setDocInfo({input, static_cast<size_t>(inputLen)}, keys, values, count, password);
        if (!result.ok()) {
            std::fprintf(stderr, "jpdfium qpdf docinfo: %s\n", result.error.c_str());
            return -1;
        }
        return writeBufferToC(result.buffer, output, outputLen);
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_set_docinfo_file(const char* in_path, const char* out_path,
                                                     const char* const* keys,
                                                     const char* const* values, int32_t count,
                                                     const char* password) {
    return setDocInfoFile(in_path, out_path, keys, values, count, password);
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_merge(const uint8_t* const* inputs, const int64_t* inputLens,
                                          int32_t count, uint8_t** output, int64_t* outputLen) {
    try {
        if (!inputs || !inputLens || count <= 0 || !output || !outputLen) return -1;
        *output = nullptr;
        *outputLen = 0;

        auto result = mergePdfs(inputs, inputLens, count);
        if (!result.ok()) {
            std::fprintf(stderr, "jpdfium qpdf merge: %s\n", result.error.c_str());
            return -1;
        }
        return writeBufferToC(result.buffer, output, outputLen);
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_merge_files(const char* const* paths, int32_t count,
                                                const char* out_path) {
    if (!paths || count <= 0 || !out_path) return -1;
    try {
        std::vector<std::shared_ptr<QPDF>> sources;
        sources.reserve(static_cast<size_t>(count));
        std::vector<PageSelection> selections;

        for (int32_t i = 0; i < count; ++i) {
            const char* path = paths[i];
            if (!path || !*path) continue;

            auto src = QPDF::create();
            src->processFile(path);
            sources.push_back(src);
            selections.push_back({src.get(), allPageIndices(*src)});
        }
        if (sources.empty()) {
            std::fprintf(stderr, "jpdfium qpdf merge files: no readable inputs\n");
            return -1;
        }
        auto& dest = sources.front();
        assemblePages(*dest, selections, true);

        FILE* out = createOutputFile(out_path);
        if (!out) {
            std::fprintf(stderr, "jpdfium qpdf file: cannot open output %s\n", out_path);
            return -1;
        }
        bool writerOwnsFile = false;
        try {
            QPDFWriter w{*dest};
            w.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            configureAssembledWriter(w);
            WriterLock lock{-1};
            w.write();
            return 0;
        } catch (...) {
            if (!writerOwnsFile) std::fclose(out);
            throw;
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf merge files: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf merge files: unknown error\n");
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_pages_file(const char* in_path,
                                                       const int32_t* pageIndices,
                                                       int32_t pageCount, const char* out_path) {
    if (!in_path || !pageIndices || pageCount <= 0 || !out_path) return -1;
    try {
        auto dest = QPDF::create();
        dest->processFile(in_path);
        assemblePages(*dest,
                      {{dest.get(), std::vector<int32_t>(pageIndices, pageIndices + pageCount)}});

        FILE* out = createOutputFile(out_path);
        if (!out) {
            std::fprintf(stderr, "jpdfium qpdf file: cannot open output %s\n", out_path);
            return -1;
        }
        bool writerOwnsFile = false;
        try {
            QPDFWriter w{*dest};
            w.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            configureAssembledWriter(w);
            WriterLock lock{-1};
            w.write();
            return 0;
        } catch (...) {
            if (!writerOwnsFile) std::fclose(out);
            throw;
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf extract file: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf extract file: unknown error\n");
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_ranges_files(const char* in_path,
                                                         const int32_t* firstPages,
                                                         const int32_t* lastPages,
                                                         int32_t rangeCount,
                                                         const char* const* out_paths) {
    if (!in_path || !firstPages || !lastPages || rangeCount <= 0 || !out_paths) return -1;
    std::shared_ptr<QPDF> src;
    try {
        src = QPDF::create();
        src->processFile(in_path);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf extract ranges: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf extract ranges: unknown error\n");
        return -1;
    }
    int32_t written = 0;
    try {
        std::vector<QPDFObjectHandle> srcPages = src->getAllPages();
        RangeSource source{*src, srcPages};
        for (; written < rangeCount; ++written) {
            if (!out_paths[written] || writeRange(source, firstPages[written], lastPages[written],
                                                  out_paths[written]) != 0) {
                break;
            }
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf extract ranges: %s\n", e.what());
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf extract ranges: unknown error\n");
    }
    return written;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_pages(const uint8_t* input, int64_t inputLen,
                                                  const int32_t* pageIndices, int32_t pageCount,
                                                  uint8_t** output, int64_t* outputLen) {
    try {
        if (!input || inputLen <= 0 || !pageIndices || pageCount <= 0 || !output || !outputLen)
            return -1;
        *output = nullptr;
        *outputLen = 0;

        auto result = extractPages({input, static_cast<size_t>(inputLen)}, pageIndices, pageCount);
        if (!result.ok()) {
            std::fprintf(stderr, "jpdfium qpdf extract: %s\n", result.error.c_str());
            return -1;
        }
        return writeBufferToC(result.buffer, output, outputLen);
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_encrypt(const uint8_t* input, int64_t inputLen,
                                            const char* userPassword, const char* ownerPassword,
                                            int32_t permissions, int32_t keyLength,
                                            uint8_t** output, int64_t* outputLen) {
    try {
        if (!input || inputLen <= 0 || !output || !outputLen) return -1;
        *output = nullptr;
        *outputLen = 0;

        auto result = encryptPdf({input, static_cast<size_t>(inputLen)}, userPassword,
                                 ownerPassword, permissions, keyLength);
        if (!result.ok()) {
            std::fprintf(stderr, "jpdfium qpdf encrypt: %s\n", result.error.c_str());
            return -1;
        }
        return writeBufferToC(result.buffer, output, outputLen);
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_decrypt(const uint8_t* input, int64_t inputLen,
                                            const char* password, uint8_t** output,
                                            int64_t* outputLen) {
    try {
        if (!input || inputLen <= 0 || !output || !outputLen) return -1;
        *output = nullptr;
        *outputLen = 0;

        auto result = decryptPdf({input, static_cast<size_t>(inputLen)}, password);
        if (!result.ok()) {
            std::fprintf(stderr, "jpdfium qpdf decrypt: %s\n", result.error.c_str());
            return -1;
        }
        return writeBufferToC(result.buffer, output, outputLen);
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_encrypt_file(const char* in_path, const char* out_path,
                                                 const char* userPassword,
                                                 const char* ownerPassword, int32_t permissions,
                                                 int32_t keyLength) {
    if (!in_path || !*in_path || !out_path || !*out_path) return -1;
    try {
        auto qpdf = QPDF::create();
        qpdf->processFile(in_path);
        FILE* out = createOutputFile(out_path);
        if (!out) return -1;
        bool writerOwnsFile = false;
        try {
            QPDFWriter w{*qpdf};
            w.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            applyEncryption(w, userPassword, ownerPassword, permissions, keyLength);
            WriterLock lock{-1};
            w.write();
            return 0;
        } catch (const std::exception& e) {
            std::fprintf(stderr, "jpdfium qpdf encrypt file: %s\n", e.what());
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        } catch (...) {
            std::fprintf(stderr, "jpdfium qpdf encrypt file: unknown error\n");
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf encrypt file: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf encrypt file: unknown error\n");
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_decrypt_file(const char* in_path, const char* out_path,
                                                 const char* password) {
    if (!in_path || !*in_path || !out_path || !*out_path) return -1;
    try {
        auto qpdf = QPDF::create();
        if (password && *password) {
            qpdf->processFile(in_path, password);
        } else {
            qpdf->processFile(in_path);
        }
        FILE* out = createOutputFile(out_path);
        if (!out) return -1;
        bool writerOwnsFile = false;
        try {
            QPDFWriter w{*qpdf};
            w.setPreserveEncryption(false);
            w.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            WriterLock lock{-1};
            w.write();
            return 0;
        } catch (const std::exception& e) {
            std::fprintf(stderr, "jpdfium qpdf decrypt file: %s\n", e.what());
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        } catch (...) {
            std::fprintf(stderr, "jpdfium qpdf decrypt file: unknown error\n");
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf decrypt file: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf decrypt file: unknown error\n");
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_copy_encryption_file(const char* in_path, const char* out_path,
                                                         const char* source_path,
                                                         const char* password) {
    if (!in_path || !*in_path || !out_path || !*out_path || !source_path || !*source_path ||
        !password) {
        return -1;
    }
    try {
        std::vector<std::string> candidates = QUtil::possible_repaired_encodings(password);
        if (candidates.size() > 1) {
            candidates.push_back(password);
        }
        std::shared_ptr<QPDF> source;
        for (auto iter = candidates.begin(); iter != candidates.end(); ++iter) {
            auto attempt = QPDF::create();
            try {
                attempt->processFile(source_path, iter->c_str());
                source = std::move(attempt);
                break;
            } catch (const QPDFExc&) {
                if (iter + 1 == candidates.end()) throw;
            }
        }
        if (!source || !source->isEncrypted()) return -1;

        auto dest = QPDF::create();
        dest->processFile(in_path);
        if (dest->isEncrypted()) return -1;

        FILE* out = createOutputFile(out_path);
        if (!out) return -1;
        bool writerOwnsFile = false;
        try {
            QPDFWriter w{*dest};
            w.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            w.copyEncryptionParameters(*source);
            w.setCompressStreams(false);
            w.setDecodeLevel(qpdf_dl_none);
            WriterLock lock{-1};
            w.write();
            return 0;
        } catch (const std::exception& e) {
            std::fprintf(stderr, "jpdfium qpdf copy encryption file: %s\n", e.what());
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        } catch (...) {
            std::fprintf(stderr, "jpdfium qpdf copy encryption file: unknown error\n");
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf copy encryption file: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf copy encryption file: unknown error\n");
        return -1;
    }
}

}  // extern "C"

#else  // !JPDFIUM_HAS_QPDF

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_qpdf_inspect(const uint8_t*, int64_t, const char*, char** json) {
    if (!json) return -1;
    *json = strdup("{\"status\":\"unavailable\",\"message\":\"qpdf not linked\",\"warnings\":[]}");
    return *json ? 0 : -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_set_docinfo(const uint8_t* input, int64_t inputLen,
                                                const char* const*, const char* const*, int32_t,
                                                const char*, uint8_t** output, int64_t* outputLen) {
    if (!input || inputLen <= 0 || !output || !outputLen) return -1;
    *outputLen = inputLen;
    *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(inputLen)));
    if (!*output) {
        *outputLen = 0;
        return -1;
    }
    memcpy(*output, input, static_cast<size_t>(inputLen));
    return 0;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_set_docinfo_file(const char*, const char*, const char* const*,
                                                     const char* const*, int32_t, const char*) {
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_merge(const uint8_t* const*, const int64_t*, int32_t,
                                          uint8_t** output, int64_t* outputLen) {
    if (output) *output = nullptr;
    if (outputLen) *outputLen = 0;
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_pages(const uint8_t*, int64_t, const int32_t*, int32_t,
                                                  uint8_t** output, int64_t* outputLen) {
    if (output) *output = nullptr;
    if (outputLen) *outputLen = 0;
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_merge_files(const char* const*, int32_t, const char*) {
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_pages_file(const char*, const int32_t*, int32_t,
                                                       const char*) {
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_ranges_files(const char*, const int32_t*,
                                                         const int32_t*, int32_t,
                                                         const char* const*) {
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_encrypt(const uint8_t*, int64_t, const char*, const char*,
                                            int32_t, int32_t, uint8_t** output,
                                            int64_t* outputLen) {
    if (output) *output = nullptr;
    if (outputLen) *outputLen = 0;
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_decrypt(const uint8_t*, int64_t, const char*, uint8_t** output,
                                            int64_t* outputLen) {
    if (output) *output = nullptr;
    if (outputLen) *outputLen = 0;
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_encrypt_file(const char*, const char*, const char*, const char*,
                                                 int32_t, int32_t) {
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_decrypt_file(const char*, const char*, const char*) {
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_copy_encryption_file(const char*, const char*, const char*,
                                                         const char*) {
    return -1;
}

}  // extern "C"

#endif  // JPDFIUM_HAS_QPDF
