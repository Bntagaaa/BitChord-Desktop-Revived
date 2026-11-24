#!/usr/bin/env python3
"""Benchmark a packaged BitChord launcher from the current GUI session."""
import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import statistics
import subprocess
import time
import xml.etree.ElementTree as xml

import psutil


def seed_synthetic_library(profile):
    """Create the same old-format local library and guest Home page for either checkout."""
    def encode(value):
        return base64.urlsafe_b64encode(value.encode()).decode().rstrip("=")

    def song(index):
        return "|".join(map(encode, (f"fixture-{index}", f"Track {index}", "Fixture Artist", "", "3:00",
                                     "", "", "Fixture Album", "", "")))

    entries = xml.Element("map", {"MAP_XML_VERSION": "1.0"})

    def put(key, value):
        xml.SubElement(entries, "entry", {"key": key, "value": value})

    def chunks(key, value):
        for offset in range(0, len(value), 4096):
            put(f"{key}.{offset // 4096}", value[offset:offset + 4096])

    chunks("queue", "\n".join(map(song, range(200))))
    put("queue_index", "17")
    chunks("history", "\n".join(map(song, range(50))))
    chunks("playlists", "\n".join(
        "#".join((encode(f"playlist-{number}"), encode(f"Playlist {number}"),
                  ";".join(song(number * 80 + index) for index in range(80))))
        for number in range(80)
    ))
    chunks("liked_ids", "\n".join(f"fixture-{index}" for index in range(1200)))
    put("automix", "true")
    # Linux FileSystemPreferences encodes node names that contain dots. Its
    # alternate Base64 alphabet avoids characters that are unsafe in a directory.
    java_alphabet = "!\"#$%&'(),-.:;<>@[]^`_{|}~abcdefghijklmnopqrstuvwxyz0123456789+?"
    standard_alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    name = base64.b64encode("com.music.bitchord.desktop".encode("utf-16-be")).decode()
    prefs = profile / "preferences" / ".java" / ".userPrefs" / (
        "_" + name.translate(str.maketrans(standard_alphabet, java_alphabet))
    )
    prefs.mkdir(parents=True)
    (prefs / "prefs.xml").write_bytes(
        b'<?xml version="1.0" encoding="UTF-8"?>\n'
        b'<!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd">\n' +
        xml.tostring(entries, encoding="utf-8")
    )
    cache = profile / "cache" / "bitchord" / "media"
    cache.mkdir(parents=True)
    (cache / "home-v1.json").write_text(json.dumps({
        "version": 1, "identity": hashlib.sha256(b"0:0:").hexdigest(),
        "shelves": [{
            "title": "Cached Home fixture", "subtitle": "", "moreBrowseId": None,
            "moreParams": None, "layout": "CARDS", "items": [{
                "title": "Fixture track", "subtitle": "Fixture Artist", "thumbnailUrl": None,
                "videoId": "fixture-1", "browseId": None, "isVideo": False,
            }],
        }],
    }), encoding="utf-8")


