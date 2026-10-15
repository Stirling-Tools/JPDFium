#!/usr/bin/env bash
# Retry a command while it fails for a transient network reason.
#
# Shared CI runners get HTTP 429/5xx, connection resets and read timeouts from
# the services this repo depends on (Maven Central, the Gradle plugin portal,
# Chocolatey, apt mirrors, download.java.net, ...). Those failures are not the
# build's fault and almost always succeed on a second attempt, so run the
# command through this wrapper instead of failing the job.
#
# Usage: retry.sh <attempts> [--delay <seconds>] -- <command> [args...]
#   bash .github/scripts/retry.sh 3 -- curl -fsSL https://example/a.jar -o a.jar
#
# The command's own exit status is returned on the last attempt. A failure that
# does not look transient is reported immediately: retrying a real compile
# error only burns CI minutes.
set -euo pipefail

attempts=3
delay=15

# The attempt count is the first positional argument; flags follow it.
if [ $# -gt 0 ] && [ -z "${1//[0-9]/}" ]; then
  attempts="$1"
  shift
fi

while [ $# -gt 0 ]; do
  case "$1" in
    --delay)
      delay="$2"
      shift 2
      ;;
    --)
      shift
      break
      ;;
    *)
      break
      ;;
  esac
done

if [ $# -eq 0 ]; then
  echo "usage: retry.sh <attempts> [--delay <seconds>] -- <command> [args...]" >&2
  exit 2
fi
if ! [ "$attempts" -ge 1 ] 2>/dev/null; then
  echo "retry.sh: attempts must be a positive integer" >&2
  exit 2
fi

# Signatures of a rate-limited or flaky network/service, worth another try.
transient_re='HTTP 429|Too Many Requests|502 Bad Gateway|503 Service Unavailable|504 Gateway Timeout|Could not (GET|resolve|download)|Connection (reset|refused|timed out|closed)|Temporary failure|Read timed out|network is unreachable|EOF occurred|SSL_ERROR|Failed to fetch|Hash Sum mismatch|Resource temporarily unavailable'

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

status=0
for attempt in $(seq 1 "$attempts"); do
  if "$@" 2>&1 | tee "$log"; then
    exit 0
  fi
  status="${PIPESTATUS[0]}"

  if ! grep -Eq "$transient_re" "$log"; then
    echo "retry.sh: '$1' failed for a non-transient reason; not retrying" >&2
    exit "$status"
  fi
  if [ "$attempt" -lt "$attempts" ]; then
    echo "retry.sh: '$1' hit a transient failure (attempt $attempt/$attempts); retrying in ${delay}s" >&2
    sleep "$delay"
  fi
done

echo "retry.sh: '$1' still failing after $attempts attempts" >&2
exit "$status"
