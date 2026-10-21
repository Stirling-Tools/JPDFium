"""Writes <artifact-dir>/maven-metadata.xml plus checksums.

usage: maven_metadata.py <artifact-dir> <live-metadata-or-missing> <version>...
Keeps every version the live file lists and adds the new ones; <latest> and
<release> become the highest version by Maven-style numeric ordering.
"""

import hashlib
import re
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def version_key(version: str) -> tuple:
    parts = re.split(r"[.\-]", version)
    return tuple((0, int(p), "") if p.isdigit() else (-1, 0, p) for p in parts)


def main() -> None:
    artifact_dir = Path(sys.argv[1])
    live = Path(sys.argv[2])
    new_versions = sys.argv[3:]
    group_id, artifact_id = artifact_dir.parent.as_posix().replace("/", "."), artifact_dir.name

    versions = set(new_versions)
    if live.is_file():
        root = ET.parse(live).getroot()
        if root.findtext("groupId") != group_id or root.findtext("artifactId") != artifact_id:
            sys.exit(f"live metadata for {artifact_dir} names a different artifact")
        versions.update(v.text for v in root.findall("./versioning/versions/version") if v.text)

    ordered = sorted(versions, key=version_key)
    releases = [v for v in ordered if not v.endswith("-SNAPSHOT")]

    metadata = ET.Element("metadata")
    ET.SubElement(metadata, "groupId").text = group_id
    ET.SubElement(metadata, "artifactId").text = artifact_id
    versioning = ET.SubElement(metadata, "versioning")
    ET.SubElement(versioning, "latest").text = ordered[-1]
    if releases:
        ET.SubElement(versioning, "release").text = releases[-1]
    versions_el = ET.SubElement(versioning, "versions")
    for v in ordered:
        ET.SubElement(versions_el, "version").text = v
    ET.SubElement(versioning, "lastUpdated").text = time.strftime("%Y%m%d%H%M%S", time.gmtime())
    ET.indent(metadata)

    out = artifact_dir / "maven-metadata.xml"
    data = b'<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(metadata) + b"\n"
    out.write_bytes(data)
    for algo in ("md5", "sha1", "sha256", "sha512"):
        Path(f"{out}.{algo}").write_text(hashlib.new(algo, data).hexdigest())
    print(f"{artifact_dir}: {', '.join(ordered)}")


if __name__ == "__main__":
    main()
