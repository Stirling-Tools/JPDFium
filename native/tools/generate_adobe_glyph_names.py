#!/usr/bin/env python3
"""Generate native/bridge/src/adobe_glyph_names.inc from Adobe glyphlist.txt.

Usage: generate_adobe_glyph_names.py <glyphlist.txt> <output.inc>

The AGL has two kinds of entries:
  * one Unicode scalar per glyph name (kAdobeGlyphNames)
  * a sequence of Unicode scalars (kAdobeGlyphSequences), used by Arabic and
    Hebrew ligature names such as dalethatafpatah

kAdobeGlyphByUnicode is the reverse table for single-scalar names. When
several names share a scalar, the first name in sorted order wins, matching
the behavior of the previously generated table (for example Idot over
Idotaccent).
"""

from __future__ import annotations

import pathlib
import sys


def parse_glyphlist(path: pathlib.Path) -> dict[str, list[int]]:
    entries: dict[str, list[int]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or ";" not in line:
            continue
        name, value = line.split(";", 1)
        codepoints = [int(part, 16) for part in value.split()]
        if not codepoints:
            raise ValueError(f"empty codepoint list for {name}")
        entries[name] = codepoints
    return entries


def emit_name_array(entries: list[tuple[str, int]], indent: str = "    ") -> str:
    lines = []
    for name, value in entries:
        lines.append(f'{indent}{{"{name}", 0x{value:X}}},')
    return "\n".join(lines)


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    glyphlist = pathlib.Path(sys.argv[1])
    output = pathlib.Path(sys.argv[2])

    entries = parse_glyphlist(glyphlist)
    single = sorted((name, values[0]) for name, values in entries.items() if len(values) == 1)
    sequences = sorted((name, values) for name, values in entries.items() if len(values) > 1)

    reverse: dict[int, str] = {}
    for name, value in single:
        reverse.setdefault(value, name)
    reverse_entries = sorted(((name, value) for value, name in reverse.items()), key=lambda e: e[1])

    if len(single) != 4200 or len(sequences) != 81 or len(reverse_entries) != 3680:
        raise ValueError(
            f"unexpected AGL shape: single={len(single)} sequence={len(sequences)} "
            f"reverse={len(reverse_entries)}"
        )

    max_sequence = max(len(values) for _, values in sequences)
    if max_sequence > 4:
        raise ValueError(f"sequence length {max_sequence} exceeds the fixed table width")

    parts = [
        "// Generated from the official Adobe Glyph List (AGL) glyphlist.txt.",
        "// Source: https://github.com/adobe-type-tools/agl-aglfn",
        "// SPDX-License-Identifier: Apache-2.0",
        "struct AdobeGlyphName {",
        "    const char* name;",
        "    uint32_t unicode;",
        "};",
        "",
        "// Names that expand to more than one Unicode scalar. The unused slots are",
        "// zero so the table stays a fixed-size constexpr array.",
        "struct AdobeGlyphSequence {",
        "    const char* name;",
        "    std::array<uint32_t, 4> unicode;",
        "    uint8_t length;",
        "};",
        "",
        f"static constexpr AdobeGlyphName kAdobeGlyphNames[{len(single)}] = {{",
        emit_name_array(single),
        "};",
        "",
        f"static constexpr AdobeGlyphSequence kAdobeGlyphSequences[{len(sequences)}] = {{",
    ]
    for name, values in sequences:
        padded = values + [0] * (4 - len(values))
        rendered = ", ".join(f"0x{value:X}" for value in padded)
        parts.append(f'    {{"{name}", {{{rendered}}}, {len(values)}}},')
    parts.extend(
        [
            "};",
            "",
            f"static constexpr AdobeGlyphName kAdobeGlyphByUnicode[{len(reverse_entries)}] = {{",
            emit_name_array(reverse_entries),
            "};",
            "",
            "static_assert(sizeof(kAdobeGlyphNames) / sizeof(kAdobeGlyphNames[0]) == 4200);",
            "static_assert(sizeof(kAdobeGlyphSequences) / sizeof(kAdobeGlyphSequences[0]) == 81);",
            "static_assert(sizeof(kAdobeGlyphByUnicode) / sizeof(kAdobeGlyphByUnicode[0]) == 3680);",
            "",
        ]
    )
    output.write_text("\n".join(parts), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
