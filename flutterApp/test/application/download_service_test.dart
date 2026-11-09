import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:bitchord_desktop/application/download_service.dart';
import 'package:bitchord_desktop/domain/models.dart';
import 'package:bitchord_desktop/playback/stream_resolver.dart';

class Resolver implements StreamResolver {
  Resolver(this.handler);
  final Future<ResolvedStream> Function(bool refresh) handler;
  int calls = 0;
  @override
  Future<ResolvedStream> resolve(
    Song song, {
    bool video = false,
    int maxVideoHeight = 1080,
    bool refresh = false,
    bool forceRefresh = false,
  }) {
    calls++;
    return handler(refresh || forceRefresh);
  }

  @override
  void invalidate(Song song) {}
  @override
  Future<void> close() async {}
}

class Client extends http.BaseClient {
  Client(this.handler);
  final Future<http.StreamedResponse> Function(http.BaseRequest) handler;
  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) =>
      handler(request);
}

const track = Song(videoId: 'track', title: 'Song', artist: 'Artist');
const stream = ResolvedStream(
  uri: 'https://audio.invalid/track.flac',
  format: StreamFormat(codec: 'flac'),
  contentLength: 32,
);
final audio = [0x66, 0x4c, 0x61, 0x43, ...List.filled(28, 0)];

http.StreamedResponse completeResponse(
  List<int> bytes, {
  int status = 200,
  Map<String, String> headers = const {},
}) => http.StreamedResponse(
  Stream.value(bytes),
  status,
  contentLength: bytes.length,
  headers: {'content-type': 'audio/flac', ...headers},
);

