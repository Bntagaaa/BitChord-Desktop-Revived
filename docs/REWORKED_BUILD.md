# Build and run Desktop Reworked

The `bitchord-desktop-reworked` branch adds a Windows/Linux JVM host to original BitChord stable v1.9.1. The Android application, TV and Wear targets remain in the repository. Desktop builds explicitly select `-Pbitchord.android=false`, which keeps their dependency graph independent of Android signing, Google-services configuration and SDK discovery.

The initial native distribution targets **Windows x86_64 and Linux x86_64**. CI uses Windows Server 2022 and Ubuntu 24.04. Compatibility with other Linux distributions, ARM machines and physical audio devices must be verified separately; the build rejects unsupported architectures rather than packaging the wrong native libraries.

## Development prerequisites

| Requirement | Purpose |
| --- | --- |
| JDK 21, including `jpackage` | JVM compilation, application runtime and native application images |
| CMake and a C++ toolchain | Original mobile audio analysis/loop-mix JNI and new desktop platform bridge |
| Windows: Visual Studio C++ Build Tools / x64 CMake generator | Native Windows analysis and media-control libraries |
| Windows installer builds: WiX tools discoverable by `jpackage` | EXE/MSI generation; the Windows CI runner provides its packaging toolchain |
| Linux native packages: `rpm` and `desktop-file-utils` | RPM and desktop package generation |
| Python 3 | Packaging/checksum scripts and real updater-helper fixtures |
| A desktop session and audio device | Interactive Compose window and Java Sound playback |

The Gradle wrapper is included. Gradle downloads the Compose, FFmpeg/JavaCPP, QuickJS, ONNX Runtime, JavaFX, SMBJ and other declared dependencies. Only the current host's native artifacts are included in its package. The original mobile analyzer/loop-mix sources and exact model files are bundled by the build tasks.

## Compile and test

On Linux:

```sh
./gradlew --no-daemon -Pbitchord.android=false :shared:jvmTest :sharedUi:jvmTest :desktopApp:test
```

On Windows PowerShell:

```powershell
.\gradlew.bat --no-daemon -Pbitchord.android=false :shared:jvmTest :sharedUi:jvmTest :desktopApp:test
```

Tests use temporary Reworked data roots and a separate Java Preferences node, including on Windows where preferences normally use the registry. Native tests exercise real decoder, QuickJS, analysis and download code; they do not log into a user's services. Test reports are under each module's `build/reports/tests/` directory.

The update workers also have disposable lifecycle fixtures:

```sh
# Linux: replacement, verified handoff and early-crash rollback
python3 desktopApp/src/test/scripts/appimage-update.py
```

```powershell
# Windows: actual disposable EXE, production portable helper, replacement and rollback
python desktopApp/src/test/scripts/windows-portable-update.py
```

The Windows fixture requires Windows PowerShell and the .NET Framework C# compiler provided on the Windows CI runner. CI separately parses both production PowerShell helpers. The Linux fixture reports any environment-specific process-identity skip rather than counting it as a pass.

## Run from source

```sh
./gradlew -Pbitchord.android=false :desktopApp:run
```

Use `gradlew.bat` on Windows. A graphical desktop session is required for the window. Running from source uses the same independent Reworked profile as a packaged Reworked application; it does not read a Revived profile.

A packaged launcher accepts `--check-platform` to verify the Java process and FFmpeg native loading without opening the application window. This smoke check does not exercise an output device or account authentication.

For a local-only graphical smoke test, start the packaged launcher with `--offline-smoke /absolute/path/to/test.wav`. This explicit test mode creates a new empty temporary profile and a unique preferences node, does not restore accounts, and disables outbound catalog/lyrics/artwork/party initialization. The shared HTTP client rejects requests before its credential interceptor or transport runs. The window still uses the real mobile-derived screens and native local-file decoder; a same-name `.lrc` file can exercise timed lyrics. Do not use this mode as evidence that an online provider or login flow works. Normal launches do not enable this policy.

## Build application images and packages

Build on the operating system the package is intended to run on.

Finalize and verify the main application JAR in a separate invocation before packaging. CI applies this check so an incomplete or corrupt archive cannot become an installer:

```sh
./gradlew --no-daemon -Pbitchord.android=false :desktopApp:jar
python3 .github/scripts/reworked_verify_image.py --archive desktopApp/build/libs/desktopApp-*.jar
```

On Windows, use `gradlew.bat` and `python`, and pass the produced JAR path to `--archive` (or use the Git Bash commands in CI).

```sh
# Linux: bundled-runtime image/portable ZIP, DEB and RPM
./gradlew --no-daemon -Pbitchord.android=false :desktopApp:portableDistribution :desktopApp:packageDeb :desktopApp:packageRpm
```

```powershell
# Windows: bundled-runtime image/portable ZIP, MSI and EXE
.\gradlew.bat --no-daemon -Pbitchord.android=false :desktopApp:portableDistribution :desktopApp:packageMsi :desktopApp:packageExe
```

