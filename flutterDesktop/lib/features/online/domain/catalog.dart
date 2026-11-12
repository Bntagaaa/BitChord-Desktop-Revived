enum CatalogKind { track, album, playlist, artist }

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
}

final class ProviderFailure implements Exception {
  const ProviderFailure(this.problem, [this.status]);
  final ProviderProblem problem;
  final int? status;
  @override
  String toString() =>
      'Provider ${problem.name}${status == null ? '' : ' (HTTP $status)'}';
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
  });
  final String provider, id, title, subtitle;
  final CatalogKind kind;
  final int? durationSeconds;
  final Uri? artwork;
  final String? params;
  String get key => '$provider:$id';
}

final class CatalogPage {
  CatalogPage(Iterable<CatalogEntry> entries, {this.continuation})
    : entries = List.unmodifiable(entries);
  final List<CatalogEntry> entries;
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
