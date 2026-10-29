#!/usr/bin/env python3
"""Summarize per-run percentiles, without pretending they are pooled percentiles."""
import argparse
import csv
import json
from pathlib import Path
import re
from statistics import median

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directory", type=Path)
args = parser.parse_args()
root = args.directory
groups = {}
fixtures = {}
resources = []
for file in sorted(root.glob("*.csv")):
    if file.name.endswith(".runtime.csv") or file.name in ("aggregate.csv", "resources.csv"):
        continue
    label = re.sub(r"-\d+$", "", file.stem)
    for row in csv.DictReader(file.open()):
        if "metric" not in row:
            continue
        groups.setdefault((label, row["metric"]), []).append(row)
    runtime = file.with_name(file.name + ".runtime.csv")
    if runtime.exists():
        values = [{key: int(value) for key, value in row.items()} for row in csv.DictReader(runtime.open())]
        if len(values) > 1:
            elapsed = (values[-1]["monotonic_ns"] - values[0]["monotonic_ns"]) / 1e9
            resources.append({
                "run": file.stem, "sampled_seconds": elapsed,
                "process_cpu_percent_one_core": (values[-1]["process_cpu_ns"] - values[0]["process_cpu_ns"]) / 1e9 / elapsed * 100,
                "peak_sampled_rss_mib": max(row["rss_bytes"] for row in values) / 2**20,
                "peak_sampled_heap_used_mib": max(row["heap_used_bytes"] for row in values) / 2**20,
                "edt_allocated_delta_mib": (values[-1]["edt_allocated_bytes"] - values[0]["edt_allocated_bytes"]) / 2**20,
            })
    log = file.with_suffix(".log")
    if log.exists():
        fixtures[file.stem] = [line for line in log.read_text().splitlines()
                               if line.startswith(("elapsed_", "motion_legacy", "gc_call", "gc_explicit"))]
rows = []
for (label, metric), runs in sorted(groups.items()):
    row = {"variant": label, "metric": metric, "runs": len(runs)}
    for key in ("count", "p50_ms", "p95_ms", "p99_ms"):
        row[f"median_{key}"] = median(float(run[key]) for run in runs)
    for key in ("over_16_67ms", "over_8_33ms"):
        row[f"median_{key}_percent"] = median(int(run[key]) / int(run["count"]) * 100 for run in runs)
    rows.append(row)
with (root / "aggregate.csv").open("w") as handle:
    writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
if resources:
    with (root / "resources.csv").open("w") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(resources[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(resources)
(root / "fixtures.json").write_text(json.dumps(fixtures, indent=2) + "\n")
print(f"Wrote {len(rows)} aggregate rows, {len(resources)} resource rows, and fixture counters.")
