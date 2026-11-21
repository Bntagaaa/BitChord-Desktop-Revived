#!/usr/bin/env bash
# Run the opt-in Linux Compose profile and sample its test JVM at one-second intervals.
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

cat > "$out/profile.init.gradle" <<'GRADLE'
gradle.projectsEvaluated {
    def desktop = gradle.rootProject.project(':desktopApp')
    desktop.tasks.named('test', Test).configure {
        jvmArgs '-XX:NativeMemoryTracking=summary'
        def baseline = System.getenv('BITCHORD_MV_PROFILE_BASELINE_CLASSES')
        if (baseline) classpath = desktop.files(baseline, classpath)
        outputs.upToDateWhen { false }
    }
}
GRADLE

./gradlew -I "$out/profile.init.gradle" :desktopApp:test --tests '*DesktopMusicVideoMemoryProfileTest' \
  --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1500m -Pkotlin.compiler.execution.strategy=in-process \
  --console=plain > "$out/build.log" 2>&1 &
build=$!
sampler=''
trap '[[ -z "$sampler" ]] || kill "$sampler" 2>/dev/null || true; kill "$build" 2>/dev/null || true' EXIT
while [[ ! -s "$out/pid" ]] && kill -0 "$build" 2>/dev/null; do sleep 1; done
if [[ -s "$out/pid" ]]; then
  BITCHORD_MEMORY_PROFILE_JCMD=0 bash scripts/profile-desktop-memory.sh \
    "$(cat "$out/pid")" "$((cycles * 150 + 30))" 1 "$out" > "$out/sampler.log" 2>&1 &
  sampler=$!
fi
if ! wait "$build"; then
  tail -40 "$out/build.log" >&2
  exit 1
fi
test -s "$out/allocations.jfr" && test -s "$out/process.csv"
echo "Profile written to $out"
