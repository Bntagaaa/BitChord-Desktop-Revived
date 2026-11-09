import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'theme.dart';

class SourceIcon extends StatelessWidget {
  const SourceIcon(this.name, {super.key, this.size = 22, this.color});
  final String name;
  final double size;
  final Color? color;
  @override Widget build(BuildContext context) => SvgPicture.asset(
    'assets/icons/$name.svg', width: size, height: size,
    colorFilter: ColorFilter.mode(color ?? paletteOf(context).foreground, BlendMode.srcIn));
}

class Artwork extends StatelessWidget {
  const Artwork(this.url, {super.key, this.size, this.radius = 12});
  final String? url;
  final double? size;
  final double radius;
  @override Widget build(BuildContext context) {
    final pixelSize = size == null ? null : (size! * MediaQuery.devicePixelRatioOf(context)).ceil();
    return ClipRRect(borderRadius: BorderRadius.circular(radius),
      child: SizedBox(width: size, height: size, child: ColoredBox(
        color: paletteOf(context).surfaceRaised,
        child: url == null ? const Center(child: Icon(Icons.music_note_rounded, size: 28)) :
          Image.network(url!, fit: BoxFit.cover, cacheWidth: pixelSize,
            errorBuilder: (_, _, _) => const Center(child: Icon(Icons.music_note_rounded, size: 28))))));
  }
}

class TransportButton extends StatelessWidget {
  const TransportButton(this.icon, this.label, this.action,
    {super.key, this.size = 34, this.active = false});
  final String icon;
  final String label;
  final VoidCallback? action;
  final double size;
  final bool active;
  @override Widget build(BuildContext context) => SizedBox(width: size, height: size,
    child: IconButton(padding: EdgeInsets.zero, tooltip: label, onPressed: action,
      icon: SourceIcon(icon, size: size == 36 ? 23 : 20,
        color: action == null ? paletteOf(context).secondary.withValues(alpha: .4) :
          active ? paletteOf(context).foreground : paletteOf(context).secondary)));
}
