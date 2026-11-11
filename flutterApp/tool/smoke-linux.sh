#!/usr/bin/env bash
set -euo pipefail
executable="$(realpath build/linux/x64/release/bundle/bitchord)"
report="$(pwd)/build/reports/native-window"
mkdir -p "$report"
profile="$(mktemp -d)"
cleanup() {
  if [[ -n "${application_pid:-}" ]]; then kill "$application_pid" 2>/dev/null || true; wait "$application_pid" 2>/dev/null || true; fi
  rm -rf "$profile"
}
trap cleanup EXIT
HOME="$profile" XDG_CONFIG_HOME="$profile/config" XDG_CACHE_HOME="$profile/cache" \
  XDG_DATA_HOME="$profile/data" "$executable" >"$report/application.log" 2>&1 &
application_pid=$!
window=""
for ((attempt=0; attempt<40; attempt++)); do
  kill -0 "$application_pid"
  window="$(xdotool search --onlyvisible --pid "$application_pid" 2>/dev/null | head -1 || true)"
  if [[ -n "$window" ]]; then break; fi
  sleep 1
done
test -n "$window"
sleep 3
import -window "$window" "$report/home.png"
xdotool mousemove --window "$window" 100 765 click 1
sleep 2
import -window "$window" "$report/settings.png"
# First verify that the real sidebar click opened Settings. The Theme
# and Font controls are below the initial viewport, so they cannot be
# verified from the first screenshot without interacting with the page.
tesseract "$report/settings.png" "$report/settings" 2>/dev/null
python3 - "$report/settings.txt" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text().lower()
for label in ("Settings", "BitChord"):
    if label.lower() not in text:
        raise SystemExit("Native Settings window not verified: " + label)
PY

# Use the live Settings search to surface Appearance in the actual window.
# This checks both input interaction and rendered controls, instead of
# expecting off-screen labels to appear in the original screenshot.
xdotool mousemove --window "$window" 930 43 click 1
xdotool type --clearmodifiers --delay 40 "Theme"
sleep 2
import -window "$window" "$report/appearance.png"
tesseract "$report/appearance.png" "$report/appearance" 2>/dev/null
python3 - "$report/appearance.txt" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text().lower()
for label in ("Settings", "Theme", "Font", "Appearance"):
    if label.lower() not in text:
        raise SystemExit("Native Appearance search not verified: " + label)
print("::notice::Native Linux Settings opened and live search revealed Appearance/Theme/Font")
PY
