import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/router.dart';
import '../../../core/domain/audio_contract.dart';
import '../../../design_system/palette.dart';
import '../../../design_system/theme.dart';
import '../application/catalog_controller.dart';
import '../domain/catalog.dart';
import '../domain/online_port.dart';
import 'catalog_components.dart';
import 'catalog_search_field.dart';
import '../application/search_suggestions.dart';
import '../application/download_queue.dart';
import '../application/catalog_playback.dart';
import 'download_manager.dart';

class CatalogSurface extends ConsumerStatefulWidget {
  const CatalogSurface({
    super.key,
    required this.destination,
    required this.title,
    this.browseId,
    this.params,
  });
  final String destination, title;
  final String? browseId, params;
  @override
  ConsumerState<CatalogSurface> createState() => _CatalogSurfaceState();
}

class _CatalogSurfaceState extends ConsumerState<CatalogSurface> {
  final _query = TextEditingController(), _addon = TextEditingController();
  String _source = 'youtube';
  SearchFilter _filter = SearchFilter.all;
  bool get _youtubeAudio {
    final port = ref.read(onlinePortProvider);
    return port is OnlineCapabilities &&
        (port as OnlineCapabilities).youtubeAudioConfigured;
  }

  String? _notice;
  bool _connecting = false;
  Future<List<DownloadedEntry>>? _downloads;
  bool _finding = false;
  String _listQuery = '';
  bool get _collection =>
      widget.browseId != null &&
      (widget.browseId!.startsWith('MPRE') ||
          widget.browseId!.startsWith('VL') ||
          widget.browseId!.startsWith('OLAK'));
  @override
  void initState() {
    super.initState();
    Future.microtask(() {
      if (!mounted) return;
      final controller = ref.read(catalogProvider.notifier);
      final restored = controller.activate(
        '${widget.destination}:${widget.browseId}:${widget.params}',
      );
      if (restored) {
        final saved = ref.read(catalogProvider);
        setState(() {
          _query.text = saved.query;
          _source = saved.provider;
          _filter = saved.filter;
        });
      }
      if (widget.destination == 'downloads') {
        _reloadDownloads();
        return;
      }
      final browse =
          widget.browseId ??
          switch (widget.destination) {
            'listen-now' => 'FEmusic_home',
            'new' => 'FEmusic_new_releases',
            'explore' => 'FEmusic_explore',
            'library' => 'app:library',
            _ => null,
          };
      if (browse != null && !restored)
        ref
            .read(catalogProvider.notifier)
            .load(browseId: browse, params: widget.params);
    });
  }

  void _reloadDownloads() {
    setState(() {
      _downloads = ref.read(onlinePortProvider)?.downloads();
    });
  }

  @override
  void dispose() {
    _query.dispose();
    _addon.dispose();
    super.dispose();
  }

  void _message(String? message) {
    if (mounted) setState(() => _notice = message);
  }

  Future<void> _play(
    CatalogEntry entry, {
    bool offline = false,
    List<CatalogEntry>? context,
    int? index,
  }) async {
    _message('Opening audio…');
    _message(
      await ref
          .read(catalogProvider.notifier)
          .play(entry, offline: offline, context: context, index: index),
    );
  }

  void _download(CatalogEntry entry) {
    ref.read(downloadQueueProvider.notifier).enqueue(entry);
    showDialog<void>(context: context, builder: (_) => const DownloadManager());
  }

  bool get _canEditPlaylists {
    final port = ref.read(onlinePortProvider);
    return port is OnlineAccounts &&
        port.signedIn &&
        port is OnlinePlaylistEditor;
  }

