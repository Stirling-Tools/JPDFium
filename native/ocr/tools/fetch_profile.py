"""Downloads an OCR model profile's pinned files and checks every digest.

  python native/ocr/tools/fetch_profile.py native/ocr/profiles/pp-ocrv6-small.profile DEST

Only the URLs and SHA-256 digests in the profile are used. The recognizer's character list is
extracted from its config into dictionary.txt, whose digest the profile also pins.
"""

from __future__ import annotations

import hashlib
import shutil
import sys
import urllib.request
from pathlib import Path


def parse_profile(text: str) -> tuple[dict[str, tuple[str, str, str]], dict[str, tuple[str, str]]]:
    files, derived = {}, {}
    for line in text.splitlines():
        fields = line.split()
        if not fields or fields[0].startswith("#"):
            continue
        if fields[0] == "file":
            role, name, digest, url = fields[1:5]
            files[role] = (name, digest, url)
        elif fields[0] == "derived":
            role, name, digest = fields[1:4]
            derived[role] = (name, digest)
    return files, derived


def dictionary_from_config(text: str) -> list[str]:
    """The `character_dict` block sequence: plain or single-quoted one-character scalars."""
    lines = text.split("\n")
    start = lines.index("  character_dict:") + 1
    chars = []
    for line in lines[start:]:
        if not line.startswith("  - "):
            break
        scalar = line[4:]
        if scalar.startswith("'"):
            if not scalar.endswith("'") or len(scalar) < 3:
                raise ValueError(f"unexpected quoted scalar {scalar!r}")
            scalar = scalar[1:-1].replace("''", "'")
        elif scalar.startswith(('"', "&", "*", "!", "|", ">")):
            raise ValueError(f"unsupported scalar style {scalar!r}")
        chars.append(scalar)
    if not chars:
        raise ValueError("no character_dict found")
    return chars


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(profile: Path, dest: Path) -> None:
    files, derived = parse_profile(profile.read_text(encoding="utf-8"))
    dest.mkdir(parents=True, exist_ok=True)
    for role, (name, digest, url) in files.items():
        target = dest / name
        if not (target.is_file() and sha256(target) == digest):
            staging = target.with_suffix(target.suffix + ".part")
            with urllib.request.urlopen(url, timeout=120) as response:  # noqa: S310 - pinned https
                with staging.open("wb") as out:
                    shutil.copyfileobj(response, out)
            actual = sha256(staging)
            if actual != digest:
                staging.unlink()
                raise SystemExit(f"{role}: digest mismatch, got {actual}")
            staging.replace(target)
        print(f"ok {role} {name}")
    if "dictionary" in derived:
        name, digest = derived["dictionary"]
        config = (dest / files["recognizer_config"][0]).read_text(encoding="utf-8")
        target = dest / name
        target.write_text("\n".join(dictionary_from_config(config)) + "\n", encoding="utf-8",
                          newline="\n")
        if sha256(target) != digest:
            raise SystemExit(f"dictionary: digest mismatch, got {sha256(target)}")
        print(f"ok dictionary {name}")
    shutil.copyfile(profile, dest / "profile.txt")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    fetch(Path(sys.argv[1]), Path(sys.argv[2]))
