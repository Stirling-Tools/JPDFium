#!/usr/bin/env python3
"""Generates jpdf_guard_* shims that admit legacy entry points to the native PDFium domain.

Usage:
  nm -D --defined-only native/pdfium/lib/libpdfium.so | awk '{print $3}' > /tmp/pdfium.exports
  python native/tools/gen_guards.py --pdfium-include native/pdfium/include \
      --pdfium-exports /tmp/pdfium.exports

Exception firewall: each shim admits the call through the domain guard, then runs the forwarded
entry point inside its own `try`/`catch (...)`. The catch records the refusal as
`JPDF_E_INTERNAL` -- so `jpdf_guard_take_refusal` reports it to the caller -- and returns the same
refused value the unadmitted path returns. Raw PDFium C symbols cannot throw across this frame,
so this is defence in depth rather than the only barrier: no C++ exception can escape
`jpdf_guard_*` and terminate the process, even if the entry point it forwards to is not itself
`noexcept`. The single lifetime-aware addition is the atomics-only accounting below.
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / "native" / "bridge"
JAVA = ROOT / "jpdfium" / "src" / "main" / "java"
OUT_BRIDGE = BRIDGE / "src" / "legacy" / "guards_bridge.cpp"
OUT_PDFIUM = BRIDGE / "src" / "legacy" / "guards_pdfium.cpp"

# Stay unguarded: QPDF and the Rust helpers never touch PDFium and may run in parallel (some are
# heap-critical downcalls, which must not block); ABI probes and legacy init precede the runtime.
# The same applies to the PDFium-free utility entry points (PCRE2, ICU, FlashText, Brotli, Luhn,
# FreeType font classification/subsetting): they reach only per-handle state and share no PDFium
# state, so admitting them would queue them behind every render/save (docs/concurrency.md promises
# this text work runs in parallel) and would make their frees refuse once the runtime is stopped,
# leaking the native object.
UNGUARDED_BRIDGE = re.compile(
    r"^jpdfium_(qpdf_\w+|rust_\w+|abi_\w+|pcre2_\w+|icu_\w+|flashtext_\w+|brotli_\w+"
    r"|luhn_validate|font_classify|font_subset|free_buffer|free_string|init|init_ex|destroy|has_rust)$"
)
TEARDOWN = re.compile(r"(Close|close|Destroy|destroy|Exit|Free|free|Release|release|abandon|forget)")
PDFIUM_SYMBOL = re.compile(r"^(FPDF|EPDF|FORM_|FSDK_)\w+$")
NEVER_GUARD = {"FPDF_InitLibrary", "FPDF_InitLibraryWithConfig", "FPDF_DestroyLibrary"}

# Raw factory/close shims that create or destroy a document/page the Java bindings own directly.
# They must keep the safe-runtime lifetime counters in step with the bridge's own accounting so
# `jpdf_runtime_shutdown` reports JPDF_E_BUSY for every supported path. The bridge's jpdfium_*
# entry points call PDFium directly and are counted by the bridge itself, so counting only here
# covers the raw surface without double counting.
LIFETIME_OPEN = {
    "FPDF_CreateNewDocument": "document_opened",
    "FPDF_ImportNPagesToOne": "document_opened",
    "EPDFPage_ExportAnnotationsAsDocument": "document_opened",
    "FPDFPage_New": "page_opened",
    "EPDF_LoadPageNormalized": "page_opened",
    "EPDFDoc_LoadPageByObjectNumber": "page_opened",
    "EPDFDoc_LoadPageByObjectNumberNormalized": "page_opened",
}
LIFETIME_CLOSE = {
    "FPDF_CloseDocument": "document_closed",
    "FPDF_ClosePage": "page_closed",
}


@dataclass
class Function:
    name: str
    ret: str
    params: list[str]


def split_params(text: str) -> list[str]:
    params, depth, current = [], 0, ""
    for ch in text:
        if ch == "," and depth == 0:
            params.append(current.strip())
            current = ""
            continue
        depth += ch == "("
        depth -= ch == ")"
        current += ch
    if current.strip():
        params.append(current.strip())
    return [] if params in ([], ["void"]) else params


def name_params(params: list[str]) -> tuple[list[str], list[str]]:
    """Returns declarations with a name for every parameter, and the argument names."""
    decls, args = [], []
    for i, param in enumerate(params):
        pointer = re.search(r"\(\s*\*\s*(\w+)\s*\)", param)
        words = re.findall(r"\w+", re.sub(r"\[.*?\]", "", param))
        if pointer:
            name = pointer.group(1)
        elif len(words) >= 2 and not param.rstrip().endswith("*"):
            name = words[-1]
        else:
            name = f"p{i}"
            param = f"{param} {name}"
        decls.append(" ".join(param.split()))
        args.append(name)
    return decls, args


def parse(text: str, pattern: re.Pattern[str]) -> dict[str, Function]:
    found = {}
    for m in pattern.finditer(text):
        ret = " ".join(m.group("ret").split())
        params = re.sub(r"/\*.*?\*/|//[^\n]*", "", m.group("params"), flags=re.S)
        found[m.group("name")] = Function(m.group("name"), ret, split_params(params))
    return found


BRIDGE_DECL = re.compile(
    r"JPDFIUM_EXPORT\s+(?P<ret>[^;(]*?)\b(?P<name>jpdfium_\w+)\s*\((?P<params>[^;]*?)\)"
    r"\s*(?:JPDFIUM_NOEXCEPT)?\s*;",
    re.S,
)
# The stub defines a few PDFium functions itself; stub mode binds them through guards too.
STUB_PDFIUM_DEF = re.compile(
    r"JPDFIUM_EXPORT\s+(?P<ret>[^;(]*?)\b(?P<name>(?:FPDF|EPDF|FORM_)\w+)\s*\((?P<params>[^;{]*?)\)\s*\{",
    re.S,
)
PDFIUM_DECL = re.compile(
    r"FPDF_EXPORT\s+(?P<ret>[^;]*?)\s*FPDF_CALLCONV\s+(?P<name>\w+)\s*\((?P<params>[^;]*?)\)\s*;",
    re.S,
)


def java_bound_pdfium_symbols() -> set[str]:
    names = set()
    for path in JAVA.rglob("*.java"):
        for name in re.findall(r'"(\w+)"', path.read_text(encoding="utf-8")):
            if PDFIUM_SYMBOL.match(name):
                names.add(name)
    return names - NEVER_GUARD


def shim(fn: Function, header_comment: str = "") -> str:
    decls, args = name_params(fn.params)
    purpose = "Teardown" if TEARDOWN.search(fn.name) else "Ordinary"
    refused = "return;" if fn.ret == "void" else "return {};"
    call = f"{fn.name}({', '.join(args)})"
    if fn.name in LIFETIME_OPEN:
        counter = LIFETIME_OPEN[fn.name]
        body = (
            f"    auto result = {call};\n"
            f"    if (result != nullptr) {{\n"
            f"        jpdf::Domain::instance().{counter}();\n"
            f"    }}\n"
            f"    return result;\n"
        )
    elif fn.name in LIFETIME_CLOSE:
        counter = LIFETIME_CLOSE[fn.name]
        body = (
            f"    {call};\n"
            f"    if ({args[0]} != nullptr) {{\n"
            f"        jpdf::Domain::instance().{counter}();\n"
            f"    }}\n"
        )
    else:
        body = f"    {call};\n" if fn.ret == "void" else f"    return {call};\n"
    return (
        f"{header_comment}JPDF_API {fn.ret} jpdf_guard_{fn.name}({', '.join(decls) or 'void'}) noexcept {{\n"
        f"    jpdf::GuardScope guard(jpdf::Purpose::{purpose});\n"
        f"    if (!guard.admitted()) {refused}\n"
        f"    try {{\n"
        f"{body}"
        f"    }} catch (...) {{\n"
        f"        jpdf::set_guard_refusal(JPDF_E_INTERNAL);\n"
        f"        {refused}\n"
        f"    }}\n"
        f"}}\n"
    )


def emit(path: Path, includes: list[str], shims: list[str], note: str) -> None:
    head = (
        "// Generated by native/tools/gen_guards.py; do not edit.\n"
        f"// {note}\n\n"
        + "".join(f"#include {inc}\n" for inc in includes)
        + '\n#include "jpdfium_api.h"\n#include "runtime/guard.h"\n\nextern "C" {\n\n'
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(head + "\n".join(shims) + '\n}  // extern "C"\n', encoding="utf-8", newline="\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pdfium-include", type=Path, required=True)
    ap.add_argument("--pdfium-exports", type=Path, required=True)
    opts = ap.parse_args()

    bridge = parse((BRIDGE / "include" / "jpdfium.h").read_text(), BRIDGE_DECL)
    bridge |= parse((BRIDGE / "include" / "jpdfium_rust.h").read_text(), BRIDGE_DECL)
    stub = (BRIDGE / "src" / "jpdfium_stub.cpp").read_text()
    bridge_shims = []
    for name in sorted(bridge):
        if UNGUARDED_BRIDGE.match(name):
            continue
        text = shim(bridge[name])
        in_stub = re.search(r"\b%s\s*\([^;{]*\)\s*(noexcept|JPDFIUM_NOEXCEPT)?\s*\{" % name, stub)
        if not in_stub:
            text = f"#ifndef JPDFIUM_STUB_GUARDS\n{text}#endif\n"
        bridge_shims.append(text)
    stub_pdfium = parse(stub, STUB_PDFIUM_DEF)
    if stub_pdfium:
        protos = "".join(
            f"{fn.ret} {fn.name}({', '.join(name_params(fn.params)[0]) or 'void'});\n"
            for fn in stub_pdfium.values())
        bridge_shims.append("#ifdef JPDFIUM_STUB_GUARDS\n" + protos + "\n"
                            + "\n".join(shim(fn) for fn in stub_pdfium.values()) + "#endif\n")
    emit(OUT_BRIDGE, ['"jpdfium.h"', '"jpdfium_rust.h"'], bridge_shims,
         "Bridge entry points; also compiled into the stub, minus what it lacks.")

    headers = sorted(opts.pdfium_include.glob("*.h"))
    declared: dict[str, Function] = {}
    owner: dict[str, str] = {}
    for header in headers:
        for name, fn in parse(header.read_text(), PDFIUM_DECL).items():
            declared[name] = fn
            owner[name] = header.name
    exported = set(opts.pdfium_exports.read_text().split())
    wanted = java_bound_pdfium_symbols()
    guarded = sorted(n for n in wanted if n in declared and n in exported)
    # Every guarded factory that hands the caller a live PDFium document/page must be counted, or
    # shutdown can destroy PDFium while that resource is still open. Fail rather than emit a shim
    # that silently drops the accounting.
    uncounted = [
        n for n in guarded
        if declared[n].ret in ("FPDF_PAGE", "FPDF_DOCUMENT") and n not in LIFETIME_OPEN
    ]
    if uncounted:
        raise SystemExit(f"guarded factories without lifetime accounting: {uncounted}")
    skipped = sorted(wanted - set(guarded))
    used_headers = sorted({owner[n] for n in guarded})
    pdfium_shims = [shim(declared[n]) for n in guarded]
    emit(OUT_PDFIUM, [f"<{h}>" for h in used_headers], pdfium_shims,
         f"PDFium symbols the Java bindings use; {len(skipped)} absent from this build: "
         + (", ".join(skipped) or "none") + ".")
    # The repo's clang-format gate covers generated files too.
    if shutil.which("clang-format"):
        subprocess.run(["clang-format", "-i", str(OUT_BRIDGE), str(OUT_PDFIUM)], check=True)
    else:
        print("warning: clang-format not found; run it on the generated files", file=sys.stderr)
    print(f"bridge guards: {len(bridge_shims)}; pdfium guards: {len(guarded)}; skipped: {skipped}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
