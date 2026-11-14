import 'dart:async';
import 'dart:convert';
import 'dart:math';

import '../../domain/catalog.dart';
import '../provider_http.dart';
import '../youtube_session.dart';

final class YouTubeHistoryTracker {
  YouTubeHistoryTracker();

  final ProviderHttp _http = const ProviderHttp(timeout: Duration(seconds: 6));
  _TrackingSession? _active;
  String? _pendingVideoId;

  static String generateCpn() {
    const chars =
        'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_';
    final random = Random.secure();
    return List.generate(16, (_) => chars[random.nextInt(chars.length)]).join();
  }

  void onPlaying({
    required String videoId,
    required YouTubeSession session,
  }) {
    if (!RegExp(r'^[a-zA-Z0-9_-]{11}$').hasMatch(videoId)) return;
    if (_active?.videoId == videoId || _pendingVideoId == videoId) return;

    _pendingVideoId = videoId;
    final cpn = generateCpn();

    unawaited(() async {
      try {
        final tracking = await _fetchTracking(
          videoId: videoId,
          session: session,
        );
        if (tracking == null || _pendingVideoId != videoId) return;

        final current = _TrackingSession(
          videoId: videoId,
          cpn: cpn,
          playbackUrl: tracking.playbackUrl,
          watchtimeUrl: tracking.watchtimeUrl,
        );
        _active = current;

        if (current.playbackUrl != null) {
          await _ping(
            url: current.playbackUrl!,
            cpn: cpn,
            session: session,
            params: {'cpn': cpn},
          );
        }
      } catch (_) {
        // History reporting failure must never block or disrupt audio playback.
      } finally {
        if (_pendingVideoId == videoId) {
          _pendingVideoId = null;
        }
      }
    }());
  }

  void onProgress({
    required String videoId,
    required int positionSeconds,
    required YouTubeSession session,
  }) {
    final current = _active;
    if (current == null || current.videoId != videoId) return;
    if (current.watchtimeUrl == null) return;
    if (positionSeconds - current.reportedSeconds < 25) return;

    current.reportedSeconds = positionSeconds;
    unawaited(
      _ping(
        url: current.watchtimeUrl!,
        cpn: current.cpn,
        session: session,
        params: {
          'cpn': current.cpn,
          'pos': '$positionSeconds',
          'state': 'playing',
        },
      ),
    );
  }

  void onPlaybackFinished({
    required String videoId,
    required int positionSeconds,
    required YouTubeSession session,
  }) {
    final current = _active;
    if (current == null || current.videoId != videoId) return;
    _active = null;

    if (current.watchtimeUrl != null) {
      unawaited(
        _ping(
          url: current.watchtimeUrl!,
          cpn: current.cpn,
          session: session,
          params: {
            'cpn': current.cpn,
            'pos': '$positionSeconds',
            'state': 'playing',
            'final': '1',
          },
        ),
      );
    }
  }

  Future<_TrackingUrls?> _fetchTracking({
    required String videoId,
    required YouTubeSession session,
  }) async {
    final scope = RequestScope();
    try {
      final origin = Uri.parse(YouTubeSession.origin);
      final response = await _http.request(
        origin.resolve('/youtubei/v1/player?prettyPrint=false'),
        scope,
        method: 'POST',
        headers: {
          ...session.headers(),
          'Content-Type': 'application/json',
          'User-Agent':
              'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
              '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
        },
        jsonBody: {
          'context': {
            'client': {
              'clientName': 'WEB_REMIX',
              'clientVersion': session.version,
              'hl': 'en',
              'gl': 'US',
              if (session.visitorData != null)
                'visitorData': session.visitorData,
            },
          },
          'videoId': videoId,
          'contentCheckOk': true,
          'racyCheckOk': true,
          'playbackContext': {
            'contentPlaybackContext': {
              'html5Preference': 'HTML5_PREF_WANTS',
              'referer': '${YouTubeSession.origin}/watch?v=$videoId',
            },
          },
        },
        maxBytes: 1024 * 1024,
      );

      if (response.status != 200) return null;
      final data = jsonDecode(utf8.decode(response.bytes));
      if (data is! Map) return null;

      final tracking = data['playbackTracking'];
      if (tracking is! Map) return null;

      final playbackUrl = tracking['videostatsPlaybackUrl'];
      final watchtimeUrl = tracking['videostatsWatchtimeUrl'];

      return _TrackingUrls(
        playbackUrl: playbackUrl is Map ? playbackUrl['baseUrl'] : null,
        watchtimeUrl: watchtimeUrl is Map ? watchtimeUrl['baseUrl'] : null,
      );
    } catch (_) {
      return null;
    } finally {
      scope.cancel();
    }
  }

  Future<void> _ping({
    required String url,
    required String cpn,
    required YouTubeSession session,
    required Map<String, String> params,
  }) async {
    final scope = RequestScope();
    try {
      final uri = Uri.parse(url);
      final target = uri.replace(
        queryParameters: {...uri.queryParameters, ...params},
      );
      await _http.request(
        target,
        scope,
        method: 'GET',
        headers: session.headers(),
        maxBytes: 64 * 1024,
      );
    } catch (_) {
    } finally {
      scope.cancel();
    }
  }
}

final class _TrackingUrls {
  const _TrackingUrls({this.playbackUrl, this.watchtimeUrl});
  final String? playbackUrl;
  final String? watchtimeUrl;
}

final class _TrackingSession {
  _TrackingSession({
    required this.videoId,
    required this.cpn,
    this.playbackUrl,
    this.watchtimeUrl,
  });
  final String videoId;
  final String cpn;
  final String? playbackUrl;
  final String? watchtimeUrl;
  int reportedSeconds = 0;
}
