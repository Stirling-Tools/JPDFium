#!/usr/bin/env bash
# Downloads and unpacks a minimal FreeBSD 14 base sysroot for cross-compiling.
# Performs atomic installation on the destination filesystem, SHA256 integrity verification,
# symlink validation, and writes a manifest.
set -euo pipefail

SYSROOT_DIR="${1:-${PWD}/native/freebsd-sysroot}"
FREEBSD_VER="${2:-14.4-RELEASE}"
ARCH="${3:-amd64}"

if [ "${FREEBSD_VER}" != "14.4-RELEASE" ] || [ "${ARCH}" != "amd64" ]; then
    echo "ERROR: Unsupported FreeBSD version/arch: ${FREEBSD_VER} / ${ARCH}. Supported: 14.4-RELEASE / amd64." >&2
    exit 1
fi

PARENT_DIR="$(cd "$(dirname "${SYSROOT_DIR}")" 2>/dev/null && pwd || { mkdir -p "$(dirname "${SYSROOT_DIR}")" && cd "$(dirname "${SYSROOT_DIR}")" && pwd; })"
TARGET_BASE="$(basename "${SYSROOT_DIR}")"
SYSROOT_DIR="${PARENT_DIR}/${TARGET_BASE}"

MANIFEST_FILE="${SYSROOT_DIR}/.sysroot-manifest"
SCRIPT_REVISION="2026-10-19-r2"

if [ -f "${MANIFEST_FILE}" ] && grep -q "status=complete" "${MANIFEST_FILE}" && grep -q "revision=${SCRIPT_REVISION}" "${MANIFEST_FILE}"; then
    echo "FreeBSD sysroot already present and verified at ${SYSROOT_DIR}."
    exit 0
fi

echo "Setting up FreeBSD ${FREEBSD_VER} (${ARCH}) sysroot at ${SYSROOT_DIR}..."

# Serialize concurrent installations targeting the same parent directory / sysroot
LOCK_DIR="${PARENT_DIR}/.sysroot-install.lock"
while ! mkdir "${LOCK_DIR}" 2>/dev/null; do
    echo "  Another installer is running. Waiting for lock ${LOCK_DIR}..."
    sleep 3
    if [ -f "${MANIFEST_FILE}" ] && grep -q "status=complete" "${MANIFEST_FILE}" && grep -q "revision=${SCRIPT_REVISION}" "${MANIFEST_FILE}"; then
        echo "FreeBSD sysroot setup completed by concurrent installer at ${SYSROOT_DIR}."
        exit 0
    fi
done

# Ensure lock and temporary staging directory are cleaned up on exit
STAGE_DIR="${PARENT_DIR}/.${TARGET_BASE}.staging.$$"
BACKUP_DIR="${PARENT_DIR}/.${TARGET_BASE}.old.$$"
PUBLISHED=0

cleanup() {
    local sig="${1:-0}"
    rm -rf "${STAGE_DIR}" "${LOCK_DIR}"
    if [ "${PUBLISHED}" -eq 1 ]; then
        if [ -d "${BACKUP_DIR}" ]; then
            rm -rf "${BACKUP_DIR}"
        fi
    else
        # If publication did not complete, restore the original installation from backup if present
        if [ -d "${BACKUP_DIR}" ]; then
            if [ ! -d "${SYSROOT_DIR}" ]; then
                echo "Interrupted or failed during publication: restoring sysroot backup from ${BACKUP_DIR}..." >&2
                mv "${BACKUP_DIR}" "${SYSROOT_DIR}" || true
            else
                rm -rf "${BACKUP_DIR}"
            fi
        fi
    fi
    if [ "$sig" -ne 0 ]; then
        exit $((128 + sig))
    fi
}
trap 'cleanup 0' EXIT
trap 'cleanup 2' INT
trap 'cleanup 15' TERM

mkdir -p "${STAGE_DIR}"

BASE_TXZ="${STAGE_DIR}/base.txz"
BASE_URL="https://download.freebsd.org/releases/${ARCH}/${FREEBSD_VER}/base.txz"
EXPECTED_BASE_SHA="769f60a6eea2938ad6b7943cfbcc17dfaf7d1f7ba59a32b869a00349302df853"

echo "Downloading FreeBSD base.txz..."
curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors --connect-timeout 30 -o "${BASE_TXZ}" "${BASE_URL}"

ACTUAL_BASE_SHA="$(sha256sum "${BASE_TXZ}" 2>/dev/null | awk '{print $1}' || shasum -a 256 "${BASE_TXZ}" | awk '{print $1}')"
if [ "${ACTUAL_BASE_SHA}" != "${EXPECTED_BASE_SHA}" ]; then
    echo "ERROR: base.txz SHA256 mismatch! Expected ${EXPECTED_BASE_SHA}, got ${ACTUAL_BASE_SHA}" >&2
    exit 1
fi

