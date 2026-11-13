import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/features/online/application/catalog_controller.dart';
import 'package:bitchord_desktop/features/online/data/youtube_music.dart';
import 'package:bitchord_desktop/features/online/domain/catalog.dart';
import 'package:bitchord_desktop/features/online/domain/online_port.dart';
import 'online_controller_test.dart' show ControlledCatalog;

class EditableCatalog extends ControlledCatalog
    implements OnlineAccounts, OnlinePlaylistEditor {
  @override
  bool signedIn = false;
  final edits = <String>[];
  Completer<void>? pendingRename;
  RequestScope? pendingScope;

  @override
  Future<void> signIn(String cookie, RequestScope scope) async {
    scope.check();
    signedIn = true;
  }

  @override
  Future<void> signOut() async {
    signedIn = false;
  }

  @override
  Future<String> createPlaylist(
    String title,
    PlaylistVisibility visibility,
    RequestScope scope,
  ) async {
    scope.check();
    edits.add('create:' + title + ':' + visibility.name);
    return 'PL1234567890';
  }

  @override
  Future<void> renamePlaylist(
    String playlistId,
    String title,
    RequestScope scope,
  ) async {
    pendingScope = scope;
    edits.add('rename:' + playlistId + ':' + title);
    await pendingRename?.future;
    scope.check();
  }

  @override
  Future<void> deletePlaylist(
    String playlistId,
    RequestScope scope,
  ) async {
    scope.check();
    edits.add('delete:' + playlistId);
  }

  @override
  Future<Map<String, String>> addToPlaylist(
    String playlistId,
    List<String> videoIds,
    RequestScope scope,
  ) async {
    scope.check();
    edits.add('add:' + playlistId + ':' + videoIds.length.toString());
    return {videoIds.single: 'set-entry-123'};
  }

  @override
  Future<void> removeFromPlaylist(
    String playlistId,
    List<({String setVideoId, String videoId})> entries,
    RequestScope scope,
  ) async {
    scope.check();
    edits.add('remove:' + playlistId + ':' + entries.single.setVideoId);
  }
}

void main() {
  test('no account never creates a playlist or fabricates success', () async {
    final port = EditableCatalog();
    final container = ProviderContainer(
      overrides: [onlinePortProvider.overrideWithValue(port)],
    );
    addTearDown(container.dispose);
    expect(
      () => container.read(catalogProvider.notifier).createPlaylist(
        'My playlist',
        PlaylistVisibility.private,
      ),
      throwsA(
        isA<ProviderFailure>().having(
          (f) => f.problem,
          'problem',
          ProviderProblem.unauthorized,
        ),
      ),
    );
    expect(port.edits, isEmpty);
  });

  test('signed-in editor sends real operations and retains entry identity',
      () async {
    final port = EditableCatalog()..signedIn = true;
    final container = ProviderContainer(
      overrides: [onlinePortProvider.overrideWithValue(port)],
    );
    addTearDown(container.dispose);
    final controller = container.read(catalogProvider.notifier);
    expect(
      await controller.createPlaylist('User list', PlaylistVisibility.private),
      'PL1234567890',
    );
    await controller.renamePlaylist('VLPL1234567890', 'New name');
    final added = await controller.addToPlaylist('VLPL1234567890', [
      'abcdefghijk',
    ]);
    expect(added['abcdefghijk'], 'set-entry-123');
    await controller.removeFromPlaylist('VLPL1234567890', [
      (setVideoId: 'set-entry-123', videoId: 'abcdefghijk'),
    ]);
    await controller.deletePlaylist('VLPL1234567890');
    expect(port.edits, [
      'create:User list:private',
      'rename:VLPL1234567890:New name',
      'add:VLPL1234567890:1',
      'remove:VLPL1234567890:set-entry-123',
      'delete:VLPL1234567890',
    ]);
  });

  test('account cancellation fences a pending mutation', () async {
    final port = EditableCatalog()
      ..signedIn = true
      ..pendingRename = Completer<void>();
    final container = ProviderContainer(
      overrides: [onlinePortProvider.overrideWithValue(port)],
    );
    addTearDown(container.dispose);
    final controller = container.read(catalogProvider.notifier);
    final pending = controller.renamePlaylist('VLPL1234567890', 'Changed');
    expect(port.pendingScope, isNotNull);
    expect(
      () => controller.deletePlaylist('VLPL1234567890'),
      throwsA(isA<ProviderFailure>()),
    );
    controller.cancel();
    port.pendingRename!.complete();
    await expectLater(
      pending,
      throwsA(
        isA<ProviderFailure>().having(
          (f) => f.problem,
          'problem',
          ProviderProblem.cancelled,
        ),
      ),
    );
    expect(port.edits, ['rename:VLPL1234567890:Changed']);
  });

  test('playlist parser preserves per-occurrence setVideoId for removal', () {
    final page = YouTubeMusic.parsePage({
      'contents': {
        'musicResponsiveListItemRenderer': {
          'title': {'simpleText': 'Repeat recording'},
          'playlistItemData': {
            'videoId': 'abcdefghijk',
            'playlistSetVideoId': 'set-a1',
          },
        },
      },
    });
    expect(page.entries.single.id, 'abcdefghijk');
    expect(page.entries.single.setVideoId, 'set-a1');
  });
}
