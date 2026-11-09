#!/usr/bin/env python3
"""Compare real render test images with the pinned baseline; never bless differences."""
import argparse
import json
from pathlib import Path
from PIL import Image, ImageChops, ImageStat


def compare(baseline, candidate, output):
    families = [
        ("landscape", baseline / "sharedUi/build/reports/landscape-player",
         candidate / "desktopUi/build/reports/landscape-player", 8),
        ("pip", baseline / "desktopApp/build/reports/pip-rebuild",
         candidate / "desktopApp/build/reports/pip-rebuild", 10),
    ]
    records = []
    problems = []
    for family, left, right, minimum in families:
        originals = {p.name: p for p in left.glob("*.png")}
        candidates = {p.name: p for p in right.glob("*.png")}
        if originals.keys() != candidates.keys() or len(originals) < minimum:
            problems.append({"family": family, "baseline_files": sorted(originals),
                             "candidate_files": sorted(candidates), "minimum": minimum})
        for name in sorted(originals.keys() & candidates.keys()):
            with Image.open(originals[name]) as a, Image.open(candidates[name]) as b:
                a, b = a.convert("RGBA"), b.convert("RGBA")
                record = {"family": family, "file": name, "baseline_size": a.size,
                          "candidate_size": b.size, "equal": False}
                if a.size == b.size:
                    # Include alpha: RGB-only comparisons can hide clipping/layer regressions.
                    delta = ImageChops.difference(a, b)
                    record["equal"] = all(high == 0 for low, high in delta.getextrema())
                    record["mean_absolute_channel_error"] = ImageStat.Stat(delta).mean
                    if not record["equal"]:
                        target = output / family / name
                        target.parent.mkdir(parents=True, exist_ok=True)
                        delta.save(target)
                records.append(record)
    result = {"scope": "landscape player and PiP render fixtures only",
              "exact_matches": sum(row["equal"] for row in records),
              "compared": len(records), "missing": problems, "images": records}
    (output / "visual-parity.json").write_text(json.dumps(result, indent=2) + "\n")
    print("::notice title=Desktop render parity::" + json.dumps(result))
    if problems or not records or any(not row["equal"] for row in records):
        raise SystemExit("::error::Render images differ or required evidence is missing; inspect visual-parity.json and diff images")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    compare(args.baseline, args.candidate, args.output)
