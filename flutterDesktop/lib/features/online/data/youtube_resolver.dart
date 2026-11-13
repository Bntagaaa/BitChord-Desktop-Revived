import 'dart:async';
import 'dart:io';

import '../domain/catalog.dart';
import 'provider_http.dart';
import 'range_source.dart';
import 'youtube/extractor.dart';
import 'youtube/formats.dart';
import 'youtube/runtime.dart';

// Coordinates explicit extraction attempts, per-video token contexts and real
// media preflight. No global signed-URL cache, arbitrary client roulette or
// cross-provider substitution. UI and the native audio engine stay unchanged.
final class YouTubeResolver {
  YouTubeResolver(
    String executable, {
    String? node,
    String? poTokenPluginDirectory,
    String? poTokenServerHome,
    bool bundled = false,
    this.timeout = const Duration(seconds: 90),
  }) : runtime = YouTubeRuntime(
         executable,
         node: node,
         pluginDirectory: poTokenPluginDirectory,
         serverHome: poTokenServerHome,
         bundled: bundled,
       ) {
    _extractor = YouTubeProcessExtractor(runtime!);
  }

  // Only a test construction seam, never used by application bootstrap.
  YouTubeResolver.testing({
    required YouTubeExtractor extractor,
    required Future<void> Function(ResolvedMedia, RequestScope) probe,
    this.timeout = const Duration(seconds: 90),
  }) : runtime = null,
       _probeOverride = probe {
    _extractor = extractor;
  }

  final YouTubeRuntime? runtime;
  final Duration timeout;
  late final YouTubeExtractor _extractor;
  Future<void> Function(ResolvedMedia, RequestScope)? _probeOverride;
  String get executable => runtime!.executable;
  String? get node => runtime?.node;
  String? get poTokenPluginDirectory => runtime?.pluginDirectory;
  String? get poTokenServerHome => runtime?.serverHome;
  bool get bundled => runtime?.bundled ?? false;
  bool get supportsSessionPipe => runtime?.supportsSessionPipe ?? false;
  Map<String, Object> _lastReport = const {};
  Map<String, Object> get lastReport => Map.unmodifiable(_lastReport);

  static YouTubeResolver _configured(YouTubeRuntime runtime) => YouTubeResolver(
    runtime.executable,
    node: runtime.node,
    poTokenPluginDirectory: runtime.pluginDirectory,
    poTokenServerHome: runtime.serverHome,
    bundled: runtime.bundled,
  );
  static YouTubeResolver? fromBundleDirectory(Directory directory) {
    final runtime = YouTubeRuntime.fromBundleDirectory(directory);
    return runtime == null ? null : _configured(runtime);
  }

  static YouTubeResolver? fromEnvironment() {
    final runtime = YouTubeRuntime.fromEnvironment();
    return runtime == null ? null : _configured(runtime);
  }

  Future<void> prepareBundledRuntime() => runtime!.prepare();
  List<String> arguments(
    CatalogEntry track,
    AudioQuality quality, {
    bool alternateClient = false,
  }) => runtime!.arguments(track.id, alternateClient ? 'android_vr' : 'mweb');
  static ProviderFailure classifyExtractorFailure(String stderr) =>
      YouTubeProcessExtractor.failure(stderr);
  static ResolvedMedia parse(Map<String, dynamic> data, CatalogEntry track) =>
      YouTubeFormats.parse(data, track);
  static List<ResolvedMedia> candidates(
    Map<String, dynamic> data,
    CatalogEntry track,
    AudioQuality quality,
  ) => YouTubeFormats.candidates(data, track, quality);

  static int _priority(ProviderFailure failure) => switch (failure.problem) {
    ProviderProblem.loginRequired ||
    ProviderProblem.youtubeContentUnavailable => 9,
    ProviderProblem.youtubeRateLimited => 8,
    ProviderProblem.mediaRefused => 7,
    ProviderProblem.tokenUnavailable => 6,
    ProviderProblem.integrity => 5,
    ProviderProblem.network || ProviderProblem.timeout => 4,
    _ => 1,
  };
  static ProviderFailure _classify(ProviderFailure failure) =>
      failure.status == 403
      ? const ProviderFailure(ProviderProblem.mediaRefused, 403)
      : failure.status == 429
      ? const ProviderFailure(ProviderProblem.youtubeRateLimited, 429)
      : failure;

