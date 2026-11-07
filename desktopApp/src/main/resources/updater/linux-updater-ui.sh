#!/bin/sh
# Optional read-only progress observer. No installs, file deletion or readiness
# acknowledgments here. The detached appimage-update.sh remains the sole owner.
set -eu
set -f
PATH=/usr/bin:/bin
export PATH
unset APPIMAGE APPDIR OWD ARGV0 LD_LIBRARY_PATH LD_PRELOAD LD_AUDIT \
    QT_PLUGIN_PATH QML2_IMPORT_PATH GDK_PIXBUF_MODULE_FILE GIO_EXTRA_MODULES
operation=${1:?private operation directory}
helper_pid=${2:?helper pid}
helper_start=${3:?helper start ticks}
case "$helper_pid:$helper_start" in *[!0-9:]*|:*) exit 1 ;; esac
[ -d "$operation" ] && [ ! -L "$operation" ] &&
    [ "$(stat -c %u -- "$operation")" = "$(id -u)" ] || exit 1
if [ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ]; then
    printf '%s\n' 'progress-ui-unavailable: no desktop display; updater continues'
    exit 0
fi
helper_alive() {
    [ -r "/proc/$helper_pid/stat" ] || return 1
    info=$(cat "/proc/$helper_pid/stat" 2>/dev/null) || return 1
    set -- ${info##*) }
    case "$1" in Z|X) return 1 ;; esac
    shift 19
    [ "$1" = "$helper_start" ]
}
read_state() {
    state=preparing
    if [ -f "$operation/status" ] && [ ! -L "$operation/status" ]; then
        IFS= read -r state < "$operation/status" || :
    fi
    case "$state" in
        waiting-for-app-exit*) percent=10; text='Waiting for BitChord to save and close...' ;;
        verifying-package*) percent=25; text='Verifying the AppImage update...' ;;
        replacing-appimage*) percent=45; text='Installing the new AppImage...' ;;
        waiting-for-updated-window*) percent=65; text='Opening BitChord and confirming its window...' ;;
        cleaning-up-previous-version*) percent=90; text='BitChord is running; cleaning the old AppImage backup...' ;;
        recovering-previous-version*) percent=50; text='Restoring the previous AppImage...' ;;
        success-gui-confirmed*) percent=100; text='BitChord is updated and running.' ;;
        gui-confirmation-pending*|recovery-pending*) percent=95; text='Startup is not confirmed. Backup retained; check updater logs.' ;;
        preparing) percent=5; text='Preparing BitChord update...' ;;
        *) percent=95; text='Update stopped. Check updater logs before retrying.' ;;
    esac
}
finished() { [ -f "$operation/completed" ] && [ ! -L "$operation/completed" ]; }
# Finite observation; an interrupted UI never kills or rolls back BitChord.
emit_progress() {
    count=0
    while [ "$count" -lt 720 ]; do
        read_state
        printf '# %s\n%s\n' "$text" "$percent"
        if finished || ! helper_alive; then break; fi
        count=$((count + 1)); sleep 1
    done
    printf '100\n'
}
backend=
qdbus_bin=
if command -v kdialog >/dev/null 2>&1; then
    for tool in qdbus6 qdbus-qt6 /usr/lib/qt6/bin/qdbus /usr/lib/qt5/bin/qdbus qdbus; do
        if command -v "$tool" >/dev/null 2>&1; then qdbus_bin=$(command -v "$tool"); break; fi
    done
fi
case "${XDG_CURRENT_DESKTOP:-}" in
    *KDE*|*kde*) [ -z "$qdbus_bin" ] || backend=kdialog ;;
esac
if [ -z "$backend" ] && command -v zenity >/dev/null 2>&1; then backend=zenity; fi
if [ -z "$backend" ] && [ -n "$qdbus_bin" ]; then backend=kdialog; fi
case "$backend" in
    zenity)
        printf '%s\n' 'progress-ui=zenity'
        # No --auto-kill: closing the UI must NEVER terminate the updater.
        emit_progress | zenity --progress --title='BitChord Updater' \
            --text='Preparing BitChord update...' --percentage=0 --auto-close --no-cancel --width=480 || :
        ;;
    kdialog)
        printf '%s\n' 'progress-ui=kdialog'
        ref=$(kdialog --title 'BitChord Updater' --progressbar 'Preparing BitChord update...' 100) || exit 0
        # kdialog returns a service + object path, not executable shell code.
        set -- $ref
        [ "$#" -eq 2 ] || exit 0
        service=$1; object=$2
        case "$service:$object" in org.kde.kdialog*:/ProgressDialog*) ;; *) exit 0 ;; esac
        close_progress() { "$qdbus_bin" "$service" "$object" close >/dev/null 2>&1 || :; }
        trap close_progress 0
        "$qdbus_bin" "$service" "$object" setAutoClose false >/dev/null 2>&1 || :
        count=0
        while [ "$count" -lt 720 ]; do
            read_state
            "$qdbus_bin" "$service" "$object" setLabelText "$text" >/dev/null 2>&1 || break
            "$qdbus_bin" "$service" "$object" Set '' value "$percent" >/dev/null 2>&1 || break
            if finished || ! helper_alive; then break; fi
            count=$((count + 1)); sleep 1
        done
        ;;
    *) printf '%s\n' 'progress-ui-unavailable: install zenity or kdialog/qdbus for a window; updater continues' ;;
esac
# Detailed outcome remains in operation/status and install.log. A UI toolkit
# failure or closing this observer has no effect on the installation result.
exit 0
