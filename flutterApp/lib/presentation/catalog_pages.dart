import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../application/app_controller.dart';
import '../application/download_service.dart';
import '../domain/models.dart';
import 'desktop_theme.dart';
import 'menus.dart';
import 'settings_page.dart';
import 'widgets.dart';

class CatalogPages extends StatefulWidget {
  const CatalogPages({super.key, required this.app});
  final AppController app;
  @override
  State<CatalogPages> createState() => _CatalogPagesState();
}

class _CatalogPagesState extends State<CatalogPages> {
  final search = TextEditingController();
  final collectionFilter = TextEditingController();
  SearchFilter filter = SearchFilter.all;
  bool findInCollection = false;
  bool pendingFind = false;
  bool sourcesSearch = false;
  String lastQuery = '';
  String? lastCollection;
  AppController get app => widget.app;
  @override
  void initState() {
    super.initState();
    search.text = app.query;
    lastQuery = app.query;
    filter = app.searchFilter;
  }

  @override
  void didUpdateWidget(covariant CatalogPages oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (lastQuery != app.query) {
      lastQuery = app.query;
      search.text = app.query;
      filter = app.searchFilter;
      sourcesSearch = false;
    }
  }

  @override
  void dispose() {
    search.dispose();
    collectionFilter.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    switch (app.route) {
      case AppRoute.home:
        return feedPage(
          'Home',
          app.homeFeed,
          recent: app.history.take(12).toList(),
        );
      case AppRoute.explore:
        return feedPage('New', app.exploreFeed);
      case AppRoute.search:
        return searchPage();
      case AppRoute.library:
        return libraryPage();
      case AppRoute.localMusic:
        return songCollection(
          'Local Music',
          app.localSongs,
          icon: Icons.library_music,
          emptyTitle: 'Your music, all together',
          emptyMessage:
              'Add MP3, FLAC, M4A, OGG, WAV or other supported audio files.',
          action: 'Add music',
          onAction: app.importLocalMusic,
        );
      case AppRoute.downloads:
        return downloadPage();
      case AppRoute.history:
        return songCollection(
          'History',
          app.history,
          icon: Icons.history,
          emptyTitle: 'Your listening history',
          emptyMessage: 'The music you play will appear here.',
        );
      case AppRoute.liked:
        return songCollection(
          'Liked Music',
          app.likedSongs,
          icon: Icons.thumb_up_rounded,
          hero: true,
          emptyTitle: 'Keep your favourites close',
          emptyMessage: 'Use the star beside a song to add it to Liked Music.',
        );
      case AppRoute.spotify:
        return spotifyPage();
      case AppRoute.settings:
        return SettingsPage(app: app);
      case AppRoute.detail:
        return detailPage();
    }
  }

  Widget pageScroll(String key, List<Widget> children) => ListView(
    key: PageStorageKey(key),
    padding: const EdgeInsets.fromLTRB(28, 22, 28, 108),
    children: children,
  );
  Widget title(String text, {List<Widget> actions = const []}) => Row(
    children: [
      Expanded(
        child: Text(text, style: Theme.of(context).textTheme.headlineLarge),
      ),
      ...actions,
    ],
  );

