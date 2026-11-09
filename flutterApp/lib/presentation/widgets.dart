import 'dart:io';
import 'dart:ui';

import 'package:flutter/material.dart';

import '../domain/models.dart';
import 'desktop_theme.dart';

class Artwork extends StatelessWidget {
  const Artwork(
    this.url, {
    super.key,
    this.size = 48,
    this.radius = 7,
    this.fit = BoxFit.cover,
    this.width,
    this.height,
  });
  final String? url;
  final double size, radius;
  final double? width, height;
  final BoxFit fit;
  @override
  Widget build(BuildContext context) {
    final p = DesktopPalette.of(context);
    final w = width ?? size, h = height ?? size;
    final physical = (w * MediaQuery.devicePixelRatioOf(context)).ceil().clamp(
      32,
      1200,
    );
    Widget fallback() => ColoredBox(
      color: p.raised,
      child: Center(
        child: Icon(
          Icons.music_note_rounded,
          size: (size * .4).clamp(18, 70),
          color: p.secondary,
        ),
      ),
    );
    final source = artworkUrlAt(url, physical);
    Widget child;
    if (source == null || source.isEmpty) {
      child = fallback();
    } else if (source.startsWith('http://') || source.startsWith('https://')) {
      child = Image.network(
        source,
        fit: fit,
        cacheWidth: physical,
        gaplessPlayback: true,
        errorBuilder: (_, _, _) => fallback(),
        loadingBuilder: (_, image, progress) =>
            progress == null ? image : fallback(),
      );
    } else {
      final file = source.startsWith('file:')
          ? File.fromUri(Uri.parse(source))
          : File(source);
      child = Image.file(
        file,
        fit: fit,
        cacheWidth: physical,
        gaplessPlayback: true,
        errorBuilder: (_, _, _) => fallback(),
      );
    }
    return RepaintBoundary(
      child: ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: SizedBox(width: w, height: h, child: child),
      ),
    );
  }
}

/// Blur is clipped to this surface; menus never add a dimming barrier.
class GlassSurface extends StatelessWidget {
  const GlassSurface({
    super.key,
    required this.child,
    this.radius = 16,
    this.blur = true,
    this.color,
    this.padding = EdgeInsets.zero,
    this.sigma = 18,
  });
  final Widget child;
  final double radius, sigma;
  final bool blur;
  final Color? color;
  final EdgeInsetsGeometry padding;
  @override
  Widget build(BuildContext context) {
    final p = DesktopPalette.of(context);
    final shape = BorderRadius.circular(radius);
    final decorated = DecoratedBox(
      decoration: BoxDecoration(
        color: color ?? (blur ? p.glass : p.player),
        borderRadius: shape,
        border: Border.all(color: p.edge, width: .5),
      ),
      child: Padding(padding: padding, child: child),
    );
    return ClipRRect(
      borderRadius: shape,
      child: blur
          ? BackdropFilter(
              filter: ImageFilter.blur(sigmaX: sigma, sigmaY: sigma),
              child: decorated,
            )
          : decorated,
    );
  }
}

class HoverTile extends StatefulWidget {
  const HoverTile({
    super.key,
    required this.child,
    this.onTap,
    this.onDoubleTap,
    this.onSecondaryTapDown,
    this.selected = false,
    this.radius = 8,
    this.padding = EdgeInsets.zero,
  });
  final Widget child;
  final VoidCallback? onTap, onDoubleTap;
  final GestureTapDownCallback? onSecondaryTapDown;
  final bool selected;
  final double radius;
  final EdgeInsetsGeometry padding;
  @override
  State<HoverTile> createState() => _HoverTileState();
}

class _HoverTileState extends State<HoverTile> {
  bool hover = false;
  @override
  Widget build(BuildContext context) {
    final p = DesktopPalette.of(context);
    return MouseRegion(
      cursor: widget.onTap == null
          ? MouseCursor.defer
          : SystemMouseCursors.click,
      onEnter: (_) => setState(() => hover = true),
      onExit: (_) => setState(() => hover = false),
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.onTap,
        onDoubleTap: widget.onDoubleTap,
        onSecondaryTapDown: widget.onSecondaryTapDown,
        child: Container(
          padding: widget.padding,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(widget.radius),
            color: widget.selected
                ? p.selected
                : hover
                ? p.rowHover
                : Colors.transparent,
          ),
          child: widget.child,
        ),
      ),
    );
  }
}

