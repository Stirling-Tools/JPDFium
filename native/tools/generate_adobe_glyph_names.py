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
        "// SPDX-License-Identifier: BSD-3-Clause",
        "//",
        "// Copyright 2002-2019 Adobe (http://www.adobe.com/).",
        "//",
        "// Redistribution and use in source and binary forms, with or without",
        "// modification, are permitted provided that the following conditions are",
        "// met:",
        "//",
        "// Redistributions of source code must retain the above copyright notice,",
        "// this list of conditions and the following disclaimer.",
        "//",
        "// Redistributions in binary form must reproduce the above copyright",
        "// notice, this list of conditions and the following disclaimer in the",
        "// documentation and/or other materials provided with the distribution.",
        "//",
        "// Neither the name of Adobe nor the names of its contributors may be",
        "// used to endorse or promote products derived from this software without",
        "// specific prior written permission.",
        "//",
        "// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS",
        '// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT',
        "// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR",
        "// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT",
        "// HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,",
        "// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT",
        "// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,",
        "// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY",
        "// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT",
        "// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE",
        "// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.",
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
