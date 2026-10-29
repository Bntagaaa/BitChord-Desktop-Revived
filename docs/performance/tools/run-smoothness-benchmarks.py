#!/usr/bin/env python3
"""Run isolated, sequential comparisons. Requires a desktop display and an instrumented baseline."""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--baseline", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True, help="new output directory")
parser.add_argument("--gradle-command", default="./gradlew", help="command prefix; never interpreted by a shell")
parser.add_argument("--groups", nargs="+", choices=("settings", "motion", "gc", "shell"), default=["settings", "motion", "gc", "shell"])
args = parser.parse_args()
project = Path(__file__).resolve().parents[3]
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
command = shlex.split(args.gradle_command)

def run(label, number, task, properties=(), checkout=project):
    stem = output / f"{label}-{number}"
    home = output / "homes" / f"{label}-{number}"
    home.mkdir(parents=True)
    invocation = command + ["-p", str(checkout), f":desktopApp:{task}",
                            f"-Psmoothness.home={home}", f"-Psmoothness.output={stem}.csv"] + list(properties)
    result = subprocess.run(invocation, cwd=project, env=os.environ.copy(), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    stem.with_suffix(".log").write_text(result.stdout)
    if result.returncode:
        raise RuntimeError(f"{label}-{number} failed; inspect {stem}.log\n{result.stdout[-2000:]}")
    print(json.dumps({"label": label, "run": number, "fixture_metrics": [line for line in result.stdout.splitlines()
                     if line.startswith(("elapsed_", "motion_legacy", "gc_call", "gc_explicit"))]}), flush=True)

# Alternate modes. Do not run builds, tests, or other benchmarks alongside these comparisons.
if "settings" in args.groups:
    for n in range(1, 4):
        for label, sync in (("settings-sync", "true"), ("settings-async", "false")):
            props = [f"-Psmoothness.sync={sync}"]
            if n == 1:
                props.append(f"-Psmoothness.jfr={output / (label + '-1.jfr')}")
            run(label, n, "smoothnessBench", props)
if "motion" in args.groups:
    for n in range(1, 4):
        for mode in ("legacy", "isolated"):
            run(f"motion-{mode}", n, "motionSceneBench", [f"-Psmoothness.motion={mode}"])
if "gc" in args.groups:
    for n in range(1, 4):
        for mode in ("gc-natural", "gc-explicit"):
            run(mode, n, "smoothnessBench", [f"-Psmoothness.workload={mode}"])
if "shell" in args.groups:
    for n in range(1, 4):
        run("shell-baseline", n, "shellSceneBench", checkout=args.baseline.resolve())
        run("shell-phase2", n, "shellSceneBench")
    run("shell-phase2-reduced", 1, "shellSceneBench", ["-Psmoothness.effects=reduced"])