class ToolButton extends StatelessWidget {
  const ToolButton({
    super.key,
    required this.icon,
    required this.label,
    this.onPressed,
    this.selected = false,
    this.size = 34,
    this.iconSize = 19,
    this.color,
  });
  final IconData icon;
  final String label;
  final VoidCallback? onPressed;
  final bool selected;
  final double size, iconSize;
  final Color? color;
  @override
  Widget build(BuildContext context) {
    final p = DesktopPalette.of(context);
    return SizedBox(
      width: size,
      height: size,
      child: IconButton(
        tooltip: label,
        onPressed: onPressed,
        padding: EdgeInsets.zero,
        style: IconButton.styleFrom(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
          backgroundColor: selected ? p.foreground.withAlpha(22) : null,
        ),
        icon: Icon(
          icon,
          size: iconSize,
          color: onPressed == null
              ? p.secondary.withAlpha(90)
              : color ?? (selected ? p.foreground : p.secondary),
        ),
      ),
    );
  }
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(
    this.title, {
    super.key,
    this.subtitle,
    this.action,
    this.onAction,
  });
  final String title;
  final String? subtitle, action;
  final VoidCallback? onAction;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 22, bottom: 14),
    child: Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleLarge),
              if (subtitle != null && subtitle!.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 3),
                  child: Text(
                    subtitle!,
                    style: TextStyle(
                      color: DesktopPalette.of(context).secondary,
                      fontSize: 12,
                    ),
                  ),
                ),
            ],
          ),
        ),
        if (onAction != null)
          TextButton(onPressed: onAction, child: Text(action ?? 'See all')),
      ],
    ),
  );
}

class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.title,
    required this.message,
    this.icon = Icons.music_note_rounded,
    this.action,
    this.onAction,
  });
  final String title, message;
  final IconData icon;
  final String? action;
  final VoidCallback? onAction;
  @override
  Widget build(BuildContext context) => Center(
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 420),
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 48, color: DesktopPalette.of(context).secondary),
            const SizedBox(height: 18),
            Text(
              title,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            Text(
              message,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: DesktopPalette.of(context).secondary,
                height: 1.5,
              ),
            ),
            if (action != null && onAction != null)
              Padding(
                padding: const EdgeInsets.only(top: 20),
                child: FilledButton(onPressed: onAction, child: Text(action!)),
              ),
          ],
        ),
      ),
    ),
  );
}

class SongRow extends StatelessWidget {
  const SongRow({
    super.key,
    required this.song,
    required this.onPlay,
    required this.onMenu,
    this.index,
    this.playing = false,
    this.liked = false,
    this.onLike,
    this.onDownload,
    this.compact = false,
    this.showAlbum = true,
  });
  final Song song;
  final VoidCallback onPlay, onMenu;
  final VoidCallback? onLike, onDownload;
  final int? index;
  final bool playing, liked, compact, showAlbum;
  @override
  Widget build(BuildContext context) {
    final p = DesktopPalette.of(context);
    return HoverTile(
      onTap: onPlay,
      onSecondaryTapDown: (_) => onMenu(),
      padding: EdgeInsets.symmetric(horizontal: 9, vertical: compact ? 5 : 7),
      child: Row(
        children: [
          if (index != null)
            SizedBox(
              width: 28,
              child: playing
                  ? Icon(Icons.graphic_eq, size: 15, color: p.navAccent)
                  : Text(
                      '${index! + 1}',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: p.secondary, fontSize: 12),
                    ),
            ),
          Artwork(song.thumbnailUrl, size: compact ? 36 : 44, radius: 5),
          const SizedBox(width: 11),
          Expanded(
            flex: 5,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  song.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontWeight: FontWeight.w600,
                    color: playing ? p.navAccent : p.foreground,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  song.artist,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: p.secondary, fontSize: 12),
                ),
              ],
            ),
          ),
          if (!compact && showAlbum)
            Expanded(
              flex: 3,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Text(
                  song.albumName ?? '',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: p.secondary, fontSize: 12),
                ),
              ),
            ),
          if (!compact)
            SizedBox(
              width: 44,
              child: Text(
                song.durationText ?? '',
                style: TextStyle(fontSize: 12, color: p.secondary),
                textAlign: TextAlign.right,
              ),
            ),
          if (onLike != null)
            ToolButton(
              icon: liked ? Icons.star_rounded : Icons.star_outline_rounded,
              label: liked ? 'Unlike' : 'Like',
              selected: liked,
              onPressed: onLike,
              size: 30,
              iconSize: 17,
            ),
          if (!compact && onDownload != null)
            ToolButton(
              icon: Icons.download_rounded,
              label: 'Download',
              onPressed: onDownload,
              size: 30,
              iconSize: 17,
            ),
          ToolButton(
            icon: Icons.more_horiz,
            label: 'Song actions',
            onPressed: onMenu,
            size: 30,
          ),
        ],
      ),
    );
  }
}

Future<String?> askText(
  BuildContext context, {
  required String title,
  required String label,
  String value = '',
  String action = 'Save',
  String? helper,
  bool obscure = false,
}) async {
  final controller = TextEditingController(text: value);
  final result = await showDialog<String>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(title),
      content: SizedBox(
        width: 430,
        child: TextField(
          controller: controller,
          autofocus: true,
          obscureText: obscure,
          decoration: InputDecoration(
            labelText: label,
            helperText: helper,
            helperMaxLines: 4,
          ),
          onSubmitted: (value) {
            if (value.trim().isNotEmpty) Navigator.pop(context, value.trim());
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () {
            if (controller.text.trim().isNotEmpty) {
              Navigator.pop(context, controller.text.trim());
            }
          },
          child: Text(action),
        ),
      ],
    ),
  );
  // Dialog reverse transitions can still reference its controller on dismissal.
  await Future<void>.delayed(const Duration(milliseconds: 250));
  controller.dispose();
  return result;
}
