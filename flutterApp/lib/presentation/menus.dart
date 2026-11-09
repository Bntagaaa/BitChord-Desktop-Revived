import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter/services.dart';

import '../application/app_controller.dart';
import '../application/download_service.dart';
import '../domain/models.dart';
import 'desktop_theme.dart';
import 'widgets.dart';

/// Use the desktop-native path drawings from DesktopPhoneMenuIcons.kt.
String? _originalMenuAsset(DesktopMenuAction action) {
  final label = action.label.toLowerCase();
  if (label == 'like') {
    return action.selected ? 'bit-star-filled' : 'bit-star';
  }
  if (label == 'dislike') {
    return action.selected ? 'menu-broken-heart-filled' : 'menu-broken-heart';
  }
  if (label == 'add to playlist') return 'menu-add-to-playlist';
  if (label == 'start radio' || label == 'start mix') return 'menu-radio';
  if (label == 'play next') return 'menu-play-next';
  if (label == 'add to queue') return 'menu-add-to-queue';
  if (label == 'download' || label == 'download all') return 'menu-download';
  if (label == 'open album') return 'menu-album';
  if (label == 'open artist') return 'menu-artist';
  if (label == 'sleep timer') return 'menu-sleep-timer';
  if (label == 'share') return 'menu-share';
  if (label == 'song info') return 'menu-info';
  if (label == 'shuffle') return 'bit-shuffle';
  return null;
}

Widget _menuIcon(
  DesktopMenuAction action, {
  required double size,
  Color? color,
}) {
  final asset = _originalMenuAsset(action);
  if (asset == null) return Icon(action.icon, size: size, color: color);
  return SvgPicture.asset(
    'assets/icons/$asset.svg',
    width: size,
    height: size,
    colorFilter: ColorFilter.mode(color ?? Colors.white, BlendMode.srcIn),
  );
}

class MenuPointer {
  static Offset? position;
}

class DesktopMenuAction {
  const DesktopMenuAction(
    this.label,
    this.icon,
    this.action, {
    this.subtitle,
    this.selected = false,
    this.destructive = false,
  });
  final String label;
  final IconData icon;
  final VoidCallback action;
  final String? subtitle;
  final bool selected, destructive;
}

