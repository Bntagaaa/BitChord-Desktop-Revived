# Standalone Windows updater and automatic relaunch

## Scope

Both Windows Portable ZIP and Windows installed EXE/MSI distributions now use one separate updater process. This is a bundled Windows PowerShell 5.1 host with a native Windows Forms window titled **BitChord Updater**; it is not a new background service or a separately downloaded executable. The app starts it with a JSON request and an isolated operation directory outside the installation. Linux keeps its existing AppImage/package-manager paths.

The UI action is **Update and Restart** for both Windows distributions. The updater is initialized and its payload verified before the application receives permission to close. It survives the application exit and controls installation, verification, cleanup and relaunch.

## Ownership and state

The main operation contains request.json, request.token, helper.identity, armed, install.log, worker.log, worker-error.log, status and completed. The format-specific worker runs with a separate payload/ operation underneath it so the worker cannot overwrite the host's process identity or report premature overall completion.

The host waits for both the JVM and any matching native jpackage launcher parent, comparing PID and start time rather than killing processes by name. A per-installation mutex and per-operation exclusive lease prevent overlapping updates. A create-new launch.requested marker prevents duplicate relaunch of the same operation.

Sequence:

    verify package -> acknowledge handoff -> wait for old processes
        -> run worker -> check result -> verify installed launcher/version
        -> request one relaunch -> observe new window -> complete

## Portable path

The existing worker verifies the ZIP again, finds the unique BitChord.exe/marker/config application root, extracts beside the old directory, keeps the original folder as a backup and restores it if the replacement rename fails. The host passes SkipLaunch: only the host may reopen the app, so two independent helpers cannot create duplicate windows.

**Automatic Portable backup cleanup:** After the host positively confirms the updated BitChord window is running, it validates the exact backup path written by the successful worker (sibling of the original installation, with an updater-generated 32-hex suffix), checks the original executable/marker/config and refuses any reparse point or symlink anywhere in the backup. Only then is the entire previous Portable folder deleted, and `backup.cleanup` records the result. An unconfirmed launch, failed update, version mismatch, or reboot-required result retains the backup. Cleanup errors are logged and do not turn a successful app launch into an update failure.

**User files warning:** The retained old Portable folder can include files added manually by the user. Once a new BitChord window is confirmed, those files are also deleted along with the old application folder. Move personal files out of the Portable application folder before updating; music libraries outside the app directory are unaffected.

## Installed EXE/MSI path

The existing verified setup wrapper/Windows Installer mechanism is retained, including its normal wizard and UAC prompt when applicable. The updater does not overwrite MSI-owned files as though they were Portable files. After setup returns, the host rejects cancellation/failure and checks the target version in the original launcher's app/BitChord.cfg before restarting it.

A reboot-required result is reported without launching the app. If setup changes the installation location, or returns success without installing the requested version, the host does not guess a new executable or launch the previous version. The UI retains an error and an Open logs button.

## Relaunch confirmation

The host runs the exact original BitChord.exe path with UseShellExecute=false, preserving its own user context rather than relaunching from an elevated installer. It supplies a unique token, expected version and expected launcher to the new application. Main.kt sends the acknowledgement only after two visible Compose window frames. Windows acknowledgements include the actual process PID/start time and launcher path; Linux retains its existing start-ticks protocol.

For older target versions without the Windows handshake, the host may confirm a newly created visible window from that exact version-checked executable. Merely creating a process is not reported as a verified startup. On timeout the host records relaunch-requested-unconfirmed and does not launch again, kill a possibly running app, or perform a blind rollback.

## Disk use

Staged EXE/ZIP files are removed after the delegated worker exits, on success and on failure. Diagnostic logs are preserved. Startup cleanup can remove leftover nested payload copies only when neither host nor delegated worker is active. Only the verified post-GUI Portable success path deletes that operation's previous-version folder; startup/background cleanup never removes unconfirmed Portable backups or the Windows Installer PackageCache. Installed EXE/MSI mode has no updater-owned Portable backup to delete.

## PowerShell compatibility

The host pins module discovery to its own Windows PowerShell modules. Inherited PowerShell 7 module paths can otherwise make Windows PowerShell 5.1 lose Get-FileHash. Worker stdout/stderr are drained asynchronously through a retained System.Diagnostics.Process handle so a quick worker exit cannot lose its exit code or block on full output pipes.

## Tests and limitations

windows-updater-host-checks.ps1 exercises the production host and both production workers on Windows using small native fixture executables. It covers Portable/setup success and exactly-one relaunch, Portable backup deletion only after confirmed startup, preserved old/user-added files when startup is unconfirmed, unrelated sibling backup protection, setup cancellation, reboot-required, unchanged installed version, checksum failure, shutdown timeout, and unconfirmed startup. These protocol tests are not a substitute for real MSI upgrade testing or manual BitChord GUI verification.

DesktopWindowsUpdateTest checks JSON staging, exact launcher capture, bundled resources, payload cleanup on cancelled preparation, and Windows readiness identity. DesktopWindowsUpdateCleanupTest protects a live delegated worker even if its parent host exited. The separate Windows Standalone Updater workflow publishes the host/worker diagnostics on failure.

Existing application binaries cannot gain this host from changing release metadata alone. A build containing this code must be installed once; subsequent in-app Windows updates use the separate updater.
