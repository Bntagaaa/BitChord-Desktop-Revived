"""Measure packaged BitChord startup to a viewable X11 window."""
import argparse
import os
from pathlib import Path
import re
import statistics
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("launcher", nargs="+", type=Path)
parser.add_argument("--runs", type=int, default=3)
args = parser.parse_args()
if not os.environ.get("DISPLAY"):
    parser.error("Run with an X11 display, for example with xvfb-run")
if args.runs < 1:
    parser.error("--runs must be positive")


def windows():
    root = subprocess.check_output(["xwininfo", "-root", "-tree"], text=True)
    return set(re.findall(r'^\s+(0x[0-9a-f]+) "BitChord[^"]*"', root, re.MULTILINE))


def measure(launcher: Path):
    previous = windows()
    with tempfile.TemporaryDirectory(prefix="bitchord-startup-") as workspace:
        env = os.environ.copy()
        for name in ("XDG_DATA_HOME", "XDG_STATE_HOME", "XDG_CACHE_HOME", "XDG_CONFIG_HOME"):
            env[name] = workspace + "/" + name.lower()
        env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + f" -Duser.home={workspace}").strip()
        logfile = Path(workspace) / "startup.log"
        with logfile.open("wb") as log:
            started = time.perf_counter()
            process = subprocess.Popen([launcher.resolve()], stdout=log, stderr=log, env=env)
            try:
                while process.poll() is None and time.perf_counter() - started < 30:
                    for window in windows() - previous:
                        view = subprocess.check_output(["xwininfo", "-id", window], text=True)
                        if "Map State: IsViewable" in view:
                            return (time.perf_counter() - started) * 1000
                    time.sleep(0.05)
                raise RuntimeError(f"No viewable application window for {launcher}\n{logfile.read_text()[-3000:]}")
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


measurements = {launcher: [] for launcher in args.launcher}
for _ in range(args.runs):
    for launcher in measurements:
        measurements[launcher].append(measure(launcher))
for launcher, values in measurements.items():
    print(f"{launcher} startup_ms={[round(x, 1) for x in values]} median_ms={statistics.median(values):.1f}")
