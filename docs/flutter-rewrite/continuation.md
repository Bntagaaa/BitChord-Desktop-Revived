# Continuation plan — resume the existing rewrite

## Preserve the current work

Work on `1.9.2-Rewrite` and inspect its latest GitHub head first. Do not regenerate
Flutter over the existing `lib`, tests or native runners. Do not create another
rewrite branch, force-push, alter `1.9.2`/`1.9.1`/Reworked, or merge the PR.
The reference commit is `348b613a0c3db1a54de56483ea6f8748d6465211`.

Read README, feature-parity, source inventory, subsystem reports and the latest
CI logs. Check the actual implementation before assuming an item is still absent:
this document is a continuation checklist, not permission to discard progress.

## First: close verification gaps

1. Run the pinned Flutter workflow and fix every format, analyzer, test and
   native compile failure. Keep assertion failures separate from toolchain or
   runner restrictions. Check the actual architecture of built binaries.
2. Launch the complete bundles on Linux GNOME/KDE (X11 and supported Wayland),
   Windows x64, macOS arm64 and x64. Exercise tray/media controls, selected output,
   local audio, remote streams, video, separate PiP engines and shutdown.
3. Verify live signed-in/anonymous Home/Search/Library, playlist ownership and
   duplicate entries, source fallback, downloads, expired URLs, logout during
   requests and persistent queue restore. Use test accounts, never committed
   credentials or captured private catalogue fixtures.
4. Capture source and Flutter screens at matching dimensions/scale/account/data:
   all fifteen PDF reference areas, empty/error/loading states, light/dark/pitch
   black and Default/Legacy fonts. Resolve drift in actual colors, icons, layout,
   animation, word timing, menu geometry and blur. Do not treat screenshots of
   fixed test fixtures as proof of live-catalogue fidelity.

## Then finish missing source subsystems

- **AutoMix and precision audio:** connect native PCM analysis to the existing
  Dart planning port, retain source confidence/eligibility decisions, implement
  transition scheduler and DSP (tempo/phase, bass/vocal/echo/rides), PCM cache,
  decode-ahead, A/B loop/waveform and cancellation across seeks/device changes.
- **Authentication:** reproduce DesktopBrowserSignIn/WebSignIn/YouTubeAuth,
  account switching and PO-token flow using native desktop services. Preserve
  session isolation and secure storage; cookie import is only the current
  partial workflow.
- **Source engines:** implement custom compatible module indexes and their
  runtime/cache/error policy. Do not revive the withdrawn seeded MODULE URL.
  Complete source diagnostic/pipeline and precision/format preferences.
- **Spotify/animation:** full authenticated library/browser flows, Canvas,
  Apple/Continued artist artwork and animated backgrounds, with bounded native
  frame/decoder/cache lifecycles.
- **Downloads:** actual HLS/DASH/MPD segment fetching/remux, source policy during
  refresh/fallback, tags/cover/lyrics export and source download formats.
- **Integrations:** Discord Rich Presence, Last.fm, ListenBrainz and Listen
  Together/PartySync, including source settings, consent/authentication,
  retries, disconnect and background lifecycle.
- **Library/catalogue:** remaining cloud reorder/batch/privacy/save actions,
  owned/foreign playlist distinctions, full paging, listening statistics,
  replay, personalized recommendations and complete historical presentation.
- **Desktop release behavior:** native protocol/deep links, all PiP modes and
  persisted geometry, original platform backdrops/captions, onboarding,
  app-icon selection, native menu-bar behavior and every source shortcut.
- **Settings/localization:** audit all source settings including dynamic/shared
  keys, not only the generated literal inventory; implement actual behavior
  before exposing controls. Add source interface translations and settings
  import equivalence tests.
- **Packaging/updater:** compatible Windows installers/update rollback, Linux
  packages/AppImage/AUR and update behavior, macOS signing/notarization. Preserve
  existing release flows until the replacement is accepted.

## Finally measure and accept

Run the equivalent-workload protocol in performance.md, retaining raw evidence.
Investigate leaks and slow frames without silently reducing visual or functional
scope. Update each parity row and platform result from observed evidence. The PR
may move out of draft only once the complete rewrite meets the user's acceptance
criteria; merging still requires a separate instruction.
