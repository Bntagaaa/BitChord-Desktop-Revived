# Phase 2 numerical evidence

Measured on 2026-10-29 in the same Linux/JDK 21/Xvfb software-rendered environment.
See [the report](../desktop-smoothness-phase2.md) for workloads, interpretation,
limitations and commands. The base is Phase 1 commit `4cf97551`.

- Root CSVs: three alternating settings/GC/shell pairs plus one Reduce Blur shell smoke.
- `steady-motion/`: three alternating pairs, 180 warmup and 600 measured updates,
  with the same draw-counter wrapper in both modes.
- `short-motion/`: earlier 30/180 comparisons, retained because they showed a regression.
  Different warmup/instrumentation means their absolute times cannot be compared
  directly with the steady-motion fixture.
- `aggregate.csv`: median of the **per-run** percentiles and rates; not pooled percentiles.
  `runs` is the number of files containing that metric (zero-observation metrics are absent).
- `resources.csv`: one row per run; peak means maximum of the one-second samples.
- `*.csv.runtime.csv`: sampled CPU, heap, RSS and cumulative EDT allocation. `-1` means
  unavailable. These are process metrics, including startup/JIT/background work.
- `fixtures.json`: only the fixture counters extracted from logs, without paths or user data.
- `jfr-summary.json`: all `jdk.GCPhasePause` durations from one matched settings pair,
  plus coarse execution-sample counts. It covers the whole JVM run, not only measured writes.
- `provenance.json`: exact base and hashes of changed production/harness source files.

CSV frame/callback/draw observations are application-side proxies. They do not measure
display presentation, first network content, physical input latency or audio stability.
No binary JFR, screenshots, fixture homes, account state or network error logs are committed.