  Future<String?> _playlistNameDialog(
    String title, {
    String initial = '',
  }) async {
    final controller = TextEditingController(text: initial);
    try {
      return await showDialog<String>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text(title),
          content: TextField(
            key: const Key('playlist-name'),
            controller: controller,
            autofocus: true,
            maxLength: 150,
            decoration: const InputDecoration(labelText: 'Playlist name'),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () {
                final name = controller.text.trim();
                if (name.isNotEmpty) Navigator.pop(dialogContext, name);
              },
              child: const Text('Save'),
            ),
          ],
        ),
      );
    } finally {
      controller.dispose();
    }
  }

  String _playlistError(Object error) => error is ProviderFailure
      ? error.toString()
      : 'Could not update playlist. Try again.';

  Future<void> _createPlaylist() async {
    if (!_canEditPlaylists) {
      _message('Sign in to create an account playlist.');
      return;
    }
    final title = await _playlistNameDialog('New playlist');
    if (title == null || !mounted) return;
    try {
      final id = await ref
          .read(catalogProvider.notifier)
          .createPlaylist(title, PlaylistVisibility.private);
      if (!mounted) return;
      _message('Playlist created.');
      final ctrl = ref.read(catalogProvider.notifier);
      await ctrl.load(browseId: 'app:library');
      if (!mounted) return;
      _open(
        CatalogEntry(
          provider: 'youtube',
          id: 'VL$id',
          title: title,
          kind: CatalogKind.playlist,
        ),
      );
    } catch (error) {
      _message(_playlistError(error));
    }
  }

  Future<void> _renamePlaylist() async {
    final id = widget.browseId;
    if (id == null || !_canEditPlaylists) return;
    final original = ref.read(catalogProvider).header?.title ?? widget.title;
    final name = await _playlistNameDialog('Rename playlist', initial: original);
    if (name == null || !mounted || name == original) return;
    try {
      await ref.read(catalogProvider.notifier).renamePlaylist(id, name);
      if (!mounted) return;
      await ref.read(catalogProvider.notifier).load(browseId: id);
      _message('Playlist renamed.');
    } catch (error) {
      _message(_playlistError(error));
    }
  }

  Future<void> _deletePlaylist() async {
    final id = widget.browseId;
    if (id == null || !_canEditPlaylists) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Delete playlist?'),
        content: const Text(
          'This deletes the playlist from your YouTube Music account.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    try {
      await ref.read(catalogProvider.notifier).deletePlaylist(id);
      if (!mounted) return;
      ref.read(historyProvider.notifier).visit('/library');
      context.go('/library');
    } catch (error) {
      _message(_playlistError(error));
    }
  }

  Future<void> _addToPlaylist(CatalogEntry track) async {
    if (!_canEditPlaylists || track.provider != 'youtube') {
      _message('Sign in to add YouTube Music tracks to a playlist.');
      return;
    }
    try {
      final playlists = await ref.read(catalogProvider.notifier).accountPlaylists();
      if (!mounted) return;
      if (playlists.isEmpty) {
        _message('No account playlists. Create one in Library first.');
        return;
      }
      final selected = await showDialog<CatalogEntry>(
        context: context,
        builder: (dialogContext) => SimpleDialog(
          title: const Text('Add to playlist'),
          children: [
            for (final playlist in playlists)
              SimpleDialogOption(
                onPressed: () => Navigator.pop(dialogContext, playlist),
                child: Text(playlist.title),
              ),
          ],
        ),
      );
      if (selected == null || !mounted) return;
      await ref
          .read(catalogProvider.notifier)
          .addToPlaylist(selected.id, [track.id]);
      _message('Added to playlist.');
    } catch (error) {
      _message(_playlistError(error));
    }
  }

  Future<void> _removeFromPlaylist(CatalogEntry track) async {
    final id = widget.browseId, setId = track.setVideoId;
    if (!_canEditPlaylists ||
        id == null ||
        setId == null ||
        track.provider != 'youtube') return;
    try {
      await ref.read(catalogProvider.notifier).removeFromPlaylist(id, [
        (setVideoId: setId, videoId: track.id),
      ]);
      if (!mounted) return;
      await ref.read(catalogProvider.notifier).load(browseId: id);
      _message('Removed from playlist.');
    } catch (error) {
      _message(_playlistError(error));
    }
  }

  void _select(CatalogEntry entry) {
    ref.read(searchSuggestionsProvider.notifier).dismiss();
    if (entry.kind == CatalogKind.track) {
      if (entry.provider == 'youtube' && !_youtubeAudio) {
        _message(
          'YouTube audio resolver is not configured. See Phase 4 setup.',
        );
      } else {
        _play(entry);
      }
    } else {
      _open(entry);
    }
  }

  void _openShelf(String id, String title, String? params) => _open(
    CatalogEntry(
      provider: 'youtube',
      id: id,
      title: title,
      params: params,
      kind: CatalogKind.playlist,
    ),
  );

  void _open(CatalogEntry entry) {
    final path = Uri(
      path: '/catalog/${entry.id}',
      queryParameters: {
        'title': entry.title,
        if (entry.params != null) 'params': entry.params!,
      },
    ).toString();
    ref.read(historyProvider.notifier).visit(path);
    context.go(path);
  }

  Widget _row(
    CatalogEntry entry, {
    bool offline = false,
    List<CatalogEntry>? playbackEntries,
    int? index,
  }) {
    final playable = offline || entry.provider != 'youtube' || _youtubeAudio;
    if (_collection && entry.kind == CatalogKind.track) {
      return CollectionTrackRow(
        entry: entry,
        index: index ?? 0,
        album: !widget.browseId!.startsWith('VL'),
        onPlay: playable
            ? () => _play(
                entry,
                context: playbackEntries,
                index: playbackEntries == null ? null : index,
              )
            : null,
        onDownload: playable ? () => _download(entry) : null,
        onAddToPlaylist: _canEditPlaylists && entry.provider == 'youtube'
            ? () => _addToPlaylist(entry)
            : null,
        onRemoveFromPlaylist: _canEditPlaylists &&
                (ref.read(catalogProvider).header?.ownedByAccount == true) &&
                entry.setVideoId != null
            ? () => _removeFromPlaylist(entry)
            : null,
      );
    }
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        children: [
          CatalogArtwork(
            url: entry.artwork,
            size: 48,
            radius: 7,
            round: entry.kind == CatalogKind.artist,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: InkWell(
              borderRadius: BorderRadius.circular(7),
              onTap: entry.kind == CatalogKind.track
                  ? playable
                        ? () => _play(
                            entry,
                            offline: offline,
                            context: playbackEntries,
                            index: playbackEntries == null ? null : index,
                          )
                        : null
                  : () => _open(entry),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 8,
                  vertical: 10,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      entry.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodyLarge,
                    ),
                    if (entry.subtitle.isNotEmpty)
                      Text(
                        entry.subtitle,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                  ],
                ),
              ),
            ),
          ),
          if (entry.kind == CatalogKind.track && playable)
            IconButton(
              tooltip: offline ? 'Play offline' : 'Play',
              onPressed: () => _play(
                entry,
                offline: offline,
                context: playbackEntries,
                index: playbackEntries == null ? null : index,
              ),
              icon: const Icon(Icons.play_arrow, size: 20),
            ),
          if (entry.kind == CatalogKind.track && !offline && playable)
            IconButton(
              tooltip: 'Download',
              onPressed: () => _download(entry),
              icon: const Icon(Icons.download_outlined, size: 20),
            ),
          if (!offline &&
              entry.kind == CatalogKind.track &&
              entry.provider == 'youtube' &&
              _canEditPlaylists)
            IconButton(
              tooltip: 'Add to playlist',
              onPressed: () => _addToPlaylist(entry),
              icon: const Icon(Icons.playlist_add, size: 20),
            ),
          if (offline)
            IconButton(
              tooltip: 'Remove download',
              onPressed: () async {
                try {
                  await ref.read(onlinePortProvider)?.removeDownload(entry);
                  _reloadDownloads();
                } catch (_) {
                  _message('Download removal failed.');
                }
              },
              icon: const Icon(Icons.delete_outline, size: 20),
            ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(catalogProvider);
    final tasks = ref.watch(downloadQueueProvider);
    ref.listen(downloadRevisionProvider, (_, _) {
      if (widget.destination == 'downloads') _reloadDownloads();
    });
    final controller = ref.read(catalogProvider.notifier);
    final search = widget.destination == 'search';
    final catalog =
        widget.browseId != null ||
        ['listen-now', 'new', 'explore', 'search', 'library'].contains(
          widget.destination,
        );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (!_collection)
          Text(
            widget.title,
            key: const Key('page-heading'),
            style: Theme.of(context).textTheme.headlineLarge,
          ),
        if (widget.browseId != null)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              onPressed: () {
                final path = ref.read(historyProvider.notifier).back();
                if (path != null) context.go(path);
              },
              child: const Text('Back'),
            ),
          ),
        if (catalog && (_source != 'youtube' || !_youtubeAudio))
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(
              _source == 'youtube'
                  ? (_youtubeAudio
                        ? 'YouTube Music · Media access is checked before playback.'
                        : 'YouTube Music · Audio resolver is not configured.')
                  : _source == 'jiosaavn'
                  ? 'JioSaavn'
                  : 'Add-on · This session',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        if (search) ...[
          CatalogSearchField(
            controller: _query,
            youtube: _source == 'youtube',
            onEdit: () => controller.clearSearch(
              query: _query.text,
              provider: _source,
              filter: _filter,
            ),
            onSubmit: (query) => controller.load(
              provider: _source,
              query: query,
              filter: _filter,
            ),
          ),
          Row(
            children: [
              DropdownButton<String>(
                value: _source,
                items: [
                  const DropdownMenuItem(
                    value: 'jiosaavn',
                    child: Text('JioSaavn'),
                  ),
                  const DropdownMenuItem(
                    value: 'youtube',
                    child: Text('YouTube Music'),
                  ),
                  if (ref
                          .read(onlinePortProvider)
                          ?.providers
                          .contains('addon') ??
                      false)
                    const DropdownMenuItem(
                      value: 'addon',
                      child: Text('Add-on'),
                    ),
                ],
                onChanged: (value) {
                  if (value != null)
                    setState(() {
                      ref.read(searchSuggestionsProvider.notifier).dismiss();
                      controller.clearSearch();
                      _source = value;
                      _filter = SearchFilter.all;
                    });
                },
              ),
            ],
          ),
          if (_source == 'youtube')
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  for (final filter in SearchFilter.values)
                    Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: ChoiceChip(
                        label: Text(filter.name),
                        selected: _filter == filter,
                        onSelected: (_) {
                          setState(() => _filter = filter);
                          if (_query.text.trim().isNotEmpty)
                            controller.load(
                              provider: _source,
                              query: _query.text,
                              filter: filter,
                            );
                        },
                      ),
                    ),
                ],
              ),
            ),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _addon,
                  obscureText: true,
                  decoration: const InputDecoration(
                    hintText: 'Add-on server address (this session)',
                  ),
                ),
              ),
              TextButton(
                onPressed: _connecting
                    ? null
                    : () async {
                        setState(() => _connecting = true);
                        final error = await controller.connect(
                          _addon.text.trim(),
                        );
                        _addon.clear();
                        if (mounted)
                          setState(() {
                            _connecting = false;
                            _notice = error;
                            if (error == null) _source = 'addon';
                          });
                      },
                child: Text(_connecting ? 'Connecting…' : 'Connect'),
              ),
            ],
          ),
        ],
        if (_notice != null)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(_notice!),
          ),
        if (tasks.isNotEmpty)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              onPressed: () => showDialog<void>(
                context: context,
                builder: (_) => const DownloadManager(),
              ),
              child: Text('Downloads · ${tasks.length}'),
            ),
          ),
        if (widget.destination == 'library' && _canEditPlaylists)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              key: const Key('new-account-playlist'),
              onPressed: _createPlaylist,
              icon: const Icon(Icons.playlist_add),
              label: const Text('New playlist'),
            ),
          ),
        if (catalog && state.loading)
          const LinearProgressIndicator(minHeight: 2),
        if (catalog && state.error != null)
          Row(
            children: [
              Expanded(child: Text(state.error!)),
              TextButton(
                onPressed: state.loading ? null : controller.retry,
                child: const Text('Retry'),
              ),
            ],
          ),
        if (catalog)
          Expanded(
            child:
                state.entries.isEmpty &&
                state.shelves.isEmpty &&
                !state.loading &&
                state.error == null
                ? Center(
                    child: Text(
                      search
                          ? 'Search for music'
                          : widget.destination == 'library' &&
                                ref.read(onlinePortProvider) is OnlineAccounts &&
                                !(ref.read(onlinePortProvider)
                                        as OnlineAccounts)
                                    .signedIn
                          ? 'Sign in from Accounts to view your library.'
                          : 'No catalog items available.',
                    ),
                  )
                : ListView(
                    children: [
                      if (_collection && state.header != null)
                        CollectionHeader(
                          header: state.header!,
                          album: !widget.browseId!.startsWith('VL'),
                          entries: state.entries,
                          onShuffle:
                              state.entries.isEmpty ||
                                  !state.entries.every(
                                    (e) => e.kind == CatalogKind.track,
                                  )
                              ? null
                              : () {
                                  final shuffled = state.entries.toList()
                                    ..shuffle();
                                  _play(
                                    shuffled.first,
                                    context: shuffled,
                                    index: 0,
                                  );
                                },
                          onPlay:
                              state.entries.isEmpty ||
                                  !state.entries.every(
                                    (e) => e.kind == CatalogKind.track,
                                  )
                              ? null
                              : () => _play(
                                  state.entries.first,
                                  context: state.entries,
                                  index: 0,
                                ),
                          onRename: _canEditPlaylists &&
                                  widget.browseId!.startsWith('VL') &&
                                  state.header?.ownedByAccount == true
                              ? _renamePlaylist
                              : null,
                          onDelete: _canEditPlaylists &&
                                  widget.browseId!.startsWith('VL') &&
                                  state.header?.ownedByAccount == true
                              ? _deletePlaylist
                              : null,
                          onFind: () => setState(() {
                            _finding = !_finding;
                            _listQuery = '';
                          }),
                          onShare: () async {
                            final id = widget.browseId!;
                            final link = id.startsWith('VL')
                                ? Uri.https('music.youtube.com', '/playlist', {
                                    'list': id.substring(2),
                                  })
                                : Uri.https('music.youtube.com', '/browse/$id');
                            try {
                              await Clipboard.setData(
                                ClipboardData(text: link.toString()),
                              );
                              _message('Link copied.');
                            } catch (_) {
                              _message('Unable to copy link.');
                            }
                          },
                          onDownload: () {
                            final queue = ref.read(
                              downloadQueueProvider.notifier,
                            );
                            for (final entry in state.entries) {
                              queue.enqueue(entry);
                            }
                            showDialog<void>(
                              context: context,
                              builder: (_) => const DownloadManager(),
                            );
                          },
                        )
                      else if (state.header != null)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 20),
                          child: Row(
                            children: [
                              CatalogArtwork(
                                url: state.header!.artwork,
                                size: 140,
                                round:
                                    widget.browseId?.startsWith('UC') ?? false,
                              ),
                              const SizedBox(width: 20),
                              Expanded(
                                child: Text(
                                  state.header!.subtitle,
                                  style: Theme.of(context).textTheme.bodyLarge,
                                ),
                              ),
                            ],
                          ),
                        ),
                      if (_collection && _finding)
                        TextField(
                          key: const Key('collection-find'),
                          decoration: const InputDecoration(
                            hintText: 'Search this list',
                          ),
                          onChanged: (value) =>
                              setState(() => _listQuery = value),
                        ),
                      if (state.shelves.isNotEmpty && !search && !_collection)
                        for (
                          var index = 0;
                          index < state.shelves.length;
                          index++
                        )
                          CatalogShelfView(
                            key: ValueKey(
                              'shelf-$index-${state.shelves[index].title}',
                            ),
                            shelf: state.shelves[index],
                            onSelect: _select,
                            onOpen: _openShelf,
                            hero:
                                widget.destination == 'listen-now' &&
                                widget.browseId == null &&
                                index == 0 &&
                                state.shelves[index].layout ==
                                    ShelfLayout.cards &&
                                !state.shelves[index].entries.any(
                                  (e) => e.isVideo,
                                ),
                          )
                      else
                        for (final (index, entry) in state.entries.indexed)
                          if (_listQuery.trim().isEmpty ||
                              '${entry.title} ${entry.subtitle}'
                                  .toLowerCase()
                                  .contains(_listQuery.trim().toLowerCase()))
                            _row(
                              entry,
                              playbackEntries:
                                  _collection &&
                                      state.entries.every(
                                        (e) => e.kind == CatalogKind.track,
                                      )
                                  ? state.entries
                                  : null,
                              index: _collection ? index : null,
                            ),
                      if (state.continuation != null)
                        TextButton(
                          onPressed: state.loading ? null : controller.more,
                          child: const Text('Load more'),
                        ),
                    ],
                  ),
          ),
        if (widget.destination == 'downloads')
          Expanded(
            child: FutureBuilder<List<DownloadedEntry>>(
              future: _downloads,
              builder: (context, snapshot) {
                if (snapshot.hasError)
                  return const Text('Unable to verify downloaded files.');
                if (!snapshot.hasData)
                  return const Center(child: CircularProgressIndicator());
                if (snapshot.data!.isEmpty)
                  return const Text('No verified downloads.');
                final rows = snapshot.data!;
                // Construct one bounded context, not a full copy per row.
                // Large offline libraries retain individual playback support.
                final context = rows.length <= 800
                    ? List<CatalogEntry>.unmodifiable(
                        rows.map((row) => row.entry),
                      )
                    : null;
                return ListView.builder(
                  itemCount: rows.length,
                  itemBuilder: (_, index) => _row(
                    rows[index].entry,
                    offline: true,
                    playbackEntries: context,
                    index: context == null ? null : index,
                  ),
                );
              },
            ),
          ),
        if (!catalog && widget.destination != 'downloads')
          const Padding(
            padding: EdgeInsets.only(top: 24),
            child: Text(
              'Library management and account operations are not yet implemented. Existing YouTube Music sessions can be verified from Accounts for catalog requests.',
            ),
          ),
      ],
    );
  }
}

