#!/bin/sh
# Reworked-only AppImage transaction. This worker is independent of the running JVM.
set -eu
umask 077

target=$1
candidate=$2
backup=$3
old_hash=$4
new_hash=$5
parent_pid=$6
parent_start=$7
operation=$8
token=$9
version=${10}
lock="${target}.reworked-update-lock"

fail() { printf '%s\n' "$1" >&2; printf 'failed\n' > "$operation/status"; exit 1; }
digest() { sha256sum "$1" | cut -d ' ' -f 1; }
start_ticks() {
    [ -r "/proc/$1/stat" ] || return 0
    record=$(cat "/proc/$1/stat" 2>/dev/null) || return 0
    rest=${record##*) }
    printf '%s\n' "$rest" | awk '{print $20}'
}

case "$target" in /*/BitChordReworked*.AppImage) ;; *) fail 'Unexpected application target.' ;; esac
case "$token" in ''|*[!A-Za-z0-9-]*) fail 'Invalid operation token.' ;; esac
[ "$candidate" = "$(dirname "$target")/.BitChordReworked-next-$token.AppImage" ] || fail 'Unexpected staged path.'
[ "$backup" = "$target.reworked-previous-$token" ] || fail 'Unexpected backup path.'
[ -d "$operation" ] && [ ! -L "$operation" ] || exit 1
[ -f "$target" ] && [ ! -L "$target" ] || fail 'The application is no longer a regular file.'
[ -f "$candidate" ] && [ ! -L "$candidate" ] || fail 'The staged file is no longer available.'
[ ! -e "$backup" ] && [ ! -L "$backup" ] || fail 'The backup path already exists.'
command -v sha256sum >/dev/null || fail 'sha256sum is required to verify the update.'
mkdir "$lock" || fail 'Another update holds this installation lock.'
trap 'rmdir "$lock" 2>/dev/null || true' EXIT
trap 'exit 1' HUP INT TERM
[ "$(digest "$candidate")" = "$new_hash" ] || fail 'Staged package checksum changed.'
[ "$(digest "$target")" = "$old_hash" ] || fail 'Installed file changed during preparation.'
if kill -0 "$parent_pid" 2>/dev/null && [ ! -r "/proc/$parent_pid/stat" ]; then
    fail 'Could not verify the running process identity; no files were replaced.'
fi
printf 'waiting-for-app\n' > "$operation/status"
printf 'accepted\n' > "$operation/accepted"

count=0
while [ "$(start_ticks "$parent_pid")" = "$parent_start" ]; do
    count=$((count + 1))
    [ "$count" -le 120 ] || fail 'The running app did not exit; no files were replaced.'
    sleep 1
done
[ ! -L "$target" ] && [ "$(digest "$target")" = "$old_hash" ] || fail 'Installed file changed before replacement.'
[ ! -L "$candidate" ] && [ "$(digest "$candidate")" = "$new_hash" ] || fail 'Staged file changed before replacement.'
printf 'replacing\n' > "$operation/status"
mv "$target" "$backup" || fail 'Could not create the recovery copy.'
if ! mv "$candidate" "$target"; then
    mv "$backup" "$target" || fail 'Replacement failed; restore the recorded backup manually.'
    fail 'Replacement failed; the previous application was restored.'
fi

launch() {
    (
        unset APPIMAGE APPDIR OWD ARGV0
        export BITCHORD_REWORKED_UPDATE_ACK="$operation/ready"
        export BITCHORD_REWORKED_UPDATE_TOKEN="$token"
        export BITCHORD_REWORKED_UPDATE_VERSION="$version"
        exec nohup "$target"
    ) >> "$operation/relaunch.log" 2>&1 < /dev/null &
    launched=$!
}
printf 'starting\n' > "$operation/status"
launch
count=0
while [ "$count" -lt 75 ]; do
    if [ -f "$operation/ready" ] && [ ! -L "$operation/ready" ]; then
        if [ "$(sed -n '1p' "$operation/ready")" = "$token" ] && [ "$(sed -n '2p' "$operation/ready")" = "$version" ]; then
            printf 'complete\n' > "$operation/status"
            printf 'Updated. Recovery copy retained: %s\n' "$backup"
            exit 0
        fi
    fi
    if ! kill -0 "$launched" 2>/dev/null; then
        # Only restore when the candidate has exited. Never launch two living copies.
        [ ! -L "$target" ] && [ "$(digest "$target")" = "$new_hash" ] || fail 'New application failed and its file changed; recovery was preserved.'
        [ ! -L "$backup" ] && [ "$(digest "$backup")" = "$old_hash" ] || fail 'Recovery copy changed; restore manually.'
        mv "$target" "$candidate"
        mv "$backup" "$target"
        unset BITCHORD_REWORKED_UPDATE_ACK BITCHORD_REWORKED_UPDATE_TOKEN BITCHORD_REWORKED_UPDATE_VERSION
        nohup "$target" >> "$operation/recovery.log" 2>&1 < /dev/null &
        printf 'rolled-back\n' > "$operation/status"
        printf 'The new application exited before confirming startup; the previous copy was restored.\n' >&2
        exit 1
    fi
    count=$((count + 1))
    sleep 1
done
printf 'startup-unconfirmed\n' > "$operation/status"
printf 'New application remains running; startup was not confirmed. Recovery copy retained: %s\n' "$backup"
