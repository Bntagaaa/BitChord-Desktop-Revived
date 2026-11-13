import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../domain/catalog.dart';
import 'provider_http.dart';
import 'range_source.dart';

// Optional, explicitly configured extractor, never a second audio engine.
// No shell, user configuration, plugins, remote components, account cookies,
// auto-update, download or persistent extractor cache are enabled.
final class YouTubeResolver {
  YouTubeResolver(
    this.executable, {
    this.node,
    this.poTokenPluginDirectory,
    this.poTokenServerHome,
    this.bundled = false,
    this.timeout = const Duration(seconds: 45),
  });
  final String executable;
  final String? node;
  // Explicitly trusted local yt-dlp plugin code. Default discovery stays off.
  final String? poTokenPluginDirectory;
  final String? poTokenServerHome;
  final bool bundled;
  final Duration timeout;

  // GitHub's artifact ZIP can discard POSIX executable bits. Repair only
  // BitChord-owned bundled executables, never arbitrary user-configured paths.
  Future<void> prepareBundledRuntime() async {
    if (!bundled || Platform.isWindows) return;
    for (final path in [executable, if (node != null) node!]) {
      final binary = File(path);
      final stat = await binary.stat();
      if (stat.type != FileSystemEntityType.file) {
        throw const ProviderFailure(ProviderProblem.unsupported);
      }
      if ((stat.mode & 0x49) != 0) continue;
      final chmod = await Process.run('/usr/bin/chmod', ['u+x', '--', path]);
      if (chmod.exitCode != 0) {
        throw const ProviderFailure(ProviderProblem.unsupported);
      }
    }
  }

  // Portable releases ship a pinned, self-contained resolver next to the
  // executable. This does not use the caller's PATH or silently install tools.
  static YouTubeResolver? fromBundleDirectory(Directory executableDirectory) {
    final separator = Platform.pathSeparator;
    final root = '${executableDirectory.path}${separator}youtube_runtime';
    final extractor = File(
      '$root${separator}${Platform.isWindows ? 'yt-dlp.exe' : 'yt-dlp'}',
    );
    final node = File(
      '$root${separator}${Platform.isWindows ? 'node.exe' : 'node'}',
    );
    final plugins = Directory('$root${separator}plugins');
    final server = Directory('$root${separator}server');
    final script = File(
      '${server.path}${separator}build${separator}generate_once.js',
    );
    final package = File(
      '${server.path}${separator}node_modules${separator}bgutils-js'
      '${separator}package.json',
    );
    if (!extractor.existsSync() ||
        !node.existsSync() ||
        !plugins.existsSync() ||
        !script.existsSync() ||
        !package.existsSync()) {
      return null;
    }
    return YouTubeResolver(
      extractor.absolute.path,
      node: node.absolute.path,
      poTokenPluginDirectory: plugins.absolute.path,
      poTokenServerHome: server.absolute.path,
      bundled: true,
      timeout: const Duration(seconds: 120),
    );
  }

  static YouTubeResolver? fromEnvironment() {
    final path = Platform.environment['BITCHORD_YTDLP'];
    if (path == null) {
      return fromBundleDirectory(File(Platform.resolvedExecutable).parent);
    }
    if (!File(path).isAbsolute) return null;
    final node = Platform.environment['BITCHORD_YTDLP_NODE'];
    final plugin = Platform.environment['BITCHORD_YTDLP_POT_PLUGIN_DIR'];
    return YouTubeResolver(
      path,
      node: node != null && File(node).isAbsolute ? node : null,
      poTokenPluginDirectory: plugin != null && plugin.isNotEmpty
          ? plugin
          : null,
      timeout: plugin != null && plugin.isNotEmpty
          ? const Duration(seconds: 90)
          : const Duration(seconds: 45),
    );
  }

