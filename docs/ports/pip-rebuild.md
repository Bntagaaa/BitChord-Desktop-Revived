# Desktop PiP rebuild

Base: latest fetched upstream-1109-port. Branch: feature/pip-rebuild-from-scratch.
PR #87 and feature/resizable-pip-player are not implementation inputs.

## Audit and implementation plan

The current base has no standalone PiP window. DesktopApp owns the single audio
engine, queue restrictions, one lyrics lookup/cache, and PlaybackPosition. The
immersive player lives in DesktopPlayerSheet, which owns its desktop fullscreen
control. Main.kt and DesktopWindowVisibility own native close-to-tray behavior.
Shared ArtworkMeshBackdrop prepares and caches the fullscreen player's tiny
pre-blurred cover; global reduceDynamicBlur is authoritative.

A naive second player would duplicate audio/video decoding, lyrics lookups and
artwork decoding; resizing could restart requests, and a stale lyrics result could
appear during a song switch. Window disposal must not release the shared engine.

Implementation responsibilities:
- DesktopPipController: idempotent open/ready/close/restore/dispose state machine.
- DesktopPipSettings: coalesced off-thread persistence of bounds and preferences.
- DesktopPipGeometry: usable monitor bounds, disconnected-monitor recovery.
- DesktopPipWindow: native decorated resizable window, focus/pin/disposal handling.
- DesktopPipState: adapt existing playback/lyrics, responsive sizing and line selection.
- DesktopPipUi: cover-derived background, fluid body, controls and lyrics.
- DesktopApp/PlayerSheet/WindowVisibility: activation buttons, tray setting, existing commands.
- Shared artwork preparation: an optional already-loaded cover input, preserving
  existing fullscreen defaults while avoiding a second PiP fetch.
- Shared PlayerLyrics: a narrow wrapper over the existing lyric clock, allowing a
  visible floating window to keep lyrics moving while another application has focus.

No new playback engine, video renderer, lyric repository or independent blur
preference. Fixed-resolution Coil requests and the existing prepared backdrop
cache are keyed by artwork, never by current window dimensions. Tests cover the
controller, monitor restoration, responsive layout, lyrics, and rendered UI. Native
X11 smoke validation/screenshots will be attempted; Windows and Wayland behavior
will be documented only to the degree actually exercised.

## Implemented behavior

Manual launchers sit beside Lyrics in both desktop player styles and beside
fullscreen in the expanded player. The floating window becomes visible before
the main window is hidden. No load/play/pause/seek is issued on open, close or
restore. Playback commands go through the same app intent functions, including
party restrictions and queue behavior. Artwork is the fallback for music videos;
PiP does not create or attach another video surface.

The Miscellaneous setting **Switch to PiP Mode When Closing App** is shown only
when both Tray icon and Keep playing when closed are enabled. Turning either off
masks automatic PiP without deleting the preference. Failure to open preserves
the normal main/tray recovery path. Returning from PiP or raising the main window
through tray/MPRIS dismisses the floating window.

PiP is an auxiliary window: its Close sends it to the recoverable tray when that
behavior is enabled; otherwise it restores main, keeping music running. Explicit
Quit and ordinary primary-window exit remain separate existing app actions.
Escape restores main; Space toggles playback once per key press. Native caption,
move, resize and system menu are retained. This intentionally favors standard
Windows/Linux behavior over custom frameless resize/drag hacks.

The controller validates request identities so a delayed ready/close/failure from
an old opening cannot affect a newer one. Native close and unexpected peer disposal
are observed; listeners are removed during composition disposal. Window size and
position persist after a quiet resize interval and on normal close. Bounds are
stored in logical AWT coordinates and clamped onto an available monitor on reopen,
including layouts with negative monitor origins and disconnected monitors.
Always-on-top is a persisted native flag, disabled when AWT reports no support.

Lyrics are the existing DesktopApp lookup result, stamped with its owning track.
Canceled lookups cannot overwrite a newer result. The floating lyric uses the same
shared LyricClock and offset/seek rules, with visible-window activity rather than
keyboard focus governing its clock. Timestamped lines, intro, explicit instrumental
gaps and known vocal ends are supported. Unsynced lyrics scroll without pretending
to be timed. Loading, missing and disabled lyrics have explicit states. Show/Hide
Lyrics persists separately from the app's global lyrics enable switch.

## Rendering and resource bounds

The user-provided small/medium/large SVGs guided composition only. They are neither
fixed-size templates nor production artwork. No red/pink reference colors were
copied. Padding, typography, spacing and transport size vary continuously; a wide
window can use side-by-side composition. Minimum native outer size starts at 340 × 210
logical pixels and includes measured native border/caption insets to retain at
least a 320 × 180 client area; render coverage includes 340 × 180. Title/artist
truncate safely and artwork preserves its ratio with Fit scaling.

