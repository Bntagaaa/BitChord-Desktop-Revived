#!/usr/bin/env bash
# Validate the actual analyser and models inside a Linux jpackage tree or extracted AppImage.
set -euo pipefail
app_root=${1:-desktopApp/build/compose/binaries/main/app/BitChord}
repo_root=$(cd "$(dirname "$0")/../../.." && pwd)
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT
python3 - "$app_root" "$tmp_dir" <<'PY'
from pathlib import Path
import sys, zipfile
app, output = map(Path, sys.argv[1:])
jars = list((app / 'lib/app').glob('desktopApp*.jar'))
if len(jars) != 1:
    raise SystemExit(f'Expected one application JAR, found {len(jars)} in {app}')
with zipfile.ZipFile(jars[0]) as jar:
    models = [n for n in jar.namelist() if n.startswith('models/') and n.endswith('.onnx')]
    if len(models) < 2:
        raise SystemExit(f'Expected two or more Automix models, found {len(models)}')
    (output / 'libbitchord_analysis.so').write_bytes(jar.read('native/libbitchord_analysis.so'))
print(f'Packaged analyser and {len(models)} ONNX models found')
PY
ldd "$tmp_dir/libbitchord_analysis.so" > "$tmp_dir/imports.txt"
cat "$tmp_dir/imports.txt"
if grep -Fq 'not found' "$tmp_dir/imports.txt"; then
    echo 'Missing shared library dependency in packaged AutoMix analyser' >&2
    exit 1
fi
javac -d "$tmp_dir/classes" "$repo_root/desktopApp/packaging/tests/native/TrackFeatures.java"
java -cp "$tmp_dir/classes" com.music.bitchord.playback.smart.TrackFeatures "$tmp_dir/libbitchord_analysis.so"
