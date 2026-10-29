#!/bin/sh
# Bundled with BitChord: replace a verified AppImage only after the old JVM exits.
set -eu
PATH=/usr/bin:/bin
export PATH
pid=${1:?missing parent pid}
current=${2:?missing AppImage path}
staged=${3:?missing staged AppImage}
backup=${4:?missing backup path}
expected=${5:?missing expected SHA-256}
ready=${6:?missing window-ready marker}

log() { printf 'BitChord updater: %s\n' "$1"; }
installed=0

# If the update fails after the old application exits, restore/reopen the old one.
recover() {
    log "$1"
    if [ "$installed" -eq 1 ] && [ -f "$backup" ]; then
        if mv -f -- "$backup" "$current"; then
            log 'Previous AppImage restored.'
        else
            log "ERROR: Could not restore $backup to $current. Manual recovery required."
            exit 1
        fi
    fi
    if [ -f "$current" ] && [ -x "$current" ]; then
        # Never pass the update-specific readiness token to the fallback process.
        unset BITCHORD_UPDATE_READY_FILE
        log 'Relaunching the previous AppImage.'
        nohup "$current" </dev/null &
    else
        log "ERROR: AppImage at $current cannot be launched."
    fi
    exit 1
}

count=0
while kill -0 "$pid" 2>/dev/null; do
    count=$((count + 1))
    if [ "$count" -ge 120 ]; then
        log 'Old application did not exit within 120 seconds; no files changed.'
        exit 1
    fi
    sleep 1
done

# The running AppImage can contaminate its replacement's process environment.
# Keep DISPLAY/WAYLAND_DISPLAY/DBUS_SESSION_BUS_ADDRESS for the desktop session.
unset APPIMAGE APPDIR OWD ARGV0 LD_LIBRARY_PATH LD_PRELOAD LD_AUDIT

actual=$(sha256sum -- "$staged") || recover 'Unable to checksum staged AppImage.'
actual=${actual%% *}
[ "$actual" = "$expected" ] || recover 'Staged AppImage hash mismatch.'
[ ! -e "$backup" ] && [ ! -L "$backup" ] || recover 'Backup path already exists.'
cp -p -- "$current" "$backup" || recover 'Unable to back up original AppImage.'
if ! mv -f -- "$staged" "$current"; then
    recover 'Unable to replace original AppImage.'
fi
installed=1
log "Installed verified AppImage: $current"

# The new JVM confirms that its first Compose window has reached the frame clock.
# Launch in the background: the updater must not wait for the user to close the GUI.
BITCHORD_UPDATE_READY_FILE="$ready" nohup "$current" </dev/null &
launched=$!
log "Started candidate process $launched; waiting for visible-window confirmation."
count=0
dead_count=0
while [ "$count" -lt 75 ]; do
    if [ -s "$ready" ]; then
        log 'New application acknowledged the window; update successful.'
        exit 0
    fi
    if ! kill -0 "$launched" 2>/dev/null; then
        dead_count=$((dead_count + 1))
        if [ "$dead_count" -ge 15 ]; then
            recover 'Updated AppImage exited before confirming its window.'
        fi
    fi
    sleep 1
    count=$((count + 1))
done
if [ -s "$ready" ]; then
    log 'New application acknowledged the window; update successful.'
    exit 0
fi
log 'New AppImage did not confirm its window within 75 seconds.'
if kill -0 "$launched" 2>/dev/null; then
    kill "$launched" 2>/dev/null || true
    count=0
    while kill -0 "$launched" 2>/dev/null && [ "$count" -lt 10 ]; do
        sleep 1
        count=$((count + 1))
    done
    if kill -0 "$launched" 2>/dev/null; then
        log "ERROR: Candidate is still running; keeping $backup for manual recovery."
        exit 1
    fi
fi
recover 'Updated AppImage failed startup; reverting to previous version.'