echo "Extracting essential FreeBSD headers and libraries..."
tar -xf "${BASE_TXZ}" -C "${STAGE_DIR}" \
    ./usr/include \
    ./usr/lib \
    ./lib

rm -f "${BASE_TXZ}"

# 2. Extract essential FreeBSD packages needed by PDFium, resolved from the repository index
echo "Installing FreeBSD build dependencies into sysroot..."
python3 - "${STAGE_DIR}" <<'PY_EOF'
import os, sys, json, time, http.client, urllib.error, urllib.request, subprocess, tarfile, io

stage_dir = os.path.abspath(sys.argv[1])
usr_inc = os.path.join(stage_dir, "usr", "include")
usr_lib = os.path.join(stage_dir, "usr", "lib")

REQUIRED_PACKAGES = [
    "fontconfig", "expat", "pcre2", "freetype2", "harfbuzz", "icu", "qpdf",
    "pugixml", "libunibreak", "png", "jpeg-turbo", "graphite2", "glib",
    "gettext-runtime", "brotli",
]
REPO_BASE = "https://pkg.freebsd.org/FreeBSD:14:amd64/quarterly/"

def fetch(url, attempts=4, timeout=120):
    """Fetch a package URL, retrying transient mirror errors and network failures."""
    last_error = None
    for attempt in range(1, attempts + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "curl/7.68.0"})
            return urllib.request.urlopen(req, timeout=timeout).read()
        except urllib.error.HTTPError as e:
            if e.code not in (404, 408, 429, 500, 502, 503, 504):
                raise
            last_error = e
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.IncompleteRead) as e:
            last_error = e
        if attempt < attempts:
            delay = min(2 ** attempt, 15)
            print(f"    transient error ({last_error}); retrying in {delay}s")
            time.sleep(delay)
    raise last_error

def load_repo_index():
    """Resolve packages from the repository index instead of pinning hashed URLs.

    Quarterly rebuilds replace the content-addressed All/Hashed/ links, so a
    pinned URL 404s as soon as a package is rebuilt. The index is fetched from
    the same repository and lists the current All/Hashed/ path for each name.
    """
    raw = fetch(REPO_BASE + "packagesite.pkg")
    proc = subprocess.run(["zstd", "-dc"], input=raw, capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(f"zstd decompression failed: {proc.stderr.decode('utf-8', errors='ignore')}")
    with tarfile.open(fileobj=io.BytesIO(proc.stdout), mode="r:") as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith("packagesite.yaml"))
        content = tar.extractfile(member).read().decode("utf-8", errors="replace")
    index = {}
    for line in content.splitlines():
        line = line.strip()
        if not line:
            continue
        entry = json.loads(line)
        index[entry["name"]] = entry
    return index

