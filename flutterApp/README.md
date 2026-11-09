# BitChord Desktop Revived — Flutter

This is the active Flutter desktop rewrite from Revived `1.9.2`. It is an
**incomplete rewrite checkpoint**, not a release-ready replacement.

See [rewrite overview, parity reports and continuation tasks](../docs/flutter-rewrite/README.md)
and [platform build instructions](../docs/flutter-rewrite/platform-support.md).

Use Flutter 3.47.9 with `flutter pub get --enforce-lockfile`, then `flutter run -d
linux` (or `windows` / `macos` on the appropriate host). A native libmpv backend
is bundled through media_kit; the Flutter application does not launch a JVM.

Assets are reused from the repository baseline. Original copyright, attribution
and asset redistribution terms remain applicable; see [asset provenance](assets/NOTICE.md)
and the repository [LICENSE](../LICENSE).
