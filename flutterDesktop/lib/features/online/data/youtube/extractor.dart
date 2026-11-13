import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import '../../domain/catalog.dart';
import 'runtime.dart';

final class YouTubeExtraction {
  const YouTubeExtraction(
    this.videoId,
    this.client, {
    this.cookie,
    this.visitorData,
  });
  final String videoId, client;
  // Provided only by a server-verified, user-entered in-memory session.
  final String? cookie;
  final String? visitorData;
  @override
  String toString() => 'YouTubeExtraction(redacted)';
}

abstract interface class YouTubeExtractor {
  Future<Map<String, dynamic>> extract(
    YouTubeExtraction request,
    RequestScope scope,
  );
}

// The extractor performs metadata/signature work only. C++ remains the sole
// audio engine. One instance serializes subprocesses; queued requests are
// cancellation-fenced, so rapid track changes cannot spawn unbounded children.
final class YouTubeProcessExtractor implements YouTubeExtractor {
  YouTubeProcessExtractor(this.runtime);
  final YouTubeRuntime runtime;
  Future<void> _tail = Future.value();

  static ProviderFailure failure(String diagnostic) {
    final text = diagnostic.toLowerCase();
    if (RegExp(
      r'private video|video unavailable|has been removed|not available in your country|members.only|copyright',
    ).hasMatch(text)) {
      return const ProviderFailure(ProviderProblem.youtubeContentUnavailable);
    }
    if (RegExp(r'http error 429|too many requests|rate.limit').hasMatch(text)) {
      return const ProviderFailure(ProviderProblem.youtubeRateLimited, 429);
    }
    if (RegExp(
      r'sign in to confirm|sign in to verify|login_required|login required|cookies are needed|please log in|requires authentication|confirm you are not a bot',
    ).hasMatch(text)) {
      return const ProviderFailure(ProviderProblem.loginRequired);
    }
    if (RegExp(
      r'failed to generate[^\n]*po token|po token[^\n]*(?:generation|generator)[^\n]*(?:error|fail)|(?:error|failed)[^\n]*bgutil',
    ).hasMatch(text)) {
      return const ProviderFailure(ProviderProblem.tokenUnavailable);
    }
    if (RegExp(
      r'http error 403|403 forbidden|status code 403',
    ).hasMatch(text)) {
      return const ProviderFailure(ProviderProblem.mediaRefused, 403);
    }
    if (RegExp(
      r'requested format is not available|no video formats found|only images are available',
    ).hasMatch(text)) {
      return const ProviderFailure(ProviderProblem.formatUnavailable);
    }
    if (RegExp(r'timed out|timeout').hasMatch(text)) {
      return const ProviderFailure(ProviderProblem.timeout);
    }
    if (RegExp(
      r'network is unreachable|name resolution|unable to connect|connection reset|connection refused',
    ).hasMatch(text)) {
      return const ProviderFailure(ProviderProblem.network);
    }
    return const ProviderFailure(ProviderProblem.youtubeUnavailable);
  }

  @override
  Future<Map<String, dynamic>> extract(
    YouTubeExtraction request,
    RequestScope scope,
  ) {
    final result = _tail.then((_) {
      scope.check();
      return _run(request, scope);
    });
    _tail = result.then<void>((_) {}, onError: (Object _, StackTrace __) {});
    // Wake a cancelled waiter immediately; its queued action still checks the
    // scope before starting, and _tail retains ordering until it is skipped.
    final cancelled = Completer<Map<String, dynamic>>();
    final detach = scope.onCancel(() {
      if (!cancelled.isCompleted) {
        cancelled.completeError(
          const ProviderFailure(ProviderProblem.cancelled),
        );
      }
    });
    return Future.any([result, cancelled.future]).whenComplete(detach);
  }

