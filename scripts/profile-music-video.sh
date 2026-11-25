#!/usr/bin/env bash
# Run an opt-in Linux Compose video profile and sample its test JVM once per second.
set -euo pipefail

if [[ $# != 2 || ! -f "$1" || -e "$2" ]]; then
  echo 'Usage: bash scripts/profile-music-video.sh CLIP.mp4 NEW_OUTPUT_DIRECTORY' >&2
  exit 2
fi
clip="$(realpath "$1")"
mkdir -p "$2"
out="$(realpath "$2")"
cd "$(dirname "${BASH_SOURCE[0]}")/.."
export BITCHORD_DESKTOP_ONLY=true
export BITCHORD_MV_PROFILE_CLIP="$clip"
export BITCHORD_MV_PROFILE_DIR="$out"
cycles="${BITCHORD_MV_PROFILE_CYCLES:-2}"
[[ "$cycles" =~ ^[1-9][0-9]*$ ]] || exit 2
fullscreen_seconds="${BITCHORD_CANVAS_FULLSCREEN_SECONDS:-0}"
if [[ "$fullscreen_seconds" != 0 ]]; then
  [[ "$fullscreen_seconds" =~ ^[1-9][0-9]*$ ]] && (( fullscreen_seconds >= 15 && fullscreen_seconds % 15 == 0 )) || {
    echo 'BITCHORD_CANVAS_FULLSCREEN_SECONDS must be a positive multiple of 15.' >&2
    exit 2
  }
fi
case "${BITCHORD_VIDEO_PROFILE_SCENARIO:-music}" in
  music) test_name='DesktopMusicVideoMemoryProfileTest' ;;
  canvas) test_name='DesktopSpotifyCanvasMemoryProfileTest'; cycles=1 ;;
  *) echo 'Expected music or canvas for BITCHORD_VIDEO_PROFILE_SCENARIO' >&2; exit 2 ;;
esac

cat > "$out/profile.init.gradle" <<'GRADLE'
gradle.projectsEvaluated {
    def desktop = gradle.rootProject.project(':desktopApp')
    desktop.tasks.named('test', Test).configure {
        jvmArgs '-Xms64m', '-Xmx512m', '-XX:NativeMemoryTracking=summary'
        def baseline = System.getenv('BITCHORD_MV_PROFILE_BASELINE_CLASSES')
        if (baseline) classpath = desktop.files(baseline, classpath)
        outputs.upToDateWhen { false }
    }
}
GRADLE

if [[ -n "${BITCHORD_CANVAS_CLOSE_MODE:-}" ]]; then
  [[ "$test_name" == DesktopSpotifyCanvasMemoryProfileTest && "$fullscreen_seconds" != 0 ]] || exit 2
  cycles=3
fi
gradle=(./gradlew)
if [[ "${BITCHORD_MV_PROFILE_NO_SWAP:-0}" == 1 ]]; then
  gradle=(systemd-run --user --scope -p MemorySwapMax=0 ./gradlew --no-daemon)
fi
"${gradle[@]}" -I "$out/profile.init.gradle" :desktopApp:test --tests "*$test_name" \
  --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1500m -Pkotlin.compiler.execution.strategy=in-process \
  --console=plain > "$out/build.log" 2>&1 &
build=$!
sampler=''
trap '[[ -z "$sampler" ]] || kill "$sampler" 2>/dev/null || true; [[ -z "$build" ]] || kill "$build" 2>/dev/null || true' EXIT
while [[ ! -s "$out/pid" ]] && kill -0 "$build" 2>/dev/null; do sleep 1; done
if [[ -s "$out/pid" ]]; then
  pid="$(cat "$out/pid")"
  if [[ "${BITCHORD_MV_PROFILE_NO_SWAP:-0}" == 1 ]]; then
    cgroup="$(sed -n 's/^0:://p' "/proc/$pid/cgroup")"
    cat "/sys/fs/cgroup$cgroup/memory.swap.max" > "$out/memory.swap.max"
    [[ "$(cat "$out/memory.swap.max")" == 0 ]]
  fi
  BITCHORD_MEMORY_PROFILE_JCMD=0 bash scripts/profile-desktop-memory.sh \
    "$pid" "$((cycles * 150 + 30 + fullscreen_seconds))" 1 "$out" > "$out/sampler.log" 2>&1 &
  sampler=$!
fi
if ! wait "$build"; then
  tail -40 "$out/build.log" >&2
  exit 1
fi
build=''
test -s "$out/allocations.jfr"
test -s "$out/process.csv"
echo "Profile written to $out"