index = load_repo_index()
for name in REQUIRED_PACKAGES:
    try:
        entry = index.get(name)
        if entry is None:
            raise RuntimeError(f"{name} is not present in the {REPO_BASE} index")
        url = REPO_BASE + entry["repopath"]
        print(f"  Fetching FreeBSD {name} {entry['version']} package...")
        raw_data = fetch(url)

        proc = subprocess.Popen(["zstd", "-dc"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        data, err = proc.communicate(input=raw_data)
        if proc.returncode != 0:
            raise RuntimeError(f"zstd decompression failed: {err.decode('utf-8', errors='ignore')}")
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as tar:
            for m in tar.getmembers():
                dest = None
                if m.name.startswith("/usr/local/include/"):
                    rel = m.name[len("/usr/local/include/"):]
                    dest = os.path.normpath(os.path.join(usr_inc, rel))
                    if not dest.startswith(stage_dir + os.sep):
                        continue
                    if m.isdir():
                        os.makedirs(dest, exist_ok=True)
                    elif m.isfile():
                        os.makedirs(os.path.dirname(dest), exist_ok=True)
                        with open(dest, "wb") as f_out:
                            f_out.write(tar.extractfile(m).read())
                elif m.name.startswith("/usr/local/lib/"):
                    rel = m.name[len("/usr/local/lib/"):]
                    if "/" not in rel:
                        dest = os.path.normpath(os.path.join(usr_lib, rel))
                        if not dest.startswith(stage_dir + os.sep):
                            continue
                        if m.isfile():
                            with open(dest, "wb") as f_out:
                                f_out.write(tar.extractfile(m).read())
                        elif m.islnk():
                            extracted = tar.extractfile(m)
                            if extracted is not None:
                                with open(dest, "wb") as f_out:
                                    f_out.write(extracted.read())
                        elif m.issym():
                            target = m.linkname
                            if target.startswith("/usr/local/lib/"):
                                target = target[len("/usr/local/lib/"):]
                            elif target.startswith("/usr/lib/"):
                                target = target[len("/usr/lib/"):]
                            # Ensure relative target does not traverse outside usr_lib
                            resolved = os.path.normpath(os.path.join(usr_lib, target))
                            if not resolved.startswith(stage_dir + os.sep):
                                continue
                            if os.path.exists(dest) or os.path.islink(dest):
                                os.unlink(dest)
                            os.symlink(target, dest)
                elif m.name.startswith("/usr/local/libdata/pkgconfig/"):
                    rel = m.name[len("/usr/local/libdata/pkgconfig/"):]
                    pkgconf_dir = os.path.join(stage_dir, "usr", "libdata", "pkgconfig")
                    dest = os.path.normpath(os.path.join(pkgconf_dir, rel))
                    if not dest.startswith(stage_dir + os.sep):
                        continue
                    os.makedirs(pkgconf_dir, exist_ok=True)
                    if m.isfile():
                        content = tar.extractfile(m).read().decode('utf-8', errors='ignore')
                        # Rewrite prefix=/usr/local to prefix=/usr so pkg-config resolves sysroot correctly
                        content = content.replace("prefix=/usr/local", "prefix=/usr")
                        with open(dest, "w", encoding="utf-8") as f_out:
                            f_out.write(content)
        print(f"  Installed FreeBSD {name}.")
    except Exception as e:
        print(f"  ERROR: failed to install FreeBSD {name}: {e}")
        sys.exit(1)
PY_EOF

# 3. Sanitize symlinks: convert absolute symlinks to relative so they resolve inside sysroot
echo "Sanitizing symlinks in sysroot..."
python3 - "${STAGE_DIR}" <<'PY_EOF'
import os, sys

sysroot = os.path.abspath(sys.argv[1])
usr_lib = os.path.join(sysroot, "usr", "lib")

if os.path.exists(usr_lib):
    for root, dirs, files in os.walk(usr_lib):
        for f in files:
            path = os.path.join(root, f)
            if os.path.islink(path):
                target = os.readlink(path)
                if target.startswith("/lib/"):
                    rel_target = os.path.relpath(os.path.join(sysroot, target.lstrip("/")), root)
                    os.unlink(path)
                    os.symlink(rel_target, path)
                elif target.startswith("/usr/lib/"):
                    rel_target = os.path.relpath(os.path.join(sysroot, target.lstrip("/")), root)
                    os.unlink(path)
                    os.symlink(rel_target, path)
print("  Symlinks sanitized.")
PY_EOF

# 4. Comprehensive validation of required headers and libraries before declaration of completion
echo "Validating sysroot completeness..."
REQUIRED_FILES=(
    "usr/include/stdlib.h"
    "usr/include/fontconfig/fontconfig.h"
    "usr/include/expat.h"
    "usr/include/pcre2.h"
    "usr/include/freetype2/ft2build.h"
    "usr/include/harfbuzz/hb.h"
    "usr/include/unicode/ucnv.h"
    "usr/include/qpdf/QPDF.hh"
    "usr/include/pugixml.hpp"
    "usr/include/linebreak.h"
    "usr/lib/libc.so"
    "usr/lib/libm.so"
    "usr/lib/libthr.so"
    "usr/lib/libfontconfig.so"
    "usr/lib/libexpat.so"
    "usr/lib/libpcre2-8.so"
    "usr/lib/libpcre2-32.so"
    "usr/lib/libfreetype.so"
    "usr/lib/libharfbuzz.so"
    "usr/lib/libicuuc.so"
    "usr/lib/libqpdf.so"
    "usr/lib/libpugixml.so"
    "usr/lib/libunibreak.so"
)
for rf in "${REQUIRED_FILES[@]}"; do
    if [ ! -e "${STAGE_DIR}/${rf}" ]; then
        echo "ERROR: Sysroot validation failed - missing required file: ${rf}" >&2
        exit 1
    fi
done

# 5. Write verified completion manifest inside staging directory
cat > "${STAGE_DIR}/.sysroot-manifest" <<EOF
version=${FREEBSD_VER}
arch=${ARCH}
revision=${SCRIPT_REVISION}
base_sha256=${EXPECTED_BASE_SHA}
status=complete
EOF

# 6. Atomic publication on the SAME filesystem:
# Move existing SYSROOT_DIR to BACKUP_DIR first, then rename STAGE_DIR to SYSROOT_DIR.
# If anything fails, restore from BACKUP_DIR.
if [ -d "${SYSROOT_DIR}" ]; then
    mv "${SYSROOT_DIR}" "${BACKUP_DIR}"
fi

if mv "${STAGE_DIR}" "${SYSROOT_DIR}"; then
    PUBLISHED=1
    rm -rf "${BACKUP_DIR}"
    echo "FreeBSD sysroot successfully verified and atomically published at ${SYSROOT_DIR}."
else
    echo "ERROR: Failed to move staged sysroot to ${SYSROOT_DIR}! Restoring backup..." >&2
    if [ -d "${BACKUP_DIR}" ]; then
        mv "${BACKUP_DIR}" "${SYSROOT_DIR}"
    fi
    exit 1
fi
