import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:path/path.dart' as p;

import '../domain/models.dart';
import '../playback/stream_resolver.dart';

enum DownloadStatus {
  queued,
  resolving,
  downloading,
  complete,
  cancelled,
  failed,
}

class DownloadTask {
  DownloadTask(this.song);
  final Song song;
  DownloadStatus status = DownloadStatus.queued;
  int received = 0;
  int? total;
  String? error;
  bool _cancelled = false;
  http.Client? _client;
  final Completer<Song> _result = Completer<Song>();
  double? get progress =>
      total == null || total == 0 ? null : (received / total!).clamp(0, 1);
}

/// Bounded downloads with resumable .part files, range validation, and atomic publication.
class DownloadService extends ChangeNotifier {
  DownloadService({
    required this.resolver,
    required this.directory,
    this.maxConcurrent = 2,
    http.Client Function()? clientFactory,
  }) : _clientFactory = clientFactory ?? (() => http.Client());
  final StreamResolver resolver;
  final Directory directory;
  final int maxConcurrent;
  final http.Client Function() _clientFactory;
  final Map<String, DownloadTask> _tasks = {};
  final List<DownloadTask> _pending = [];
  int _running = 0;
  bool _closed = false;
  List<DownloadTask> get tasks => List.unmodifiable(_tasks.values);

  Future<Song> download(Song song) {
    if (_closed) return Future.error(StateError('Download manager is closed'));
    if (song.isLocal) return Future.value(song);
    final existing = _tasks[song.videoId];
    if (existing != null && !existing._result.isCompleted) {
      return existing._result.future;
    }
    final task = DownloadTask(song);
    _tasks[song.videoId] = task;
    _pending.add(task);
    notifyListeners();
    _pump();
    return task._result.future;
  }

  void cancel(String videoId) {
    final task = _tasks[videoId];
    if (task == null || task._result.isCompleted) return;
    task._cancelled = true;
    task._client?.close();
    if (_pending.remove(task)) _finishCancelled(task);
    notifyListeners();
  }

  void _pump() {
    while (!_closed && _running < maxConcurrent && _pending.isNotEmpty) {
      final task = _pending.removeAt(0);
      _running++;
      unawaited(
        _run(task).whenComplete(() {
          _running--;
          _pump();
        }),
      );
    }
  }

