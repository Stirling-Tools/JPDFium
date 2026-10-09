#!/usr/bin/env python3
"""Compare a JMH results.json against a baseline.json and fail on regressions.

The gate is intentionally conservative because the benchmarks run on shared
GitHub runners:

* A benchmark is flagged only when the current run's lower confidence bound
  (score - scoreError) still exceeds the baseline's upper bound
  (score + scoreError) by more than MAX_REGRESSION_PCT. Within-run JMH noise
  can therefore never trip the gate.
* Benchmarks whose baseline score is below NOISE_FLOOR_MS are exempt from the
  gate: on shared runners, very short measurements are dominated by scheduler
  jitter and CPU frequency scaling, so a percentage change there is runner noise,
  not a signal. They are reported but can never fail the job. The floor sits where
  a GitHub-hosted runner stops producing repeatable numbers (see NOISE_FLOOR_MS
  below) rather than at an arbitrary small value: too low a floor gates
  single-digit-microsecond no-ops on numbers no runner can reproduce.
* The caller (ci.yml) re-runs the suite once when this script fails, to rule
  out transient host noise, before failing the job.

Exit code: 1 if any gated benchmark regressed, 0 otherwise.
"""

import json
import sys

MAX_REGRESSION_PCT = 15.0
# Baselines below this are reported but never gated.
#
# A GitHub-hosted runner cannot resolve differences reliably below roughly 50us:
# it shares the vCPU with other jobs, and repeated runs of the same unchanged
# benchmark drift by tens of microseconds at that scale. With a 1us floor, two
# runs of identical code disagreed by +65% and -39% on the same operation within
# one job. Once baselines reflect real (non-stub) natives these benchmarks land
# well above the floor and are gated normally.
NOISE_FLOOR_MS = 0.05

# Whole-document filesystem benchmarks are reported but never gated. Their
# absolute latency is a property of the runner's disk, not of the code under
# test: across two GitHub-hosted runners the same unchanged benchmark
# (splitEveryTenPages) measured +51% while every CPU-bound benchmark in the same
# run measured about -50%. A cached baseline recorded on a different runner
# instance is therefore not a sound reference for whole-document I/O. These stay
# in the report for trend visibility; the FFM/CPU gate is unaffected.
#
# optimizeFileToFile is included because its timed region is dominated by the
# file write (it tracks saveToPath run for run), so a cached cross-runner
# baseline cannot resolve its optimizer CPU work. That CPU cost is gated
# separately and deterministically by PdfOperationBenchmark.optimizeInMemory.
IO_BOUND_BENCHMARKS = {
    "openFromPath",
    "saveToPath",
    "saveToTempFile",
    "splitEveryTenPages",
    "splitMultiRangeEveryTenPages",
    "splitMultiRangeReusingSource",
    "optimizeFileToFile",
}


def load(path: str) -> dict:
    with open(path) as f:
        return {b["benchmark"]: b["primaryMetric"] for b in json.load(f)}


def main() -> int:
    results_path = "jpdfium/build/results/jmh/results.json"
    baseline_path = "jpdfium/build/results/jmh/baseline.json"

    results = load(results_path)
    baseline = load(baseline_path)

    regressions = []
    missing = []
    for name, m in results.items():
        if name not in baseline:
            missing.append(name)
            continue
        b = baseline[name]
        base = b["score"]
        base_err = b.get("scoreError", 0.0) or 0.0
        score = m["score"]
        err = m.get("scoreError", 0.0) or 0.0

        pct = (score - base) / base * 100.0
        conservative = (score - err - (base + base_err)) / (base + base_err) * 100.0
        short = name.rsplit(".", 1)[-1]
        if base < NOISE_FLOOR_MS:
            status = "OK (noise)"
        elif short in IO_BOUND_BENCHMARKS:
            status = "OK (io)"
        elif conservative > MAX_REGRESSION_PCT:
            status = "REGRESSED"
        else:
            status = "OK"
        print(f"  {status:10s}  {name}: {base:.3f} -> {score:.3f} ms  ({pct:+.1f}%)")
        if status == "REGRESSED":
            regressions.append((name, pct))

    if missing:
        print(
            f"\n{len(missing)} benchmark(s) have no baseline entry and are not gated "
            f"this run:"
        )
        for name in sorted(missing):
            print(f"  {name}")

    if regressions:
        print(
            f"\nFAIL: {len(regressions)} benchmark(s) regressed by more than "
            f"{MAX_REGRESSION_PCT}% (error-adjusted):"
        )
        for name, pct in regressions:
            print(f"  {name}: +{pct:.1f}%")
        return 1

    print(f"\nAll gated benchmarks within {MAX_REGRESSION_PCT}% of baseline (error-adjusted).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
