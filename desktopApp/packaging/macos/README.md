# macOS development builds

The baseline is Revived `integration/v1.8.4` (`700ef284`), preserving its shared JVM
UI, FFmpeg PCM player, AutoMix, JavaFX music videos, PiP and updater policy.
Upstream references inspected:

- `kushagrasinghx/BitChord` v1.8, `32f4e7c5f06d0f62ea14a15111a4c32d6d10ac3c`:
  its DESKTOP.md explicitly excludes macOS.
- Last available desktop revision `0a3931e5e6033859f341571365f241d9bab18069`,
  immediately before desktop removal in `e4dd63d8`: architecture classifiers,
  Compose macOS targets, ICNS, package metadata and DesktopMacFrame.
- Upstream main `b690dc146009d4815825fa4a20815ef2e4ee8898` no longer has desktopApp.

Only packaging configuration and the upstream ICNS are ported directly. The
upstream frame forces dark appearance and creates native vibrancy; Revived keeps
its opaque decorated window and existing Reduce Blur behavior instead. Apple
Spaces fullscreen uses the JDK macOS adapter, separate from Win32 fullscreen.
No extra native blur layer is installed.

## Supported OS baseline

Apple Silicon requires macOS 14 or later; Intel requires macOS 13.3 or later.
These minima come from the actual bundled ONNX dylib Mach-O headers and are
encoded in Info.plist. The verifier also checks that inspected libraries do not
require a newer OS than the bundle declares. CI uses macOS 26 for Apple Silicon and macOS 15 for Intel; physical devices
and earlier supported OS versions still need manual verification.

## Build on each architecture

Install an architecture-matching JDK 21 and Xcode command-line tools. CMake must
be on PATH for native analysis and the MediaPlayer/CoreAudio bridge. No Homebrew
runtime dependency is required by users. Build natively on Intel or Apple Silicon;
`bitchord.target` is not a macOS cross-compilation switch.

```sh
./gradlew :shared:jvmTest :sharedUi:jvmTest :desktopApp:test
./gradlew :desktopApp:createDistributable :desktopApp:packageDmg :desktopApp:packagePkg
BUILD_ARCH=arm64 python3 desktopApp/packaging/macos/verify-bundle.py # Intel: x64
python3 desktopApp/packaging/macos/smoke-startup.py
```

Build Test macOS uses `macos-26` (Apple Silicon) and `macos-15-intel` (Intel),
verified against actions/runner-images. Apple Silicon moved from macos-15 after
GitHub canceled that job because no hosted runner acquired it despite repeated
attempts. Both native JDKs are explicitly selected.
The feature-branch push trigger enables testing before a PR exists; integration
pushes, integration PRs and workflow_dispatch also run the matrix.

Each CI job creates an unlocked disposable Keychain for native credential tests,
then resets it before packaged startup to avoid cross-identity permission dialogs.
It restores the runner default and deletes the temporary Keychain afterward.
This uses no Apple account or signing credentials.

Each job uploads `BitChord-Revived-macOS-{arm64,x64}` containing identically named
DMG and PKG files, plus separate diagnostics. Look under the run's Artifacts area.
Normal Gradle packages retain the numeric native version in their metadata.
Verification checks plist, icon, executable, bundled Java, native Mach-O
architecture and JNI loading. JNI smoke runs through the native app launcher and its bundled runtime; no
external Java command is used. Fat dependency jars (ONNX/JNA/Skiko) may contain foreign-platform resources;
only matching macOS resources are loaded and verified. The official Skiko x64
archive contains both arm64 and x64 dylibs; the verifier requires the resource
whose filename and Mach-O architecture match the app. No foreign executable is
added to the macOS launcher path.

## Platform adapters

- ONNX 1.29 ships Apple Silicon macOS natives only. Intel selects 1.22.0,
  whose published jar contains both Intel JNI and the runtime dylib. Packaged
  smoke opens both actual AutoMix models to verify compatibility. Windows, Linux
  and Apple Silicon retain 1.29.
- Java Sound provides CoreAudio output and mixer enumeration, feeding the same
  FFmpeg/PCM engine. CoreAudio default-device and inventory events reconfigure the
  output; the JDK sleep listener triggers the same recovery after wake.
- MediaPlayer publishes title, artist, duration, position and playback state and
  registers play/pause/toggle/next/previous commands. Callbacks return to the AWT
  event thread. Listener tokens are removed on disposal. Hardware media keys and
  Control Center routing still require manual verification on a real Mac.
- Source credentials, SecretStore sessions, Spotify/Discord/Last.fm/ListenBrainz
  credentials and Listen Together membership tokens use Login Keychain via Security;
  locked/denied Keychain errors preserve data and do not silently downgrade new
  macOS credentials to plaintext. Existing legacy source credentials migrate after
  a successful Keychain write. Java Preferences retains ordinary settings.
- Data: `~/Library/Application Support/bitchord`, caches:
  `~/Library/Caches/bitchord`, diagnostics: `~/Library/Logs/bitchord`.
  Analysis extraction is separated by CPU architecture. Chromium interactive
  sign-in uses a dedicated macOS profile; Firefox profile import uses its macOS
  location. Direct macOS Chromium cookie decryption is not offered.
- Command replaces Control for app shortcuts; Dock reopen raises the existing
  window. Closing the last window keeps the macOS application alive. Quit from
  the application menu ends the Compose application. Zoom/minimize remain native.
- macOS updater detection always uses a manual DMG/PKG handoff through the trusted
  release page. It never replaces a running app. Intel and ARM instructions are
  displayed explicitly; release publication of macOS assets is a separate step.

## Signing and runtime limits

These are unsigned, unnotarized development packages. No Apple credentials are
required. Gatekeeper may block downloaded apps; use macOS Privacy & Security's
Open Anyway only for an artifact you trust. Signing/notarization is not implemented.
A successful architecture/JNI smoke test does not prove audible playback. The GUI
smoke test requires an actual visible window via System Events; GUI permission or
session restrictions fail the check instead of passing silently. No network login,
headphone, Bluetooth or real audio device is simulated as success.

## Manual validation on BOTH Intel and Apple Silicon

- [ ] First launch from a fresh DMG and PKG; Gatekeeper behavior; no installed Java.
- [ ] Login through browser and Firefox import; session survives app restart;
      locked Keychain reports an error without losing credentials.
- [ ] Search, Quick Search (Command-K), navigation, artist pages, custom playlists,
      New & Trending, Canvas, lyrics and account selection.
- [ ] Continuous multi-track playback, queue/autoplay, seek/skip/pause, quality
      switching, Instant Playback, AutoMix and normal Crossfade separately.
- [ ] Music videos and existing PiP: open, resize, fullscreen, restore and close.
- [ ] Spaces fullscreen through app button, green button and Escape; minimize,
      zoom, resize, rapid enter/exit reversals, Dock reopen and Command-Q; no duplicate player/window.
- [ ] Light, dark, Follow System, Retina/HiDPI, sidebar and Reduce Blur enabled.
- [ ] Media keys and Control Center remote commands/metadata, background playback.
- [ ] Built-in, USB and Bluetooth outputs; switch default route, disconnect the
      selected device, sleep/wake and confirm position/continuity.
- [ ] Close/reopen and app restart; updater opens architecture-appropriate official
      download instructions and leaves the installed application untouched.

Windows/Linux/Android workflows are unchanged. Changes to shared desktop code are
platform-guarded. The macOS Compose/Haze graph matches Windows' stable 1.10.3 graph;
Linux's 1.12 graph and Windows native packaging are preserved. Full regression
packages for those platforms still need their existing workflows to run on the PR.
