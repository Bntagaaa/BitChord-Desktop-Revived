# Desktop app icons

Settings → Appearance → App icon offers **Dark** and **Light**. The choice is saved
per installation, independently of the UI theme, and updates the running window
and system tray without restarting playback. Dark is the default.

The original uploaded SVGs are kept unchanged in `src/main/composeResources/drawable/`
(relative to `desktopApp/`): `app_icon_dark.svg` and `app_icon_light_source.svg`.
The tray renders these vectors at its requested sizes. Their 256 × 256 PNG exports,
`app_icon.png` and `app_icon_light.png`, provide the window icons and Settings previews.

`AppIcon.png` matches the Dark PNG; `AppIcon.ico` contains Dark at 16, 20, 24, 32, 48,
64, 128 and 256 pixels. These are the static defaults used by Linux packages,
AppImage and Windows installers. Installed shortcuts and pinned launcher icons
are managed by the OS and may retain that default after changing the running app icon.

To refresh the assets, export each source SVG with Inkscape at 256 × 256 pixels
with its transparent background, copy the Dark PNG to `AppIcon.png`, and generate
the multi-size `AppIcon.ico` from it. No SVG renderer is needed at build time.
