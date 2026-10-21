#!/usr/bin/env bash
# upload-to-r2.sh <maven-repo-dir> [<bucket-prefix>]
# Uploads a Maven repo dir to the bucket behind https://maven.stirlingpdf.com.
# Fails if any version is already released at the root. Without a prefix it also
# rewrites each maven-metadata.xml from the live copy, last; callers must hold the
# stirling-maven concurrency group or two releases drop each other's version.
set -euo pipefail

REPO_DIR="${1:?usage: upload-to-r2.sh <maven-repo-dir> [<bucket-prefix>]}"
PREFIX="${2:-}"
BUCKET="${R2_BUCKET:-s3://stirling-maven}"
: "${R2_ENDPOINT:?R2_ENDPOINT is required}"
export AWS_DEFAULT_REGION=auto
# Not every S3-compatible store accepts the CRC checksums newer AWS CLIs send.
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required
export AWS_RESPONSE_CHECKSUM_VALIDATION=when_required
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

s3() { aws s3 "$@" --endpoint-url "$R2_ENDPOINT" --no-progress; }
key_count() {
  aws s3api list-objects-v2 --endpoint-url "$R2_ENDPOINT" --bucket "${BUCKET#s3://}" \
    --prefix "$1" --max-items 1 --query 'KeyCount' --output text
}

cd "$REPO_DIR"
mapfile -t VERSION_DIRS < <(find . -name '*.pom' -printf '%h\n' | sed 's#^\./##' | sort -u)
if [ "${#VERSION_DIRS[@]}" -eq 0 ]; then
  echo "ERROR: no publications under $REPO_DIR" >&2
  exit 1
fi

for dir in "${VERSION_DIRS[@]}"; do
  count=$(key_count "$dir/")
  if [ "$count" != "0" ] && [ "$count" != "None" ]; then
    echo "ERROR: $dir is already released; published versions are immutable." >&2
    exit 1
  fi
done

find . -name 'maven-metadata.xml*' -delete
s3 sync . "$BUCKET/$PREFIX"
echo "Uploaded ${#VERSION_DIRS[@]} publications to $BUCKET/$PREFIX"

if [ -n "$PREFIX" ]; then
  exit 0
fi

mapfile -t ARTIFACT_DIRS < <(printf '%s\n' "${VERSION_DIRS[@]}" | xargs -n1 dirname | sort -u)
for artifact in "${ARTIFACT_DIRS[@]}"; do
  live="$artifact/maven-metadata.live.xml"
  s3 cp "$BUCKET/$artifact/maven-metadata.xml" "$live" >/dev/null 2>&1 || rm -f "$live"
  mapfile -t new_versions < <(printf '%s\n' "${VERSION_DIRS[@]}" | grep "^$artifact/[^/]*$" | xargs -n1 basename)
  python3 -I "$SCRIPT_DIR/maven_metadata.py" "$artifact" "$live" "${new_versions[@]}"
  rm -f "$live"
  for f in "$artifact"/maven-metadata.xml*; do
    s3 cp "$f" "$BUCKET/$f"
  done
done
echo "Updated maven-metadata.xml for ${#ARTIFACT_DIRS[@]} artifacts"