  // Dedicated per-video PO Token providers are opt-in because yt-dlp plugins
  // execute external code. Without an explicit directory, disable every plugin
  // search location and never load ambient config or account cookies.
  List<String> arguments(
    CatalogEntry track,
    AudioQuality quality, {
    bool alternateClient = false,
  }) {
    final plugin = poTokenPluginDirectory;
    if (plugin != null &&
        (!Directory(plugin).isAbsolute || !Directory(plugin).existsSync())) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    final serverHome = poTokenServerHome;
    if (serverHome != null &&
        (!Directory(serverHome).isAbsolute ||
            !File(
              '$serverHome${Platform.pathSeparator}build'
              '${Platform.pathSeparator}generate_once.js',
            ).existsSync())) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    final ceiling = switch (quality) {
      AudioQuality.low => 64,
      AudioQuality.medium => 128,
      _ => 1000,
    };
    return [
      '--ignore-config',
      '--no-plugin-dirs',
      if (plugin != null) ...['--plugin-dirs', plugin],
      if (plugin != null || alternateClient) ...[
        '--extractor-args',
        'youtube:player_client='
            '${alternateClient ? 'android_vr,web_embedded' : 'mweb'}'
            '${serverHome == null ? '' : ';youtubepot-bgutilscript:server_home=$serverHome'}',
      ],
      '--no-remote-components',
      '--no-cache-dir',
      '--no-playlist',
      '--skip-download',
      '--dump-single-json',
      '--no-warnings',
      '--socket-timeout',
      '10',
      '--retries',
      '0',
      '--extractor-retries',
      '0',
      '--no-js-runtimes',
      if (node != null) ...['--js-runtimes', 'node:$node'],
      '-f',
      // Missing ABR metadata must not hide a playable direct HTTPS stream.
      'bestaudio[protocol=https][abr<=$ceiling]/bestaudio[protocol=https]',
      '--',
      'https://www.youtube.com/watch?v=${track.id}',
    ];
  }

  // Keep extractor diagnostics private. Only report a fixed, redacted failure
  // category: yt-dlp debug output can contain signed locations or tokens.
  static ProviderFailure classifyExtractorFailure(String stderr) {
    final value = stderr.toLowerCase();
    if (RegExp(
      r'sign in to confirm|sign in to verify|login_required|login required|'
      r'cookies are needed|please log in|requires authentication|'
      r'confirm you are not a bot',
    ).hasMatch(value)) {
      return const ProviderFailure(ProviderProblem.loginRequired);
    }
    if (RegExp(
      r'failed to generate[^\n]*po token|'
      r'po token[^\n]*(?:generation|generator)[^\n]*(?:error|fail)|'
      r'(?:error|failed)[^\n]*bgutil',
    ).hasMatch(value)) {
      return const ProviderFailure(ProviderProblem.tokenUnavailable);
    }
    if (value.contains('requested format is not available') ||
        value.contains('no video formats found') ||
        value.contains('only images are available')) {
      return const ProviderFailure(ProviderProblem.formatUnavailable);
    }
    if (RegExp(r'http error 403|403 forbidden|status code 403').hasMatch(value)) {
      return const ProviderFailure(ProviderProblem.mediaRefused, 403);
    }
    // Upstream extraction is not the same as the catalog provider being down.
    return const ProviderFailure(ProviderProblem.youtubeUnavailable);
  }

  Future<ResolvedMedia> resolve(
    CatalogEntry track,
    AudioQuality quality,
    RequestScope scope,
  ) async {
    scope.check();
    if (!RegExp(r'^[a-zA-Z0-9_-]{11}$').hasMatch(track.id) ||
        track.kind != CatalogKind.track) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    ProviderFailure? firstFailure;
    for (final alternateClient in [false, true]) {
      try {
        return await _resolveOnce(
          track,
          quality,
          scope,
          alternateClient: alternateClient,
        );
      } on ProviderFailure catch (failure) {
        scope.check();
        if (!alternateClient &&
            [
              ProviderProblem.youtubeUnavailable,
              ProviderProblem.formatUnavailable,
              ProviderProblem.loginRequired,
              ProviderProblem.tokenUnavailable,
              ProviderProblem.mediaRefused,
            ].contains(failure.problem)) {
          firstFailure = failure;
          continue;
        }
        // Keep an actionable primary error if the alternate client only
        // reports an unspecified extraction failure or missing format.
        if (firstFailure != null &&
            [
              ProviderProblem.loginRequired,
              ProviderProblem.mediaRefused,
              ProviderProblem.tokenUnavailable,
            ].contains(firstFailure.problem) &&
            [
              ProviderProblem.youtubeUnavailable,
              ProviderProblem.formatUnavailable,
            ].contains(failure.problem)) {
          throw firstFailure;
        }
        rethrow;
      }
    }
    throw firstFailure ??
        const ProviderFailure(ProviderProblem.formatUnavailable);
  }

