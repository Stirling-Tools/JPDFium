#!/usr/bin/env bash
set -euo pipefail
for jar in jpdfium-natives/*/build/libs/*.jar; do
  [ -f "$jar" ] || continue
  if ! jar tf "$jar" | grep -q "licenses/"; then
    echo "::warning::No licenses/ dir in $jar — add bundled dep license texts"
  else
    echo "OK licenses in $jar"
  fi
done
