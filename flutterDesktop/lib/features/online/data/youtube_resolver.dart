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
  List<String> arguments(CatalogEntry track, AudioQuality quality) {
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
      if (plugin != null) ...[
        '--plugin-dirs',
        plugin,
        '--extractor-args',
        'youtube:player_client=mweb'
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
      'bestaudio[protocol=https][abr<=$ceiling]',
      '--',
      'https://www.youtube.com/watch?v=${track.id}',
    ];
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
    final processArguments = arguments(track, quality);
    Process? process;
    final output = StringBuffer();
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
      final stderrDone = running.stderr.listen((_) {}).asFuture<void>();
      final exit = await (() async {
        final code = await running.exitCode;
        await Future.wait([stdoutDone, stderrDone]);
        return code;
      })().timeout(timeout);
      scope.check();
      if (oversized) throw const ProviderFailure(ProviderProblem.malformed);
      if (exit != 0) throw const ProviderFailure(ProviderProblem.unavailable);
      final decoded = jsonDecode(output.toString());
      if (decoded is! Map<String, dynamic>) throw const FormatException();
      final media = parse(decoded, track);
      // Kotlin StreamResolver.probe: actually read a production-sized span,
      // including past 1 MiB when possible. A URL/exit=0 is not success.
      final source = RangeSource(media, const ProviderHttp(), scope);
      await source.chunk(0);
      if (source.total! > 1024 * 1024) await source.chunk(2);
      scope.check();
      return media;
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
