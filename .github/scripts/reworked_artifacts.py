#!/usr/bin/env python3
"""Name only Reworked packages and emit checksums for the update client."""
import hashlib
from pathlib import Path
import re
import shutil
import sys

repository = Path(__file__).resolve().parents[2]
platform = sys.argv[1] if len(sys.argv) == 2 else ""
if platform not in {"Linux", "Windows"}:
    raise SystemExit("Usage: reworked_artifacts.py Linux|Windows")
properties = (repository / "gradle.properties").read_text(encoding="utf-8")
version_match = re.search(r"^bitchord\.version=(\S+)$", properties, re.MULTILINE)
if not version_match or not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", version_match[1]):
    raise SystemExit("Set a valid bitchord.version in gradle.properties before packaging.")
prefix = "BitChordReworked-" + version_match[1]
build = repository / "desktopApp" / "build"
output = build / "reworked-artifacts"
output.mkdir(parents=True, exist_ok=True)
packages = (
    {
        "compose/binaries/main/exe/*.exe": "-windows-x64-setup.exe",
        "compose/binaries/main/msi/*.msi": "-windows-x64-installer.msi",
        "distributions/*-windows-x64-portable.zip": "-windows-x64-portable.zip",
    }
    if platform == "Windows"
    else {
        "compose/binaries/main/deb/*.deb": "-linux-x86_64.deb",
        "compose/binaries/main/rpm/*.rpm": "-linux-x86_64.rpm",
        "distributions/*-linux-x86_64-portable.zip": "-linux-x86_64-portable.zip",
        "reworked-appimage/*.AppImage": "-linux-x86_64.AppImage",
    }
)
checksums = []
for pattern, suffix in packages.items():
    matches = list(build.glob(pattern))
    if len(matches) != 1:
        raise SystemExit(f"Expected exactly one package for {pattern}; got {len(matches)}.")
    destination = output / (prefix + suffix)
    shutil.copy2(matches[0], destination)
    checksum = hashlib.sha256()
    with destination.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(chunk)
    checksums.append(f"{checksum.hexdigest()}  {destination.name}\n")
(output / "SHA256SUMS.txt").write_text("".join(checksums), encoding="utf-8")
print(f"Staged {len(checksums)} {platform} packages in {output}")