void main() {
  late Directory root;
  setUp(() async {
    root = await Directory.systemTemp.createTemp('bitchord-download-test-');
  });
  tearDown(() async {
    await root.delete(recursive: true);
  });

  test('shutdown drains resolving tasks and rejects later downloads', () async {
    final blocked = Completer<ResolvedStream>();
    final service = DownloadService(
      resolver: Resolver((_) => blocked.future),
      directory: root,
      clientFactory: () =>
          Client((_) async => throw StateError('Cancelled before transfer')),
    );
    final pending = expectLater(
      service.download(track),
      throwsA(isA<ServiceException>()),
    );
    final closing = service.close();
    blocked.complete(stream);
    await pending;
    await closing;
    expect(service.tasks.single.status, DownloadStatus.cancelled);
    await expectLater(service.download(track), throwsStateError);
    service.dispose();
  });

  test(
    'refreshing to a different transport restarts partial bytes safely',
    () async {
      var requests = 0;
      String? freshRange;
      final replacement = [0x66, 0x4c, 0x61, 0x43, ...List.filled(28, 9)];
      Stream<List<int>> interrupted() async* {
        yield audio.sublist(0, 8);
        throw http.ClientException('Connection closed');
      }

      final service = DownloadService(
        resolver: Resolver(
          (refresh) async => refresh
              ? const ResolvedStream(
                  uri: 'https://audio.invalid/new-track.flac',
                  format: StreamFormat(codec: 'flac'),
                  contentLength: 32,
                )
              : stream,
        ),
        directory: root,
        clientFactory: () => Client((request) async {
          requests++;
          if (requests == 1) {
            return http.StreamedResponse(
              interrupted(),
              200,
              contentLength: audio.length,
              headers: {'content-type': 'audio/flac'},
            );
          }
          if (requests == 2) {
            return http.StreamedResponse(const Stream.empty(), 403);
          }
          freshRange = request.headers['Range'];
          return completeResponse(replacement);
        }),
      );
      await expectLater(
        service.download(track),
        throwsA(isA<http.ClientException>()),
      );
      final saved = await service.download(track);
      expect(requests, 3);
      expect(freshRange, isNull);
      expect(await File(saved.localPath!).readAsBytes(), replacement);
      await service.close();
      service.dispose();
    },
  );

  test(
    'successful download atomically publishes bytes and local metadata',
    () async {
      final service = DownloadService(
        resolver: Resolver((_) async => stream),
        directory: root,
        clientFactory: () => Client((_) async => completeResponse(audio)),
      );
      final saved = await service.download(track);
      expect(saved.videoId, track.videoId);
      expect(saved.isLocal, isTrue);
      expect(saved.downloadFormat, 'FLAC');
      expect(await File(saved.localPath!).readAsBytes(), audio);
      expect(root.listSync().where((f) => f.path.endsWith('.part')), isEmpty);
      expect(service.tasks.single.status, DownloadStatus.complete);
      await service.close();
      service.dispose();
    },
  );

  test(
    'a server ignoring Range replaces a partial download instead of appending',
    () async {
      var attempt = 0;
      String? resumedRange;
      Stream<List<int>> interrupted() async* {
        yield audio.sublist(0, 8);
        throw http.ClientException('Connection closed');
      }

      final service = DownloadService(
        resolver: Resolver((_) async => stream),
        directory: root,
        clientFactory: () => Client((request) async {
          attempt++;
          if (attempt == 1) {
            return http.StreamedResponse(
              interrupted(),
              200,
              contentLength: audio.length,
              headers: {'content-type': 'audio/flac'},
            );
          }
          resumedRange = request.headers['Range'];
          return completeResponse(audio);
        }),
      );
      await expectLater(
        service.download(track),
        throwsA(isA<http.ClientException>()),
      );
      final saved = await service.download(track);
      expect(resumedRange, 'bytes=8-');
      expect(await File(saved.localPath!).readAsBytes(), audio);
      await service.close();
      service.dispose();
    },
  );

  test(
    'an inconsistent range is rejected without publishing corrupt audio',
    () async {
      var attempt = 0;
      Stream<List<int>> interrupted() async* {
        yield audio.sublist(0, 8);
        throw http.ClientException('Connection closed');
      }

      final service = DownloadService(
        resolver: Resolver((_) async => stream),
        directory: root,
        clientFactory: () => Client((_) async {
          if (++attempt == 1) {
            return http.StreamedResponse(
              interrupted(),
              200,
              contentLength: audio.length,
              headers: {'content-type': 'audio/flac'},
            );
          }
          return completeResponse(
            audio.sublist(8),
            status: 206,
            headers: {'content-range': 'bytes 0-23/32'},
          );
        }),
      );
      await expectLater(
        service.download(track),
        throwsA(isA<http.ClientException>()),
      );
      await expectLater(
        service.download(track),
        throwsA(
          isA<ServiceException>().having(
            (e) => e.message,
            'message',
            contains('inconsistent byte range'),
          ),
        ),
      );
      expect(root.listSync().where((f) => f.path.endsWith('.flac')), isEmpty);
      await service.close();
      service.dispose();
    },
  );

  test(
    'playback-only policy is enforced again after an expired stream refresh',
    () async {
      var requests = 0;
      final service = DownloadService(
        resolver: Resolver(
          (refresh) async => refresh
              ? const ResolvedStream(
                  uri: 'https://audio.invalid/track.flac',
                  format: StreamFormat(codec: 'flac'),
                  allowDownloads: false,
                )
              : stream,
        ),
        directory: root,
        clientFactory: () => Client((_) async {
          requests++;
          return requests == 1
              ? http.StreamedResponse(const Stream.empty(), 403)
              : completeResponse(audio);
        }),
      );
      await expectLater(
        service.download(track),
        throwsA(isA<ServiceException>()),
      );
      expect(requests, 1);
      expect(root.listSync().where((f) => f.path.endsWith('.flac')), isEmpty);
      await service.close();
      service.dispose();
    },
  );

  test(
    'queued cancellation does not resolve or fetch the cancelled recording',
    () async {
      final blocked = Completer<ResolvedStream>();
      final resolver = Resolver((_) => blocked.future);
      final service = DownloadService(
        resolver: resolver,
        directory: root,
        maxConcurrent: 1,
        clientFactory: () => Client((_) async => completeResponse(audio)),
      );
      final first = service.download(track);
      final next = service.download(track.copyWith(videoId: 'cancelled'));
      final cancelled = expectLater(next, throwsA(isA<ServiceException>()));
      service.cancel('cancelled');
      await cancelled;
      blocked.complete(stream);
      await first;
      expect(resolver.calls, 1);
      expect(service.tasks.last.status, DownloadStatus.cancelled);
      await service.close();
      service.dispose();
    },
  );

  test(
    'documents and mislabeled FLAC are rejected without completed files',
    () async {
      final service = DownloadService(
        resolver: Resolver((_) async => stream),
        directory: root,
        clientFactory: () => Client(
          (_) async => completeResponse('<html>denied</html>'.codeUnits),
        ),
      );
      await expectLater(
        service.download(track),
        throwsA(isA<ServiceException>()),
      );
      expect(root.listSync().where((f) => f.path.endsWith('.flac')), isEmpty);
      await service.close();
      service.dispose();
    },
  );

  test('filenames normalize Windows reserved characters and trailing dots', () {
    expect(safeDownloadName('CON'), '_CON');
    expect(safeDownloadName('A/B:*? .'), 'A_B___');
    expect(safeDownloadName('...'), 'Track');
    expect(safeDownloadName('🎵' * 200).runes.length, 140);
  });
}
