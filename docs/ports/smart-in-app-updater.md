# Smart in-app updater: reliability audit

Working branch: `feature/smart-in-app-updater`. Existing PR: [#86](https://github.com/Bntagaaa/BitChord-Desktop-Revived/pull/86).
Audit baseline: `7eaa99bc89111d6ad2c33c44f617ed76ae57e87e`, fetched before editing.
Reference `upstream-1109-port` remains unchanged. No new branch/PR or merge is required.

## Findings and root cause evidence

The baseline **did** reopen a real packaged AppImage in the ordinary Xvfb scenario.
The exact cause of the user's KDE/Wayland/FUSE failure therefore remains unconfirmed.
This patch does not pretend that a generic successful launch reproduces that machine.

A real-package failure test did establish a serious runtime/PID and recovery defect:

1. The AppImage runtime/extract-and-run launcher and the JVM have different PIDs.
2. The candidate JVM mapped a visible BitChord window but could not write its readiness
   file (the test used a missing parent directory). The old helper waited 75 seconds.
3. It killed the **runtime PID**, treated the launch as failed, restored the backup and
   requested another launch while the **candidate JVM/window was still alive**.
4. The `.previous` backup had been consumed by rollback. A nonempty marker alone also
   lacked a binding to a particular JVM/start identity/version.

The baseline record contains runtime PID `97059`, mapped JVM PID `97061`, helper exit
1 and a surviving window with no acknowledgment. See the committed
[baseline evidence](updater-validation/baseline-missing.json). This reproduces unsafe
recovery of a functioning GUI, not proof of every original no-reopen report.

Other confirmed defects/code paths:

- The helper inherited the old session, stdin pipe, environment and working directory.
  Its log lived under temporary files. Session independence was not established before
  exiting the application. The final real test tears down the old process group and
  confirms that the detached helper/replacement survive.
- Cancellation/channel changes could permit old progress publications and shared
  `.part` cleanup to interfere with a newer operation. A terminal state could also be
  published just before `job.isActive` cleared, dropping an immediate Download click.
- A package/manifest could come from another tag in the official repository. Integrity
  alone does not make an asset the correct release. Duplicate candidate names were
  selected arbitrarily.
- Failed retries removed an earlier verified cached download with the same filename.
  The predictable shared partial file could follow a symlink.
- General alphanumeric prerelease names were rewritten as numeric historic beta forms.
  Invalid core versions/numeric prerelease identifiers were accepted.
- Windows only started the setup wizard without observing its result. Both EXE and MSI
  install the same jpackage application payload; the installed marker does not reliably
  identify which wrapper was used. An automatic MSI-specific mechanism is not claimed.
- Native shutdown includes a potentially blocking Windows media thread join. It needs
  an actual timeout at the waiting caller, rather than a timeout that still waits for
  an uninterruptible native operation. A malformed explicit package-build override
  silently reverted to a fallback installer version.

## Corrected lifecycle

`DesktopUpdateDialog` → fenced `DesktopUpdateService.install()` → worker preparation
and checksum/header checks → persistent private operation → detached bundled helper →
helper **armed acknowledgment before shutdown** → snapshot/save queue/history/settings →
bounded native shutdown → process exit → helper identity-aware wait → backup/hash →
atomic replacement → independent candidate session → visible-window acknowledgment.

Preparation/launch and update preferences stay off the UI thread. After helper launch,
the UI remains responsive during shutdown. The existing two-second audio join remains.
A separate daemon performs blocking cleanup with a 30-second caller bound. If it cannot
finish, the helper is aborted and installation stays untouched; that worker cannot
later call `exitProcess`. Overlapping shutdown attempts are rejected. Native cleanup
cannot safely be forcibly interrupted; playback may already have stopped after an
explicit update/shutdown request, so a timed-out shutdown requires manual closure.

### AppImage installation and recovery

- Resolve a writable regular AppImage and stage beside it; reject symlink files and
  unexpected executable headers. Rehash the staged copy, preserve executable/read
  permissions without introducing group/other write permission, and fsync it. Reserve
  space for staging, backup **and** rollback copy before closing the app.
- Use a bundled helper, `setsid`, closed stdin, redirected diagnostic streams and a
  persistent host working directory. Clear `APPIMAGE`, `APPDIR`, `OWD`, `ARGV0`,
  `LD_LIBRARY_PATH`, `LD_PRELOAD`, `LD_AUDIT` and stale updater environment before spawning.
  Preserve `DISPLAY`, Wayland/XWayland, Xauthority, XDG runtime and D-Bus session variables.
  A session change does not escape a systemd cgroup's `KillMode=control-group`; that
  desktop-specific behavior still needs a real session test.
- Use a per-image atomic helper lock. Parent PID **and Linux start ticks** distinguish
  PID reuse; zombies do not block shutdown. Rehash both staged and current files after
  the correct parent exits. Refuse replacement if the current installation changed.
- Copy and verify the backup without overwriting an existing backup/symlink. Flush it,
  then rename on the same filesystem with no launcher-path gap. Record every stage.
- Start the candidate in another session. Track the whole session if its outer runtime
  exits while a descendant JVM survives. Do not infer GUI failure from launcher exit.
- Bind readiness to a private per-operation token, target version, actual JVM PID,
  process start ticks and candidate session. Write atomically after a showing,
  non-minimized Compose window passes two frame callbacks. This is an application GUI
  milestone, not proof of physical display presentation or successful network content.
- If the candidate session definitively exits before readiness, restore by a verified
  copy/atomic rename and launch the previous package. **Retain the backup** after rollback.
- If a candidate remains alive without a valid marker after the bounded observation,
  report **confirmation pending** (helper exit 2). Keep current+backup; do not kill,
  roll back or spawn another instance. Slow/failed marker IO must not destroy a healthy
  application. A marker arriving after observation still cannot change a pending status
  into a previously claimed success.

The helper does not claim that a requested fallback launch is already a healthy GUI;
that is verified separately by the packaged integration tests. It cannot prevent a
user from manually launching a separate copy simultaneously. Abrupt power loss or
forced termination during preparation can leave a private staged file for manual
cleanup. A stale lock or permissions that prevent lock cleanup cause a recoverable
error; inspect the operation/owner log and verify no updater is active before removing
that lock. Never force-delete an active lock or the only backup.

### Compatibility with an already-installed updater

A replacement JVM must also acknowledge the previous helper, which only exports
`BITCHORD_UPDATE_READY_FILE`. Otherwise changing the handshake itself would recreate
the old 75-second timeout on the first upgrade. The new JVM supports a narrow legacy
bridge after the same visible-window milestone: only an owned private
`bitchord-update-helper-*` directory and a unique `window-ready-<UUID>` filename, with
exclusive/no-follow creation. Existing files are never overwritten. An **invalid
modern token never falls back** to this bridge. A frozen baseline helper is a test-only
fixture and is exercised against the actual new AppImage.

This compatibility path cannot repair unsafe logic in a helper that was already
shipped/running. The full session/PID/recovery fix takes effect once this build is
installed. If the old updater cannot complete that first installation, replace the
AppImage manually once; do not assume a newly downloaded payload can retrofit its
parent helper. Legacy acknowledgment lacks the modern token/version/start-identity
checks, and is deliberately limited to the old private marker capability.

### Windows and native package handoff

The bundled PowerShell helper validates parent start time, waits for the original
process, rehashes a private setup copy and holds a read-sharing handle through setup.
It waits for the interactive wizard's exit and distinguishes success, reboot-required,
cancellation/failure and shutdown timeout. Ordinary Windows authorization is retained;
there is no silent privileged installation. The UI says **Close and open setup**,
not an automatic restart promise. Reopen BitChord manually afterward. A successful
installer exit is not equivalent to a verified BitChord window or completed upgrade.

EXE/MSI wrappers cannot reliably be distinguished from the current shared installed
payload. Installed Windows builds offer the EXE setup wizard explicitly; standalone
MSI installation/download stays available through the official release page. Automatic
MSI-specific selection/installation and installer rollback are unsupported. Portable
markers take priority, and portable users receive only the ZIP/manual instructions.
The quick-build CI removes its temporary marker after creating the ZIP so subsequent
EXE/MSI audit builds cannot accidentally include it.

DEB, RPM and pacman detection queries ownership of the canonical launcher, with bounded
process timeouts; AppImage detection takes precedence. Native packages are never
replaced directly. DEB/RPM packages are downloaded/revealed for the user's package
manager. Arch/CachyOS uses pacman/AUR guidance and never falls back to a DEB or AppImage.
Unknown formats, unsupported architectures and uncomparable development versions get
safe release-page guidance, not a guessed executable.

## Discovery, downloads and state

Stable is default; Preview is opt-in. Drafts are ignored. SemVer handles historic
`alpha1`/`beta1`/`rc1` labels while retaining ordinary alphanumeric ordering, ignores
build metadata for comparison and rejects invalid numeric versions. Discovery chooses
the newest compatible release; an incomplete newer release cannot trigger a
cross-format download. If none matches, show release instructions.

Only HTTPS official repository release URLs and the selected tag's exact assets and
manifest are accepted. Direct GitHub redirects must remain official release assets;
CDN redirects allow only GitHub's release CDN hosts, without userinfo/alternate ports.
HTTP timeouts, bounded redirects, status errors and rate-limit messages remain explicit.
No authentication credentials are forwarded. Download byte count and SHA-256 are
mandatory. Metadata/manifest digests are **integrity checks from the release publisher**,
not independent publisher authentication or a separately trusted signature.

Stream into a unique private download directory with exclusive/no-follow partial-file
creation. Reject traversal, symlink cache roots, malformed hashes and implausible sizes
(maximum 4 GiB). Check available space, cancellation, exact size and hash before atomic
publication. Failure deletes only that operation, preserving prior verified downloads.
Keep three prior cache directories plus the next download; no user library/download
folders are traversed. The updater never extracts a portable ZIP or runs a downloaded
helper script. AppImage/EXE headers and hashes are checked again before preparation.

State generations fence **all** progress/results. A replacement job joins cancelled
work before filesystem cleanup, so stale cancellation cannot erase another job's file.
Installing freezes channel/cancel actions. Preparation/helper/shutdown exceptions return
a failure state instead of leaving Installing forever. Saved updater settings use an
ordered background writer. Network/update failure before handoff does not shut playback.

## Persistent diagnostics

Linux: `$XDG_STATE_HOME/bitchord/updater/operation-*`, or
`~/.local/state/bitchord/updater/operation-*` when XDG is absent/relative.
Windows: `%LOCALAPPDATA%/bitchord/updater/operation-*`.
The Settings page offers **Open updater logs** and **Official releases**.

Operations contain `install.log`, `status`, `completed`, helper identity, preparation/
readiness metadata and the bundled helper. Record versions, format, parent/helper/
launch/JVM PIDs, process identities, executable/backup paths, checksums, replacement,
readiness and recovery stages. Do not dump environment, accounts or application stdout.
Active process identities protect active logs. Retain nineteen previous completed
operations plus the new one; abandoned inactive operations older than thirty days are
retired. Files remain private; logs are not an independent authenticity mechanism.

## Cross-platform audit matrix

These labels describe the evidence available for **this audit**, not an unconditional
product guarantee. Shared unit/fixture checks do not establish a real OS upgrade.

| Distribution | Discovery | Download | Verification | Install / Handoff | Relaunch | Validation |
|---|---|---|---|---|---|---|
| Linux AppImage | Partially Verified | Partially Verified | Verified | Verified | Verified | Partially Verified |
| Windows EXE | Partially Verified | Partially Verified | Partially Verified | Partially Verified | Manual | Partially Verified |
| Windows MSI | Manual | Manual | Manual | Manual | Manual | Partially Verified |
| Windows Portable | Partially Verified | Partially Verified | Partially Verified | Manual | Manual | Partially Verified |
| Linux DEB | Partially Verified | Partially Verified | Verified | Manual | Manual | Partially Verified |
| Linux RPM | Partially Verified | Partially Verified | Verified | Manual | Manual | Partially Verified |
| Arch/CachyOS | Partially Verified | Manual | Manual | Manual | Manual | Partially Verified |

AppImage install/relaunch evidence is real **extract-and-run** on Xvfb. Normal FUSE
launch was attempted and is unavailable here (`/dev/fuse`/fusermount missing); no real
Wayland compositor or user's KDE session was exercised. The matrix is consequently
partial for overall AppImage validation. GitHub API access is Forbidden and media
endpoints are unavailable, so live release discovery/download is not verified.
Windows helper syntax is parsed with PowerShell 7 on Linux. A JVM Windows portable
ZIP was also cross-built and inspected for its format marker, version, Windows Skiko
and bundled helper; it was not executed on Windows and lacks the optional native
Automix/SMTC libraries in this Linux cross-build. That archive is not the fully bundled
jpackage portable artifact built by the Windows release job. Windows execution, real
EXE/MSI generation, UAC and actual installer upgrades await Windows CI/device testing.
DEB/RPM are real generated/inspected artifacts, not installed system packages. Arch
ownership is tested with realistic command/launcher fixtures, not a CachyOS desktop.

## Validation commands and evidence

Environment: Linux x86_64, JDK 21.0.9, Xvfb `:99`, software rendering. Build tools are
extracted into the workspace; no privileged package installation or live app upgrade
was run on the host. RPM tooling needed its library/config paths and a writable
`_tmppath` instead of the sandbox's read-only `/var/tmp`.

```sh
./gradlew :shared:jvmTest :sharedUi:jvmTest :desktopApp:test \
  :desktopApp:createDistributable -Pbitchord.version=1.8.5-updater-audit
./gradlew :desktopApp:packageDeb :desktopApp:packageRpm \
  -Pbitchord.version=1.8.5-updater-audit
./gradlew :desktopApp:updaterPackagingMetadata -Pbitchord.version=1.8.5 -Pbitchord.packageBuild=1200
./gradlew :desktopApp:updaterPackagingMetadata -Pbitchord.version=1.8.5 -Pbitchord.packageBuild=1201
# Invalid explicit build numbers fail configuration, instead of silently producing version 29.
./gradlew :desktopApp:updaterPackagingMetadata -Pbitchord.packageBuild=not-a-number
```

The final Linux suite passes **652 tests** (70 shared, 34 sharedUi, 548 desktop),
with zero failures/errors/skips. Results and final package rerun counts are recorded in
[validation evidence](updater-validation/results.json). Full desktop/shared tests,
not only updater fixtures, are run. Coverage includes real parent exit, helper session
detachment/abort, launcher-versus-session liveness, slow/missing/wrong/stale readiness,
crash/rollback with retained backup, executable permission, paths with spaces,
environment preservation, checksum/cancellation/retry/symlink/size rejection, sparse-file
disk reservation, ownership probes, release format/tag/channel rules, stale state,
installation failure/unblocking, log retention and native-shutdown timeout.

Actual package tests build two AppImages from the fixed code (public versions
`1.8.4-updater-audit` and `1.8.5-updater-audit`) and use the helper **extracted from the
packaged target JAR**. A Java source fixture seeds only an isolated profile, marks the normal old/new
fixture identities as onboarded and verifies a preserved preferences sentinel. X11 checks a mapped,
nonzero-size BitChord window with the actual new JVM PID; screenshots show the real
application. No production test URL, updater bypass or fake GUI is compiled into BitChord.

```sh
desktopApp/packaging/appimage.sh desktopApp/build/compose/binaries/main/app/BitChord \
  /tmp/BitChord-new.AppImage /path/to/appimagetool
xvfb-run -a python3 desktopApp/packaging/tests/appimage-relaunch.py /tmp/BitChord-new.AppImage \
  --old /tmp/BitChord-old.AppImage --output /tmp/bitchord-updater-e2e
```

The output directory must be new. Its six cases exercise normal relaunch, old-session
hangup, a living GUI without acknowledgment, an actual AppImage made unlaunchable via
its ELF machine header, backup creation failure via directory permissions, and the old installed-helper protocol. They
assert one mapped replacement/recovery window, expected helper status, unchanged user
sentinels and backup/hash behavior. The original packaged JVM exits in every case.
Large disposable copies are removed; diagnostics and optional screenshots remain.

This is a real **packaged helper/GUI lifecycle integration test**, not a live click-to-
GitHub-download test. Discovery/service/UI paths are separately unit tested; the test
launches the bundled helper externally and terminates the isolated old JVM with a
signal. It does not claim to click the production Restart button or complete a live
internet update. FUSE/Wayland, authenticated data and active audio remain device checks.

Additional cross-build command (artifact inspection, not Windows execution):

```sh
./gradlew :desktopApp:windowsPortable -Pbitchord.target=windows -Pbitchord.version=1.8.5-updater-audit
```

DEB metadata inspected: `bitchord`, `1.8.29`, `amd64`.
RPM metadata inspected: `bitchord`, `1.8.29-1`, `x86_64`.
Both contain `/opt/bitchord/bin/BitChord` and `/opt/bitchord/lib/app/BitChord.cfg`.
Local native versions retain the legacy build 29; those artifacts are not published.
Configuration checks confirm `1.8.1200 → 1.8.1201`; invalid explicit builds fail.
The release workflow's `1000 + GITHUB_RUN_NUMBER` must remain monotonically increasing
for published installers. Counters from different workflows are not interchangeable;
never publish a local/default/test build as a production upgrade. The shared upgrade
UUID stays `8f3c1d24-6a2b-4f5e-9c17-2b8d54e0a913`.

## CI and remaining device checks

`updater-reliability.yml` reuses the AppImage and Windows quick-build workflows for PR
checks. Linux separately runs units, native packaging/metadata, actual AppImage wrapping
and real helper/GUI recovery, including the old installed-helper protocol. Windows audit mode builds EXE/MSI/portable artifacts,
checks MSI ProductVersion/ProductCode/UpgradeCode, EXE version metadata and ZIP marker/
version injection, then exercises the PowerShell helper with harmless compiled fixture
executables. Manual portable quick builds remain quick. The release workflow also runs
the relevant packaged tests. Failure uploads exclude extracted launch configs/profiles
that can contain build credentials. The Windows script explicitly states that fixture
handoff/artifact inspection is **not** a completed BitChord installer upgrade.

CI definitions pass actionlint 1.7.7; PowerShell/YAML/shell/Python syntax is validated locally; a Windows/hosted CI
green result is not claimed without observing that run. Test real upgrades on a clean
Windows machine, actual FUSE and KDE/Wayland sessions, native package managers and
CachyOS before calling those paths fully verified. Also test slow disks, real power loss,
separate filesystem/mount permissions and desktop cgroup teardown; no claim here covers
all such faults. Keep a backup until the replacement window is usable.
