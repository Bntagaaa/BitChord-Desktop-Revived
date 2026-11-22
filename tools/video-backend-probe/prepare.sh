#!/usr/bin/env bash
# Build the finite probe and generate deterministic, audio-free H.264 clips.
set -euo pipefail

output="${1:?Usage: bash tools/video-backend-probe/prepare.sh OUTPUT_DIRECTORY}"
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p -- "$output"

read -r -a gst_flags <<< "$(pkg-config --cflags --libs gstreamer-video-1.0)"
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror "$here/gstreamer-probe.c" -o "$output/gstreamer-probe" "${gst_flags[@]}"

make_clip() {
  local name="$1" size="$2" seconds="$3"
  ffmpeg -hide_banner -loglevel error -f lavfi -i "testsrc2=size=${size}:rate=30" \
    -t "$seconds" -an -c:v libx264 -preset ultrafast -pix_fmt yuv420p -y "$output/$name.mp4"
}

make_clip cycles 1920x1080 1
make_clip landscape 1920x1080 5
make_clip portrait 540x960 5
