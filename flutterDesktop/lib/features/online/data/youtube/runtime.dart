import 'dart:io';

import '../../domain/catalog.dart';

// Immutable deployment settings. No PATH-based extractor discovery, user config,
// background token server, automatic download, or credentials in arguments.
final class YouTubeRuntime {
  const YouTubeRuntime(
    this.executable, {
    this.node,
    this.pluginDirectory,
    this.serverHome,
    this.bundled = false,
  });
  final String executable;
  final String? node, pluginDirectory, serverHome;
  final bool bundled;

  static YouTubeRuntime? fromBundleDirectory(Directory directory) {
    final root = '${directory.path}${Platform.pathSeparator}youtube_runtime';
    final executable = File(
      '$root/${Platform.isWindows ? 'yt-dlp.exe' : 'yt-dlp'}',
    );
    final node = File('$root/${Platform.isWindows ? 'node.exe' : 'node'}');
    final plugins = Directory('$root/plugins');
    final server = Directory('$root/server');
    if (!executable.existsSync() ||
        !node.existsSync() ||
        !plugins.existsSync() ||
        !File('${server.path}/build/generate_once.js').existsSync() ||
        !File(
          '${server.path}/node_modules/bgutils-js/package.json',
        ).existsSync()) {
      return null;
    }
    return YouTubeRuntime(
      executable.absolute.path,
      node: node.absolute.path,
      pluginDirectory: plugins.absolute.path,
      serverHome: server.absolute.path,
      bundled: true,
    );
  }

  static YouTubeRuntime? fromEnvironment() {
    final env = Platform.environment;
    final path = env['BITCHORD_YTDLP'];
    if (path == null) {
      return fromBundleDirectory(File(Platform.resolvedExecutable).parent);
    }
    if (path.isEmpty || !File(path).isAbsolute) return null;
    return YouTubeRuntime(
      path,
      node: env['BITCHORD_YTDLP_NODE'],
      pluginDirectory: env['BITCHORD_YTDLP_POT_PLUGIN_DIR'],
      serverHome: env['BITCHORD_YTDLP_POT_SERVER_HOME'],
    );
  }

  bool get supportsSessionPipe =>
      pluginDirectory != null &&
      File('$pluginDirectory/bitchord-session.zip').existsSync();

  void validatePaths() {
    for (final path in [executable, node, pluginDirectory, serverHome]) {
      if (path != null &&
          (!File(path).isAbsolute || RegExp(r'[;\r\n\x00]').hasMatch(path))) {
        throw const ProviderFailure(ProviderProblem.unsupported);
      }
    }
    if (pluginDirectory != null && !Directory(pluginDirectory!).existsSync()) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    if (serverHome != null &&
        (pluginDirectory == null ||
            node == null ||
            !File('$serverHome/build/generate_once.js').existsSync())) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
  }

  static final _preparedPaths = <String>{};

  Future<void> prepare() async {
    validatePaths();
    if (!bundled || Platform.isWindows) return;
    if (_preparedPaths.contains(executable)) return;
    for (final path in [executable, if (node != null) node!]) {
      final stat = await File(path).stat();
      if (stat.type != FileSystemEntityType.file) {
        throw const ProviderFailure(ProviderProblem.unsupported);
      }
      // GitHub ZIP extraction may discard executable bits. Only our bundled
      // files are repaired; never chmod a user-configured external executable.
      if ((stat.mode & 0x40) == 0) {
        final result = await Process.run('/usr/bin/chmod', ['u+x', '--', path]);
        if (result.exitCode != 0) {
          throw const ProviderFailure(ProviderProblem.unsupported);
        }
      }
    }
    _preparedPaths.add(executable);
  }

  List<String> arguments(String videoId, String client) {
    validatePaths();
    if (!RegExp(r'^[a-zA-Z0-9_-]{11}$').hasMatch(videoId) ||
        !['mweb', 'android_vr', 'web_music'].contains(client)) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    return [
      '--ignore-config',
      '--no-plugin-dirs',
      if (pluginDirectory != null) ...['--plugin-dirs', pluginDirectory!],
      '--no-remote-components',
      '--no-cache-dir',
      '--no-playlist',
      '--skip-download',
      '--dump-single-json',
      '--no-progress',
      '--socket-timeout', '10',
      '--retries', '0',
      '--extractor-retries', '0',
      '--no-js-runtimes',
      if (node != null) ...['--js-runtimes', 'node:$node'],
      '--extractor-args', 'youtube:player_client=$client',
      // Different extractor namespaces MUST be separate CLI arguments. A
      // semicolon appends a YouTube option; it does not configure BgUtils.
      if (serverHome != null) ...[
        '--extractor-args',
        'youtubepot-bgutilscript:server_home=$serverHome',
      ],
      // Quality is selected from independently validated formats in Dart.
      // An absent ABR field must not exclude an otherwise valid audio stream.
      '-f', 'bestaudio[protocol=https]',
      '--', 'https://www.youtube.com/watch?v=$videoId',
    ];
  }

  @override
  String toString() => 'YouTubeRuntime(redacted)';
}
