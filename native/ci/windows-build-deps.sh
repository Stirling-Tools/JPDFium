#!/usr/bin/env bash
# Install Windows build deps: preinstalled tools win, choco is a retried fallback.
# Usage: windows-build-deps.sh [package ...]   # default: cmake ninja
set -euo pipefail

packages=("$@")
if [ ${#packages[@]} -eq 0 ]; then
  packages=(cmake ninja)
fi

for tool in "${packages[@]}"; do
  if command -v "$tool" >/dev/null 2>&1; then
    echo "$tool already present: $(command -v "$tool")"
    continue
  fi
  installed=0
  for attempt in 1 2 3; do
    if choco install -y --allow-downgrade --no-progress "$tool"; then
      installed=1
      break
    fi
    echo "choco install $tool failed (attempt $attempt/3); retrying in 15s"
    sleep 15
  done
  if [ "$installed" -ne 1 ]; then
    echo "::error::unable to install $tool after 3 attempts"
    exit 1
  fi
done

# A Chocolatey cmake lands off the inherited PATH; publish it for later steps.
if ! command -v cmake >/dev/null 2>&1 && [ -x "C:/Program Files/CMake/bin/cmake.exe" ]; then
  echo "C:/Program Files/CMake/bin" >> "${GITHUB_PATH:-/dev/null}"
fi