/// The transparent barrier dismisses on outside click without darkening the app.
Future<void> showGlassMenu(
  BuildContext context,
  AppController app,
  List<DesktopMenuAction?> actions, {
  List<DesktopMenuAction>? quickActions,
}) {
  final anchor = MenuPointer.position ?? const Offset(350, 140);
  return showGeneralDialog<void>(
    context: context,
    barrierDismissible: true,
    barrierLabel: 'Dismiss menu',
    barrierColor: Colors.transparent,
    transitionDuration: Duration.zero,
    pageBuilder: (context, _, _) => LayoutBuilder(
      builder: (context, constraints) {
        final width = math.min(292.0, constraints.maxWidth - 24);
        final height = math.min(520.0, constraints.maxHeight - 24);
        final estimated =
            actions.fold<double>(
              8,
              (sum, action) =>
                  sum +
                  (action == null
                      ? 7
                      : action.subtitle == null
                      ? 36
                      : 48),
            ) +
            (quickActions == null ? 0 : 66);
        final actualHeight = math.min(estimated, height);
        final left = anchor.dx.clamp(
          12.0,
          math.max(12.0, constraints.maxWidth - width - 12),
        );
        final top =
            (anchor.dy + actualHeight + 12 <= constraints.maxHeight
                    ? anchor.dy
                    : anchor.dy - actualHeight)
                .clamp(
                  12.0,
                  math.max(12.0, constraints.maxHeight - actualHeight - 12),
                );
        return Stack(
          children: [
            Positioned(
              left: left.toDouble(),
              top: top.toDouble(),
              width: width,
              child: Material(
                color: Colors.transparent,
                child: GlassSurface(
                  blur: !app.setting('reduce_dynamic_blur', false),
                  radius: 16,
                  child: ConstrainedBox(
                    constraints: BoxConstraints(maxHeight: height),
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (quickActions != null)
                            Padding(
                              padding: const EdgeInsets.all(6),
                              child: Row(
                                children: quickActions
                                    .map(
                                      (action) => Expanded(
                                        child: HoverTile(
                                          onTap: () {
                                            Navigator.pop(context);
                                            action.action();
                                          },
                                          padding: const EdgeInsets.symmetric(
                                            vertical: 7,
                                          ),
                                          child: Column(
                                            children: [
                                              _menuIcon(
                                                action,
                                                size: 22,
                                                color: action.selected
                                                    ? DesktopPalette.of(
                                                        context,
                                                      ).navAccent
                                                    : DesktopPalette.of(
                                                        context,
                                                      ).foreground,
                                              ),
                                              const SizedBox(height: 4),
                                              Text(
                                                action.label,
                                                style: const TextStyle(
                                                  fontSize: 11,
                                                  fontWeight: FontWeight.w600,
                                                ),
                                              ),
                                            ],
                                          ),
                                        ),
                                      ),
                                    )
                                    .toList(),
                              ),
                            ),
                          for (final action in actions)
                            if (action == null)
                              const Padding(
                                padding: EdgeInsets.symmetric(vertical: 3),
                                child: Divider(),
                              )
                            else
                              Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 4,
                                ),
                                child: HoverTile(
                                  onTap: () {
                                    Navigator.pop(context);
                                    action.action();
                                  },
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 10,
                                    vertical: 8,
                                  ),
                                  child: Row(
                                    children: [
                                      _menuIcon(
                                        action,
                                        size: 18,
                                        color: action.destructive
                                            ? Theme.of(
                                                context,
                                              ).colorScheme.error
                                            : DesktopPalette.of(
                                                context,
                                              ).foreground,
                                      ),
                                      const SizedBox(width: 12),
                                      Expanded(
                                        child: Column(
                                          crossAxisAlignment:
                                              CrossAxisAlignment.start,
                                          children: [
                                            Text(
                                              action.label,
                                              style: TextStyle(
                                                fontSize: 13,
                                                fontWeight: FontWeight.w600,
                                                color: action.destructive
                                                    ? Theme.of(
                                                        context,
                                                      ).colorScheme.error
                                                    : null,
                                              ),
                                            ),
                                            if (action.subtitle != null)
                                              Text(
                                                action.subtitle!,
                                                maxLines: 1,
                                                overflow: TextOverflow.ellipsis,
                                                style: TextStyle(
                                                  fontSize: 11,
                                                  color: DesktopPalette.of(
                                                    context,
                                                  ).secondary,
                                                ),
                                              ),
                                          ],
                                        ),
                                      ),
                                      if (action.selected)
                                        const Icon(Icons.check, size: 16),
                                    ],
                                  ),
                                ),
                              ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        );
      },
    ),
  );
}

void showSongMenu(
  BuildContext context,
  AppController app,
  Song song, {
  String? playlistId,
  String? cloudPlaylistId,
  int? queueIndex,
}) {
  final youtubeTrack =
      !song.isLocal && RegExp(r'^[A-Za-z0-9_-]{11}$').hasMatch(song.videoId);
  final downloaded = app.downloads
      .where((s) => s.videoId == song.videoId)
      .firstOrNull;
  final downloading = app.downloadTasks.any(
    (t) =>
        t.song.videoId == song.videoId &&
        const [
          DownloadStatus.queued,
          DownloadStatus.resolving,
          DownloadStatus.downloading,
        ].contains(t.status),
  );
  final actions = <DesktopMenuAction?>[
    DesktopMenuAction(
      'Add to playlist',
      Icons.playlist_add,
      () => choosePlaylist(context, app, song),
    ),
    null,
    if (youtubeTrack)
      DesktopMenuAction(
        'Start radio',
        Icons.radio_rounded,
        () => app.startRadio(song),
      ),
    DesktopMenuAction(
      'Play next',
      Icons.playlist_play_rounded,
      () => app.playback.playNext(song),
    ),
    DesktopMenuAction(
      'Add to queue',
      Icons.queue_music_rounded,
      () => app.playback.addToQueue(song),
    ),
    if (downloading)
      DesktopMenuAction(
        'Cancel download',
        Icons.close,
        () => app.cancelDownload(song.videoId),
      )
    else if (downloaded != null)
      DesktopMenuAction(
        'Remove download',
        Icons.download_done,
        () => app.removeDownload(downloaded),
      )
    else if (!song.isLocal)
      DesktopMenuAction(
        'Download',
        Icons.download_rounded,
        () => app.download(song),
      ),
    if (queueIndex != null)
      DesktopMenuAction(
        'Remove from queue',
        Icons.remove_circle_outline,
        () => app.playback.removeAt(queueIndex),
      ),
    if (playlistId != null)
      DesktopMenuAction(
        'Remove from playlist',
        Icons.remove_circle_outline,
        () => app.removeFromPlaylist(playlistId, song),
      ),
    if (cloudPlaylistId != null)
      DesktopMenuAction(
        'Remove from playlist',
        Icons.remove_circle_outline,
        () => app.removeFromCloudPlaylist(cloudPlaylistId, song),
      ),
    if (song.albumId != null || song.artistId != null) null,
    if (song.albumId != null)
      DesktopMenuAction(
        'Open album',
        Icons.album_outlined,
        () => app.openBrowse(
          BrowseItem(
            browseId: song.albumId!,
            title: song.albumName ?? 'Album',
            thumbnailUrl: song.thumbnailUrl,
            type: BrowseType.album,
          ),
        ),
        subtitle: song.albumName,
      ),
    if (song.artistId != null)
      DesktopMenuAction(
        'Open artist',
        Icons.person_outline,
        () => app.openBrowse(
          BrowseItem(
            browseId: song.artistId!,
            title: song.artist,
            type: BrowseType.artist,
          ),
        ),
        subtitle: song.artist,
      ),
    null,
    DesktopMenuAction(
      'Song info',
      Icons.info_outline,
      () => showSongInfo(context, song),
    ),
    null,
    DesktopMenuAction(
      'Sleep timer',
      Icons.bedtime_outlined,
      () => showSleepTimer(context, app),
      subtitle: app.playback.sleepAfterTrack
          ? 'End of this track'
          : app.playback.sleepTimerRemaining == null
          ? null
          : '${app.playback.sleepTimerRemaining!.inMinutes + 1} minutes remaining',
    ),
  ];
  showGlassMenu(
    context,
    app,
    actions,
    quickActions: [
      DesktopMenuAction(
        'Like',
        app.isLiked(song) ? Icons.star_rounded : Icons.star_outline_rounded,
        () => app.toggleLike(song),
        selected: app.isLiked(song),
      ),
      DesktopMenuAction(
        'Dislike',
        app.isDisliked(song) ? Icons.heart_broken : Icons.heart_broken_outlined,
        () => app.toggleDislike(song),
        selected: app.isDisliked(song),
      ),
      if (youtubeTrack)
        DesktopMenuAction(
          'Share',
          Icons.ios_share_rounded,
          () => copySongLink(context, song),
        ),
    ],
  );
}

void copySongLink(BuildContext context, Song song) {
  Clipboard.setData(
    ClipboardData(
      text:
          'https://music.youtube.com/watch?v=${Uri.encodeComponent(song.videoId)}',
    ),
  );
  ScaffoldMessenger.of(
    context,
  ).showSnackBar(const SnackBar(content: Text('Song link copied')));
}

Future<void> choosePlaylist(
  BuildContext context,
  AppController app,
  Song song,
) async {
  final canAddCloud =
      app.signedIn &&
      !song.isLocal &&
      RegExp(r'^[A-Za-z0-9_-]{11}$').hasMatch(song.videoId);
  final cloud = canAddCloud
      ? app.cloudPlaylists()
      : Future.value(<BrowseItem>[]);
  await showDialog<void>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: const Text('Add to playlist'),
      content: SizedBox(
        width: 420,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxHeight: 420),
          child: ListView(
            shrinkWrap: true,
            children: [
              for (final playlist in app.localPlaylists)
                ListTile(
                  leading: const Icon(Icons.queue_music),
                  title: Text(playlist.title),
                  subtitle: Text('${playlist.songs.length} songs'),
                  onTap: () {
                    app.addToPlaylist(playlist.id, song);
                    Navigator.pop(dialogContext);
                  },
                ),
              if (canAddCloud)
                FutureBuilder<List<BrowseItem>>(
                  future: cloud,
                  builder: (context, snapshot) {
                    if (snapshot.connectionState != ConnectionState.done) {
                      return const Padding(
                        padding: EdgeInsets.all(20),
                        child: Center(
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                      );
                    }
                    if (snapshot.hasError) {
                      return const Padding(
                        padding: EdgeInsets.all(16),
                        child: Text(
                          'Your YouTube Music playlists could not be loaded.',
                          style: TextStyle(fontSize: 12),
                        ),
                      );
                    }
                    final playlists = snapshot.data ?? const <BrowseItem>[];
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Divider(),
                        const Padding(
                          padding: EdgeInsets.fromLTRB(16, 14, 16, 5),
                          child: Text('YouTube Music'),
                        ),
                        if (playlists.isEmpty)
                          const Padding(
                            padding: EdgeInsets.all(16),
                            child: Text('No editable playlists found.'),
                          ),
                        for (final playlist in playlists)
                          ListTile(
                            leading: const Icon(Icons.cloud_outlined),
                            title: Text(playlist.title),
                            subtitle: Text(playlist.subtitle),
                            onTap: () {
                              Navigator.pop(dialogContext);
                              app.addToCloudPlaylist(playlist.browseId, song);
                            },
                          ),
                      ],
                    );
                  },
                ),
              ListTile(
                leading: const Icon(Icons.add),
                title: const Text('New playlist'),
                onTap: () async {
                  Navigator.pop(dialogContext);
                  final name = await askText(
                    context,
                    title: 'New playlist',
                    label: 'Playlist name',
                    action: 'Create',
                  );
                  if (name == null) return;
                  final before = app.localPlaylists.map((p) => p.id).toSet();
                  await app.createPlaylist(name);
                  final created = app.localPlaylists
                      .where((p) => !before.contains(p.id) && p.title == name)
                      .firstOrNull;
                  if (created != null) {
                    await app.addToPlaylist(created.id, song);
                  }
                },
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(dialogContext),
          child: const Text('Done'),
        ),
      ],
    ),
  );
}

void showCollectionMenu(
  BuildContext context,
  AppController app, {
  required List<Song> songs,
  required String title,
  required VoidCallback onFind,
  String? playlistId,
  String? cloudPlaylistId,
  String? browseId,
  bool? pinned,
}) {
  showGlassMenu(context, app, [
    if (songs.isNotEmpty)
      DesktopMenuAction('Shuffle', Icons.shuffle, () async {
        await app.playback.setShuffle(true);
        await app.playSongs(
          songs,
          index: math.Random().nextInt(songs.length),
          source: title,
        );
      }),
    DesktopMenuAction('Find in playlist', Icons.search, onFind),
    if (songs.isNotEmpty &&
        !songs.first.isLocal &&
        RegExp(r'^[A-Za-z0-9_-]{11}$').hasMatch(songs.first.videoId))
      DesktopMenuAction(
        'Start mix',
        Icons.radio,
        () => app.startRadio(songs.first),
      ),
    null,
    if (songs.isNotEmpty)
      DesktopMenuAction('Play next', Icons.playlist_play, () async {
        for (final song in songs.reversed) {
          await app.playback.playNext(song);
        }
      }),
    if (songs.isNotEmpty)
      DesktopMenuAction('Add to queue', Icons.queue_music, () async {
        for (final song in songs) {
          await app.playback.addToQueue(song);
        }
      }),
    if (songs.any((s) => !s.isLocal))
      DesktopMenuAction('Download all', Icons.download, () {
        for (final song in songs.where((s) => !s.isLocal)) {
          app.download(song);
        }
      }),
    if (browseId != null)
      DesktopMenuAction('Share', Icons.ios_share, () {
        Clipboard.setData(
          ClipboardData(
            text:
                'https://music.youtube.com/browse/${Uri.encodeComponent(browseId)}',
          ),
        );
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('Link copied')));
      }),
    if (playlistId != null) ...[
      null,
      DesktopMenuAction(
        app.localPlaylists.any((p) => p.id == playlistId && p.pinned)
            ? 'Unpin playlist'
            : 'Pin playlist',
        Icons.push_pin_outlined,
        () => app.pinPlaylist(
          playlistId,
          !app.localPlaylists.any((p) => p.id == playlistId && p.pinned),
        ),
      ),
      DesktopMenuAction('Rename', Icons.edit_outlined, () async {
        final value = await askText(
          context,
          title: 'Rename playlist',
          label: 'Name',
          value: title,
        );
        if (value != null) await app.renamePlaylist(playlistId, value);
      }),
      DesktopMenuAction('Delete playlist', Icons.delete_outline, () async {
        final confirmed = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('Delete playlist?'),
            content: Text(
              'Remove "$title" from your library? Your music files are kept.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('Cancel'),
              ),
              TextButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('Delete'),
              ),
            ],
          ),
        );
        if (confirmed == true) await app.deletePlaylist(playlistId);
      }, destructive: true),
    ],
    if (cloudPlaylistId != null) ...[
      null,
      DesktopMenuAction('Rename', Icons.edit_outlined, () async {
        final name = await askText(
          context,
          title: 'Rename YouTube Music playlist',
          label: 'Name',
          value: title,
        );
        if (name != null) await app.renameCloudPlaylist(cloudPlaylistId, name);
      }),
      DesktopMenuAction('Delete playlist', Icons.delete_outline, () async {
        final confirmed = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('Delete playlist?'),
            content: Text('Delete "$title" from your YouTube Music account?'),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('Cancel'),
              ),
              TextButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('Delete'),
              ),
            ],
          ),
        );
        if (confirmed == true) await app.deleteCloudPlaylist(cloudPlaylistId);
      }, destructive: true),
    ],
  ]);
}