class OnlineTransport extends ConsumerStatefulWidget {
  const OnlineTransport({
    super.key,
    required this.empty,
    required this.palette,
    required this.reduceBlur,
  });
  final Widget empty;
  final BitChordPalette palette;
  final bool reduceBlur;

  @override
  ConsumerState<OnlineTransport> createState() => _OnlineTransportState();
}

class _OnlineTransportState extends ConsumerState<OnlineTransport> {
  double? _scrubPreview;
  int? _scrubSelection, _scrubRevision;
  String? _scrubEntry;

  bool _isCurrentScrub(PlaybackSnapshot snapshot) =>
      _scrubPreview != null &&
      _scrubEntry == snapshot.entryId &&
      _scrubSelection == snapshot.selection &&
      _scrubRevision == snapshot.queueRevision;

  @override
  Widget build(BuildContext context) {
    final snapshot = ref.watch(transportProvider);
    if (snapshot == null ||
        snapshot.entryId.isEmpty ||
        snapshot.phase == AudioPhase.idle) {
      return widget.empty;
    }
    final port = ref.read(onlinePortProvider);
    final playback = ref.watch(catalogPlaybackProvider);
    final contextPlayback = ref.read(catalogPlaybackProvider.notifier);
    final displayUs = _isCurrentScrub(snapshot)
        ? _scrubPreview!
        : snapshot.positionUs.toDouble();
    final position = displayUs ~/ 1000000;
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 760),
      child: GlassSurface(
        palette: widget.palette,
        reduceBlur: widget.reduceBlur,
        child: SizedBox(
          height: 58,
          child: Row(
            children: [
              const SizedBox(width: 12),
              if (playback.entries.length > 1)
                IconButton(
                  tooltip: 'Previous',
                  onPressed: playback.hasPrevious
                      ? () => contextPlayback.previous()
                      : null,
                  icon: const Icon(Icons.skip_previous),
                ),
              IconButton(
                tooltip: snapshot.phase == AudioPhase.playing
                    ? 'Pause'
                    : 'Play',
                onPressed: () async {
                  try {
                    await ref.read(transportProvider.notifier).toggle();
                  } catch (_) {}
                },
                icon: Icon(
                  snapshot.phase == AudioPhase.playing
                      ? Icons.pause
                      : Icons.play_arrow,
                ),
              ),
              const SizedBox(width: 8),
              if (playback.entries.length > 1)
                IconButton(
                  tooltip: 'Next',
                  onPressed: playback.hasNext
                      ? () => contextPlayback.next()
                      : null,
                  icon: const Icon(Icons.skip_next),
                ),
              Expanded(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      port?.currentTitle ?? '',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    Text(
                      playback.error ??
                          (snapshot.phase == AudioPhase.failed
                              ? 'Playback failed (${snapshot.error})'
                              : '${snapshot.phase.name} · ${position ~/ 60}:${(position % 60).toString().padLeft(2, '0')} · ${port?.currentSubtitle ?? ''}'),
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              if (snapshot.durationUs > 0)
                SizedBox(
                  width: 110,
                  child: Slider(
                    value: displayUs.clamp(0, snapshot.durationUs).toDouble(),
                    max: snapshot.durationUs.toDouble(),
                    // Dragging is only a visual preview. Restarting FFmpeg for
                    // every pointer movement can exhaust live broker requests
                    // and fail playback, especially immediately after play.
                    onChangeStart: (value) => setState(() {
                      _scrubPreview = value;
                      _scrubSelection = snapshot.selection;
                      _scrubRevision = snapshot.queueRevision;
                      _scrubEntry = snapshot.entryId;
                    }),
                    onChanged: (value) {
                      if (_isCurrentScrub(snapshot)) {
                        setState(() => _scrubPreview = value);
                      }
                    },
                    onChangeEnd: (value) async {
                      final latest = ref.read(transportProvider);
                      final valid =
                          latest != null &&
                          _isCurrentScrub(latest) &&
                          latest.phase != AudioPhase.failed &&
                          latest.durationUs > 0;
                      if (!mounted) return;
                      setState(() {
                        _scrubPreview = null;
                        _scrubSelection = null;
                        _scrubRevision = null;
                        _scrubEntry = null;
                      });
                      if (!valid) return;
                      // The nominal end can be beyond the last seekable audio
                      // packet. Leave a small tail rather than seeking to EOF.
                      final maxSeek = latest.durationUs > 250000
                          ? latest.durationUs - 250000
                          : latest.durationUs - 1;
                      try {
                        await port?.seek(
                          value.round().clamp(0, maxSeek).toInt(),
                        );
                      } catch (_) {}
                    },
                  ),
                ),
              const SizedBox(width: 8),
            ],
          ),
        ),
      ),
    );
  }
}
