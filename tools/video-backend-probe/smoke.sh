#!/usr/bin/env bash
set -euo pipefail

output="${1:?Usage: bash tools/video-backend-probe/smoke.sh OUTPUT_DIRECTORY}"
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
bash "$here/prepare.sh" "$output"
probe="$output/gstreamer-probe"
if [[ -f "$probe.exe" ]]; then probe="$probe.exe"; fi
"$probe" headless-cpu "$output/cycles.mp4" 3 | tee "$output/probe.log"
test "$(grep -c '^cycle=.* rendered=30 dropped=0 ' "$output/probe.log")" -eq 3

# Use a unique missing input so a repeated smoke run cannot inherit the file.
missing="$output/missing-input-$$.mp4"
status=0
"$probe" headless-cpu "$missing" 1 > "$output/missing.log" 2>&1 || status=$?
cat "$output/missing.log"
test "$status" -eq 1
grep -q 'rendered=0' "$output/missing.log"
