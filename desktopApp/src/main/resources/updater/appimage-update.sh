#!/bin/sh
# Bundled local helper. Never download/execute a remote helper script.
set -eu
PATH=/usr/bin:/bin
export PATH
# Sanitize before invoking tools, not only before the final AppImage launch.
unset APPIMAGE APPDIR OWD ARGV0 LD_LIBRARY_PATH LD_PRELOAD LD_AUDIT
pid=${1:?parent pid}
current=${2:?current AppImage}
staged=${3:?staged AppImage}
backup=${4:?backup}
expected=${5:?SHA-256}
ready=${6:?readiness path}
token=${7:?operation token}
version=${8:?target version}
parent_start=${9:?parent process start ticks}
original_hash=${10:?original SHA-256}
# Explicit polling bounds also permit fast, real-process regression tests.
shutdown_polls=${11:-120}
startup_polls=${12:-90}
pause=${13:-1}
operation=$(dirname -- "$ready")
umask 077
installed=0
owns_lock=0
launched=0
lock="$current.update-lock"

log() { printf '%s stage=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1"; }
finish() {
    log "$1"
    printf '%s\n' "$1" > "$operation/status"
    printf '%s\n' "$1" > "$operation/completed"
    exit "$2"
}
cleanup() {
    if [ "$owns_lock" -eq 1 ]; then rm -f -- "$lock/owner"; rmdir -- "$lock" 2>/dev/null || true; fi
}
trap cleanup 0
# The helper is detached by setsid. Ignore terminal hangup as a second protection.
trap '' HUP
trap 'finish interrupted 1' INT TERM

# /proc identity includes start ticks; ignore zombies and PID reuse.
identity() {
    [ -r "/proc/$1/stat" ] || return 1
    stat=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    rest=${stat##*) }
    set -- $rest
    case "$1" in Z|X) return 1 ;; esac
    shift 19
    printf '%s' "$1"
}
alive() { actual_start=$(identity "$1") && [ "$actual_start" = "$2" ]; }
session_of() {
    [ -r "/proc/$1/stat" ] || return 1
    stat=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    rest=${stat##*) }
    set -- $rest
    case "$1" in Z|X) return 1 ;; esac
    printf '%s' "$4"
}
candidate_alive() {
    [ "$launched" -gt 1 ] || return 1
    # Usually the runtime remains alive: avoid scanning /proc in that common path.
    runtime_session=$(session_of "$launched") || runtime_session=
    [ "$runtime_session" = "$launched" ] && return 0
    # AppImage runtime/extract-and-run PID is not necessarily the JVM PID.
    for entry in /proc/[0-9]*; do
        sid=$(session_of "${entry##*/}") || continue
        [ "$sid" = "$launched" ] && return 0
    done
    return 1
}
checksum() { value=$(sha256sum -- "$1") || return 1; printf '%s' "${value%% *}"; }
regular() { [ ! -L "$1" ] && [ -f "$1" ]; }
verified_ready() {
    regular "$ready" || return 1
    [ "$(wc -c < "$ready")" -le 1024 ] || return 1
    grep -Fqx -- "token=$token" "$ready" || return 1
    grep -Fqx -- "version=$version" "$ready" || return 1
    grep -Fqx -- 'state=gui-ready' "$ready" || return 1
    gui_pid=$(sed -n 's/^pid=//p' "$ready")
    gui_start=$(sed -n 's/^start_ticks=//p' "$ready")
    case "$gui_pid:$gui_start" in *[!0-9:]*|:*) return 1 ;; esac
    alive "$gui_pid" "$gui_start" || return 1
    [ "$(session_of "$gui_pid")" = "$launched" ] || return 1
    log "gui-ready pid=$gui_pid start_ticks=$gui_start version=$version"
}
recover() {
    reason=$1
    log "$reason"
    if candidate_alive; then finish 'recovery-pending-live-candidate; keeping current and backup; no second launch' 2; fi
    if [ "$installed" -eq 1 ]; then
        regular "$backup" && [ "$(checksum "$backup")" = "$original_hash" ] || finish 'recovery-failed-invalid-backup; manual recovery required' 1
        regular "$current" && [ "$(checksum "$current")" = "$expected" ] || finish 'recovery-refused-current-changed; preserving backup' 1
        restore=$(mktemp "$(dirname -- "$current")/.bitchord-rollback-XXXXXXXX") || finish 'recovery-failed-staging; preserving backup' 1
        if ! cp -p -- "$backup" "$restore" || [ "$(checksum "$restore")" != "$original_hash" ]; then
            rm -f -- "$restore"
            finish 'recovery-failed-copy; preserving backup' 1
        fi
        if ! mv -fT -- "$restore" "$current"; then rm -f -- "$restore"; finish 'recovery-failed-rename; preserving backup' 1; fi
        log "rollback-restored backup=$backup"
    fi
    unset BITCHORD_UPDATE_READY_FILE BITCHORD_UPDATE_TOKEN BITCHORD_UPDATE_EXPECTED_VERSION
    if regular "$current" && [ -x "$current" ]; then
        setsid "$current" </dev/null >/dev/null 2>&1 &
        log "recovery-launch-requested runtime_pid=$!; GUI not yet verified"
    fi
    finish 'failed; previous installation preserved' 1
}