The cover uses a fixed 512px Coil request, sharing the app image loader/cache. The
fullscreen player's existing prepared 128px cover blur and eight-entry cache are
reused, deriving the tiny blurred texture from PiP's already-loaded cover instead
of fetching it again, with its neutral fallback and legibility scrim. Neither request depends on
window dimensions. PiP never creates an audio engine, audio decoder, video decoder,
lyrics repository, or polling service.

With Reduce Blur enabled, the prepared-blur composable is not called; a sharp cover
is drawn under the same scrim. The previous blurred image is excluded immediately.
There is no Haze/RenderEffect, text blur, animated video background, or independent
PiP blur setting. Artwork crossfades are short and skip the main transition when
Reduce Animation is enabled. Closing/iconifying disposes image/lyric composition
and stops its frame work. Preferences use one lazy serial daemon, a coalesced
snapshot and a normal-JVM-exit flush. Only the on-close Boolean is observed at app
root, so moving/resizing PiP does not invalidate the whole main application.

These are architectural resource limits, not measured process RSS/CPU savings.
The shared Coil cache may retain bounded reusable images after PiP dismissal.

## Reproduction

Linux native smoke requires a real X11 display or Xvfb:

```sh
BITCHORD_PIP_NATIVE_TEST=1 xvfb-run -a ./gradlew \
  :desktopApp:test :shared:jvmTest :sharedUi:jvmTest :desktopApp:createDistributable
```

The normal test suite runs the rendered UI/model/controller tests. The real AWT
lifecycle test is opt-in; without the variable its method returns without running
that scenario. Local validation explicitly enabled it. Check CI now enables it
under Xvfb on Linux, also runs sharedUi tests on Linux/Windows, and uploads actual
PiP render evidence. Hosted CI results were not observed from this environment.

The UI tests render nine sizes: 340×180, 360×210, 437×283, 500×340, 639×360,
700×350, 820×410, 1000×300 and 360×620. They inspect control/artwork bounds, preserve
cover aspect, delegate a seek once, honor locked controls, change lyrics visibility,
switch blur rendering, and reject old-song lyrics. Paused seeks and positive/negative
offset changes are exercised through the shared clock. The native test checks one
live peer during repeated show requests, resize, pin flags, restore, close-to-tray,
native close, unexpected native disposal and reopening.

## Screenshots and remaining manual QA

The following are actual test renders of production PiP components, using generated
fixture artwork/text, not captures of a live account or an audible music session.
The Xvfb native captures have no full window manager, so they do not certify native
caption styling, drag handles or stacking above another real application.

![Small PiP](pip-rebuild/small.png)
![Intermediate dimensions](pip-rebuild/intermediate.png)
![Wide PiP](pip-rebuild/wide.png)
![Tall PiP](pip-rebuild/tall.png)
![Reduce Blur off](pip-rebuild/blur.png)
![Reduce Blur on](pip-rebuild/no-blur.png)
![Native X11 window capture](pip-rebuild/native.png)

Before release, check on actual Windows and KDE/GNOME machines:

- Manual activation from both main-player styles and fullscreen, with real audio.
- All tray/keep-playing/automatic-PiP combinations and explicit Quit.
- WASAPI/Linux output, media keys, real queue transitions, seeks and video/audio versions.
- Native resize borders, decorations, focus/Alt-Tab, pin stacking and rapid reopen.
- Multiple monitors, negative origins, disconnect/reconnect and mixed DPI/UI scaling.
- X11 and Wayland sessions through supported AWT/XWayland behavior; no native Wayland
  implementation or compositor-specific global positioning is claimed.
- Bright/dark/missing covers, long/RTL/unsynced lyrics, buffering and global Reduce Blur.

There is no physical audio device or Windows/Wayland runtime here. Listening tests,
Bluetooth, real desktop stacking and app-level end-to-end audio continuity remain
manual QA. Android sources are unchanged. Shared changes are the small clock wrapper/default
visibility override and optional bitmap input to the existing artwork preparation;
existing Android/fullscreen calls retain their defaults. JVM shared tests compile/run it; an Android
SDK/build was unavailable locally. Final numerical/build results are recorded in
[pip-rebuild/validation.json](pip-rebuild/validation.json).

## Recorded validation

- **633 tests passed:** desktop 529, shared 70, sharedUi 34; zero failures/errors/skips.
  The native-window opt-in was explicitly enabled in the full local Linux run.
- The local HTTP artwork test returns `Cache-Control: no-store` and observes exactly
  **one request** supplying cover plus prepared background, across subsequent resizes.
- Final Linux `createDistributable` passed. Windows JVM/portable cross-build passed;
  this Linux environment has no `bitchord_analysis.dll`, so the cross-built archive
  is not evidence of a working Automix-enabled Windows release. Actual Windows CI
  compiles its own native helpers and remains unobserved here.
- `actionlint` passed for the changed Check workflow. Existing Android application
  code and unrelated updater/performance branches were not changed or merged.
- Screenshots were inspected after actual rendering. No regressions were observed
  in executed tests; the device/platform checks above remain release QA.
