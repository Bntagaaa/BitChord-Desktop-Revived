// Source: DesktopLyricsSession.kt. A selected track never borrows the previous
// engine's duration; a zero-length database match must wait for real metadata.
import 'dart:async';
import 'package:flutter/foundation.dart';
import 'music.dart';

typedef LyricsLookup =
    Future<List<LyricLine>> Function(Song song, Duration duration);

class LyricsSession extends ChangeNotifier {
  LyricsSession(this.lookup);
  final LyricsLookup lookup;
  Song? _song;
  Duration _nativeDuration = Duration.zero;
  Duration? _askedDuration;
  int _request = 0;
  bool _closed = false, _enabled = true;
  List<LyricLine> lines = [];
  bool loading = false;
  String? error;

  void select(Song? song, {bool enabled = true}) {
    _request++;
    _song = song;
    _nativeDuration = Duration.zero;
    _askedDuration = null;
    _enabled = enabled;
    lines = [];
    loading = song != null && enabled;
    error = enabled ? null : 'Synced lyrics are switched off';
    notifyListeners();
    _refresh();
  }

  // The application supplies this only for the adopted audio generation.
  void reportDuration(Duration value) {
    if (_closed || _nativeDuration > Duration.zero || value <= Duration.zero) {
      return;
    }
    _nativeDuration = value;
    _refresh();
  }

  void _refresh() {
    final song = _song;
    if (_closed || song == null || !_enabled) return;
    final duration = _nativeDuration > Duration.zero
        ? _nativeDuration
        : song.duration ?? Duration.zero;
    if (duration == _askedDuration) return;
    _askedDuration = duration;
    final request = ++_request;
    unawaited(() async {
      try {
        final result = await lookup(song, duration);
        if (_closed || request != _request) return;
        lines = withBackgroundVocals(result);
        error = lines.any((line) => line.text.trim().isNotEmpty)
            ? null
            : 'Lyrics unavailable';
      } on Exception {
        if (_closed || request != _request) return;
        error = 'Lyrics unavailable';
      } finally {
        if (!_closed && request == _request) {
          loading = false;
          notifyListeners();
        }
      }
    }());
  }

  @override
  void dispose() {
    _closed = true;
    _request++;
    super.dispose();
  }
}
