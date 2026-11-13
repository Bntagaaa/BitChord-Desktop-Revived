enum CatalogKind { track, album, playlist, artist }

enum SearchFilter { all, songs, albums, artists, playlists }

enum ShelfLayout { cards, tracks }

final class CatalogShelf {
  CatalogShelf({
    required this.title,
    required Iterable<CatalogEntry> entries,
    this.subtitle = '',
    this.layout = ShelfLayout.cards,
    this.moreId,
    this.params,
  }) : entries = List.unmodifiable(entries);
  final String title, subtitle;
  final List<CatalogEntry> entries;
  final ShelfLayout layout;
  final String? moreId, params;
}

final class CatalogHeader {
  const CatalogHeader({required this.title, this.subtitle = '', this.artwork});
  final String title, subtitle;
  final Uri? artwork;
}

enum AudioQuality { low, medium, high, lossless }

enum ProviderProblem {
  cancelled,
  timeout,
  network,
  unauthorized,
  unavailable,
  malformed,
  unsupported,
  expired,
  integrity,
  mediaRefused,
  loginRequired,
  tokenUnavailable,
  formatUnavailable,
  youtubeUnavailable,
  youtubeRuntimeUnavailable,
  youtubeContentUnavailable,
  youtubeRateLimited,
}

final class ProviderFailure implements Exception {
  const ProviderFailure(this.problem, [this.status]);
  final ProviderProblem problem;
  final int? status;
  @override
  String toString() {
    if (problem == ProviderProblem.mediaRefused) {
      return 'YouTube media refused (HTTP 403). A fresh GVS PO Token '
          'or an authorized session may be required.';
    }
    if (problem == ProviderProblem.loginRequired) {
      return 'YouTube requested sign-in or blocked anonymous playback '
          'on this connection. The song was not opened.';
    }
    if (problem == ProviderProblem.tokenUnavailable) {
      return 'YouTube PO Token generation failed. The media source '
          'is not usable.';
    }
    if (problem == ProviderProblem.formatUnavailable) {
      return 'YouTube offered no compatible direct HTTPS audio-only stream '
          'for this track. Sign-in or a different client may be required.';
    }
    if (problem == ProviderProblem.youtubeUnavailable) {
      return 'YouTube could not resolve an audio source. The request may '
          'be restricted by YouTube, a sign-in check, or this connection.';
    }
    if (problem == ProviderProblem.youtubeRuntimeUnavailable) {
      return 'The bundled YouTube runtime is missing or incomplete. Re-extract the full application bundle.';
    }
    if (problem == ProviderProblem.youtubeContentUnavailable) {
      return 'This YouTube item is private, removed, or restricted for this account or region.';
    }
    if (problem == ProviderProblem.youtubeRateLimited) {
      return 'YouTube is rate-limiting this connection. Pause requests and try again later.';
    }
    return 'Provider ${problem.name}${status == null ? '' : ' (HTTP $status)'}';
  }
}

// Cancellation closes owned transport, and account/query generations fence late results.
final class RequestScope {
  bool _cancelled = false;
  final _listeners = <void Function()>{};
  bool get cancelled => _cancelled;
  void Function() onCancel(void Function() callback) {
    if (_cancelled) {
      callback();
      return () {};
    }
    _listeners.add(callback);
    return () => _listeners.remove(callback);
  }

  void check() {
    if (_cancelled) throw const ProviderFailure(ProviderProblem.cancelled);
  }

  void cancel() {
    if (!_cancelled) {
      _cancelled = true;
      for (final callback in _listeners.toList()) {
        callback();
      }
      _listeners.clear();
    }
  }
}

final class CatalogEntry {
  const CatalogEntry({
    required this.provider,
    required this.id,
    required this.title,
    this.subtitle = '',
    this.kind = CatalogKind.track,
    this.durationSeconds,
    this.artwork,
    this.params,
    this.isVideo = false,
  });
  final String provider, id, title, subtitle;
  final CatalogKind kind;
  final int? durationSeconds;
  final Uri? artwork;
  final String? params;
  final bool isVideo;
  String get key => '$provider:$id';
}

final class CatalogPage {
  CatalogPage(
    Iterable<CatalogEntry> entries, {
    this.continuation,
    Iterable<CatalogShelf> shelves = const [],
    this.header,
  }) : entries = List.unmodifiable(entries),
       shelves = List.unmodifiable(shelves);
  final List<CatalogEntry> entries;
  final List<CatalogShelf> shelves;
  final CatalogHeader? header;
  final String? continuation;
}

// Secrets/headers and signed locations are deliberately absent from toString and persistence.
final class ResolvedMedia {
  ResolvedMedia({
    required this.provider,
    required this.trackId,
    required this.rendition,
    required this.url,
    Map<String, String> headers = const {},
    this.expiresAt,
    this.downloadsAllowed = true,
    this.manifest = false,
  }) : headers = Map.unmodifiable(headers);
  final String provider, trackId, rendition;
  final Uri url;
  final Map<String, String> headers;
  final DateTime? expiresAt;
  final bool downloadsAllowed, manifest;
  void check() {
    if (!['http', 'https'].contains(url.scheme) ||
        url.host.isEmpty ||
        url.userInfo.isNotEmpty ||
        manifest) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    if (expiresAt != null &&
        !expiresAt!.isAfter(DateTime.now().add(const Duration(seconds: 10)))) {
      throw const ProviderFailure(ProviderProblem.expired);
    }
  }

  @override
  String toString() => 'ResolvedMedia(redacted)';
}

abstract interface class CatalogProvider {
  String get id;
  Future<CatalogPage> search(
    String query,
    RequestScope scope, {
    String? continuation,
    SearchFilter filter = SearchFilter.all,
  });
  Future<CatalogPage> browse(
    String browseId,
    RequestScope scope, {
    String? continuation,
    String? params,
  });
  Future<ResolvedMedia> resolve(
    CatalogEntry track,
    AudioQuality quality,
    RequestScope scope,
  );
}
