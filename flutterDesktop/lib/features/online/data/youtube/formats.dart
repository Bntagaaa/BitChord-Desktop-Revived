import '../../domain/catalog.dart';

// A direct audio-only adaptive URL is a ranged media file, not an MPD/HLS
// manifest. Metadata for each format is validated independently: never inherit
// a selected format's URL/codec when another format omits those fields.
final class YouTubeFormats {
  static ResolvedMedia parse(Map<String, dynamic> data, CatalogEntry track) {
    final urlText = data['url'];
    final url = urlText is String ? Uri.tryParse(urlText) : null;
    final audio = data['acodec'];
    final format = data['format_id'];
    if (data['id'] != track.id ||
        track.provider != 'youtube' ||
        data['vcodec'] != 'none' ||
        audio is! String ||
        audio.isEmpty ||
        audio == 'none' ||
        data['protocol'] != 'https' ||
        data['is_live'] == true ||
        data['has_drm'] == true ||
        url == null ||
        url.scheme != 'https' ||
        url.port != 443 ||
        !(url.host == 'googlevideo.com' ||
            url.host.endsWith('.googlevideo.com')) ||
        url.userInfo.isNotEmpty ||
        url.hasFragment ||
        format is! String ||
        !RegExp(r'^[a-zA-Z0-9_.-]{1,64}$').hasMatch(format)) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    final headers = <String, String>{};
    final rawHeaders = data['http_headers'];
    if (rawHeaders is Map) {
      for (final entry in rawHeaders.entries) {
        if (entry.key is! String || entry.value is! String) continue;
        final name = entry.key as String, value = entry.value as String;
        final key = name.toLowerCase();
        if (![
              'user-agent',
              'accept',
              'accept-language',
              'origin',
              'referer',
            ].contains(key) ||
            value.length > 4096 ||
            RegExp(r'[\r\n\x00]').hasMatch(value))
          continue;
        if (key == 'origin' || key == 'referer') {
          final origin = Uri.tryParse(value);
          if (origin == null ||
              origin.scheme != 'https' ||
              ![
                'www.youtube.com',
                'music.youtube.com',
                'm.youtube.com',
                'youtube.com',
              ].contains(origin.host) ||
              origin.userInfo.isNotEmpty)
            continue;
        }
        headers[name] = value;
      }
    }
    final expires = int.tryParse(url.queryParameters['expire'] ?? '');
    if (expires != null && (expires < 0 || expires > 8640000000000)) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    final media = ResolvedMedia(
      provider: 'youtube',
      trackId: track.id,
      rendition: 'youtube:$format',
      url: url,
      headers: headers,
      expiresAt: expires == null
          ? null
          : DateTime.fromMillisecondsSinceEpoch(expires * 1000),
    );
    media.check();
    return media;
  }

  static List<ResolvedMedia> candidates(
    Map<String, dynamic> data,
    CatalogEntry track,
    AudioQuality quality, {
    String? requiredRendition,
  }) {
    if (data['id'] != track.id ||
        data['is_live'] == true ||
        data['has_drm'] == true) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    final found = <({ResolvedMedia media, double? bitrate, int order})>[];
    final seen = <String>{};
    void add(Map<String, dynamic> format) {
      try {
        final media = parse({
          ...format,
          'id': data['id'],
          'is_live': data['is_live'],
          'http_headers': format['http_headers'] ?? data['http_headers'],
        }, track);
        if (requiredRendition != null && media.rendition != requiredRendition)
          return;
        if (!seen.add(media.url.toString())) return;
        final rawBitrate = format['abr'] ?? format['tbr'];
        final bitrate =
            rawBitrate is num && rawBitrate.isFinite && rawBitrate > 0
            ? rawBitrate.toDouble()
            : null;
        found.add((media: media, bitrate: bitrate, order: found.length));
      } on ProviderFailure {
        // Unsafe, manifest, video-only, expired and DRM formats are not sources.
      }
    }

    add(data);
    if (data['formats'] case final List formats) {
      for (final format in formats.take(512)) {
        if (format is Map<String, dynamic>) add(format);
      }
    }
    if (found.isEmpty)
      throw const ProviderFailure(ProviderProblem.formatUnavailable);
    final ceiling = switch (quality) {
      AudioQuality.low => 64.0,
      AudioQuality.medium => 128.0,
      _ => double.infinity,
    };
    int rank(double? bitrate) =>
        bitrate == null ? 1 : (bitrate <= ceiling ? 0 : 2);
    found.sort((a, b) {
      final ra = rank(a.bitrate), rb = rank(b.bitrate);
      if (ra != rb) return ra.compareTo(rb);
      if (a.bitrate != null && b.bitrate != null) {
        final order = ra == 2
            ? a.bitrate!.compareTo(b.bitrate!)
            : b.bitrate!.compareTo(a.bitrate!);
        if (order != 0) return order;
      }
      return a.order.compareTo(b.order);
    });
    return found.take(3).map((item) => item.media).toList(growable: false);
  }
}
