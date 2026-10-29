#!/bin/sh
# Shipped with the app, never downloaded. All variable inputs are separate arguments.
set -eu
PATH=/usr/bin:/bin
export PATH
pid=$1
current=$2
staged=$3
backup=$4
expected=$5
count=0
while kill -0 "$pid" 2>/dev/null; do
    count=$((count + 1))
    if [ "$count" -gt 120 ]; then exit 1; fi
    sleep 1
done
actual=$(sha256sum -- "$staged")
actual=${actual%% *}
[ "$actual" = "$expected" ] || exit 1
[ ! -e "$backup" ] || exit 1
cp -p -- "$current" "$backup"
# Atomic replacement on the same filesystem; the original path is never absent.
if ! mv -f -- "$staged" "$current"; then
    exit 1
fi
unset APPIMAGE APPDIR OWD
if ! "$current"; then
    # Preserve the failed replacement for diagnostics and restore the previous image.
    cp -p -- "$current" "$staged" || true
    mv -f -- "$backup" "$current"
    "$current" || true
    exit 1
fi
