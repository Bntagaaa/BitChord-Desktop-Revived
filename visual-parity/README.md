# Real desktop visual evidence

See [VISUAL_PARITY_REPORT.md](../VISUAL_PARITY_REPORT.md) for scope, discrepancies,
environment, commands and verification status. These are actual application
client captures, not widget goldens or generated screen mockups.

- `reference/`: immutable Kotlin 1.9.2, CI distributable from run 43225291641.
- `flutter/`: the migration app, with baseline and post-change captures.
- `diff/`: red heatmaps, max channel difference amplified four times.
- `overlays/`: 50% image blends.
- `reports/`: reference-left / Flutter-right pairs and supporting JSON metrics.

Capture with a disposable signed-out profile and a visible X11/XWayland window:

```sh
python3 flutterDesktop/tool/visual_parity.py capture \
  --window 0xWINDOW --output visual-parity/flutter/example.png \
  --state 'home; signed out; empty queue' --source COMMIT_OR_SOURCE_DIGEST \
  --theme Light --font 'installed bitchord-sans' \
  --display-scale 'XWayland 1x; 96 DPI' --width 1220 --height 780
```

The command activates and resizes that client, moves the pointer away, checks
geometry and records a SHA-256 sidecar. Manually navigate to the stated screen
first and inspect the result: a matching label cannot prove matching app state.
Use `--expected-pid PID` to reject a window owned by another process. When both
apps have the same title, confirm each ID with `xdotool getwindowpid`; do not
assume a title search identifies the right application.
Never capture an authenticated profile for this evidence set.

```sh
python3 flutterDesktop/tool/visual_parity.py compare \
  --reference visual-parity/reference/final-home-1220x780.png \
  --flutter visual-parity/flutter/final-home-1220x780.png \
  --root visual-parity --name final-home-1220x780
python3 -m unittest discover -s flutterDesktop/tool -p test_visual_parity.py
```

Unequal sizes, modified capture hashes and inconsistent state/theme/scale
metadata are rejected. No image is stretched or automatically aligned. Optional
repeatable `--mask x,y,width,height` excludes a rectangle from metrics and paints
it gray in derivatives; originals are unchanged. Masks and compared-pixel counts
are recorded. A zero metric over a masked region proves nothing about that region.
Different guest feeds and blur over different artwork cannot establish content
or material parity. No metric automatically accepts a screen.

Dependencies: Python 3 + Pillow; ImageMagick `import`; xdotool; an X11/XWayland
display. Missing native GUI access is a blocker, not a substitute widget capture.
Local package tools were extracted under `/tmp` after checking their detached
signatures with the installed Arch keyring; no system package was installed.
