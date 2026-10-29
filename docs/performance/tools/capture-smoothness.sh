#!/usr/bin/env bash
# Run the normal app with the user's existing state; this does not seed or replace any data.
set -euo pipefail
app=${1:?usage: capture-smoothness.sh /path/to/BitChord /path/to/report-directory}
output=${2:?usage: capture-smoothness.sh /path/to/BitChord /path/to/report-directory}
mkdir -p "$output"
output=$(cd "$output" && pwd)
case "$output" in *'"'*|*$'\n'*) echo 'Choose a report directory without quotes or newlines' >&2; exit 2 ;; esac
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dbitchord.smoothness.output=\"$output/timings.csv\" -XX:StartFlightRecording=filename=\"$output/capture.jfr\",settings=profile,dumponexit=true"
exec "$app"