The application image is created in `desktopApp/build/compose/binaries/main/app/BitChordReworked/`. Its launcher is `BitChordReworked.exe` on Windows and `bin/BitChordReworked` on Linux. Portable ZIPs are written to `desktopApp/build/distributions/`; they contain the complete application image and Java runtime. The `reworked-portable.marker` stays beside the launcher, at the Windows package root or the Linux `bin` directory.

Validate the completed image and bundled launcher after packaging:

```sh
python3 .github/scripts/reworked_verify_image.py
```

On Linux, the native media-control wire test uses an isolated session bus and a paused synthetic queue. It does not resolve media or require a sound device:

```sh
dbus-run-session -- python3 desktopApp/src/test/scripts/mpris-wire-smoke.py
```

The fixture checks actual MPRIS properties, seeking, repeat/rate controls, errors, and release of the bus name. `dbus-daemon`, `dbus-bin`, and a JDK must be installed; environments that prohibit private Unix sockets cannot run this fixture locally.

Linux AppImage packaging wraps that image with the original mobile icon and a new launcher:

```sh
python3 .github/scripts/reworked_appimage.py \
  desktopApp/build/compose/binaries/main/app/BitChordReworked \
  desktopApp/build/reworked-appimage/BitChordReworked.AppImage \
  /absolute/path/to/appimagetool
```

The [workflow](../.github/workflows/reworked-desktop.yml) pins and checksums its `appimagetool` download. Use the same verified tool when reproducing CI.

After all the host's package formats have been built, the staging script validates that each expected format has exactly one output and writes updater-compatible names and checksums:

```sh
python3 .github/scripts/reworked_artifacts.py Linux
# On Windows: python .github/scripts/reworked_artifacts.py Windows
```

The resulting `desktopApp/build/reworked-artifacts/` directory contains:

| Platform | Asset suffix, after `BitChordReworked-<full-version>` |
| --- | --- |
| Windows setup | `-windows-x64-setup.exe` |
| Windows MSI | `-windows-x64-installer.msi` |
| Windows portable | `-windows-x64-portable.zip` |
| Linux AppImage | `-linux-x86_64.AppImage` |
| Linux DEB | `-linux-x86_64.deb` |
| Linux RPM | `-linux-x86_64.rpm` |
| Linux portable | `-linux-x86_64-portable.zip` |
| Checksums | `SHA256SUMS.txt` in each host artifact |

The workflow uploads these as Actions artifacts named `BitChordReworked-<platform>-<commit>`. It does not publish a release automatically. When manually publishing a release containing both operating systems, combine the two checksum files into one `SHA256SUMS.txt` without changing their entries.

## Versions and updater releases

The public product version is set by `bitchord.version` in `gradle.properties`, initially `1.9.1-reworked.1`. Set that file consistently before release packaging: the staging script reads the file, while a Gradle command-line override applies only to that local Gradle invocation.

Native package managers need a numeric version that increases for each Reworked revision. The default mapping is:

```text
public version:   major.minor.patch-reworked.revision
package version:  major.minor.(patch * 1000 + revision)

1.9.1-reworked.1 → 1.9.1001
1.9.1-reworked.2 → 1.9.1002
```

Ordinary revision values are 1–999. Major/minor and the computed final field are validated against MSI numeric limits. Custom/preview versions or values outside that mapping require an explicit `-Pbitchord.packageVersion=MAJOR.MINOR.PATCH`; maintainers must choose a monotonic native version. The full application version, launcher `-Dbitchord.version`, archive marker, update comparison and asset names remain the public version.

Updater releases belong to the same repository and use a tag such as `reworked-v1.9.1-reworked.1`. Stable Reworked revisions are accepted on the stable update channel when the release is not marked prerelease. Alpha/beta/RC labels and releases explicitly marked prerelease require the preview option.

The updater checks the exact repository/tag/product namespace, asset name, byte count and SHA-256 before offering installation. Windows portable updates stage the complete image, check each extracted file, wait for the precise application process to exit, retain the old image and roll back an early startup crash. Installed Windows updates hand off to the actual installer. Linux automatic replacement targets an AppImage; DEB/RPM and Linux portable users receive their package/release workflow instead. A visible-window acknowledgment is required before an automatic handoff is marked complete.

## Profile and feature verification

Reworked uses its own `BitChordReworked`/`bitchord-reworked` directories and Java Preferences node `com.music.bitchord.reworked`. Exported downloads go only to the folder selected in Local music and downloads settings; changing that selection does not move existing files. Revived data is not migrated implicitly.

Read [feature coverage](REWORKED_FEATURES.md) before treating a successful build as proof of external-service or hardware parity. Android hardware-attested addons and verified exclusive USB/Atmos output have explicit platform boundaries. Live YouTube/Spotify/Discord/scrobbling accounts, hosted Listen Together, physical output devices, SMB/WebDAV servers and a real Windows installer update still need their stated runtime checks.
