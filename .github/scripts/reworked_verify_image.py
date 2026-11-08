#!/usr/bin/env python3
"""Verify the actual jpackage image before it can become a release artifact.

A successful jpackage exit alone does not establish that its main JAR is complete
or that the bundled runtime can start it. This check uses no GUI or account.
"""
from pathlib import Path
import re
import subprocess
import sys
import zipfile


def main() -> None:
    repository = Path(__file__).resolve().parents[2]
    image = (Path(sys.argv[1]) if len(sys.argv) > 1 else
             repository / "desktopApp/build/compose/binaries/main/app/BitChordReworked").resolve()
    windows = sys.platform == "win32"
    application = image / ("app" if windows else "lib/app")
    configuration = (application / "BitChordReworked.cfg").read_text(encoding="utf-8")
    main_jar = re.findall(r"^app\.classpath=\$APPDIR[\\/](desktopApp[^\\/\r\n]+\.jar)$", configuration, re.MULTILINE)
    if len(main_jar) != 1:
        raise RuntimeError("The packaged launcher must name exactly one desktop application JAR")
    if "java-options=-Dbitchord.product=reworked" not in configuration.splitlines():
        raise RuntimeError("The packaged launcher has lost its separate Reworked product identity")
    platform = "windows-x86_64" if windows else "linux-x86_64"
    libraries = ["bitchord_analysis", "bitchord_loopmix"] + (["bitchord_platform"] if windows else [])
    required = ["com/music/bitchord/reworked/MainKt.class", "models/beat_this_final0_q8.onnx",
                "models/vocals_umxhq_int8.onnx"]
    required += [f"native/{platform}/{name}.dll" if windows else f"native/{platform}/lib{name}.so"
                 for name in libraries]
    with zipfile.ZipFile(application / main_jar[0]) as archive:
        for entry in required:
            if archive.getinfo(entry).file_size <= 0:
                raise RuntimeError(f"The packaged resource is empty: {entry}")
        corrupt = archive.testzip()
        if corrupt is not None:
            raise RuntimeError(f"The packaged JAR has a corrupt ZIP entry: {corrupt}")
    print("Verified main JAR CRC, entry point, mobile models, and platform libraries", flush=True)
    launcher = image / ("BitChordReworked.exe" if windows else "bin/BitChordReworked")
    completed = subprocess.run([str(launcher), "--check-platform"], check=True, timeout=90,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if completed.stdout:
        print(completed.stdout, end="" if completed.stdout.endswith("\n") else "\n", flush=True)
    print("Verified the packaged launcher and its bundled Java runtime", flush=True)


if __name__ == "__main__":
    main()
