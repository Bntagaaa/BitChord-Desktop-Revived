# Windows portable: update in the same folder

Windows cannot overwrite the running BitChord.exe or runtime DLLs. The updated portable installer now uses a detached PowerShell helper bundled in the app. After a verified ZIP download, the UI offers **Replace in Same Folder** rather than only showing the ZIP.

## Operation

1. Detect the live launcher folder using the jpackage app path. Automatic replacement requires an actual .bitchord-portable marker, BitChord.exe and app/BitChord.cfg in that folder; an environment flag alone is not sufficient.
2. Download and verify the official version-specific portable ZIP. Copy it to a private updater operation under %LOCALAPPDATA%/BitChordUpdater/operations and verify the copy again.
3. Spawn the helper outside the portable application directory, wait for the old application's exact PID and start timestamp to exit, then verify ZIP integrity again.
4. Validate archive entries before filesystem changes: a single BitChord/ root, launcher, marker and config; no traversal, external folders, case-insensitive duplicate paths, ZIP symlink metadata, or expansion beyond 4 GiB.
5. Check free disk space on the portable drive, extract to a new sibling staging directory, rename the original folder to a sibling backup, and move the new directory to the **same original path** (even if the original portable directory has been renamed).
6. If the second rename fails, restore the original folder. Preserve the original directory backup including any user-added files; the helper never recursively overwrites locked executable files.
7. Request restart of the new BitChord.exe. Keep operation status and backup location in the external updater log.

## Important limits

An already-installed older portable version without this feature still needs its first manual ZIP replacement. Once a portable release containing this change is running, subsequent releases can update in place. Setup EXE/MSI packages are a different Windows Installer path and never use portable folder replacement.

Backups are deliberately retained and are not deleted automatically. Any personal files placed inside the old application folder stay in the sibling backup; they are not automatically copied into the new executable directory because that risks stale libraries/plugins.

The workflow in .github/workflows/windows-portable-inplace.yml performs actual Windows filesystem integration tests for same-path replacement, backup preservation, rollback after a simulated failure, and ZIP path traversal rejection.
