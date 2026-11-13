import 'dart:async';
import 'dart:io';

import '../domain/catalog.dart';
import 'youtube/direct_player.dart';
import 'youtube/extractor.dart';
import 'youtube/formats.dart';
import 'youtube/media_probe.dart';
import 'youtube/runtime.dart';

// Guest playback uses one direct player request first. The maintained extractor
// remains a bounded fallback, and remains the authenticated path. Both paths
// share format isolation, byte validation, account cancellation and renewal IDs.
final class YouTubeResolver {
  YouTubeResolver(
    String executable, {
    String? node,
    String? poTokenPluginDirectory,
    String? poTokenServerHome,
    bool bundled = false,
    this.timeout = const Duration(seconds: 90),
    this.fastTimeout = const Duration(seconds: 8),
  }) : runtime = YouTubeRuntime(
         executable,
         node: node,
         pluginDirectory: poTokenPluginDirectory,
         serverHome: poTokenServerHome,
         bundled: bundled,
       ) {
    _extractor = YouTubeProcessExtractor(runtime!);
    _direct = YouTubeDirectExtractor();
  }

  YouTubeResolver.directOnly({
    this.timeout = const Duration(seconds: 90),
    this.fastTimeout = const Duration(seconds: 8),
  }) : runtime = null {
    _direct = YouTubeDirectExtractor();
  }

  // Test construction seams never selected by application bootstrap.
  YouTubeResolver.testing({
    required YouTubeExtractor extractor,
    required Future<void> Function(ResolvedMedia, RequestScope) probe,
    YouTubeExtractor? direct,
    this.timeout = const Duration(seconds: 90),
    this.fastTimeout = const Duration(seconds: 8),
  }) : runtime = null,
       _probeOverride = probe {
    _extractor = extractor;
    _direct = direct;
  }

  final YouTubeRuntime? runtime;
  final Duration timeout, fastTimeout;
  YouTubeExtractor? _extractor, _direct;
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
  static bool _terminal(ProviderFailure failure) => [
    ProviderProblem.cancelled,
    ProviderProblem.loginRequired,
    ProviderProblem.youtubeRateLimited,
    ProviderProblem.youtubeContentUnavailable,
  ].contains(failure.problem);

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
        if (_terminal(failure)) throw failure;
        if (bestFailure == null ||
            _priority(failure) > _priority(bestFailure)) {
          bestFailure = failure;
        }
      }
    }
    throw bestFailure ??
        const ProviderFailure(ProviderProblem.formatUnavailable);
  }

  Future<ResolvedMedia> _prepare(
    ResolvedMedia media,
    RequestScope scope,
  ) async {
    if (_probeOverride != null) {
      await _probeOverride!(media, scope);
      return media;
    }
    return YouTubeMediaProbe.prepare(media, scope);
  }

  Future<ResolvedMedia> resolve(
    CatalogEntry track,
    AudioQuality quality,
    RequestScope scope, {
    String? cookie,
    String? visitorData,
    String? requiredRendition,
  }) async {
    scope.check();
    if (track.provider != 'youtube' ||
        track.kind != CatalogKind.track ||
        !RegExp(r'^[a-zA-Z0-9_-]{11}$').hasMatch(track.id)) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    final operation = RequestScope();
    final detach = scope.onCancel(operation.cancel);
    var timedOut = false, attempts = 0, probes = 0, directRequests = 0;
    var directMs = 0, extractorMs = 0, probeMs = 0;
    var path = 'extractor', client = 'mweb';
    String? directFailure;
    final watch = Stopwatch()..start();
    final timer = Timer(timeout, () {
      timedOut = true;
      operation.cancel();
    });
    void report(String result, [ResolvedMedia? media]) {
      _lastReport = {
        'attempts': attempts,
        'probes': probes,
        'direct_requests': directRequests,
        'path': path,
        'client': client,
        'result': result,
        'direct_ms': directMs,
        'extractor_ms': extractorMs,
        'probe_ms': probeMs,
        'elapsed_ms': watch.elapsedMilliseconds,
        'preflight_bytes': media?.warmup?.verifiedBytes ?? 0,
        'reused_prefix_bytes': media?.warmup?.prefix.length ?? 0,
        if (directFailure != null) 'direct_failure': directFailure,
      };
    }

    Future<ResolvedMedia> prepare(
      Map<String, dynamic> data,
      RequestScope owner,
    ) async {
      final options = YouTubeFormats.candidates(
        data,
        track,
        quality,
        requiredRendition: requiredRendition,
      );
      ResolvedMedia? prepared;
      await firstPlayable(options, (candidate) async {
        owner.check();
        probes++;
        final timing = Stopwatch()..start();
        try {
          prepared = await _prepare(candidate, owner);
        } finally {
          probeMs += timing.elapsedMilliseconds;
        }
        owner.check();
      });
      return prepared!;
    }

    ProviderFailure? bestFailure;
    try {
      final direct = _direct;
      // VISIONOS is guest-only. A verified account must not be silently changed
      // to anonymous playback or have cookies sent to an incompatible client.
      if (cookie == null && direct != null) {
        final fast = RequestScope();
        final detachFast = operation.onCancel(fast.cancel);
        final deadline = Timer(fastTimeout, fast.cancel);
        final timing = Stopwatch()..start();
        directRequests++;
        path = 'direct';
        client = 'visionos';
        try {
          final data = await direct.extract(
            YouTubeExtraction(track.id, 'visionos', visitorData: visitorData),
            fast,
          );
          fast.check();
          final media = await prepare(data, fast);
          fast.check();
          operation.check();
          directMs = timing.elapsedMilliseconds;
          report('range_verified', media);
          return media;
        } on ProviderFailure catch (error) {
          operation.check();
          final failure = fast.cancelled
              ? const ProviderFailure(ProviderProblem.timeout)
              : _classify(error);
          directFailure = failure.problem.name;
          bestFailure = failure;
          if (_terminal(failure)) throw failure;
        } finally {
          directMs = timing.elapsedMilliseconds;
          deadline.cancel();
          detachFast();
          fast.cancel();
        }
      }
      path = 'extractor';
      client = 'mweb';
      final extractor = _extractor;
      if (extractor == null) {
        throw const ProviderFailure(ProviderProblem.youtubeRuntimeUnavailable);
      }
      var refreshedToken = false, triedAlternate = false;
      while (attempts < 3) {
        operation.check();
        attempts++;
        try {
          final timing = Stopwatch()..start();
          late Map<String, dynamic> data;
          try {
            data = await extractor.extract(
              YouTubeExtraction(track.id, client, cookie: cookie),
              operation,
            );
          } finally {
            extractorMs += timing.elapsedMilliseconds;
          }
          operation.check();
          final media = await prepare(data, operation);
          operation.check();
          report('range_verified', media);
          return media;
        } on ProviderFailure catch (error) {
          operation.check();
          final failure = _classify(error);
          if (bestFailure == null ||
              _priority(failure) > _priority(bestFailure)) {
            bestFailure = failure;
          }
          if (_terminal(failure) ||
              [
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
            refreshedToken = true;
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
      report(result.problem.name);
      throw result;
    } finally {
      timer.cancel();
      detach();
      operation.cancel();
    }
  }
}