  Future<ResolvedMedia> _resolveOnce(
    CatalogEntry track,
    AudioQuality quality,
    RequestScope scope, {
    required bool alternateClient,
  }) async {
    final processArguments = arguments(
      track,
      quality,
      alternateClient: alternateClient,
    );
    Process? process;
    final output = StringBuffer();
    var stderrTail = '';
    var bytes = 0;
    var oversized = false;
    void Function() detach = () {};
    try {
      await prepareBundledRuntime();
      process = await Process.start(
        executable,
        processArguments,
        runInShell: false,
        includeParentEnvironment: false,
        environment: {
          for (final key in [
            'PATH',
            'SystemRoot',
            'WINDIR',
            'TEMP',
            'TMP',
            'TMPDIR',
            if (poTokenPluginDirectory != null) ...[
              'HOME',
              'USERPROFILE',
              'APPDATA',
              'LOCALAPPDATA',
              'DISPLAY',
              'WAYLAND_DISPLAY',
              'XDG_RUNTIME_DIR',
              'XAUTHORITY',
              'DBUS_SESSION_BUS_ADDRESS',
              'XDG_CACHE_HOME',
            ],
          ])
            if (Platform.environment[key] != null)
              key: Platform.environment[key]!,
        },
      );
      final running = process;
      detach = scope.onCancel(() => running.kill(ProcessSignal.sigkill));
      // Drain both pipes concurrently; never forward URL/cookie-bearing output.
      final stdoutDone = running.stdout.listen((part) {
        bytes += part.length;
        if (bytes > 4 * 1024 * 1024) {
          oversized = true;
          running.kill(ProcessSignal.sigkill);
        } else {
          output.write(utf8.decode(part, allowMalformed: true));
        }
      }).asFuture<void>();
      final stderrDone = running.stderr.listen((part) {
        // Bounded, in-memory diagnostic context. Never forward any of it to
        // UI, console, preferences, persistence, fixtures or app telemetry.
        stderrTail += utf8.decode(part, allowMalformed: true);
        if (stderrTail.length > 96000) {
          stderrTail = stderrTail.substring(stderrTail.length - 96000);
        }
      }).asFuture<void>();
      final exit = await (() async {
        final code = await running.exitCode;
        await Future.wait([stdoutDone, stderrDone]);
        return code;
      })().timeout(timeout);
      scope.check();
      if (oversized) throw const ProviderFailure(ProviderProblem.malformed);
      if (exit != 0) throw classifyExtractorFailure(stderrTail);
      final decoded = jsonDecode(output.toString());
      if (decoded is! Map<String, dynamic>) throw const FormatException();
      // Validate real byte ranges, including beyond the first MiB, before
      // adopting one of a bounded set of trusted audio-only CDN URLs.
      return await firstPlayable(
        candidates(decoded, track, quality),
        (media) async {
          final source = RangeSource(media, const ProviderHttp(), scope);
          await source.chunk(0);
          if (source.total! > 1024 * 1024) await source.chunk(2);
          scope.check();
        },
      );
    } on ProviderFailure catch (e) {
      // A signed URL does not establish usable media. In particular, GVS may
      // reject clients without a fresh per-video PO Token even after extraction.
      if (e.status == 403) {
        throw const ProviderFailure(ProviderProblem.mediaRefused, 403);
      }
      rethrow;
    } on TimeoutException {
      throw const ProviderFailure(ProviderProblem.timeout);
    } on ProcessException {
      throw const ProviderFailure(ProviderProblem.unsupported);
    } catch (_) {
      scope.check();
      throw const ProviderFailure(ProviderProblem.malformed);
    } finally {
      detach();
      if (process != null) {
        process.kill(ProcessSignal.sigkill);
        await process.exitCode;
      }
    }
  }

