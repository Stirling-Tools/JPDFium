#!/usr/bin/env python3
"""Fix up the Windows vips bundle so it coexists and stays GPL-free.

Rename: JPDFium ships two native bundles (core PDFium + libvips) that each
contain their own build of LLVM libc++ under the identical file name
``libc++.dll``. Windows binds loaded DLLs by file name process-wide, so
whichever copy loads first silently satisfies the other bundle's imports -
and the two builds are not ABI-compatible, which breaks whichever bundle
loads second. Our copy gets a distinct name (``libcxx.dll``, chosen to be
exactly as long so the replacement fits in place) and every import reference
to it is rewritten, both in the regular and the delay-load import tables.

Drop: import descriptors listed in ``DROP_IMPORTS`` are removed entirely.
Used for GPL libraries nothing in our product paths ever calls (libfftw3:
only FFT ops use it). A call into a dropped import crashes loudly instead
of silently misbehaving, and dropping is refused loudly if the pattern no
longer matches, so GPL code can never slip back in quietly.

Standard library only. Usage: patch-windows-libcxx.py <dist-dir>
"""

import struct
import sys
from pathlib import Path

RENAME = {
    "libc++.dll": "libcxx.dll",
}

DROP_IMPORTS = {
    # file (lowercase) -> import names to remove (lowercase)
    "libvips-42.dll": ["libfftw3-3.dll"],
}

for old, new in RENAME.items():
    assert len(old) == len(new), f"{old!r} and {new!r} must be equally long"


def rva_to_offset(sections, rva):
    for va, size, raw in sections:
        if va <= rva < va + max(size, 1):
            return rva - va + raw
    return None


def parse_sections(data):
    (e_lfanew,) = struct.unpack_from("<I", data, 0x3C)
    if data[e_lfanew : e_lfanew + 4] != b"PE\x00\x00":
        raise ValueError("not a PE file")
    coff = e_lfanew + 4
    (num_sections,) = struct.unpack_from("<H", data, coff + 2)
    opt_off = coff + 20
    (magic,) = struct.unpack_from("<H", data, opt_off)
    if magic == 0x10B:
        dirs_off = opt_off + 96
    elif magic == 0x20B:
        dirs_off = opt_off + 112
    else:
        raise ValueError(f"unknown optional header magic {magic:#x}")
    sect_off = dirs_off + 8 * 16
    sections = []
    for i in range(num_sections):
        base = sect_off + 40 * i
        va, size, raw, _ = struct.unpack_from("<IIII", data, base + 12)
        sections.append((va, size, raw))
    return dirs_off, sections


def read_cstring(data, off):
    end = data.index(b"\x00", off)
    return data[off:end].decode("ascii")


def patch_table(data, dirs_off, sections, dir_index, entry_size, name_off):
    """Rewrite RENAME keys found in an import-style table. Returns hit count."""
    (rva, size) = struct.unpack_from("<II", data, dirs_off + 8 * dir_index)
    if not rva or not size:
        return 0
    off = rva_to_offset(sections, rva)
    if off is None:
        return 0
    hits = 0
    pos = off
    while True:
        entry = data[pos : pos + entry_size]
        if len(entry) < entry_size or not any(entry):
            break
        (name_rva,) = struct.unpack_from("<I", entry, name_off)
        name_off_in_file = rva_to_offset(sections, name_rva) if name_rva else None
        if name_off_in_file is not None:
            try:
                name = read_cstring(data, name_off_in_file)
            except (ValueError, UnicodeDecodeError):
                name = ""
            lowered = name.lower()
            if lowered in RENAME:
                new_name = RENAME[lowered]
                data[name_off_in_file : name_off_in_file + len(new_name)] = new_name.encode(
                    "ascii"
                )
                hits += 1
        pos += entry_size
    return hits