  Future<void> _run(DownloadTask task) async {
    try {
      task.status = DownloadStatus.resolving;
      _notify();
      final stream = await resolver.resolve(task.song);
      _validateStream(stream);
      _checkCancelled(task);
      final extension = _extension(stream);
      await directory.create(recursive: true);
      final id = sha256
          .convert(utf8.encode(task.song.videoId))
          .toString()
          .substring(0, 10);
      final safe = safeDownloadName('${task.song.artist} - ${task.song.title}');
      final target = File(p.join(directory.path, '$safe [$id].$extension'));
      final part = File('${target.path}.part');
      final resumeFile = File('${part.path}.json');
      if (await target.exists() && await target.length() > 0) {
        await _validateAudio(target, extension);
        _finish(
          task,
          task.song.copyWith(
            localPath: target.path,
            localUri: target.uri.toString(),
            downloadFormat: extension.toUpperCase(),
          ),
        );
        return;
      }
      task.status = DownloadStatus.downloading;
      var offset = await part.exists() ? await part.length() : 0;
      var fingerprint = _streamFingerprint(stream);
      String? validator;
      if (offset > 0) {
        Map<String, dynamic>? resume;
        try {
          resume = jsonDecode(
            await resumeFile.readAsString(),
          ) as Map<String, dynamic>;
        } on Object {
          /* A partial file without transport identity is restarted. */
        }
        if (resume?['fingerprint'] != fingerprint) {
          await part.writeAsBytes(const []);
          offset = 0;
        } else {
          validator = resume?['validator'] as String?;
        }
      }
      var knownTotal = stream.contentLength;
      final client = _clientFactory();
      task._client = client;
      var refreshed = false;
      var activeStream = stream;
      try {
        while (true) {
          _checkCancelled(task);
          final request = http.Request('GET', Uri.parse(activeStream.uri));
          request.headers.addAll(activeStream.headers);
          final windowed = activeStream.windowedReads;
          if (offset > 0 || windowed) {
            final end = windowed
                ? math.min(
                    offset + 2 * 1024 * 1024 - 1,
                    (knownTotal ?? (1 << 62)) - 1,
                  )
                : null;
            request.headers['Range'] = 'bytes=$offset-${end ?? ''}';
            if (offset > 0 && validator != null) {
              request.headers['If-Range'] = validator;
            }
          }
          final response = await client
              .send(request)
              .timeout(const Duration(seconds: 25));
          if ((response.statusCode == 401 || response.statusCode == 403) &&
              !refreshed) {
            await response.stream.drain<void>();
            activeStream = await resolver.resolve(task.song, refresh: true);
            _validateStream(activeStream);
            if (_extension(activeStream) != extension) {
              throw const ServiceException(
                'The source changed audio format. Retry the download to use the new format.',
              );
            }
            final refreshedFingerprint = _streamFingerprint(activeStream);
            if (refreshedFingerprint != fingerprint) {
              // A refreshed source may return a different recording/encoding.
              // Its bytes must never be appended to the previous transport.
              if (await part.exists()) await part.writeAsBytes(const []);
              offset = 0;
              validator = null;
              knownTotal = activeStream.contentLength;
              fingerprint = refreshedFingerprint;
            }
            refreshed = true;
            continue;
          }
          if (response.statusCode == 416 &&
              knownTotal != null &&
              offset == knownTotal) {
            await response.stream.drain<void>();
            break;
          }
          if (response.statusCode != 200 && response.statusCode != 206) {
            await response.stream.drain<void>();
            throw ServiceException(
              'Download failed (HTTP ${response.statusCode}).',
              statusCode: response.statusCode,
            );
          }
          final type = response.headers['content-type']?.toLowerCase() ?? '';
          if (type.contains('text/') ||
              type.contains('json') ||
              type.contains('xml') ||
              type.contains('mpegurl')) {
            await response.stream.drain<void>();
            throw const ServiceException(
              'The server returned a document instead of audio.',
            );
          }
          final append = response.statusCode == 206;
          if (append) {
            final range = RegExp(r'^bytes (\d+)-(\d+)/(\d+|\*)$')
                .firstMatch(response.headers['content-range'] ?? '');
            if (range == null || int.parse(range.group(1)!) != offset) {
              await response.stream.drain<void>();
              throw const ServiceException(
                'The download server returned an inconsistent byte range.',
              );
            }
            knownTotal = int.tryParse(range.group(3)!) ?? knownTotal;
          } else {
            // A server ignoring Range starts from zero; never append its entire file.
            offset = 0;
            knownTotal = response.contentLength;
          }
          task.total = knownTotal;
          task.received = offset;
          final etag = response.headers['etag'];
          validator = etag != null && !etag.startsWith('W/')
              ? etag
              : response.headers['last-modified'];
          await resumeFile.writeAsString(
            jsonEncode({'fingerprint': fingerprint, 'validator': validator}),
            flush: true,
          );
          final sink = part.openWrite(
            mode: append ? FileMode.append : FileMode.write,
          );
          var receivedThisRequest = 0;
          final clock = Stopwatch()..start();
          try {
            await for (final chunk in response.stream.timeout(
              const Duration(seconds: 30),
            )) {
              _checkCancelled(task);
              sink.add(chunk);
              receivedThisRequest += chunk.length;
              task.received += chunk.length;
              if (clock.elapsedMilliseconds >= 150) {
                _notify();
                clock.reset();
              }
            }
            await sink.flush();
          } finally {
            await sink.close();
          }
          if (response.contentLength != null &&
              receivedThisRequest != response.contentLength) {
            throw const ServiceException(
              'Download ended before the expected audio bytes arrived. Retry to resume.',
            );
          }
          offset = task.received;
          if (receivedThisRequest == 0) {
            throw const ServiceException(
              'The server sent an empty audio stream.',
            );
          }
          if (knownTotal != null && offset > knownTotal) {
            throw const ServiceException(
              'Download size does not match the server response.',
            );
          }
          if (knownTotal != null && offset == knownTotal) break;
          if (!append || !windowed) {
            if (knownTotal != null && offset < knownTotal) {
              throw const ServiceException(
                'Download is incomplete. Retry to resume.',
              );
            }
            break;
          }
          if (knownTotal == null) {
            throw const ServiceException(
              'The server did not state the total size for a segmented audio download.',
            );
          }
        }
      } finally {
        client.close();
        task._client = null;
      }
      _checkCancelled(task);
      await _validateAudio(part, extension);
      // Each destination includes recording identity, preventing same-title collisions.
      await part.rename(target.path);
      if (await resumeFile.exists()) await resumeFile.delete();
      _finish(
        task,
        task.song.copyWith(
          localPath: target.path,
          localUri: target.uri.toString(),
          downloadFormat: extension.toUpperCase(),
        ),
      );
    } on Object catch (error) {
      if (task._cancelled) {
        _finishCancelled(task);
      } else {
        task.status = DownloadStatus.failed;
        task.error = error.toString();
        if (!task._result.isCompleted) task._result.completeError(error);
      }
      _notify();
    }
  }