  Widget feedPage(String name, HomeFeed? feed, {List<Song> recent = const []}) {
    if ((feed?.shelves.isEmpty ?? true) && app.busy) {
      return const Center(child: CircularProgressIndicator(strokeWidth: 2));
    }
    if (feed == null || feed.shelves.isEmpty) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(28, 22, 28, 0),
            child: title(name),
          ),
          Expanded(
            child: EmptyState(
              title: 'Music is a click away',
              message:
                  app.error ??
                  'Connect to the internet to load your music recommendations.',
              icon: Icons.wifi_rounded,
              action: 'Try again',
              onAction: app.refreshCurrent,
            ),
          ),
        ],
      );
    }
    final shelves = name == 'Home'
        ? feed.shelves
              .map((section) {
                final normalized = section.title.toLowerCase();
                if (app.setting('content_hide_recents', false) &&
                    (normalized == 'recents' ||
                        normalized == 'recently played')) {
                  return null;
                }
                if (app.setting('content_hide_top_picks', false) &&
                    normalized.contains('top picks')) {
                  return null;
                }
                if (app.setting('content_hide_home_moods', false) &&
                    normalized.contains('mood')) {
                  return null;
                }
                if (!app.setting('content_hide_home_videos', false)) {
                  return section;
                }
                if (section.layout == ShelfLayout.video) return null;
                final kept = section.items
                    .where((item) => !item.isVideo)
                    .toList();
                if (kept.isEmpty) return null;
                return HomeShelf(
                  title: section.title,
                  items: kept,
                  subtitle: section.subtitle,
                  moreBrowseId: section.moreBrowseId,
                  moreParams: section.moreParams,
                  layout: section.layout,
                );
              })
              .whereType<HomeShelf>()
              .toList()
        : feed.shelves;
    return pageScroll(name, [
      title(
        name,
        actions: [
          ToolButton(
            icon: Icons.refresh,
            label: 'Refresh',
            onPressed: app.refreshCurrent,
          ),
        ],
      ),
      for (var i = 0; i < shelves.length; i++) ...[
        shelf(shelves[i], hero: name == 'Home' && i == 0),
        if (i == 0 &&
            recent.isNotEmpty &&
            !app.setting('content_hide_recents', false)) ...[
          const SectionTitle('Recents'),
          compactSongs(recent),
        ],
      ],
      if (name == 'Home' && feed.continuation != null)
        Padding(
          padding: const EdgeInsets.only(top: 24),
          child: Center(
            child: TextButton(
              onPressed: app.busy ? null : app.loadMoreHome,
              child: const Text('Load more'),
            ),
          ),
        ),
    ]);
  }

  Widget shelf(HomeShelf shelf, {bool hero = false}) {
    if (shelf.items.isEmpty) return const SizedBox.shrink();
    final songs = shelf.items.map((e) => e.song).whereType<Song>().toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionTitle(
          shelf.title,
          subtitle: shelf.subtitle,
          onAction: shelf.moreBrowseId == null
              ? null
              : () => app.openBrowse(
                  BrowseItem(browseId: shelf.moreBrowseId!, title: shelf.title),
                ),
        ),
        if (shelf.layout == ShelfLayout.list &&
            songs.length == shelf.items.length)
          compactSongs(songs)
        else
          SizedBox(
            height: hero
                ? 268
                : shelf.layout == ShelfLayout.video &&
                      !app.setting('content_square_video_thumbnails', false)
                ? 202
                : 224,
            child: ScrollConfiguration(
              behavior: const _HorizontalDragBehavior(),
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: shelf.items.length,
                separatorBuilder: (_, _) => const SizedBox(width: 14),
                itemBuilder: (context, index) => shelfCard(
                  shelf.items[index],
                  width: hero
                      ? 204
                      : shelf.layout == ShelfLayout.video &&
                            !app.setting(
                              'content_square_video_thumbnails',
                              false,
                            )
                      ? 248
                      : 166,
                  height: hero
                      ? 266
                      : shelf.layout == ShelfLayout.video &&
                            !app.setting(
                              'content_square_video_thumbnails',
                              false,
                            )
                      ? 202
                      : 224,
                  hero: hero,
                  songContext: songs,
                  source: shelf.title,
                ),
              ),
            ),
          ),
      ],
    );
  }

  void openShelfItem(ShelfItem item, List<Song> songs, String source) {
    final song = item.song;
    if (song != null) {
      app.playSong(song, context: songs, source: source);
    } else if (item.browseId != null) {
      app.openBrowse(
        BrowseItem(
          browseId: item.browseId!,
          title: item.title,
          subtitle: item.subtitle,
          thumbnailUrl: item.thumbnailUrl,
          type: item.type,
        ),
      );
    }
  }

  Widget shelfCard(
    ShelfItem item, {
    required double width,
    required double height,
    bool hero = false,
    List<Song> songContext = const [],
    required String source,
  }) {
    final p = DesktopPalette.of(context);
    return SizedBox(
      width: width,
      height: height,
      child: HoverTile(
        onTap: () => openShelfItem(item, songContext, source),
        onSecondaryTapDown: item.song == null
            ? null
            : (_) => showSongMenu(context, app, item.song!),
        child: hero
            ? ClipRRect(
                borderRadius: BorderRadius.circular(10),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    Artwork(
                      item.thumbnailUrl,
                      width: width,
                      height: height,
                      radius: 0,
                    ),
                    Align(
                      alignment: Alignment.bottomCenter,
                      child: GlassSurface(
                        radius: 0,
                        blur: !app.setting('reduce_dynamic_blur', false),
                        color: Colors.black.withAlpha(60),
                        padding: const EdgeInsets.all(14),
                        child: SizedBox(
                          width: double.infinity,
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                item.title,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontWeight: FontWeight.w600,
                                  fontSize: 16,
                                ),
                              ),
                              if (item.subtitle.isNotEmpty)
                                Padding(
                                  padding: const EdgeInsets.only(top: 4),
                                  child: Text(
                                    item.subtitle,
                                    maxLines: 2,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                      color: Colors.white70,
                                      fontSize: 11,
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ),
                      ),
                    ),
                    const Positioned(
                      right: 10,
                      top: 10,
                      child: Icon(
                        Icons.graphic_eq_rounded,
                        color: Colors.white,
                        size: 20,
                      ),
                    ),
                  ],
                ),
              )
            : Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Artwork(
                    item.thumbnailUrl,
                    width: width,
                    height:
                        item.isVideo &&
                            !app.setting(
                              'content_square_video_thumbnails',
                              false,
                            )
                        ? width * 9 / 16
                        : width,
                    radius: item.type == BrowseType.artist ? width / 2 : 8,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    item.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontWeight: FontWeight.w600,
                      fontSize: 13,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    item.subtitle,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 11, color: p.secondary),
                  ),
                ],
              ),
      ),
    );
  }

  Widget compactSongs(List<Song> songs) => LayoutBuilder(
    builder: (context, constraints) {
      final columns = constraints.maxWidth > 940
          ? 3
          : constraints.maxWidth > 620
          ? 2
          : 1;
      final rows = math.min(4, (songs.length / columns).ceil());
      return SizedBox(
        height: rows * 52.0,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          itemCount: (songs.length / rows).ceil(),
          separatorBuilder: (_, _) => const SizedBox(width: 14),
          itemBuilder: (_, column) => SizedBox(
            width: (constraints.maxWidth - (columns - 1) * 14) / columns,
            child: Column(
              children: [
                for (var row = 0; row < rows; row++)
                  if (column * rows + row < songs.length)
                    Builder(
                      builder: (context) {
                        final song = songs[column * rows + row];
                        return SizedBox(
                          height: 52,
                          child: SongRow(
                            song: song,
                            compact: true,
                            playing:
                                app.playback.currentSong?.videoId ==
                                song.videoId,
                            onPlay: () => app.playSong(song, context: songs),
                            onMenu: () => showSongMenu(context, app, song),
                          ),
                        );
                      },
                    ),
              ],
            ),
          ),
        ),
      );
    },
  );

  void submitSearch(String value) {
    lastQuery = value.trim();
    if (sourcesSearch) {
      app.searchSources(value);
    } else {
      app.search(value, filter: filter);
    }
  }

  Widget searchPage() {
    final page = app.searchPage;
    return pageScroll('search', [
      title('Search'),
      const SizedBox(height: 22),
      TextField(
        controller: search,
        onSubmitted: submitSearch,
        decoration: InputDecoration(
          hintText: 'Songs, artists, albums, playlists',
          prefixIcon: const Icon(Icons.search),
          suffixIcon: IconButton(
            tooltip: 'Search',
            onPressed: () => submitSearch(search.text),
            icon: const Icon(Icons.arrow_forward_rounded),
          ),
        ),
      ),
      const SizedBox(height: 14),
      Wrap(
        spacing: 8,
        runSpacing: 6,
        children: SearchFilter.values
            .map(
              (value) => ChoiceChip(
                label: Text(value.label),
                selected: !sourcesSearch && filter == value,
                onSelected: (_) {
                  setState(() {
                    filter = value;
                    sourcesSearch = false;
                  });
                  if (search.text.trim().isNotEmpty) {
                    app.search(search.text, filter: value);
                  }
                },
              ),
            )
            .toList(),
      ),
      if (app.addonSources.any((source) => source.enabled))
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Align(
            alignment: Alignment.centerLeft,
            child: ChoiceChip(
              label: const Text('Add-on sources'),
              selected: sourcesSearch,
              onSelected: (_) {
                setState(() => sourcesSearch = true);
                submitSearch(search.text);
              },
            ),
          ),
        ),
      if (app.busy)
        const Padding(
          padding: EdgeInsets.all(32),
          child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      if (!app.busy && page.results.isEmpty)
        EmptyState(
          title: app.query.isEmpty ? 'Find your next favourite' : 'No results',
          message: app.query.isEmpty
              ? 'Search the YouTube Music catalogue for a song, artist, album or playlist.'
              : 'Try a different title, artist or search filter.',
          icon: Icons.search,
        ),
      ...[
        if (page.results.isNotEmpty) SectionTitle('Results for “${app.query}”'),
        for (final result in page.results) searchResult(result, page.songs),
        if (page.continuation != null)
          Padding(
            padding: const EdgeInsets.only(top: 18),
            child: Center(
              child: TextButton(
                onPressed: app.busy ? null : app.loadMoreSearch,
                child: const Text('Load more'),
              ),
            ),
          ),
      ],
    ]);
  }

  Widget searchResult(SearchResult result, List<Song> songs) {
    final song = result.song;
    if (song != null) {
      return SongRow(
        song: song,
        playing: app.playback.currentSong?.videoId == song.videoId,
        liked: app.isLiked(song),
        onLike: () => app.toggleLike(song),
        onDownload: () => app.download(song),
        onPlay: () => app.playSong(song, context: songs, source: 'Search'),
        onMenu: () => showSongMenu(context, app, song),
      );
    }
    return HoverTile(
      onTap: () => app.openBrowse(result.item!),
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 7),
      child: Row(
        children: [
          Artwork(
            result.thumbnailUrl,
            size: 44,
            radius: result.item!.type == BrowseType.artist ? 22 : 7,
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  result.title,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                Text(
                  result.subtitle,
                  style: TextStyle(
                    fontSize: 12,
                    color: DesktopPalette.of(context).secondary,
                  ),
                ),
              ],
            ),
          ),
          Text(
            result.item!.type.name,
            style: TextStyle(
              fontSize: 11,
              color: DesktopPalette.of(context).secondary,
            ),
          ),
          const SizedBox(width: 10),
          const Icon(Icons.chevron_right, size: 18),
        ],
      ),
    );
  }

  Widget libraryPage() {
    final p = DesktopPalette.of(context);
    return pageScroll('library', [
      title(
        'Library',
        actions: [
          TextButton.icon(
            onPressed: () => newPlaylist(context, app),
            icon: const Icon(Icons.add, size: 18),
            label: const Text('New playlist'),
          ),
        ],
      ),
      const SizedBox(height: 22),
      LayoutBuilder(
        builder: (context, constraints) => Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            for (final metric in [
              ('Songs played', '${app.history.length}', Icons.history),
              ('Liked Music', '${app.likedSongs.length}', Icons.star_rounded),
              ('Local Music', '${app.localSongs.length}', Icons.library_music),
              ('Downloaded', '${app.downloads.length}', Icons.download_done),
            ])
              Container(
                width: math.max(150, (constraints.maxWidth - 36) / 4),
                padding: const EdgeInsets.all(18),
                decoration: BoxDecoration(
                  color: p.raised,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: p.edge),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            metric.$1,
                            style: TextStyle(fontSize: 11, color: p.secondary),
                          ),
                        ),
                        Icon(metric.$3, size: 17, color: p.secondary),
                      ],
                    ),
                    const SizedBox(height: 16),
                    Text(
                      metric.$2,
                      style: Theme.of(context).textTheme.headlineMedium,
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
      const SizedBox(height: 22),
      for (final entry in [
        ('History', Icons.history, AppRoute.history),
        ('Downloads', Icons.download_outlined, AppRoute.downloads),
        ('Local Music', Icons.library_music_outlined, AppRoute.localMusic),
        ('Spotify', Icons.album_outlined, AppRoute.spotify),
      ])
        HoverTile(
          onTap: () => app.navigate(entry.$3),
          padding: const EdgeInsets.symmetric(vertical: 15),
          child: Row(
            children: [
              Icon(entry.$2, size: 20),
              const SizedBox(width: 14),
              Expanded(child: Text(entry.$1)),
              const Icon(Icons.chevron_right, size: 18),
            ],
          ),
        ),
      for (final remoteShelf in app.libraryPage.shelves) shelf(remoteShelf),
      const SectionTitle('Playlists'),
      Wrap(
        spacing: 20,
        runSpacing: 22,
        children: [
          SizedBox(
            width: 166,
            child: HoverTile(
              onTap: () => app.navigate(AppRoute.liked),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  likedArtwork(166),
                  const SizedBox(height: 8),
                  const Text(
                    'Liked Music',
                    style: TextStyle(fontWeight: FontWeight.w600),
                  ),
                  Text(
                    '${app.likedSongs.length} songs',
                    style: TextStyle(fontSize: 11, color: p.secondary),
                  ),
                ],
              ),
            ),
          ),
          for (final playlist in app.localPlaylists)
            SizedBox(
              width: 166,
              child: HoverTile(
                onTap: () => app.openPlaylist(playlist),
                onSecondaryTapDown: (_) => showCollectionMenu(
                  context,
                  app,
                  songs: playlist.songs,
                  title: playlist.title,
                  playlistId: playlist.id,
                  onFind: () {
                    pendingFind = true;
                    app.openPlaylist(playlist);
                    setState(() => findInCollection = true);
                  },
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Artwork(
                      playlist.songs.firstOrNull?.thumbnailUrl,
                      size: 166,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      playlist.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w600),
                    ),
                    Text(
                      '${playlist.songs.length} songs',
                      style: TextStyle(fontSize: 11, color: p.secondary),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    ]);
  }

  Widget likedArtwork(double size) => Container(
    width: size,
    height: size,
    decoration: BoxDecoration(
      borderRadius: BorderRadius.circular(8),
      gradient: const LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [Color(0xFF8C6BEC), Color(0xFFEC53BF)],
      ),
    ),
    child: Icon(Icons.thumb_up_rounded, size: size * .46, color: Colors.white),
  );

  Widget songCollection(
    String name,
    List<Song> songs, {
    required IconData icon,
    bool hero = false,
    String? artwork,
    String? subtitle,
    String? playlistId,
    String? cloudPlaylistId,
    String? browseId,
    required String emptyTitle,
    required String emptyMessage,
    List<Widget> leading = const [],
    String? action,
    VoidCallback? onAction,
  }) {
    final p = DesktopPalette.of(context);
    final identity = playlistId ?? browseId ?? name;
    if (lastCollection != identity) {
      lastCollection = identity;
      collectionFilter.clear();
      findInCollection = pendingFind;
      pendingFind = false;
    }
    final term = collectionFilter.text.toLowerCase();
    final filtered = term.isEmpty
        ? songs
        : songs
              .where(
                (s) => '${s.title} ${s.artist} ${s.albumName}'
                    .toLowerCase()
                    .contains(term),
              )
              .toList();
    return CustomScrollView(
      key: PageStorageKey(identity),
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(28, 24, 28, 16),
          sliver: SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                ...leading,
                if (hero)
                  LayoutBuilder(
                    builder: (context, constraints) {
                      final size = constraints.maxWidth < 550 ? 130.0 : 220.0;
                      return Row(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          name == 'Liked Music'
                              ? likedArtwork(size)
                              : Artwork(artwork, size: size, radius: 9),
                          const SizedBox(width: 26),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  playlistId == null
                                      ? 'COLLECTION'
                                      : 'PLAYLIST',
                                  style: TextStyle(
                                    fontSize: 10,
                                    letterSpacing: 1,
                                    color: p.secondary,
                                  ),
                                ),
                                const SizedBox(height: 10),
                                Text(
                                  name,
                                  style: Theme.of(
                                    context,
                                  ).textTheme.headlineLarge,
                                  maxLines: 3,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                const SizedBox(height: 7),
                                if (subtitle?.isNotEmpty ?? false)
                                  Text(
                                    subtitle!,
                                    style: TextStyle(color: p.secondary),
                                  ),
                                Text(
                                  '${songs.length} songs · ${formatDuration(songs.fold(Duration.zero, (sum, song) => sum + song.duration))}',
                                  style: TextStyle(
                                    fontSize: 11,
                                    color: p.secondary,
                                  ),
                                ),
                                const SizedBox(height: 18),
                                collectionActions(
                                  name,
                                  songs,
                                  playlistId: playlistId,
                                  cloudPlaylistId: cloudPlaylistId,
                                  browseId: browseId,
                                ),
                              ],
                            ),
                          ),
                        ],
                      );
                    },
                  )
                else ...[
                  title(
                    name,
                    actions: [
                      if (onAction != null)
                        TextButton.icon(
                          onPressed: onAction,
                          icon: const Icon(Icons.add, size: 18),
                          label: Text(action!),
                        ),
                    ],
                  ),
                  if (songs.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 14),
                      child: collectionActions(
                        name,
                        songs,
                        playlistId: playlistId,
                        cloudPlaylistId: cloudPlaylistId,
                        browseId: browseId,
                      ),
                    ),
                ],
                if (findInCollection)
                  Padding(
                    padding: const EdgeInsets.only(top: 18),
                    child: TextField(
                      controller: collectionFilter,
                      autofocus: true,
                      onChanged: (_) => setState(() {}),
                      decoration: InputDecoration(
                        hintText: 'Find in $name',
                        prefixIcon: const Icon(Icons.search),
                        suffixIcon: IconButton(
                          icon: const Icon(Icons.close),
                          onPressed: () => setState(() {
                            collectionFilter.clear();
                            findInCollection = false;
                          }),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
        if (songs.isEmpty)
          SliverFillRemaining(
            hasScrollBody: false,
            child: EmptyState(
              title: emptyTitle,
              message: emptyMessage,
              icon: icon,
              action: action,
              onAction: onAction,
            ),
          )
        else
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 110),
            sliver: SliverList(
              delegate: SliverChildBuilderDelegate((context, index) {
                final song = filtered[index];
                return ColoredBox(
                  color: index.isOdd ? p.stripedRow : Colors.transparent,
                  child: LayoutBuilder(
                    builder: (context, constraints) => SongRow(
                      song: song,
                      index: index,
                      showAlbum: constraints.maxWidth > 700,
                      playing:
                          app.playback.currentSong?.videoId == song.videoId,
                      liked: app.isLiked(song),
                      onLike: () => app.toggleLike(song),
                      onDownload: song.isLocal
                          ? null
                          : () => app.download(song),
                      onPlay: () =>
                          app.playSong(song, context: filtered, source: name),
                      onMenu: () => showSongMenu(
                        context,
                        app,
                        song,
                        playlistId: playlistId,
                        cloudPlaylistId: cloudPlaylistId,
                      ),
                    ),
                  ),
                );
              }, childCount: filtered.length),
            ),
          ),
        if (browseId != null && app.detailPage?.continuation != null)
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 110),
              child: Center(
                child: TextButton(
                  onPressed: app.busy ? null : app.loadMoreDetail,
                  child: const Text('Load more'),
                ),
              ),
            ),
          ),
      ],
    );
  }

  Widget collectionActions(
    String title,
    List<Song> songs, {
    String? playlistId,
    String? cloudPlaylistId,
    String? browseId,
  }) => Row(
    children: [
      ToolButton(
        icon: Icons.shuffle,
        label: 'Shuffle',
        size: 42,
        onPressed: songs.isEmpty
            ? null
            : () {
                app.playback.setShuffle(true);
                app.playSongs(
                  songs,
                  index: math.Random().nextInt(songs.length),
                  source: title,
                );
              },
      ),
      const SizedBox(width: 8),
      SizedBox(
        width: 44,
        height: 44,
        child: FilledButton(
          onPressed: songs.isEmpty
              ? null
              : () => app.playSongs(songs, source: title),
          style: FilledButton.styleFrom(
            padding: EdgeInsets.zero,
            shape: const CircleBorder(),
          ),
          child: const Icon(Icons.play_arrow_rounded, size: 28),
        ),
      ),
      const SizedBox(width: 8),
      ToolButton(
        icon: Icons.search,
        label: 'Find in playlist',
        onPressed: () => setState(() => findInCollection = !findInCollection),
        size: 42,
      ),
      ToolButton(
        icon: Icons.more_horiz,
        label: 'Playlist actions',
        size: 42,
        onPressed: () => showCollectionMenu(
          context,
          app,
          songs: songs,
          title: title,
          playlistId: playlistId,
          cloudPlaylistId: cloudPlaylistId,
          browseId: browseId,
          onFind: () => setState(() => findInCollection = true),
        ),
      ),
    ],
  );

  Widget detailPage() {
    final detail = app.detailPage;
    if (detail == null) {
      return app.busy
          ? const Center(child: CircularProgressIndicator(strokeWidth: 2))
          : EmptyState(
              title: 'Unable to open collection',
              message: app.error ?? 'Please try again.',
              action: 'Try again',
              onAction: app.refreshCurrent,
            );
    }
    final local = app.localPlaylists
        .where(
          (p) =>
              p.id == detail.playlistId || 'local:${p.id}' == detail.browseId,
        )
        .firstOrNull;
    final cloudId = local == null && detail.isOwned ? detail.playlistId : null;
    if (detail.type != BrowseType.artist && detail.shelves.isEmpty) {
      return songCollection(
        detail.title,
        detail.songs,
        icon: Icons.album_outlined,
        hero: true,
        artwork: detail.thumbnailUrl,
        subtitle: detail.subtitle,
        playlistId: local?.id,
        cloudPlaylistId: cloudId,
        browseId: local == null ? detail.browseId : null,
        emptyTitle: 'This collection is empty',
        emptyMessage: 'Add songs from a song menu to build your playlist.',
      );
    }
    return pageScroll(detail.browseId, [
      Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Artwork(
            detail.thumbnailUrl,
            size: 180,
            radius: detail.type == BrowseType.artist ? 90 : 10,
          ),
          const SizedBox(width: 26),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  detail.type.name.toUpperCase(),
                  style: TextStyle(
                    fontSize: 11,
                    color: DesktopPalette.of(context).secondary,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  detail.title,
                  style: Theme.of(context).textTheme.headlineLarge,
                ),
                const SizedBox(height: 8),
                Text(detail.subtitle),
                const SizedBox(height: 16),
                collectionActions(
                  detail.title,
                  detail.songs,
                  browseId: detail.browseId,
                  cloudPlaylistId: cloudId,
                ),
              ],
            ),
          ),
        ],
      ),
      if (detail.description != null)
        Padding(
          padding: const EdgeInsets.only(top: 20),
          child: SelectableText(
            detail.description!,
            style: TextStyle(
              color: DesktopPalette.of(context).secondary,
              height: 1.5,
            ),
          ),
        ),
      if (detail.songs.isNotEmpty) ...[
        const SectionTitle('Songs'),
        for (final song in detail.songs)
          SongRow(
            song: song,
            onPlay: () =>
                app.playSong(song, context: detail.songs, source: detail.title),
            onMenu: () =>
                showSongMenu(context, app, song, cloudPlaylistId: cloudId),
            onLike: () => app.toggleLike(song),
            liked: app.isLiked(song),
          ),
      ],
      for (final section in detail.shelves) shelf(section),
      if (detail.continuation != null)
        Center(
          child: TextButton(
            onPressed: app.busy ? null : app.loadMoreDetail,
            child: const Text('Load more'),
          ),
        ),
    ]);
  }

  Widget downloadPage() {
    final tasks = app.downloadTasks
        .where((task) => task.status != DownloadStatus.complete)
        .toList();
    return songCollection(
      'Downloads',
      app.downloads,
      icon: Icons.download_done,
      emptyTitle: tasks.isEmpty
          ? 'Your offline collection'
          : 'Downloads are on their way',
      emptyMessage: tasks.isEmpty
          ? 'Download songs from their menu to keep them available offline.'
          : 'Completed downloads will appear below.',
      leading: tasks.isEmpty
          ? const []
          : [
              const SectionTitle('Transfers'),
              for (final task in tasks)
                Padding(
                  padding: const EdgeInsets.only(bottom: 14),
                  child: Row(
                    children: [
                      Artwork(task.song.thumbnailUrl, size: 42),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              task.song.title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            const SizedBox(height: 6),
                            if (task.status == DownloadStatus.downloading ||
                                task.status == DownloadStatus.resolving ||
                                task.status == DownloadStatus.queued)
                              LinearProgressIndicator(value: task.progress),
                            const SizedBox(height: 5),
                            Text(
                              task.error ??
                                  '${task.status.name} · ${(task.received / 1024 / 1024).toStringAsFixed(1)} MB',
                              style: TextStyle(
                                color: DesktopPalette.of(context).secondary,
                                fontSize: 11,
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 8),
                      if (task.status == DownloadStatus.failed ||
                          task.status == DownloadStatus.cancelled)
                        ToolButton(
                          icon: Icons.refresh,
                          label: 'Retry download',
                          onPressed: () => app.download(task.song),
                        )
                      else
                        ToolButton(
                          icon: Icons.close,
                          label: 'Cancel download',
                          onPressed: () =>
                              app.cancelDownload(task.song.videoId),
                        ),
                    ],
                  ),
                ),
              const SizedBox(height: 14),
            ],
    );
  }

  Widget spotifyPage() => pageScroll('spotify', [
    title(
      'Spotify',
      actions: [
        TextButton.icon(
          onPressed: () => importSpotifyDialog(context, app),
          icon: const Icon(Icons.add),
          label: const Text('Import playlist'),
        ),
      ],
    ),
    const SizedBox(height: 24),
    GlassSurface(
      blur: !app.setting('reduce_dynamic_blur', false),
      padding: const EdgeInsets.all(24),
      child: Row(
        children: [
          const Icon(Icons.library_music_rounded, size: 44),
          const SizedBox(width: 20),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Bring your playlists with you',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 8),
                Text(
                  'Paste a public Spotify playlist link to match its tracks with YouTube Music and save a local playlist.',
                  style: TextStyle(
                    color: DesktopPalette.of(context).secondary,
                    height: 1.4,
                  ),
                ),
                const SizedBox(height: 18),
                FilledButton.icon(
                  onPressed: () => importSpotifyDialog(context, app),
                  icon: const Icon(Icons.download, size: 18),
                  label: const Text('Import from Spotify'),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
    if (app.importingSpotify)
      Padding(
        padding: const EdgeInsets.symmetric(vertical: 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            LinearProgressIndicator(
              value: app.spotifyTotal == 0
                  ? null
                  : app.spotifyProgress / app.spotifyTotal,
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: Text(
                    'Matching ${app.spotifyProgress} of ${app.spotifyTotal} tracks',
                  ),
                ),
                TextButton(
                  onPressed: app.cancelSpotifyImport,
                  child: const Text('Cancel'),
                ),
              ],
            ),
          ],
        ),
      ),
    if (app.spotifyImportWarnings.isNotEmpty)
      Padding(
        padding: const EdgeInsets.symmetric(vertical: 14),
        child: ExpansionTile(
          title: Text(
            '${app.spotifyImportWarnings.length} tracks need attention',
          ),
          children: [
            for (final warning in app.spotifyImportWarnings)
              ListTile(
                title: Text(warning, style: const TextStyle(fontSize: 12)),
              ),
          ],
        ),
      ),
    const SectionTitle('Your playlists'),
    for (final playlist in app.localPlaylists)
      ListTile(
        contentPadding: EdgeInsets.zero,
        leading: Artwork(playlist.songs.firstOrNull?.thumbnailUrl, size: 48),
        title: Text(playlist.title),
        subtitle: Text('${playlist.songs.length} songs'),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => app.openPlaylist(playlist),
      ),
  ]);
}

Future<void> importSpotifyDialog(
  BuildContext context,
  AppController app,
) async {
  final url = await askText(
    context,
    title: 'Import Spotify playlist',
    label: 'Spotify playlist URL',
    action: 'Import',
    helper:
        'Public playlist links are supported. Tracks are matched with YouTube Music.',
  );
  if (url != null) await app.importSpotify(url);
}

Future<void> newPlaylist(BuildContext context, AppController app) async {
  final choice = await showDialog<String>(
    context: context,
    builder: (context) => SimpleDialog(
      title: const Text('New playlist'),
      children: [
        SimpleDialogOption(
          onPressed: () => Navigator.pop(context, 'local'),
          child: const ListTile(
            leading: Icon(Icons.add),
            title: Text('Create playlist'),
            subtitle: Text('Save a playlist on this device'),
          ),
        ),
        if (app.signedIn)
          SimpleDialogOption(
            onPressed: () => Navigator.pop(context, 'cloud'),
            child: const ListTile(
              leading: Icon(Icons.cloud_outlined),
              title: Text('Create in YouTube Music'),
              subtitle: Text('Save a playlist to your account'),
            ),
          ),
        SimpleDialogOption(
          onPressed: () => Navigator.pop(context, 'spotify'),
          child: const ListTile(
            leading: Icon(Icons.download),
            title: Text('Import from Spotify'),
            subtitle: Text('Match songs with YouTube Music'),
          ),
        ),
      ],
    ),
  );
  if (!context.mounted) return;
  if (choice == 'spotify') return importSpotifyDialog(context, app);
  if (choice == 'local' || choice == 'cloud') {
    final name = await askText(
      context,
      title: 'Create playlist',
      label: 'Playlist name',
      action: 'Create',
    );
    if (name != null) {
      if (choice == 'cloud') {
        await app.createCloudPlaylist(name);
      } else {
        await app.createPlaylist(name);
      }
    }
  }
}

class _HorizontalDragBehavior extends MaterialScrollBehavior {
  const _HorizontalDragBehavior();
  // Native scrollbar and trackpad scrolling remain available without capturing taps.
}
