import 'package:flutter/material.dart';

import '../features/shell/domain/shell_settings.dart';

/// Like Main.kt's LocalDensity override: zoom geometry and type together,
/// independently of the monitor's device scale and accessibility text scale.
class DesktopScale extends StatelessWidget {
  const DesktopScale({super.key, required this.percent, required this.child});
  final int percent;
  final Widget child;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final scale = normalizeUiScale(percent) / 100;
      final size = Size(
        constraints.maxWidth / scale,
        constraints.maxHeight / scale,
      );
      final media = MediaQuery.of(context);
      return FittedBox(
        fit: BoxFit.fill,
        alignment: Alignment.topLeft,
        child: SizedBox.fromSize(
          size: size,
          child: MediaQuery(
            data: media.copyWith(
              size: size,
              devicePixelRatio: media.devicePixelRatio * scale,
            ),
            child: child,
          ),
        ),
      );
    },
  );
}
