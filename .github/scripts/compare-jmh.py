#!/usr/bin/env python3
"""Compare a JMH results.json against a baseline.json and fail on regressions.

The gate is intentionally conservative because the benchmarks run on shared
GitHub runners:

* A benchmark is flagged only when the current run's lower confidence bound
  (score - scoreError) still exceeds the baseline's upper bound
  (score + scoreError) by more than MAX_REGRESSION_PCT. Within-run JMH noise
  can therefore never trip the gate.
* The caller (ci.yml) re-runs the suite once when this script fails, to rule
  out transient host noise, before failing the job.

Exit code: 1 if any benchmark regressed, 0 otherwise.
"""

import json
import sys

MAX_REGRESSION_PCT = 15.0


def load(path: str) -> dict:
    with open(path) as f:
        return {b["benchmark"]: b["primaryMetric"] for b in json.load(f)}


def main() -> int:
    results_path = "jpdfium/build/results/jmh/results.json"
    baseline_path = "jpdfium/build/results/jmh/baseline.json"

    results = load(results_path)
    baseline = load(baseline_path)

    regressions = []
    for name, m in results.items():
        if name not in baseline:
            continue
        b = baseline[name]
        base = b["score"]
        base_err = b.get("scoreError", 0.0) or 0.0
        score = m["score"]
        err = m.get("scoreError", 0.0) or 0.0

        pct = (score - base) / base * 100.0
        conservative = (score - err - (base + base_err)) / (base + base_err) * 100.0
        status = "REGRESSED" if conservative > MAX_REGRESSION_PCT else "OK"
        print(f"  {status:10s}  {name}: {base:.3f} -> {score:.3f} ms  ({pct:+.1f}%)")
        if conservative > MAX_REGRESSION_PCT:
            regressions.append((name, pct))

    if regressions:
        print(
            f"\nFAIL: {len(regressions)} benchmark(s) regressed by more than "
            f"{MAX_REGRESSION_PCT}% (error-adjusted):"
        )
        for name, pct in regressions:
            print(f"  {name}: +{pct:.1f}%")
        return 1

    print(f"\nAll benchmarks within {MAX_REGRESSION_PCT}% of baseline (error-adjusted).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
