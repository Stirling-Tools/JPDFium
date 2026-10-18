#!/usr/bin/env bash
#
# Regression tests for bump-release-version.sh.
#
# The script rewrites release references inside a checkout, so the tests run it
# in a throwaway fixture tree with a fixed set of target files and assert on the
# result. The cases cover what the earlier implementation got wrong: a run used
# to read the version from docs/installation.md and skip the remaining targets
# when the guide already documented the released version, so a partial or manual
# bump stayed partial. They also cover the development version in
# gradle.properties, the version boundary, the downgrade guard, the hyphenated
# prerelease spellings the release format accepts, and the versions the script
# does not own: an unrelated dependency keeps its version even when it shares a
# line with a JPDFium reference.
#
# Usage: bash .github/scripts/bump-release-version-self-test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$SCRIPT_DIR/bump-release-version.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jpdfium-bump-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

PASSED=0
FAILED=0
RUN_RC=0
RUN_OUT=""

check() { # check <description> <0 for pass>
  if [ "$2" -eq 0 ]; then
    printf '  [test ok]   %s\n' "$1"
    PASSED=$((PASSED + 1))
  else
    printf '  [test failed] %s\n' "$1"
    FAILED=$((FAILED + 1))
  fi
}

# fixture <name> <install> <readme> <deployment> <example> <gradle> <javadoc>
fixture() {
  local dir="$WORK/$1"
  mkdir -p "$dir/docs" "$dir/examples/render-first-page" "$dir/.github/scripts"
  cp "$SCRIPT" "$dir/.github/scripts/bump-release-version.sh"

  cat >"$dir/docs/installation.md" <<EOF
JPDFium publishes to Maven Central under the \`com.stirling\` group.
The current release is **$2**.

\`\`\`kotlin
implementation(platform("com.stirling:jpdfium-bom:$2"))
\`\`\`

\`\`\`xml
<dependency>
  <groupId>com.stirling</groupId>
  <artifactId>jpdfium-bom</artifactId>
  <version>$2</version>
</dependency>
<dependency>
  <groupId>some.other.group</groupId>
  <artifactId>some-other-library</artifactId>
  <version>9.9.9</version>
</dependency>
\`\`\`
EOF
  cat >"$dir/README.md" <<EOF
\`\`\`kotlin
implementation(platform("com.stirling:jpdfium-bom:$3"))
implementation("some.other:library:8.8.8") // used alongside jpdfium
\`\`\`

JPDFium 11.2.0 and any other future major line are not covered here.
EOF
  cat >"$dir/docs/deployment.md" <<EOF
\`\`\`kotlin
implementation(platform("com.stirling:jpdfium-bom:$4"))
\`\`\`
EOF
  cat >"$dir/examples/render-first-page/build.gradle.kts" <<EOF
dependencies {
    implementation(platform("com.stirling:jpdfium-bom:$5"))
}
EOF
  printf 'jpdfium.version=%s\n' "$6" >"$dir/gradle.properties"
  printf '[javadoc](https://javadoc.io/doc/com.stirling/jpdfium/%s/)\n' "$7" >"$dir/docs/javadoc.md"
}

run() { # run <dir> <version>, sets RUN_RC and RUN_OUT
  local dir="$1" version="$2"
  if RUN_OUT="$(cd "$dir" && bash .github/scripts/bump-release-version.sh --version "$version" 2>&1)"; then
    RUN_RC=0
  else
    RUN_RC=$?
  fi
}

expect_rc() { # expect_rc <expected> <description>
  if [ "$RUN_RC" -eq "$1" ]; then
    check "$2" 0
  else
    check "$2" 1
    printf '    exit %s, expected %s:\n%s\n' "$RUN_RC" "$1" "$RUN_OUT"
  fi
}

expect_contains() { # expect_contains <file> <text> <description>
  if grep -qF -- "$2" "$1"; then
    check "$3" 0
  else
    check "$3" 1
    printf '    %s does not contain: %s\n' "$1" "$2"
  fi
}

expect_absent() { # expect_absent <file> <text> <description>
  if grep -qF -- "$2" "$1"; then
    check "$3" 1
    printf '    %s still contains: %s\n' "$1" "$2"
  else
    check "$3" 0
  fi
}

expect_occurrences() { # expect_occurrences <file> <text> <count> <description>
  local actual
  actual="$(grep -oF -- "$2" "$1" 2>/dev/null | wc -l | tr -d ' ')"
  if [ "$actual" = "$3" ]; then
    check "$4" 0
  else
    check "$4" 1
    printf '    %s holds %s occurrence(s) of "%s", expected %s\n' "$1" "$actual" "$2" "$3"
  fi
}

expect_no_match() { # expect_no_match <file> <extended regex> <description>
  if grep -qE -- "$2" "$1"; then
    check "$3" 1
    printf '    %s matches: %s\n' "$1" "$2"
  else
    check "$3" 0
  fi
}

echo "Version bump script regression tests"

# The installation guide was already updated by hand, and the other targets
# still point at the previous release.
fixture installation-first 1.2.1 1.2.0 1.2.0 1.2.0 1.2.0 1.2.0
run "$WORK/installation-first" 1.2.1
expect_rc 0 "installation guide updated first: exit code"
expect_contains "$WORK/installation-first/README.md" "jpdfium-bom:1.2.1" "installation guide updated first: README is repaired"
expect_contains "$WORK/installation-first/docs/deployment.md" "jpdfium-bom:1.2.1" "installation guide updated first: deployment guide is repaired"
expect_contains "$WORK/installation-first/examples/render-first-page/build.gradle.kts" "jpdfium-bom:1.2.1" "installation guide updated first: example is repaired"
expect_contains "$WORK/installation-first/docs/javadoc.md" "jpdfium/1.2.1/" "installation guide updated first: javadoc link is repaired"
expect_contains "$WORK/installation-first/README.md" "11.2.0" "a newer unrelated version is left alone"

# A different target was updated first, then the run is retried.
fixture readme-first 1.2.0 1.2.1 1.2.0 1.2.0 1.2.1-SNAPSHOT 1.2.0
run "$WORK/readme-first" 1.2.1
expect_rc 0 "README updated first: exit code"
expect_contains "$WORK/readme-first/docs/installation.md" "current release is **1.2.1**" "README updated first: installation guide is repaired"

fixture example-first 1.2.0 1.2.0 1.2.0 1.2.1 1.2.1-SNAPSHOT 1.2.0
run "$WORK/example-first" 1.2.1
expect_rc 0 "example updated first: exit code"
expect_contains "$WORK/example-first/docs/deployment.md" "jpdfium-bom:1.2.1" "example updated first: deployment guide is repaired"

# The ordinary case: every target still points at the previous release.
fixture ordinary 1.2.0 1.2.0 1.2.0 1.2.0 1.2.1-SNAPSHOT 1.2.0
run "$WORK/ordinary" 1.2.1
expect_rc 0 "ordinary release: exit code"
expect_contains "$WORK/ordinary/docs/installation.md" "current release is **1.2.1**" "ordinary release: guide documents the release"
expect_contains "$WORK/ordinary/README.md" "jpdfium-bom:1.2.1" "ordinary release: README points at the release"
expect_contains "$WORK/ordinary/gradle.properties" "jpdfium.version=1.2.2-SNAPSHOT" "ordinary release: development version moves to the next patch"
expect_contains "$WORK/ordinary/docs/installation.md" "<version>1.2.1</version>" "ordinary release: the com.stirling Maven block is updated"
expect_contains "$WORK/ordinary/docs/installation.md" "<version>9.9.9</version>" "ordinary release: an unrelated Maven dependency keeps its version"
expect_contains "$WORK/ordinary/README.md" "some.other:library:8.8.8" "ordinary release: an unrelated version on a JPDFium line is kept"

# A tree that is already correct must not change, so a rerun is idempotent.
fixture already-correct 1.2.1 1.2.1 1.2.1 1.2.1 1.3.0-SNAPSHOT 1.2.1
BEFORE="$(find "$WORK/already-correct" -type f | sort | xargs shasum -a 256)"
run "$WORK/already-correct" 1.2.1
expect_rc 0 "already correct tree: exit code"
AFTER="$(find "$WORK/already-correct" -type f | sort | xargs shasum -a 256)"
check "already correct tree: no file is rewritten" "$([ "$BEFORE" = "$AFTER" ] && echo 0 || echo 1)"
expect_contains "$WORK/already-correct/gradle.properties" "jpdfium.version=1.3.0-SNAPSHOT" "already correct tree: development version is preserved"

# A release on an older line must not move the development version backwards.
fixture development-ahead 1.2.0 1.2.0 1.2.0 1.2.0 1.3.0-SNAPSHOT 1.2.0
run "$WORK/development-ahead" 1.2.1
expect_rc 0 "development version ahead: exit code"
expect_contains "$WORK/development-ahead/gradle.properties" "jpdfium.version=1.3.0-SNAPSHOT" "development version ahead: it stays ahead"
expect_contains "$WORK/development-ahead/README.md" "jpdfium-bom:1.2.1" "development version ahead: the release is still documented"

# Promoting a prerelease to its stable release updates every reference.
fixture promotion 1.2.1-rc.1 1.2.1-rc.1 1.2.1-rc.1 1.2.1-rc.1 1.2.1-SNAPSHOT 1.2.1-rc.1
run "$WORK/promotion" 1.2.1
expect_rc 0 "prerelease promotion: exit code"
expect_absent "$WORK/promotion/docs/installation.md" "1.2.1-rc.1" "prerelease promotion: the prerelease is gone"
expect_contains "$WORK/promotion/docs/installation.md" "current release is **1.2.1**" "prerelease promotion: the stable release is documented"
expect_contains "$WORK/promotion/gradle.properties" "jpdfium.version=1.2.2-SNAPSHOT" "prerelease promotion: development moves past the stable release"

# A late tag for an older release must be refused instead of rewriting history.
fixture downgrade 1.2.1 1.2.1 1.2.1 1.2.1 1.3.0-SNAPSHOT 1.2.1
run "$WORK/downgrade" 1.2.0
expect_rc 2 "older release is refused: exit code"
expect_contains "$WORK/downgrade/docs/installation.md" "current release is **1.2.1**" "older release is refused: nothing is rewritten"

# A stale javadoc.io link alone is enough to make the file a target.
fixture javadoc-only 1.2.1 1.2.1 1.2.1 1.2.1 1.3.0-SNAPSHOT 1.2.0
run "$WORK/javadoc-only" 1.2.1
expect_rc 0 "stale javadoc link: exit code"
expect_contains "$WORK/javadoc-only/docs/javadoc.md" "jpdfium/1.2.1/" "stale javadoc link: the link is repaired"

# Hyphenated prereleases are accepted by the format gate, so they have to work
# end to end: the token must be read whole, replaced whole, and counted whole.
fixture hyphen-promotion 1.3.0-rc-1 1.3.0-rc-1 1.3.0-rc-1 1.3.0-rc-1 1.2.0 1.3.0-rc-1
run "$WORK/hyphen-promotion" 1.3.0
expect_rc 0 "hyphenated prerelease to stable: exit code"
expect_contains "$WORK/hyphen-promotion/docs/installation.md" "current release is **1.3.0**" "hyphenated prerelease to stable: the stable release is documented"
expect_no_match "$WORK/hyphen-promotion/docs/installation.md" "1\\.3\\.0-rc-1([^0-9]|$)" "hyphenated prerelease to stable: no fragment is left behind"
expect_contains "$WORK/hyphen-promotion/docs/installation.md" "<version>1.3.0</version>" "hyphenated prerelease to stable: the Maven block is updated"
expect_contains "$WORK/hyphen-promotion/docs/installation.md" "<version>9.9.9</version>" "hyphenated prerelease to stable: the unrelated dependency is kept"
expect_contains "$WORK/hyphen-promotion/gradle.properties" "jpdfium.version=1.3.1-SNAPSHOT" "hyphenated prerelease to stable: development moves on"

fixture hyphen-to-hyphen 1.3.0-rc-1 1.3.0-rc-1 1.3.0-rc-1 1.3.0-rc-1 1.2.0 1.3.0-rc-1
run "$WORK/hyphen-to-hyphen" 1.3.0-rc-2
expect_rc 0 "hyphenated prerelease to prerelease: exit code"
expect_contains "$WORK/hyphen-to-hyphen/docs/installation.md" "current release is **1.3.0-rc-2**" "hyphenated prerelease to prerelease: the new prerelease is documented"
expect_contains "$WORK/hyphen-to-hyphen/gradle.properties" "jpdfium.version=1.3.0-SNAPSHOT" "hyphenated prerelease to prerelease: development tracks the core"

# A shorter prerelease must not match inside a longer one, and a stale shorter
# one in an owned field is still reconciled.
fixture boundary 1.3.0-rc-11 1.3.0-rc-11 1.3.0-rc-11 1.3.0-rc-11 1.2.0 1.3.0-rc-11
printf 'A second block still pins the earlier candidate:\n\nimplementation(platform("com.stirling:jpdfium-bom:1.3.0-rc-1"))\n' \
  >>"$WORK/boundary/docs/installation.md"
run "$WORK/boundary" 1.3.0-rc-11
expect_rc 0 "prerelease boundary: exit code"
expect_occurrences "$WORK/boundary/docs/installation.md" "jpdfium-bom:1.3.0-rc-11" 2 "prerelease boundary: the stale shorter token is reconciled"
expect_no_match "$WORK/boundary/docs/installation.md" "jpdfium-bom:1\\.3\\.0-rc-1([^0-9]|$)" "prerelease boundary: no standalone shorter token remains"

# A checkout whose gradle.properties lost its version line must fail instead of
# reporting a reconciliation it cannot perform.
fixture no-property 1.2.0 1.2.0 1.2.0 1.2.0 1.3.0-SNAPSHOT 1.2.0
printf '# no version property here\n' >"$WORK/no-property/gradle.properties"
run "$WORK/no-property" 1.2.1
expect_rc 1 "missing jpdfium.version: exit code"
case "$RUN_OUT" in
  *"no jpdfium.version line"*)
    check "missing jpdfium.version: the error names the property" 0
    ;;
  *)
    check "missing jpdfium.version: the error names the property" 1
    ;;
esac

printf 'Version bump script regression tests: %s passed, %s failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
