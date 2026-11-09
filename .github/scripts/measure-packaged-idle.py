#!/usr/bin/env python3
"""Measure real jpackage launchers, never Gradle. Scope: clean welcome-window idle."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import tempfile
import time


def kib_fields(text):
    fields = {}
    for line in text.splitlines():
        key, separator, value = line.partition(":")
        parts = value.split()
        if separator and len(parts) == 2 and parts[1] == "kB":
            fields[key] = int(parts[0]) * 1024
    return fields


def sample(pid):
    proc = Path("/proc") / str(pid)
    status = kib_fields((proc / "status").read_text())
    rollup = kib_fields((proc / "smaps_rollup").read_text())
    # comm may contain spaces and parentheses. Fields after its closing ')' start at state.
    stat = (proc / "stat").read_text().rsplit(")", 1)[1].split()
    return {
        "monotonic_s": time.monotonic(),
        "rss_bytes": status.get("VmRSS"),
        "pss_bytes": rollup.get("Pss"),
        "private_bytes": rollup.get("Private_Clean", 0) + rollup.get("Private_Dirty", 0),
        "swap_pss_bytes": rollup.get("SwapPss"),
        "cpu_s": (int(stat[11]) + int(stat[12])) / os.sysconf("SC_CLK_TCK"),
        "threads": int(stat[17]),
    }


def command(args):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=30)
    return {"exit_code": result.returncode, "output": result.stdout}


def summarize(samples):
    answer = {}
    for key in ("rss_bytes", "pss_bytes", "private_bytes", "swap_pss_bytes", "threads"):
        values = [row[key] for row in samples if row[key] is not None]
        answer[key] = {"median": statistics.median(values), "max": max(values)} if values else None
    if len(samples) > 1:
        elapsed = samples[-1]["monotonic_s"] - samples[0]["monotonic_s"]
        answer["cpu_percent_one_core"] = (
            (samples[-1]["cpu_s"] - samples[0]["cpu_s"]) / elapsed * 100
        )
    return answer


def measure(label, launcher, output, seconds, round_number):
    run_dir = output / f"{round_number}-{label}"
    run_dir.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix="bitchord-profile-") as scratch:
        home = Path(scratch)
        env = {key: os.environ[key] for key in
               ("PATH", "DISPLAY", "XAUTHORITY", "LANG", "LC_ALL") if key in os.environ}
        env.update({
            "HOME": str(home), "XDG_CONFIG_HOME": str(home / "config"),
            "XDG_CACHE_HOME": str(home / "cache"), "XDG_DATA_HOME": str(home / "data"),
            "XDG_STATE_HOME": str(home / "state"),
            "BITCHORD_SHOW_WELCOME": "1", "BITCHORD_PERF_DIAGNOSTICS": "1",
            # Identical instrumentation on both unmodified packaged launchers.
            "JAVA_TOOL_OPTIONS": "-XX:NativeMemoryTracking=summary "
                                 f"-Duser.home={home} -Djava.util.prefs.userRoot={home / 'prefs'}",
        })
        with (run_dir / "application.log").open("w") as log:
            started = time.monotonic()
            process = subprocess.Popen([str(launcher)], env=env, stdout=log,
                                       stderr=subprocess.STDOUT, cwd=home)
            try:
                windows = []
                while time.monotonic() - started < 60:
                    if process.poll() is not None:
                        raise RuntimeError(f"{label} exited before opening a window: {process.returncode}")
                    found = subprocess.run(
                        ["xdotool", "search", "--onlyvisible", "--pid", str(process.pid)],
                        text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5)
                    windows = found.stdout.split()
                    if windows:
                        break
                    time.sleep(0.25)
                if not windows:
                    raise RuntimeError(f"{label}: no visible native window in 60 seconds")
                window_ms = (time.monotonic() - started) * 1000
                # This is a native window latency, NOT an asserted usable-frame latency.
                time.sleep(20)
                samples = []
                for _ in range(seconds):
                    if process.poll() is not None:
                        raise RuntimeError(f"{label} exited during sampling: {process.returncode}")
                    samples.append(sample(process.pid))
                    time.sleep(1)
                # NMT tracks JVM allocations, not all third-party/driver native memory.
                jcmd = shutil.which("jcmd")
                diagnostics = {}
                if jcmd:
                    for name, args in (
                        ("heap", ["GC.heap_info"]),
                        ("native_memory", ["VM.native_memory", "summary", "scale=KB"]),
                        ("flags", ["VM.flags"]),
                    ):
                        diagnostics[name] = command([jcmd, str(process.pid), *args])
                        (run_dir / f"{name}.txt").write_text(diagnostics[name]["output"])
                screenshot = command(["import", "-window", "root", str(run_dir / "window.png")])
                result = {
                    "label": label, "round": round_number, "pid": process.pid,
                    "scenario": "forced-welcome-clean-profile-visible-idle",
                    "native_window_ms": window_ms,
                    "warmup_s": 20, "sample_s": seconds,
                    "summary": summarize(samples), "samples": samples,
                    "jcmd_exit_codes": {key: value["exit_code"] for key, value in diagnostics.items()},
                    "screenshot_exit_code": screenshot["exit_code"],
                }
                (run_dir / "metrics.json").write_text(json.dumps(result, indent=2) + "\n")
                return result
            finally:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--seconds", type=int, default=60)
    parser.add_argument("--rounds", type=int, default=3)
    args = parser.parse_args()
    if args.seconds < 10 or args.rounds < 1:
        parser.error("at least 10 sample seconds and one round are required")
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    launchers = {"baseline": args.baseline.resolve(), "candidate": args.candidate.resolve()}
    for number in range(1, args.rounds + 1):
        # Alternate order to reduce systematic first-run effects.
        order = ("baseline", "candidate") if number % 2 else ("candidate", "baseline")
        for label in order:
            result = measure(label, launchers[label], args.output, args.seconds, number)
            results.append(result)
            print(json.dumps({key: result[key] for key in ("label", "round", "summary")}), flush=True)
    medians = {
        label: statistics.median(row["summary"]["pss_bytes"]["median"]
                                 for row in results if row["label"] == label)
        for label in launchers
    }
    report = {
        "scenario": "clean welcome idle only; no playback/navigation/long-session acceptance",
        "kernel": platform.release(), "logical_cpus": os.cpu_count(),
        "machine_memory_bytes": kib_fields(Path("/proc/meminfo").read_text()).get("MemTotal"),
        "graphics": "Xvfb; not representative of a physical GPU",
        "pss_median_bytes": medians,
        "pss_reduction_percent": 100 * (1 - medians["candidate"] / medians["baseline"]),
        "runs": results,
        "unmeasured": ["playback", "navigation", "fullscreen", "long-session",
                       "track transition", "usable first frame", "frame times", "GPU activity"],
    }
    (args.output / "comparison.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
