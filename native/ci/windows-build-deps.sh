#!/usr/bin/env bash
# Install Windows build deps: preinstalled tools win, choco is a retried fallback.
# Usage: windows-build-deps.sh [--optional] [package ...]   # default: cmake ninja
#
# The Chocolatey community feed is a commonly rate-limited service: it throttles
# shared-runner IPs and can serve a stale V2 index, which fails resolution with
#   Failed to fetch results from V2 feed ... Unable to resolve dependencies
#   'cmake.install 4.4.3' is not compatible with 'cmake 4.4.4 constraint: ...'
# Those are transient, so every install is retried. After the first failure the
# retry drops dependency resolution: the tools installed here are self-contained
# and the stale index is exactly what breaks them.
#
# --optional: warn and continue when a package cannot be installed (used for
# pkgconf, which vcpkg also provides inside the triplet tree).
set -euo pipefail

optional=0
packages=()
for arg in "$@"; do
  case "$arg" in
    --optional|--best-effort) optional=1 ;;
    *) packages+=("$arg") ;;
  esac
done
if [ ${#packages[@]} -eq 0 ]; then
  packages=(cmake ninja)
fi

# Some tools are published under more than one package id; try them in order.
pkg_ids_for() {
  case "$1" in
    pkgconf|pkg-config) echo "pkgconf pkgconfiglite" ;;
    *) echo "$1" ;;
  esac
}

tool_usable() {
  case "$1" in
    pkgconf|pkg-config) command -v pkg-config >/dev/null 2>&1 ;;
    *) command -v "$1" >/dev/null 2>&1 ;;
  esac
}

for tool in "${packages[@]}"; do
  if tool_usable "$tool"; then
    echo "$tool already present"
    continue
  fi

  installed=0
  for id in $(pkg_ids_for "$tool"); do
    for attempt in 1 2 3; do
      if [ "$attempt" -eq 1 ]; then
        extra=()
      else
        extra=(--ignore-dependencies)
      fi
      # choco also exits non-zero for a package that is already installed, so
      # accept the install when the tool becomes usable regardless of its code.
      if choco install -y --allow-downgrade --no-progress ${extra[@]+"${extra[@]}"} "$id" \
        || tool_usable "$tool" || tool_usable "$id"; then
        installed=1
        break
      fi
      echo "choco install $id failed (attempt $attempt/3); retrying in 15s"
      sleep 15
    done
    [ "$installed" -eq 1 ] && break
  done

  if [ "$installed" -ne 1 ]; then
    if [ "$optional" -eq 1 ]; then
      echo "::warning::could not install $tool from the Chocolatey feed; continuing"
      continue
    fi
    echo "::error::unable to install $tool after 3 attempts"
    exit 1
  fi
done

# A Chocolatey cmake lands off the inherited PATH; publish it for later steps.
if ! command -v cmake >/dev/null 2>&1 && [ -x "C:/Program Files/CMake/bin/cmake.exe" ]; then
  echo "C:/Program Files/CMake/bin" >> "${GITHUB_PATH:-/dev/null}"
fi
