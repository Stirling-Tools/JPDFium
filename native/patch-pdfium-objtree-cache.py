#!/usr/bin/env python3
"""Apply the object-tree memoization patch to a local PDFium checkout.
Runtime gate: the JPDFIUM_OBJTREE_CACHE environment variable."""

import sys
import os


def die(msg):
    sys.stderr.write("ERROR: %s\n" % msg)
    raise SystemExit(1)


def patch_file(path, edits, revert=False):
    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()
    changed = False
    for old, new in edits:
        if revert:
            old, new = new, old
        if text.count(old) != 1:
            die("%s: expected 1 occurrence, found %d for:\n%s"
                % (path, text.count(old), old[:200]))
        text = text.replace(old, new, 1)
        changed = True
    if changed:
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        print("  %s %s" % ("reverted" if revert else "patched", path))
    return changed


DOC_H_MARKER = """  void SetEpdfAttachment(std::unique_ptr<Attachment> attachment) {
    epdf_attachment_ = std::move(attachment);
  }
"""

DOC_H_ACCESSORS = DOC_H_MARKER + """
  // Memoizes GetObjectsWithMultipleReferences(); the object tree is otherwise
  // walked once per page. Mutable for the const traversal helper.
  void SetCachedObjectsWithMultipleReferences(
      std::set<uint32_t> objects_with_multiple_references) const {
    cached_objects_with_multiple_references_ =
        std::move(objects_with_multiple_references);
  }
  const std::optional<std::set<uint32_t>>&
  cached_objects_with_multiple_references() const {
    return cached_objects_with_multiple_references_;
  }
"""

DOC_H_MEMBER_OLD = """  bool typographic_features_ = false;  // EmbedPDF, see above.
"""

DOC_H_MEMBER_NEW = DOC_H_MEMBER_OLD + """
  mutable std::optional<std::set<uint32_t>>
      cached_objects_with_multiple_references_;
"""

UTIL_HELPER = """namespace {

bool IsMultiRefObjectCacheEnabled() {
  static const bool enabled = getenv("JPDFIUM_OBJTREE_CACHE") != nullptr;
  return enabled;
}

}  // namespace

"""

UTIL_OLD = """std::set<uint32_t> GetObjectsWithMultipleReferences(
    const CPDF_Document* document,
    ObjectTreeReferenceResolveMode resolve_mode) {
  ObjectTreeTraverser traverser(document, resolve_mode);
  traverser.Traverse();

  std::set<uint32_t> results;
  for (const auto& it : traverser.referenced_objects()) {
    if (it.second > 1) {
      results.insert(it.first);
    }
  }
  return results;
}
"""

UTIL_NEW = UTIL_HELPER + """std::set<uint32_t> GetObjectsWithMultipleReferences(
    const CPDF_Document* document,
    ObjectTreeReferenceResolveMode resolve_mode) {
  const bool use_cache =
      IsMultiRefObjectCacheEnabled() &&
      resolve_mode == ObjectTreeReferenceResolveMode::kReferenceHolder;
  if (use_cache) {
    const std::optional<std::set<uint32_t>>& cached =
        document->cached_objects_with_multiple_references();
    if (cached.has_value()) {
      return cached.value();
    }
  }

  ObjectTreeTraverser traverser(document, resolve_mode);
  traverser.Traverse();

  std::set<uint32_t> results;
  for (const auto& it : traverser.referenced_objects()) {
    if (it.second > 1) {
      results.insert(it.first);
    }
  }
  if (use_cache) {
    document->SetCachedObjectsWithMultipleReferences(results);
  }
  return results;
}
"""

UTIL_INCLUDE_OLD = """#include <stdint.h>

#include <map>
"""

UTIL_INCLUDE_NEW = """#include <stdint.h>
#include <stdlib.h>

#include <map>
"""


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    revert = "--revert" in sys.argv
    if len(args) != 1:
        die("usage: patch-pdfium-objtree-cache.py <pdfium-src-dir> [--revert]")
    src = os.path.join(args[0], "core", "fpdfapi")
    doc_h = os.path.join(src, "parser", "cpdf_document.h")
    util_cpp = os.path.join(src, "parser", "object_tree_traversal_util.cpp")
    for path in (doc_h, util_cpp):
        if not os.path.isfile(path):
            die("missing %s" % path)

    print("Applying multi-ref object cache patch:")
    patch_file(doc_h, [(DOC_H_MARKER, DOC_H_ACCESSORS),
                       (DOC_H_MEMBER_OLD, DOC_H_MEMBER_NEW)], revert)
    patch_file(util_cpp, [(UTIL_INCLUDE_OLD, UTIL_INCLUDE_NEW),
                          (UTIL_OLD, UTIL_NEW)], revert)
    print("Done.")


if __name__ == "__main__":
    main()
