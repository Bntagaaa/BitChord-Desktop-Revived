# Reused asset provenance

These assets are carried forward from BitChord Desktop Revived `1.9.2`, commit
`348b613a0c3db1a54de56483ea6f8748d6465211`. Existing copyright, attribution,
trademark and redistribution terms continue to apply. Copying an asset into this
directory does not relicense it under the Dart code's license.

| Flutter assets | Original source |
| --- | --- |
| `fonts/sf_pro_display_*.otf` | `desktopApp/src/main/composeResources/font/` (also present under Android resources) |
| `fonts/bintang_music_black_about.ttf` | `desktopApp/src/main/composeResources/font/bintang_music_black_about.ttf` |
| `branding/app-icon.png` | `desktopApp/src/main/composeResources/drawable/app_icon.png` |
| `branding/app-icon.ico` | `desktopApp/packaging/icons/AppIcon.ico` |
| `icons/wordmark.svg` | `desktopApp/src/main/composeResources/drawable/bitchord_wordmark.svg` |
| `icons/no-music.svg` | `desktopApp/src/main/composeResources/drawable/bitchord_no_music.svg` |
| `icons/nav-*.svg` | Paths from `DesktopPrimaryNavigationIcons.kt`, converted to SVG |
| `icons/ic_*.svg` | Corresponding original vector drawable paths under `app/src/main/res/drawable/`, converted to SVG |
| Native launcher icon resources | Existing BitChord desktop icon resources, adapted to platform-required sizes |

SF Pro Display is an Apple font. The repository's software license alone is not
a new grant of font redistribution rights; preserve the original asset terms
and verify the applicable grant as part of public release review. The same
principle applies to the original brand artwork and custom display font.

The native playback bundle includes libmpv and its codec dependencies via
media_kit. Their license notices and source/distribution obligations must remain
with release packaging. Flutter generates package notices for dependencies; the
application's About/licenses view exposes Flutter's license registry. Repository
LICENSE and existing upstream attribution remain unchanged.