  static Future<ResolvedMedia> firstPlayable(
    List<ResolvedMedia> options,
    Future<void> Function(ResolvedMedia) probe,
  ) async {
    ProviderFailure? bestFailure;
    for (final media in options.take(3)) {
      try {
        await probe(media);
        return media;
      } on ProviderFailure catch (error) {
        final failure = _classify(error);
        if ([
          ProviderProblem.cancelled,
          ProviderProblem.youtubeRateLimited,
          ProviderProblem.loginRequired,
        ].contains(failure.problem))
          throw failure;
        if (bestFailure == null || _priority(failure) > _priority(bestFailure))
          bestFailure = failure;
      }
    }
    throw bestFailure ??
        const ProviderFailure(ProviderProblem.formatUnavailable);
  }

  Future<void> _probe(ResolvedMedia media, RequestScope scope) async {
    if (_probeOverride != null) return _probeOverride!(media, scope);
    final source = RangeSource(
      media,
      const ProviderHttp(timeout: Duration(seconds: 12)),
      scope,
    );
    await source.chunk(0);
    if (source.total! > 1024 * 1024) await source.chunk(2);
    scope.check();
  }

  Future<ResolvedMedia> resolve(
    CatalogEntry track,
    AudioQuality quality,
    RequestScope scope, {
    String? cookie,
    String? requiredRendition,
  }) async {
    scope.check();
    if (track.provider != 'youtube' ||
        track.kind != CatalogKind.track ||
        !RegExp(r'^[a-zA-Z0-9_-]{11}$').hasMatch(track.id)) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    // One deadline covers queueing, all extraction attempts and all range
    // probes. Previously every candidate/client could consume a fresh timeout.
    final operation = RequestScope();
    final detach = scope.onCancel(operation.cancel);
    var timedOut = false, attempts = 0, probes = 0;
    final timer = Timer(timeout, () {
      timedOut = true;
      operation.cancel();
    });
    ProviderFailure? bestFailure;
    var client = 'mweb';
    var refreshedToken = false;
    var triedAlternate = false;
    final watch = Stopwatch()..start();
    try {
      while (attempts < 3) {
        operation.check();
        attempts++;
        try {
          final data = await _extractor.extract(
            YouTubeExtraction(track.id, client, cookie: cookie),
            operation,
          );
          operation.check();
          final options = YouTubeFormats.candidates(
            data,
            track,
            quality,
            requiredRendition: requiredRendition,
          );
          final media = await firstPlayable(options, (media) async {
            operation.check();
            probes++;
            await _probe(media, operation);
            operation.check();
          });
          operation.check();
          _lastReport = {
            'attempts': attempts,
            'probes': probes,
            'client': client,
            'result': 'range_verified',
            'elapsed_ms': watch.elapsedMilliseconds,
          };
          return media;
        } on ProviderFailure catch (error) {
          operation.check();
          final failure = _classify(error);
          if (bestFailure == null ||
              _priority(failure) > _priority(bestFailure))
            bestFailure = failure;
          // Account/rights restrictions and rate limits are terminal. Do not
          // hammer clients or disguise an authenticated failure as a guest.
          if ([
            ProviderProblem.loginRequired,
            ProviderProblem.youtubeRateLimited,
            ProviderProblem.youtubeContentUnavailable,
            ProviderProblem.youtubeRuntimeUnavailable,
            ProviderProblem.unsupported,
            ProviderProblem.malformed,
          ].contains(failure.problem))
            break;
          if (client == 'mweb' &&
              !refreshedToken &&
              [
                ProviderProblem.mediaRefused,
                ProviderProblem.tokenUnavailable,
                ProviderProblem.expired,
              ].contains(failure.problem)) {
            refreshedToken = true; // new subprocess + isolated token cache
            continue;
          }
          if (!triedAlternate) {
            triedAlternate = true;
            client = cookie == null ? 'android_vr' : 'web_music';
            continue;
          }
          break;
        }
      }
      throw bestFailure ??
          const ProviderFailure(ProviderProblem.youtubeUnavailable);
    } on ProviderFailure catch (failure) {
      final result = timedOut
          ? const ProviderFailure(ProviderProblem.timeout)
          : failure;
      _lastReport = {
        'attempts': attempts,
        'probes': probes,
        'client': client,
        'result': result.problem.name,
        'elapsed_ms': watch.elapsedMilliseconds,
      };
      throw result;
    } finally {
      timer.cancel();
      detach();
      operation.cancel();
    }
  }
}
