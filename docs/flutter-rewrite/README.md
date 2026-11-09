# BitChord Desktop Revived — Flutter rewrite checkpoint

**Status: incomplete; draft review only. This is not a release or a claim of 1:1 parity.**

The rewrite lives in `flutterApp/` and starts from the exact `1.9.2` source commit
`348b613a0c3db1a54de56483ea6f8748d6465211`. All changes belong on
`1.9.2-Rewrite`; its pull request targets `1.9.2` and must remain unmerged until
the acceptance gates below are met. The Kotlin source, native implementation,
packaging, and existing workflows remain available as the behavioral reference.

This checkpoint contains application code, rather than a static UI demonstration:
production screens call live repositories; local music reads real files; playback
uses native libmpv through media_kit; playlists, ratings, queue edits, downloads,
lyric transforms, source preferences and desktop commands reach their respective
services. Fixtures are confined to tests. A working code path still requires
runtime acceptance on each platform and cannot be counted as tested merely
because it passes static analysis.

## Reading order

- [Feature parity](feature-parity.md): what has code, what has evidence, and what remains absent.
- [Visual fidelity](visual-parity.md): source tokens and the fifteen supplied reference screenshots.
- [Platform support and builds](platform-support.md): runners, native dependencies, commands and verification limits.
- [Playback](playback-parity.md): engine behavior, queue identity, transports, EQ and AutoMix boundary.
- [Data and services](data-parity.md): network contracts, authentication, lyrics, sources, persistence and migration.
- [Verification evidence](verification.md): exact checks, executed assertions and pending native gates.
- [Performance](performance.md): implemented resource controls and the measurements still required.
- [Continuation](continuation.md): concrete remaining work in priority order.
- [Source inventory](source-inventory.json) and [literal preference inventory](settings-inventory.md): reproducible indexes tied to the immutable source SHA.

## Implementation layout

| Directory | Responsibility |
| --- | --- |
| `flutterApp/lib/presentation` | Desktop shell, source themes, catalogue pages, menus, settings, dock, full player, lyrics and queue views |
| `flutterApp/lib/application` | Navigation and orchestration, file scanning, download lifecycle, user-visible operation state |
| `flutterApp/lib/domain` | Tracks, queue identities, catalogue renderers, accounts, lyric timing and playlist models |
| `flutterApp/lib/data` | Innertube, source parsers, profile persistence, secure credentials, lyrics, Spotify import, addons and JioSaavn |
| `flutterApp/lib/playback` | Native decks, queue timeline, stream resolution, bounded range proxy, prefetch, crossfade, EQ, transition planning |
| `flutterApp/lib/platform` | Window lifecycle, tray, notifications, MPRIS, Windows/macOS media controls and a separate PiP view engine |
| `flutterApp/linux`, `windows`, `macos` | Native launchers and plugin registration |
| `flutterApp/test` | Explicit fixtures and behavioral regression tests, never production catalogue data |

There is no runtime JVM dependency in the Flutter application. libmpv and its
bundled codecs remain native dependencies; removing the JVM does not establish a
memory or CPU improvement without measurement.

## Build entry point

Use Flutter **3.47.9** with the committed `pubspec.lock`:

```sh
cd flutterApp
flutter pub get --enforce-lockfile
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test
flutter build linux --release
# On a Windows host: flutter build windows --release
# On a macOS host: flutter build macos --release
```

See the platform report for required system libraries, architecture checks,
packaging, and unsigned macOS limitations. `.github/workflows/flutter-desktop.yml`
contains a separate validation/build matrix. Adding that workflow is not itself
evidence that the builds passed; actual run results belong in the platform report.

## Data and credentials

The new application uses a separate profile, JSON snapshots with atomic writes
and a recovery copy, and OS credential storage for sessions and addon URLs.
Settings can import an explicitly selected Java Preferences XML export from the
old desktop. The original profile is read only; authentication secrets are not
migrated by that importer. Queue restoration is paused and does not open decoders
until playback is requested. Download files are outside the Git working tree.

The current account UI imports a YouTube Music cookie session. It does not provide
the original embedded/browser sign-in flow yet. Do not publish session cookies,
signed streaming URLs, private libraries or profile files in issues or test logs.

## Acceptance gates still required

A release is acceptable only after the outstanding features in the parity matrix
are implemented, all platform builds and native smoke tests pass, signed-in and
anonymous workflows are exercised, source and Flutter screenshots are compared
at identical viewport/scale/state, and sustained memory/CPU/frame/transition tests
are measured against Kotlin on the same machine. Windows installer/updater,
Linux distribution integration, and macOS signing/notarization are separate from
building an application bundle. This branch is not configured to replace existing
releases automatically.
