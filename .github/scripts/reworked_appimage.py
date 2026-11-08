#!/usr/bin/env python3
"""Wrap the new jpackage image in the standard AppImage directory format."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

if len(sys.argv) != 4:
    raise SystemExit("Usage: reworked_appimage.py JPACKAGE_IMAGE OUTPUT APPIMAGETOOL")

repository = Path(__file__).resolve().parents[2]
source, output, tool = (Path(value).resolve() for value in sys.argv[1:])
if not (source / "bin" / "BitChordReworked").is_file():
    raise SystemExit("The Reworked jpackage launcher is missing.")
output.parent.mkdir(parents=True, exist_ok=True)

with tempfile.TemporaryDirectory(prefix="reworked-appimage-") as temporary:
    appdir = Path(temporary) / "BitChordReworked.AppDir"
    shutil.copytree(source, appdir / "usr")
    launcher = appdir / "AppRun"
    launcher.write_text(
        '#!/bin/sh\n'
        'root="$(dirname "$(readlink -f "$0")")"\n'
        'exec "$root/usr/bin/BitChordReworked" "$@"\n',
        encoding="utf-8",
    )
    launcher.chmod(0o755)
    (appdir / "bitchord-reworked.desktop").write_text(
        "[Desktop Entry]\n"
        "Type=Application\n"
        "Name=BitChord Desktop Reworked\n"
        "Exec=BitChordReworked\n"
        "Icon=bitchord-reworked\n"
        "Categories=AudioVideo;Audio;Player;\n"
        "Terminal=false\n"
        "StartupWMClass=com-music-bitchord-reworked-MainKt\n",
        encoding="utf-8",
    )
    phone_icon = repository / "app/src/main/ic_launcher-playstore.png"
    shutil.copyfile(phone_icon, appdir / "bitchord-reworked.png")
    shutil.copyfile(phone_icon, appdir / ".DirIcon")
    environment = dict(os.environ, ARCH="x86_64", APPIMAGE_EXTRACT_AND_RUN="1")
    subprocess.run([str(tool), str(appdir), str(output)], env=environment, check=True)
print(output)