def list_imports(data, dirs_off, sections):
    """Names referenced by the regular import table (lowercased)."""
    (rva, size) = struct.unpack_from("<II", data, dirs_off + 8 * 1)
    if not rva or not size:
        return []
    off = rva_to_offset(sections, rva)
    if off is None:
        return []
    names = []
    pos = off
    end = off + size
    while pos + 20 <= end:
        entry = data[pos : pos + 20]
        if not any(entry):
            break
        (name_rva,) = struct.unpack_from("<I", entry, 12)
        name_off_in_file = rva_to_offset(sections, name_rva) if name_rva else None
        if name_off_in_file is not None:
            try:
                names.append(read_cstring(data, name_off_in_file).lower())
            except (ValueError, UnicodeDecodeError):
                pass
        pos += 20
    return names


def drop_imports(data, dirs_off, sections, dll_name):
    """Remove DROP_IMPORTS descriptors for this file. Returns dropped count."""
    wanted = DROP_IMPORTS.get(dll_name.lower(), [])
    if not wanted:
        return 0
    (rva, size) = struct.unpack_from("<II", data, dirs_off + 8 * 1)
    if not rva or not size:
        return 0
    off = rva_to_offset(sections, rva)
    if off is None:
        return 0
    end = off + size
    starts = []
    pos = off
    while pos + 20 <= end:
        entry = data[pos : pos + 20]
        if not any(entry):
            break
        (name_rva,) = struct.unpack_from("<I", entry, 12)
        name = ""
        name_off_in_file = rva_to_offset(sections, name_rva) if name_rva else None
        if name_off_in_file is not None:
            try:
                name = read_cstring(data, name_off_in_file)
            except (ValueError, UnicodeDecodeError):
                pass
        starts.append((pos, name.lower() in wanted))
        pos += 20
    if not any(drop for _, drop in starts):
        return 0
    blob = b"".join(data[p : p + 20] for p, drop in starts if not drop)
    data[off : off + len(blob)] = blob
    tail = off + len(blob)
    data[tail : tail + 20] = b"\x00" * 20
    for i in range(tail + 20, end):
        data[i] = 0
    return sum(1 for _, drop in starts if drop)


def patch_file(path):
    with open(path, "r+b") as f:
        data = bytearray(f.read())
        try:
            dirs_off, sections = parse_sections(data)
        except ValueError:
            return 0, 0
        hits = 0
        hits += patch_table(data, dirs_off, sections, 1, 20, 12)  # import table
        try:
            hits += patch_table(data, dirs_off, sections, 13, 32, 4)  # delay-load
        except Exception:
            pass
        try:
            dropped = drop_imports(data, dirs_off, sections, path.name)
        except Exception:
            dropped = 0
        if hits or dropped:
            f.seek(0)
            f.write(data)
            f.truncate()
        return hits, dropped


def main():
    dist = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    if dist is None or not dist.is_dir():
        print(f"usage: {sys.argv[0]} <dist-dir>", file=sys.stderr)
        return 2
    total_files = 0
    total_hits = 0
    total_dropped = 0
    for dll in sorted(dist.glob("*.dll")):
        try:
            hits, dropped = patch_file(dll)
        except OSError as e:
            print(f"skip {dll.name}: {e}")
            continue
        if hits:
            print(f"patched {hits} reference(s) in {dll.name}")
            total_files += 1
            total_hits += hits
        if dropped:
            print(f"dropped {dropped} import(s) in {dll.name}")
            total_dropped += dropped
    for old, new in RENAME.items():
        src = dist / old
        if src.exists():
            src.rename(dist / new)
            print(f"renamed {old} -> {new}")
    for dll_name, wanted in DROP_IMPORTS.items():
        target = dist / dll_name
        if not target.exists():
            continue
        try:
            raw = bytearray(target.read_bytes())
            dirs_off, sections = parse_sections(raw)
            leftover = [w for w in wanted if w in list_imports(raw, dirs_off, sections)]
        except Exception:
            leftover = list(wanted)
        if leftover:
            print(
                f"ERROR: {dll_name} still references {leftover}; "
                f"refusing to ship GPL code quietly",
                file=sys.stderr,
            )
            return 1
    print(f"done: {total_hits} reference(s) in {total_files} file(s), {total_dropped} import(s) dropped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