  // A direct HTTPS audio-only adaptive URL supports HTTP ranges, unlike an
  // MPD/HLS manifest. Reuse parse() to enforce codec, CDN and header isolation.
  static List<ResolvedMedia> candidates(
    Map<String, dynamic> data,
    CatalogEntry track,
    AudioQuality quality,
  ) {
    if (data['id'] != track.id || data['is_live'] == true) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    final found = <({ResolvedMedia media, double? bitrate, int order})>[];
    final seen = <String>{};
    void add(Map<String, dynamic> format) {
      final candidate = <String, dynamic>{
        ...data,
        ...format,
        'id': data['id'],
        'is_live': data['is_live'],
        'http_headers': format['http_headers'] ?? data['http_headers'],
      };
      try {
        final media = parse(candidate, track);
        if (seen.add(media.url.toString())) {
          found.add((
            media: media,
            bitrate: (format['abr'] as num? ?? format['tbr'] as num?)
                ?.toDouble(),
            order: found.length,
          ));
        }
      } on ProviderFailure {
        // Video-only, manifest, malformed and non-CDN entries are ignored.
      }
    }

    add(data);
    final formats = data['formats'];
    if (formats is List) {
      for (final item in formats.take(512)) {
        if (item is Map<String, dynamic>) add(item);
      }
    }
    if (found.isEmpty) {
      throw const ProviderFailure(ProviderProblem.formatUnavailable);
    }
    final ceiling = switch (quality) {
      AudioQuality.low => 64.0,
      AudioQuality.medium => 128.0,
      _ => 1000.0,
    };
    int rank(double? bitrate) {
      if (bitrate == null) return 1;
      return bitrate <= ceiling ? 0 : 2;
    }

    found.sort((a, b) {
      final left = rank(a.bitrate), right = rank(b.bitrate);
      if (left != right) return left.compareTo(right);
      if (a.bitrate != null && b.bitrate != null) {
        // Prefer the best format below the target, else the smallest above.
        final comparison = left == 2
            ? a.bitrate!.compareTo(b.bitrate!)
            : b.bitrate!.compareTo(a.bitrate!);
        if (comparison != 0) return comparison;
      }
      return a.order.compareTo(b.order);
    });
    // Bound retries; do not repeatedly probe hundreds of candidate URLs.
    return found.take(6).map((entry) => entry.media).toList();
  }

  // Inject the probe for controlled tests; production checks real HTTP ranges.
  static Future<ResolvedMedia> firstPlayable(
    List<ResolvedMedia> options,
    Future<void> Function(ResolvedMedia) probe,
  ) async {
    ProviderFailure? firstFailure;
    for (final candidate in options) {
      try {
        await probe(candidate);
        return candidate;
      } on ProviderFailure catch (failure) {
        if (failure.problem == ProviderProblem.cancelled) rethrow;
        final classified = failure.status == 403
            ? const ProviderFailure(ProviderProblem.mediaRefused, 403)
            : failure;
        if (firstFailure == null ||
            (classified.problem == ProviderProblem.mediaRefused &&
                firstFailure.problem != ProviderProblem.mediaRefused)) {
          firstFailure = classified;
        }
      }
    }
    throw firstFailure ??
        const ProviderFailure(ProviderProblem.formatUnavailable);
  }

  static ResolvedMedia parse(Map<String, dynamic> data, CatalogEntry track) {
    final url = Uri.tryParse('${data['url'] ?? ''}');
    if (data['id'] != track.id ||
        data['vcodec'] != 'none' ||
        data['acodec'] == null ||
        data['acodec'] == 'none' ||
        data['protocol'] != 'https' ||
        data['is_live'] == true ||
        url == null ||
        url.scheme != 'https' ||
        !(url.host == 'googlevideo.com' ||
            url.host.endsWith('.googlevideo.com')) ||
        url.userInfo.isNotEmpty ||
        data['format_id'] is! String) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    // Extractor media headers only; never move an authenticated shell's cookie
    // or Authorization value to the CDN.
    final headers = <String, String>{};
    for (final pair in (data['http_headers'] as Map? ?? {}).entries) {
      if ([
            'user-agent',
            'accept',
            'accept-language',
            'origin',
            'referer',
          ].contains('${pair.key}'.toLowerCase()) &&
          pair.value is String &&
          !RegExp(r'[\r\n]').hasMatch(pair.value as String)) {
        headers['${pair.key}'] = pair.value as String;
      }
    }
    final expires = int.tryParse(url.queryParameters['expire'] ?? '');
    final media = ResolvedMedia(
      provider: 'youtube',
      trackId: track.id,
      rendition: 'youtube:${data['format_id']}',
      url: url,
      headers: headers,
      expiresAt: expires == null
          ? null
          : DateTime.fromMillisecondsSinceEpoch(expires * 1000),
    );
    media.check();
    return media;
  }
}
