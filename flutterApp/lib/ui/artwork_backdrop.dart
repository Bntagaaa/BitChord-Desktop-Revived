// Source: rememberFullArtworkBlurImage / FullArtworkBlurBackdrop at 348b613a.
import 'dart:async';
import 'dart:collection';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import '../data/artwork_blur.dart';
import 'widgets.dart';

// A top-level worker keeps native ui.Image handles out of the isolate message.
Uint8List _blurPixels((Uint8List, int, int) input) => blurArtworkRgba(input.$1, input.$2, input.$3);

class ArtworkBlurCache {
  final _ready = LinkedHashMap<String, ui.Image>();
  final _inFlight = <String, Future<ui.Image?>>{};
  static final shared = ArtworkBlurCache();

  Future<ui.Image?> read(String url, ImageConfiguration configuration) async {
    final cached = _ready.remove(url);
    if (cached != null) { _ready[url] = cached; return cached.clone(); }
    final pending = _inFlight.putIfAbsent(url, () => _prepare(url, configuration));
    try {
      final image = await pending;
      // Each view owns a reference: evicting a cached image must not invalidate
      // another native view that is still drawing the same cover.
      return image?.clone();
    } finally { if (identical(_inFlight[url], pending)) _inFlight.remove(url); }
  }

  Future<ui.Image?> _prepare(String url, ImageConfiguration configuration) async {
    final provider = ResizeImage(artworkProvider(url), width: 128, height: 128, policy: ResizeImagePolicy.fit);
    final stream = provider.resolve(configuration), received = Completer<ImageInfo>();
    late final ImageStreamListener listener;
    listener = ImageStreamListener((info, _) {
      if (!received.isCompleted) { received.complete(info); } else { info.dispose(); }
    }, onError: (Object error, StackTrace? trace) {
      if (!received.isCompleted) received.completeError(error, trace);
    });
    stream.addListener(listener);
    ImageInfo? info;
    try {
      info = await received.future.timeout(const Duration(seconds: 20));
      final image = info.image;
      final bytes = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
      if (bytes == null) return null;
      final width = image.width, height = image.height;
      final raw = bytes.buffer.asUint8List(bytes.offsetInBytes, bytes.lengthInBytes);
      final blurred = await compute(_blurPixels, (raw, width, height));
      final result = Completer<ui.Image>();
      ui.decodeImageFromPixels(blurred, width, height, ui.PixelFormat.rgba8888, result.complete);
      final prepared = await result.future;
      _ready[url] = prepared;
      while (_ready.length > 8) { _ready.remove(_ready.keys.first)?.dispose(); }
      return prepared;
    } catch (_) { return null; }
    finally { stream.removeListener(listener); info?.dispose(); }
  }

  void dispose() { for (final image in _ready.values) { image.dispose(); } _ready.clear(); }
}

class FullArtworkBackdrop extends StatefulWidget {
  const FullArtworkBackdrop({super.key, required this.url});
  final String? url;
  @override
  State<FullArtworkBackdrop> createState() => _FullArtworkBackdropState();
}
class _FullArtworkBackdropState extends State<FullArtworkBackdrop> with SingleTickerProviderStateMixin {
  late final AnimationController _alpha = AnimationController(vsync: this, duration: const Duration(milliseconds: 320));
  late final CurvedAnimation _fade = CurvedAnimation(parent: _alpha, curve: Curves.fastOutSlowIn);
  ui.Image? _image;
  int _generation = 0;
  String? _loaded;
  @override
  void didChangeDependencies() { super.didChangeDependencies(); _refresh(); }
  @override
  void didUpdateWidget(FullArtworkBackdrop oldWidget) { super.didUpdateWidget(oldWidget); _refresh(); }
  void _refresh() {
    if (_loaded == widget.url) return;
    _loaded = widget.url;
    final generation = ++_generation, url = widget.url;
    _image?.dispose(); _image = null; _alpha.value = 0;
    if (url == null) return;
    final configuration = createLocalImageConfiguration(context);
    unawaited(() async {
      final image = await ArtworkBlurCache.shared.read(url, configuration);
      if (!mounted || generation != _generation) { image?.dispose(); return; }
      setState(() { _image = image; });
      if (image != null) unawaited(_alpha.forward());
    }());
  }
  @override
  void dispose() { _generation++; _image?.dispose(); _fade.dispose(); _alpha.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) => ColoredBox(
    color: const Color(0xff121212),
    child: Stack(fit: StackFit.expand, children: [
      if (_image != null) FadeTransition(opacity: _fade,
        child: RawImage(image: _image, fit: BoxFit.cover, filterQuality: FilterQuality.low)),
      const DecoratedBox(decoration: BoxDecoration(gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter,
        colors: [Color(0x57000000), Color(0x7a000000), Color(0xa3000000)], stops: [0,.55,1]))),
    ]),
  );
}