def measure(launcher, root, duration, fixture, jvm_options, synthetic_library):
    profile = root / "profile"
    if fixture:
        shutil.copytree(fixture, profile)
    else:
        profile.mkdir()
    if synthetic_library:
        seed_synthetic_library(profile)
    for directory in ("preferences", "cache", "state", "data", "local-app-data"):
        (profile / directory).mkdir(exist_ok=True)
    token = secrets.token_hex(16)
    (root / "request.token").write_text(token, encoding="ascii")
    ready = root / "window-ready"
    environment = dict(os.environ)
    java_options = [
        environment.get("JAVA_TOOL_OPTIONS", ""),
        f'"-Duser.home={profile}"',
        f'"-Djava.util.prefs.userRoot={profile / "preferences"}"',
        *jvm_options,
    ]
    environment.update({
        "JAVA_TOOL_OPTIONS": " ".join(java_options).strip(),
        "XDG_CACHE_HOME": str(profile / "cache"),
        "XDG_STATE_HOME": str(profile / "state"),
        "XDG_DATA_HOME": str(profile / "data"),
        "LOCALAPPDATA": str(profile / "local-app-data"),
        "BITCHORD_PERF_DIAGNOSTICS": "1",
        "BITCHORD_UPDATE_READY_FILE": str(ready),
        "BITCHORD_UPDATE_TOKEN": token,
        "BITCHORD_UPDATE_EXPECTED_LAUNCHER": str(launcher),
    })
    samples = []
    with (root / "launcher.log").open("wb") as output:
        start = time.monotonic_ns()
        environment["BITCHORD_LAUNCH_EPOCH_MS"] = str(time.time_ns() // 1_000_000)
        child = subprocess.Popen([str(launcher)], env=environment, stdout=output, stderr=output)
        process = psutil.Process(child.pid)
        tracked = {process.pid: process}
        observed_ready_ms = None
        try:
            while child.poll() is None:
                elapsed_ms = (time.monotonic_ns() - start) / 1_000_000
                if observed_ready_ms is None and ready.is_file():
                    observed_ready_ms = elapsed_ms
                tracked.update((descendant.pid, descendant) for descendant in process.children(recursive=True))
                for candidate in tracked.values():
                    try:
                        with candidate.oneshot():
                            cpu = candidate.cpu_times()
                            samples.append({
                                "pid": candidate.pid,
                                "elapsed_ms": round(elapsed_ms, 2),
                                "rss_bytes": candidate.memory_info().rss,
                                "cpu_ms": round((cpu.user + cpu.system) * 1000, 2),
                            })
                    except psutil.NoSuchProcess:
                        continue
                if elapsed_ms >= duration * 1000:
                    break
                time.sleep(0.025 if observed_ready_ms is None else 0.1)
        finally:
            exit_code = child.poll()
            if exit_code is None:
                # The AppImage extraction runtime may wrap the actual JVM in a child.
                for candidate in tracked.values():
                    if candidate.pid != child.pid:
                        try:
                            candidate.terminate()
                        except psutil.NoSuchProcess:
                            pass
                child.terminate()
                try:
                    child.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()

    # The readiness marker embeds an unpredictable token and can only be written by this run.
    marker = ready.read_text(encoding="utf-8") if ready.is_file() else ""
    pid_line = re.search(r"^pid=(\d+)$", marker, re.MULTILINE)
    verified_ready = f"token={token}\n" in marker and pid_line is not None
    jvm_pid = int(pid_line[1]) if verified_ready else child.pid
    jvm_samples = [sample for sample in samples if sample["pid"] == jvm_pid]
    startup_samples = [sample for sample in jvm_samples if not verified_ready or
                       observed_ready_ms is None or sample["elapsed_ms"] <= observed_ready_ms + 1]
    result = {
        "ready_ms": round(observed_ready_ms, 2) if verified_ready and observed_ready_ms is not None else None,
        "early_exit_code": exit_code,
        "peak_startup_rss_bytes": max((sample["rss_bytes"] for sample in startup_samples), default=None),
        "last_rss_bytes": jvm_samples[-1]["rss_bytes"] if exit_code is None and jvm_samples else None,
        "cpu_at_ready_ms": startup_samples[-1]["cpu_ms"] if verified_ready and startup_samples else None,
        "milestones": {},
    }
    for log in (root / "launcher.log", profile / "state" / "bitchord" / "desktop.log"):
        if log.is_file():
            for match in re.finditer(
                r"\[PERF_DIAGNOSTICS\] \[([+-]\d+) ms\] (.+?)(?: - | \||$)",
                log.read_text(errors="replace"),
                re.MULTILINE,
            ):
                result["milestones"].setdefault(match[2], int(match[1]))
    launcher_elapsed = result["milestones"].get("External launcher invocation")
    if launcher_elapsed is not None:
        result["launcher_to_milestone_ms"] = {
            name: elapsed - launcher_elapsed
            for name, elapsed in result["milestones"].items()
        }
    (root / "samples.json").write_text(json.dumps(samples, indent=2) + "\n")
    return result


def summary(results):
    metrics = {}
    for name in ("ready_ms", "peak_startup_rss_bytes", "last_rss_bytes", "cpu_at_ready_ms"):
        values = sorted(result[name] for result in results if result[name] is not None)
        if values:
            metrics[name] = {
                "samples": len(values),
                "median": statistics.median(values),
                "p95": values[math.ceil(0.95 * len(values)) - 1],
            }
    milestones = {}
    for result in results:
        for name, elapsed in result.get("launcher_to_milestone_ms", {}).items():
            milestones.setdefault(name, []).append(elapsed)
    metrics["launcher_to_milestone_ms"] = {
        name: {
            "samples": len(values),
            "median": statistics.median(values),
            "p95": sorted(values)[math.ceil(0.95 * len(values)) - 1],
        }
        for name, values in milestones.items()
    }
    return metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launcher", type=Path, required=True)
    parser.add_argument("--version", required=True, help="Exact BitChord application version")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runs", type=int, default=10)
    parser.add_argument("--duration", type=float, default=25, help="Total observation seconds per launch")
    profiles = parser.add_mutually_exclusive_group()
    profiles.add_argument("--fixture", type=Path, help="Sanitized profile template copied for each launch")
    profiles.add_argument("--synthetic-library", action="store_true",
                          help="Populate a Linux test library with playlists, history, ratings, and a saved queue")
    parser.add_argument("--scenario", default="fresh-profile/warm-filesystem")
    parser.add_argument("--before-run", help="Optional local conditioning command, such as cache eviction")
    parser.add_argument("--java-option", action="append", default=[])
    parser.add_argument("--disposable-windows-user", action="store_true",
                        help="Confirm this Windows user has a disposable Java Preferences registry hive")
    args = parser.parse_args()
    launcher = args.launcher.resolve(strict=True)
    output = args.output.resolve()
    if args.runs < 1 or args.duration <= 0:
        parser.error("runs and duration must be positive")
    if os.name == "nt" and not args.disposable_windows_user:
        parser.error("Windows Java Preferences use the registry. Use a disposable user and pass --disposable-windows-user.")
    if os.name == "nt" and args.synthetic_library:
        parser.error("The synthetic profile uses Linux Java Preferences.")
    output.mkdir(parents=True)
    os.environ["BITCHORD_UPDATE_EXPECTED_VERSION"] = args.version
    results = []
    for index in range(args.runs):
        if args.before_run:
            subprocess.run(args.before_run, shell=True, check=True)
        root = output / f"run-{index + 1:02d}"
        root.mkdir()
        result = measure(launcher, root, args.duration, args.fixture, args.java_option, args.synthetic_library)
        results.append(result)
        print(f"{index + 1}/{args.runs} readiness={result['ready_ms']} ms", flush=True)
        # A copied profile can contain personal data. Keep it out of measurement artifacts.
        shutil.rmtree(root / "profile")
    report = {
        "platform": platform.platform(),
        "scenario": args.scenario,
        "observation_seconds": args.duration,
        "results": results,
        "summary": summary(results),
    }
    (output / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    return 0 if all(result["ready_ms"] is not None for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
