# Linux updater, verified restart and confirmed-backup cleanup

## Supported installation paths

The self-managed **AppImage** uses a detached, bundled shell helper. It outlives
BitChord's JVM, waits for the original PID/start-ticks identity to exit,
checks SHA-256 again, backs up the current image, atomically replaces it at
its existing path, and starts the new image in its own session. Desktop and
session environment is retained, but stale AppImage mount/loader variables
are removed. No downloaded helper or root installation service is introduced.

A small independent **BitChord Updater** progress observer uses Zenity on
GNOME or KDialog/qdbus on KDE when available. Without these optional programs
or a desktop display the update still works and writes its logs. Closing the
observer never kills the update or declares it successful. The helper's state
files remain authoritative. Linux UI observes progress only; it does not
handle the Windows EXE/MSI workflow.

**DEB, RPM, Arch/pacman/AUR and unknown installations are not overwritten by
this helper.** Existing package-manager guidance is retained. This change
does not add automatic package-manager installation, privileged rollback,
package-cache deletion, or automatic relaunch after an external package
manager finishes. Linux Portable TAR/ZIP is not a new supported format here.

## Backup lifetime

During the update the old AppImage remains at:

    <current-AppImage-path>.previous-<operation-token>

Only after the new visible BitChord window acknowledges the operation token,
expected version, PID/start ticks and launch session can the old image be
removed. The helper verifies its exact path, owner, device/inode and original
checksum, verifies the installed image, then rechecks readiness immediately
before deleting the single old file. A receipt is written to backup.cleanup.
It never recursively deletes an application directory or sweeps previous-*
files from other operations. Personal files beside the AppImage are untouched.

If the updated process exits before readiness, the existing rollback restores
the old image and requests its launch. If the new process is alive but startup
is unconfirmed, the helper keeps both images and does NOT kill it, roll back
blindly or launch a duplicate. Wrong tokens/versions, altered backups, update
failures and cleanup errors preserve recovery material. Cleanup failure does
not invalidate an already confirmed new window. A completed operation cannot
be replayed to relaunch the app again.

Only an unchanged updater-generated .bitchord-update-*.AppImage staging file
owned by this operation is removed on abort/failure. The normal download
cache retains its existing policy; package-manager caches are untouched.

## Diagnostics and verification

Logs and status are under $XDG_STATE_HOME/bitchord/updater/operation-* (or
~/.local/state/bitchord/updater/operation-*): install.log, ui.log, status,
completed, helper.identity, backup.path, backup.cleanup and window-ready.
The updater's GUI readiness is reported after two mapped Compose frames,
not merely when a process is started.

linux-updater-checks.py exercises real subprocess/session behavior with
small executable fixtures: confirmed success and deletion, healthy missing
acknowledgment, wrong token/version, crash rollback, checksum mismatch,
pre-shutdown abort, symlink/modified backup protection and duplicate request
rejection. Its --ui mode verifies actual Zenity and KDE windows under
Xvfb/D-Bus. The existing appimage-relaunch.py separately tests the complete
packaged BitChord GUI; success/hangup cases now assert backup deletion,
while legacy-helper and unconfirmed/crash cases retain recovery material.

An installed older binary needs one build containing these bundled helpers;
changing only the release download cannot patch its already-running updater.