case "$pid:$parent_start:$token:$expected:$original_hash" in *[!0-9a-f:]*|:*) finish 'invalid-operation-arguments' 1 ;; esac
[ "$pid" -gt 1 ] && [ "${#token}" -eq 32 ] && [ "${#expected}" -eq 64 ] && [ "${#original_hash}" -eq 64 ] || finish 'invalid-operation-identity' 1
[ "$ready" = "$operation/window-ready" ] && [ "$(cat "$operation/request.token")" = "$token" ] || finish 'invalid-readiness-operation' 1
[ ! -e "$ready" ] && [ ! -L "$ready" ] || finish 'stale-readiness-marker; no files changed' 1
regular "$current" && regular "$staged" && [ -x "$staged" ] || finish 'invalid-package-path; no files changed' 1
[ ! -e "$backup" ] && [ ! -L "$backup" ] || finish 'backup-already-exists; no files changed' 1
mkdir -- "$lock" 2>/dev/null || finish 'another-helper-or-stale-lock; no files changed' 1
owns_lock=1
printf 'pid=%s\nstart_ticks=%s\n' "$$" "$(identity "$$")" > "$lock/owner"
cp -- "$lock/owner" "$operation/helper.identity"
log "helper-started pid=$$ parent_pid=$pid parent_start=$parent_start current=$current staged=$staged backup=$backup target=$version"
printf 'armed\n' > "$operation/armed"
count=0
while alive "$pid" "$parent_start"; do
    [ ! -e "$operation/abort" ] || finish 'aborted-before-shutdown; no files changed' 1
    [ "$count" -lt "$shutdown_polls" ] || finish 'old-process-shutdown-timeout; no files changed' 1
    sleep "$pause"
    count=$((count + 1))
done
[ ! -e "$operation/abort" ] || finish 'aborted-before-replacement; no files changed' 1
log 'old-process-exited'
regular "$staged" && [ "$(checksum "$staged")" = "$expected" ] || recover 'checksum-mismatch; no replacement'
regular "$current" && [ "$(checksum "$current")" = "$original_hash" ] || finish 'current-file-changed; no replacement' 1
# GNU cp -n never follows/overwrites an existing backup; verify its result before replacement.
cp -pn -- "$current" "$backup" || recover 'backup-creation-failed'
regular "$backup" && [ "$(checksum "$backup")" = "$original_hash" ] || recover 'backup-verification-failed'
sync -f -- "$backup" || recover 'backup-sync-failed'
log "backup-verified path=$backup"
if ! mv -fT -- "$staged" "$current"; then recover 'replacement-failed'; fi
installed=1
log "replacement-complete path=$current sha256=$expected"
BITCHORD_UPDATE_READY_FILE="$ready" BITCHORD_UPDATE_TOKEN="$token" BITCHORD_UPDATE_EXPECTED_VERSION="$version" \
    setsid "$current" </dev/null >/dev/null 2>&1 &
launched=$!
log "candidate-started runtime_pid=$launched session=$launched"
count=0
dead=0
while [ "$count" -lt "$startup_polls" ]; do
    if verified_ready; then finish 'success-gui-confirmed' 0; fi
    if candidate_alive; then dead=0; else
        dead=$((dead + 1))
        if [ "$dead" -ge 3 ]; then wait "$launched" 2>/dev/null || true; recover 'candidate-exited-before-gui-ready'; fi
    fi
    sleep "$pause"
    count=$((count + 1))
done
if verified_ready; then finish 'success-gui-confirmed' 0; fi
if candidate_alive; then
    finish 'gui-confirmation-pending; candidate still running; no kill, rollback, or duplicate launch; backup retained' 2
fi
recover 'candidate-exited-without-gui-ready'