  Future<Map<String, dynamic>> _run(
    YouTubeExtraction request,
    RequestScope scope,
  ) async {
    await runtime.prepare();
    scope.check();
    if (request.cookie != null && !runtime.supportsSessionPipe) {
      throw const ProviderFailure(ProviderProblem.youtubeRuntimeUnavailable);
    }
    // BgUtils has its own cache, independent of yt-dlp's --no-cache-dir.
    // Each extraction gets a new owned cache, never the user's HOME. A 403
    // retry therefore cannot accidentally reuse the same stale token session.
    final scratch = await Directory.systemTemp.createTemp('bitchord-youtube-');
    Process? process;
    StreamSubscription<List<int>>? stdoutSubscription, stderrSubscription;
    final cancelled = Completer<void>();
    var exited = false;
    Future<void>? stopping;
    void Function() detach = () {};
    var group = false;
    Future<void> stop() => stopping ??= (() async {
      final running = process;
      if (running == null) return;
      if (exited && !group) return;
      if (Platform.isWindows) {
        final root = Platform.environment['SystemRoot'];
        if (root != null) {
          try {
            await Process.run('$root/System32/taskkill.exe', [
              '/PID',
              '${running.pid}',
              '/T',
              '/F',
            ]).timeout(const Duration(seconds: 3));
          } catch (_) {}
        }
      } else if (group) {
        try {
          Process.killPid(-running.pid, ProcessSignal.sigkill);
        } catch (_) {}
      }
      if (!exited) running.kill(ProcessSignal.sigkill);
    })();
    try {
      final args = runtime.arguments(request.videoId, request.client);
      final setsid = File('/usr/bin/setsid');
      group = Platform.isLinux && setsid.existsSync();
      process = await Process.start(
        group ? setsid.path : runtime.executable,
        group ? [runtime.executable, ...args] : args,
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
          ])
            if (Platform.environment[key] != null)
              key: Platform.environment[key]!,
          'HOME': scratch.path,
          'USERPROFILE': scratch.path,
          'XDG_CACHE_HOME': scratch.path,
          'APPDATA': scratch.path,
          'LOCALAPPDATA': scratch.path,
          if (request.cookie != null) 'BITCHORD_YT_SESSION_PIPE': '1',
        },
      );
      final running = process;
      final exit = running.exitCode.then((code) {
        exited = true;
        return code;
      });
      detach = scope.onCancel(() {
        if (!cancelled.isCompleted) cancelled.complete();
        unawaited(stop());
      });
      final output = BytesBuilder(copy: false);
      var oversized = false;
      var stderr = <int>[];
      stdoutSubscription = running.stdout.listen((data) {
        if (output.length + data.length > 8 * 1024 * 1024) {
          oversized = true;
          unawaited(stop());
        } else if (!oversized) {
          output.add(data);
        }
      });
      final stdoutDone = stdoutSubscription.asFuture<void>();
      stderrSubscription = running.stderr.listen((data) {
        stderr.addAll(data);
        if (stderr.length > 96000)
          stderr = stderr.sublist(stderr.length - 96000);
      });
      final stderrDone = stderrSubscription.asFuture<void>();
      if (request.cookie != null) {
        running.stdin.add(
          utf8.encode('${jsonEncode({'cookie': request.cookie})}\n'),
        );
      }
      final completed = (() async {
        await running.stdin.close();
        final code = await exit;
        await Future.wait([stdoutDone, stderrDone]);
        return code;
      })();
      final code = await Future.any([
        completed,
        cancelled.future.then<int>(
          (_) => throw const ProviderFailure(ProviderProblem.cancelled),
        ),
      ]);
      scope.check();
      if (oversized) throw const ProviderFailure(ProviderProblem.malformed);
      if (code != 0) throw failure(utf8.decode(stderr, allowMalformed: true));
      // Decode once, after collecting complete bytes: UTF-8 characters may
      // straddle pipe chunks. Per-chunk utf8.decode corrupts music metadata.
      final value = jsonDecode(utf8.decode(output.takeBytes()));
      if (value is! Map<String, dynamic>) {
        throw const ProviderFailure(ProviderProblem.malformed);
      }
      return value;
    } on ProviderFailure {
      rethrow;
    } on ProcessException {
      throw const ProviderFailure(ProviderProblem.youtubeRuntimeUnavailable);
    } catch (_) {
      scope.check();
      throw const ProviderFailure(ProviderProblem.malformed);
    } finally {
      detach();
      await stop();
      await stdoutSubscription?.cancel();
      await stderrSubscription?.cancel();
      if (process != null) {
        try {
          await process.exitCode.timeout(const Duration(seconds: 3));
        } catch (_) {}
      }
      // The directory is newly created and owned by this call; no user files
      // or system cache paths are ever removed.
      try {
        await scratch.delete(recursive: true);
      } catch (_) {}
    }
  }
}
