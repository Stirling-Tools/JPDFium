#!/usr/bin/env bash
set -euo pipefail
ROOT=".github/workflows"
FAIL=0
while IFS= read -r line; do
  file=$(echo "$line" | cut -d: -f1)
  match=$(echo "$line" | cut -d: -f2-)
  # Allowlist: actions that are intentionally floating (e.g. local composite actions)
  if echo "$match" | grep -Eq 'uses:\s*\./'; then continue; fi
  if echo "$match" | grep -Eq 'uses:\s*[^@]+@[0-9a-f]{40}'; then continue; fi
  if echo "$match" | grep -Eq 'uses:\s*docker://'; then continue; fi
  echo "::warning file=$file::Unpinned action: $match (pin to full commit SHA)"
  FAIL=1
done < <(grep -R -n "uses:" "$ROOT" 2>/dev/null || true)
if [ "$FAIL" -ne 0 ]; then
  echo "Action pin lint: $FAIL unpinned action(s) - pin every action to a full commit SHA (with the version comment)."
  exit 1
fi
echo "Action pin lint: all actions pinned to commit SHAs."
exit 0
