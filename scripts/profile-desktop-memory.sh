#!/usr/bin/env bash
# Sample the actual BitChord process, not the Gradle daemon, during a long session.
set -euo pipefail

if [[ ${1:-} == "" || ! ${1:-} =~ ^[0-9]+$ ]]; then
  echo "Usage: bash scripts/profile-desktop-memory.sh PID [duration_seconds=3600] [interval_seconds=30] [output_directory]" >&2
  exit 2
fi

pid="$1"
duration="${2:-3600}"
interval="${3:-30}"
outdir="${4:-memory-profile-$(date +%Y%m%d-%H%M%S)}"
if [[ ! "$duration" =~ ^[0-9]+$ || ! "$interval" =~ ^[0-9]+$ ]] || (( interval < 1 )); then
  echo "Duration and interval must be positive whole seconds." >&2
  exit 2
fi
if [[ ! -r "/proc/$pid/status" ]]; then
  echo "PID $pid is not a readable Linux process." >&2
  exit 1
fi
mkdir -p "$outdir"
csv="$outdir/process.csv"
printf 'epoch_seconds,elapsed_seconds,rss_kib,peak_rss_kib,pss_kib,private_dirty_kib,swap_kib,threads,open_fds\n' > "$csv"
started="$(date +%s)"
echo "Sampling BitChord PID $pid -> $csv"
echo "Keep the same playback, page and window configuration throughout each run."

while [[ -r "/proc/$pid/status" ]]; do
  now="$(date +%s)"
  elapsed=$(( now - started ))
  if (( elapsed > duration )); then break; fi
  read -r rss peak swap threads < <(
    awk '/^VmRSS:/ {rss=$2} /^VmHWM:/ {peak=$2} /^VmSwap:/ {swap=$2} /^Threads:/ {threads=$2}
      END {printf "%s %s %s %s\n", rss+0, peak+0, swap+0, threads+0}' "/proc/$pid/status"
  )
  pss=0
  private_dirty=0
  if [[ -r "/proc/$pid/smaps_rollup" ]]; then
    read -r pss private_dirty < <(
      awk '/^Pss:/ {pss=$2} /^Private_Dirty:/ {dirty=$2}
        END {printf "%s %s\n", pss+0, dirty+0}' "/proc/$pid/smaps_rollup"
    )
  fi
  fds="$(find "/proc/$pid/fd" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l || true)"
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "$now" "$elapsed" "$rss" "$peak" "$pss" "$private_dirty" "$swap" "$threads" "$fds" >> "$csv"

  # Heap/NMT are diagnostic only. Absence of a full JDK or enabled NMT is not a failure.
  if (( elapsed % 300 < interval )) && command -v jcmd >/dev/null 2>&1; then
    jcmd "$pid" GC.heap_info > "$outdir/heap-$elapsed.txt" 2>&1 || true
    jcmd "$pid" VM.native_memory summary > "$outdir/nmt-$elapsed.txt" 2>&1 || true
  fi
  if (( elapsed >= duration )); then break; fi
  sleep "$interval"
done

echo "Finished. Compare RSS and PSS slopes, heap snapshots, thread count and open FDs."
