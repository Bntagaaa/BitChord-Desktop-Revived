import 'package:flutter/material.dart';
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
  String? _progress;
  Future<List<DownloadedEntry>>? _downloads;
  @override
  void initState() {
    super.initState();
    Future.microtask(() {
      if (!mounted) return;
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
            _ => null,
          };
      if (browse != null)
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

  Future<void> _play(CatalogEntry entry, {bool offline = false}) async {
    _message('Opening audio…');
    _message(
      await ref.read(catalogProvider.notifier).play(entry, offline: offline),
    );
  }

  Future<void> _download(CatalogEntry entry) async {
    setState(() => _progress = 'Starting download…');
    final error = await ref.read(catalogProvider.notifier).download(entry, (
      done,
      total,
    ) {
      if (mounted)
        setState(
          () => _progress = 'Downloading ${(done * 100 / total).floor()}%',
        );
    });
    if (mounted)
      setState(() {
        _progress = null;
        _notice = error ?? 'Download verified and saved.';
      });
  }

  void _select(CatalogEntry entry) {
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

  Widget _row(CatalogEntry entry, {bool offline = false}) {
    final playable = offline || entry.provider != 'youtube' || _youtubeAudio;
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
                        ? () => _play(entry, offline: offline)
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
              onPressed: () => _play(entry, offline: offline),
              icon: const Icon(Icons.play_arrow, size: 20),
            ),
          if (entry.kind == CatalogKind.track && !offline && playable)
            IconButton(
              tooltip: 'Download',
              onPressed: _progress == null ? () => _download(entry) : null,
              icon: const Icon(Icons.download_outlined, size: 20),
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
    final controller = ref.read(catalogProvider.notifier);
    final search = widget.destination == 'search';
    final catalog =
        widget.browseId != null ||
        ['listen-now', 'new', 'explore', 'search'].contains(widget.destination);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
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
        if (catalog)
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
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _query,
                  decoration: const InputDecoration(
                    hintText: 'Search songs, albums, artists and playlists',
                  ),
                  onSubmitted: (query) {
                    if (query.trim().isNotEmpty)
                      controller.load(
                        provider: _source,
                        query: query,
                        filter: _filter,
                      );
                  },
                ),
              ),
              const SizedBox(width: 8),
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
                      _source = value;
                      _filter = SearchFilter.all;
                    });
                },
              ),
              IconButton(
                tooltip: 'Search',
                onPressed: () {
                  if (_query.text.trim().isNotEmpty)
                    controller.load(
                      provider: _source,
                      query: _query.text,
                      filter: _filter,
                    );
                },
                icon: const Icon(Icons.search),
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
        if (_progress != null)
          Row(
            children: [
              Expanded(child: Text(_progress!)),
              TextButton(
                onPressed: controller.cancelDownload,
                child: const Text('Cancel'),
              ),
            ],
          ),
        if (catalog && state.loading)
          const LinearProgressIndicator(minHeight: 2),
        if (catalog && state.error != null)
          Row(
            children: [
              Expanded(child: Text(state.error!)),
              TextButton(
                onPressed: () => controller.load(
                  provider: state.provider,
                  query: state.query,
                  browseId: state.browseId,
                  params: state.params,
                  filter: state.filter,
                ),
                child: const Text('Retry'),
              ),
            ],
          ),
        if (catalog)
          Expanded(
            child:
                state.entries.isEmpty && !state.loading && state.error == null
                ? Center(
                    child: Text(
                      search
                          ? 'Search for music'
                          : 'No catalog items available.',
                    ),
                  )
                : ListView(
                    children: [
                      if (state.header != null)
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
                      if (state.shelves.isNotEmpty && !search)
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
                        for (final entry in state.entries) _row(entry),
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
                return ListView(
                  children: [
                    for (final row in snapshot.data!)
                      _row(row.entry, offline: true),
                  ],
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

class OnlineTransport extends ConsumerWidget {
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
  Widget build(BuildContext context, WidgetRef ref) {
    final snapshot = ref.watch(transportProvider);
    if (snapshot == null ||
        snapshot.entryId.isEmpty ||
        snapshot.phase == AudioPhase.idle)
      return empty;
    final port = ref.read(onlinePortProvider);
    final position = snapshot.positionUs ~/ 1000000;
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 760),
      child: GlassSurface(
        palette: palette,
        reduceBlur: reduceBlur,
        child: SizedBox(
          height: 58,
          child: Row(
            children: [
              const SizedBox(width: 12),
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
                      snapshot.phase == AudioPhase.failed
                          ? 'Playback failed (${snapshot.error})'
                          : '${snapshot.phase.name} · ${position ~/ 60}:${(position % 60).toString().padLeft(2, '0')} · ${port?.currentSubtitle ?? ''}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              if (snapshot.durationUs > 0)
                SizedBox(
                  width: 110,
                  child: Slider(
                    value: snapshot.positionUs
                        .clamp(0, snapshot.durationUs)
                        .toDouble(),
                    max: snapshot.durationUs.toDouble(),
                    onChanged: (v) async {
                      try {
                        await port?.seek(v.round());
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