  void _finish(DownloadTask task, Song song) {
    task.status = DownloadStatus.complete;
    if (!task._result.isCompleted) task._result.complete(song);
    _notify();
  }

  void _finishCancelled(DownloadTask task) {
    task.status = DownloadStatus.cancelled;
    if (!task._result.isCompleted) {
      task._result.completeError(const ServiceException('Download cancelled.'));
    }
  }

  void _checkCancelled(DownloadTask task) {
    if (_closed || task._cancelled) {
      throw const ServiceException('Download cancelled.');
    }
  }

  void _notify() {
    if (!_closed) notifyListeners();
  }

  Future<void> close() async {
    if (_closed) return;
    for (final task
        in _tasks.values.where((t) => !t._result.isCompleted).toList()) {
      cancel(task.song.videoId);
    }
    _closed = true;
    await Future.wait(
      _tasks.values.map(
        (task) =>
            task._result.future.then<void>((_) {}, onError: (Object _) {}),
      ),
    );
  }
}

String safeDownloadName(String input) {
  var name = input.replaceAll(RegExp(r'[\\/:*?"<>|\x00-\x1f]'), '_').trim();
  name = name.replaceAll(RegExp(r'[. ]+$'), '');
  if (name.isEmpty) name = 'Track';
  if (RegExp(
    r'^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$',
    caseSensitive: false,
  ).hasMatch(name)) {
    name = '_$name';
  }
  return String.fromCharCodes(name.runes.take(140));
}

String _extension(ResolvedStream stream) => stream.extension;

void _validateStream(ResolvedStream stream) {
  if (!stream.allowDownloads) {
    throw const ServiceException(
      'This source allows streaming only and does not permit downloads.',
    );
  }
  final uri = Uri.tryParse(stream.uri);
  if (uri == null ||
      !{'http', 'https'}.contains(uri.scheme) ||
      uri.host.isEmpty) {
    throw const ServiceException(
      'The source did not return an HTTP audio stream.',
    );
  }
  final suffix = uri.path.toLowerCase();
  if (suffix.endsWith('.mpd') ||
      suffix.endsWith('.m3u8') ||
      {'dash', 'hls', 'm3u8', 'mpd'}.contains(stream.extension)) {
    throw const ServiceException(
      'This source requires a segmented download. Choose a direct audio source.',
    );
  }
}

String _streamFingerprint(ResolvedStream stream) {
  final keys = stream.headers.keys.toList()..sort();
  // Persist only a one-way transport identity, never a signed URL or credential.
  return sha256
      .convert(
        utf8.encode(
          jsonEncode({
            'uri': stream.uri,
            'source': stream.source,
            'codec': stream.format.codec,
            'kbps': stream.format.kbps,
            'length': stream.contentLength,
            'headers': {for (final key in keys) key: stream.headers[key]},
          }),
        ),
      )
      .toString();
}

Future<void> _validateAudio(File file, String extension) async {
  final reader = await file.open();
  try {
    final bytes = await reader.read(512);
    if (bytes.isEmpty) {
      throw const ServiceException('The downloaded audio is empty.');
    }
    final text = latin1.decode(bytes).trimLeft().toLowerCase();
    if (text.startsWith('<') ||
        text.startsWith('{') ||
        text.startsWith('[') ||
        text.startsWith('#extm3u')) {
      throw const ServiceException(
        'The downloaded file contains a server response rather than audio.',
      );
    }
    if (extension == 'flac' &&
        !(bytes.length >= 4 &&
            ascii.decode(bytes.take(4).toList(), allowInvalid: true) ==
                'fLaC')) {
      throw const ServiceException(
        'The source did not return a valid FLAC header.',
      );
    }
  } finally {
    await reader.close();
  }
}