void showSongInfo(BuildContext context, Song song) => showDialog<void>(
  context: context,
  builder: (context) => AlertDialog(
    title: const Text('Song info'),
    content: SizedBox(
      width: 430,
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                Artwork(song.thumbnailUrl, size: 72),
                const SizedBox(width: 16),
                Expanded(
                  child: Text(
                    song.title,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            for (final entry in <String, String?>{
              'Artist': song.artist,
              'Album': song.albumName,
              'Duration': song.durationText,
              'Source': song.isLocal
                  ? 'Local file'
                  : song.playbackSource ?? 'YouTube Music',
              'Quality': song.sourceQuality,
              'Format': song.downloadFormat,
              'Location': song.localPath ?? song.localUri,
              'Video ID': song.isLocal ? null : song.videoId,
            }.entries)
              if (entry.value != null && entry.value!.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: SelectableText(
                    '${entry.key}: ${entry.value}',
                    style: const TextStyle(fontSize: 13),
                  ),
                ),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Done'),
      ),
    ],
  ),
);

void showSleepTimer(BuildContext context, AppController app) =>
    showGlassMenu(context, app, [
      for (final minutes in [15, 30, 45, 60, 90])
        DesktopMenuAction(
          '$minutes minutes',
          Icons.bedtime_outlined,
          () => app.playback.setSleepTimer(Duration(minutes: minutes)),
        ),
      DesktopMenuAction('Custom duration…', Icons.timer_outlined, () async {
        final value = await askText(
          context,
          title: 'Sleep timer',
          label: 'Minutes',
          action: 'Start',
        );
        final minutes = int.tryParse(value ?? '');
        if (minutes != null && minutes > 0 && minutes <= 1440) {
          await app.playback.setSleepTimer(Duration(minutes: minutes));
        } else if (value != null && context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Enter a number between 1 and 1440.')),
          );
        }
      }),
      null,
      DesktopMenuAction(
        'End of this track',
        Icons.music_note,
        () => app.playback.setSleepAfterTrack(true),
        selected: app.playback.sleepAfterTrack,
      ),
      if (app.playback.sleepAfterTrack || app.playback.sleepAt != null)
        DesktopMenuAction(
          'Turn off',
          Icons.close,
          () => app.playback.setSleepTimer(null),
        ),
    ]);
