# Smart in-app updater

The startup and Settings flows share `DesktopUpdateChecker` and `DesktopUpdateService`.
The checker reads the official repository's published releases; drafts are ignored.
Stable is the default, Preview is opt-in. Semantic version ordering includes numeric
prerelease identifiers and the historic `beta1` tag form. Unknown development version
labels are not guessed to be old releases.

## Update flow

Settings → App Updates exposes the actual `bitchord.version` build property, channel,
automatic checks, last successful check, status and a manual check action. Automatic
checks run after onboarding on a worker, at most once per six hours. API failure never
changes playback. Later remembers the offered version; Settings can reopen it.

Discovery selects the newest compatible release, with a release-page fallback if no
matching artifact exists. It does not switch distribution formats. Release notes are
plain text, never executable HTML. Downloads stream to `.part` files with bounded
network timeouts, cancellation, byte progress and retry. A single service job coalesces
concurrent requests. Abandoned partial downloads are cleaned on the next check.

Only HTTPS assets under `Bntagaaa/BitChord-Desktop-Revived` are accepted. Download
redirects are limited to GitHub's release CDN hosts. Asset size and SHA-256 are mandatory;
the digest comes from the official GitHub asset metadata or the release's `SHA256SUMS`.
Missing checksums, excess/truncated bytes and mismatches leave no executable download.
Packages are rehashed before installation and by the external helper after process exit.
No downloaded update script is executed.

## Supported mechanisms

| Installation | Behavior |
| --- | --- |
| Windows EXE installation | Verified setup download; explicit Restart to Update starts a bundled local PowerShell helper, saves queue/history, shuts down playback and exits. The helper waits for exit, rechecks the digest and launches the interactive setup wizard with ordinary Windows authorization. |
| Windows portable | Verified ZIP download and reveal in file manager. Extract into a **new** directory and launch manually. No automatic archive extraction or replacement, so zip traversal cannot affect the running installation through this updater. |
| Writable Linux AppImage | Verified download, staging beside the original, executable permissions retained. A bundled shell helper waits for process exit, verifies again, keeps a `.previous-<timestamp>` backup, replaces and launches the new image. A failed rename leaves the original untouched; a nonzero launch restores the backup. |
| Read-only AppImage | Verified download with manual replacement instructions; never requests root or edits a protected image. |
| DEB / RPM owned launcher | Verified matching package download and reveal. Open it with the system package installer; privileges and restart remain user-assisted. |
| pacman / AUR owned launcher | Release notes and package manager instructions. No cross-format downloads or replacement. |
| Unknown format / non-x64 architecture | Release page only. No guessed installer. |

AppImage detection precedes distro/package ownership. Native Linux packages are
identified by querying ownership of `jpackage.app-path`, not by guessing from the OS.
Windows jpackage builds carry an installation marker; both portable ZIP workflows add
`.bitchord-portable` before archiving. The JVM-only portable launcher sets its own
portable marker. Raw development launches have a safe unknown-format fallback.

Helpers are shipped resources, not fetched scripts. All paths are separate process
arguments. The AppImage backup is copied first; replacement is an atomic rename on one filesystem, leaving no gap at the launcher path. User preferences, authentication,
playlists, media downloads and library directories are never replaced or deleted.
Helper logs and previous AppImages remain available for recovery. If relaunch fails
without a nonzero exit (e.g. an application hangs), the preserved backup permits manual
recovery; there is no health handshake claiming otherwise. Windows setup does not
promise automatic relaunch, silent installation, or installer rollback.

## Packaging and release publication

The existing version property is injected into jpackage and JVM portable launchers.
Native installer build numbers use `1000 + GITHUB_RUN_NUMBER` in CI (or an explicit
`bitchord.packageBuild` override, 0–65535); local builds retain the legacy default 29.
This prevents all releases in one major/minor series having the identical MSI version.
Public semantic version comparison still uses the actual `bitchord.version` property.
The release workflow validates required Windows/Linux assets, generates and checks
`SHA256SUMS`, and creates a **draft** only after builds complete. Prerelease tags mark
Preview releases and skip the stable-only Arch conversion. A maintainer still publishes
the completed draft. Windows portable quick builds carry the same format marker, but
are CI artifacts and are not discovered as public releases.

## Validation and limits

Automated tests cover version ordering, channel/draft filtering, OS/architecture/format
classification, strict repository URLs, missing assets, streaming checksums, corruption,
truncation, missing manifests, cancellation cleanup, duplicate check coalescing, offline
state, ready state, portable manual fallback and actual Linux helper rollback using
synthetic executables with spaces in their paths. Desktop/shared suites and Linux
packaging are exercised separately in the implementation report.

Windows execution, UAC, real AppImage/FUSE relaunch, and live GitHub release downloads
must also be validated on their target systems before describing this as universally
seamless auto-update support. This environment's GitHub API access currently returns
Forbidden, so fixture tests do not establish live service connectivity.
