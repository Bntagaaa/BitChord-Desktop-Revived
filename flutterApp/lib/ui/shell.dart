// Layout source: DesktopApp.kt: DesktopFrame, DesktopSidebar, DesktopBottomChrome.
import 'dart:async';
import 'dart:ui';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import '../domain/application.dart';
import '../domain/music.dart';
import 'player.dart';
import 'home.dart';
import 'settings/settings_page.dart';
import 'theme.dart';
import 'widgets.dart';

class BitChordApplication extends StatelessWidget {
  const BitChordApplication({super.key, required this.application});
  final MusicApplication application;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: application.settings,
    builder: (context, _) => MaterialApp(
      title: 'BitChord',
      debugShowCheckedModeBanner: false,
      theme: appTheme(
        application.settings,
        WidgetsBinding.instance.platformDispatcher.platformBrightness,
      ),
      home: MusicShell(application: application),
    ),
  );
}

class MusicShell extends StatefulWidget {
  const MusicShell({super.key, required this.application});
  final MusicApplication application;
  @override
  State<MusicShell> createState() => _MusicShellState();
}

class _MusicShellState extends State<MusicShell> {
  final _query = TextEditingController();
  Timer? _searchDebounce;
  MusicApplication get app => widget.application;
  @override
  void initState() {
    super.initState();
    unawaited(app.navigate(Destination.home));
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _query.dispose();
    super.dispose();
  }

  void _search(String value) {
    _searchDebounce?.cancel();
    _searchDebounce = Timer(
      const Duration(milliseconds: 300),
      () => unawaited(app.search(value)),
    );
  }

  void _expanded({PlayerPane initialPane = PlayerPane.main}) {
    if (app.song == null) return;
    unawaited(
      Navigator.of(context).push(
        PageRouteBuilder<void>(
          transitionDuration: const Duration(milliseconds: 360),
          reverseTransitionDuration: const Duration(milliseconds: 360),
          pageBuilder: (_, _, _) =>
              ImmersivePlayer(app: app, initialPane: initialPane),
          transitionsBuilder: (_, animation, _, child) => SlideTransition(
            position: Tween(begin: const Offset(0, 1), end: Offset.zero)
                .animate(
                  CurvedAnimation(
                    parent: animation,
                    curve: Curves.easeInOutCubic,
                  ),
                ),
            child: child,
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: app,
    builder: (context, _) => Scaffold(
      body: LayoutBuilder(
        builder: (context, bounds) {
          final collapsed =
              bounds.maxWidth < 980 ||
              app.settings.get('sidebar_collapsed', false);
          return Row(
            children: [
              _sidebar(collapsed),
              Expanded(
                child: Stack(
                  children: [
                    Positioned.fill(child: _page()),
                    Align(
                      alignment: Alignment.bottomCenter,
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(10, 8, 10, 20),
                        child: _dock(),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          );
        },
      ),
    ),
  );

  Widget _sidebar(bool collapsed) {
    final colors = paletteOf(context);
    final entries = [
      (Destination.home, 'Home'),
      (Destination.newMusic, 'TabExplore'),
      (Destination.search, 'TabSearch'),
      (Destination.library, 'TabLibrary'),
    ];
    Widget entry(Destination target, Widget icon) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 1),
      child: Material(
        color: app.destination == target
            ? colors.navSelectedSurface
            : Colors.transparent,
        borderRadius: BorderRadius.circular(8),
        child: InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: () {
            _query.clear();
            unawaited(app.navigate(target));
          },
          child: SizedBox(
            height: 38,
            child: Row(
              mainAxisAlignment: collapsed
                  ? MainAxisAlignment.center
                  : MainAxisAlignment.start,
              children: [
                if (!collapsed) const SizedBox(width: 9),
                icon,
                if (!collapsed) ...[
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      target.label,
                      style: TextStyle(
                        fontSize: 14,
                        color: app.destination == target
                            ? colors.foreground
                            : colors.secondary,
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
    return AnimatedContainer(
      duration: const Duration(milliseconds: 180),
      width: collapsed ? 72 : 220,
      color: colors.sidebarSurface,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 16, 12, 16),
        child: Column(
          children: [
            SizedBox(
              height: 51,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  GestureDetector(
                    onTap: () => unawaited(
                      app.settings.set('sidebar_collapsed', !collapsed),
                    ),
                    child: const SourceIcon('bitchord_logo_original', size: 28),
                  ),
                  if (!collapsed) ...[
                    const SizedBox(width: 13),
                    const Expanded(
                      child: SourceIcon('bitchord_wordmark', size: 84),
                    ),
                    IconButton(
                      onPressed: () => unawaited(
                        app.settings.set('sidebar_collapsed', true),
                      ),
                      icon: const Icon(Icons.chevron_left, size: 20),
                    ),
                  ],
                ],
              ),
            ),
            for (final item in entries)
              entry(
                item.$1,
                SourceIcon(
                  item.$2,
                  size: 21,
                  color: app.destination == item.$1
                      ? colors.navAccent
                      : colors.secondary,
                ),
              ),
            Divider(color: colors.divider, height: 30),
            entry(
              Destination.history,
              Icon(Icons.history_rounded, color: colors.secondary, size: 21),
            ),
            entry(
              Destination.downloads,
              Icon(Icons.download_rounded, color: colors.secondary, size: 21),
            ),
            entry(
              Destination.localMusic,
              Icon(
                Icons.library_music_rounded,
                color: colors.secondary,
                size: 21,
              ),
            ),
            entry(
              Destination.spotify,
              Icon(Icons.album_rounded, color: colors.secondary, size: 21),
            ),
            Divider(color: colors.divider, height: 30),
            if (!collapsed)
              Text(
                'Your playlists will appear here',
                style: TextStyle(
                  fontSize: 12,
                  color: colors.secondary.withValues(alpha: .68),
                ),
              ),
            const Spacer(),
            Divider(color: colors.divider),
            if (!collapsed)
              const ListTile(
                contentPadding: EdgeInsets.zero,
                leading: Icon(Icons.account_circle_outlined),
                title: Text('Sign in'),
                subtitle: Text('Connect account'),
              ),
            entry(
              Destination.settings,
              Icon(Icons.settings_rounded, color: colors.secondary, size: 21),
            ),
          ],
        ),
      ),
    );
  }

  Widget _page() {
    final colors = paletteOf(context);
    if (app.destination == Destination.settings) return SettingsPage(app: app);
    if (app.destination == Destination.home) return HomePage(app: app);
    final songs = switch (app.destination) {
      Destination.localMusic => app.localSongs,
      Destination.history when !app.api.signedIn => app.history,
      _ => app.page.songs,
    };
    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(28, 26, 28, 12),
          sliver: SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  app.page.title.isNotEmpty
                      ? app.page.title
                      : app.destination.label,
                  style: Theme.of(context).textTheme.displayLarge,
                ),
                if (app.destination == Destination.search) ...[
                  const SizedBox(height: 20),
                  TextField(
                    controller: _query,
                    onChanged: _search,
                    onSubmitted: (query) => unawaited(app.search(query)),
                    decoration: InputDecoration(
                      hintText: 'Search songs, artists, albums',
                      prefixIcon: const Icon(Icons.search),
                      filled: true,
                      fillColor: colors.searchSurface,
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(10),
                        borderSide: BorderSide.none,
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 8,
                    children: [
                      for (final filter in SearchFilter.values)
                        ChoiceChip(
                          label: Text(filter.label),
                          selected: app.filter == filter,
                          onSelected: (_) {
                            app.filter = filter;
                            unawaited(app.search(_query.text));
                          },
                        ),
                    ],
                  ),
                ],
                if (app.destination == Destination.localMusic) ...[
                  const SizedBox(height: 16),
                  OutlinedButton.icon(
                    onPressed: () async {
                      final folder = await FilePicker.platform
                          .getDirectoryPath();
                      if (folder != null) await app.scanLocal(folder);
                    },
                    icon: const Icon(Icons.folder_open),
                    label: const Text('Choose music folder'),
                  ),
                ],
                if (app.error != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 20),
                    child: SelectableText(
                      app.error!,
                      style: TextStyle(color: colors.destructive),
                    ),
                  ),
                if (app.busy)
                  const Padding(
                    padding: EdgeInsets.all(24),
                    child: Center(
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  ),
                if (!app.busy &&
                    app.page.shelves.isEmpty &&
                    app.page.items.isEmpty &&
                    songs.isEmpty &&
                    app.error == null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 32),
                    child: Text(switch (app.destination) {
                      Destination.search => 'Search for music',
                      Destination.localMusic =>
                        'Choose a folder to listen to music on this computer',
                      Destination.library => 'Sign in to see your library',
                      Destination.spotify =>
                        'Connect Spotify to see your playlists',
                      Destination.history =>
                        'Your listening history will appear here',
                      Destination.downloads => 'No downloads',
                      _ => 'No music returned',
                    }, style: TextStyle(color: colors.secondary)),
                  ),
              ],
            ),
          ),
        ),
        for (final shelf in app.page.shelves)
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(28, 18, 0, 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    shelf.title,
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                  const SizedBox(height: 16),
                  SizedBox(
                    height: 228,
                    child: ListView.separated(
                      scrollDirection: Axis.horizontal,
                      itemCount: shelf.items.length,
                      separatorBuilder: (_, _) => const SizedBox(width: 18),
                      itemBuilder: (context, index) {
                        final item = shelf.items[index];
                        return SizedBox(
                          width: 160,
                          child: InkWell(
                            onTap: () => unawaited(app.browse(item)),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Artwork(item.artwork, size: 160),
                                const SizedBox(height: 10),
                                Text(
                                  item.title,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: Theme.of(context).textTheme.titleMedium
                                      ?.copyWith(fontWeight: FontWeight.w500),
                                ),
                                Text(
                                  item.subtitle,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontSize: 14,
                                    color: colors.secondary,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                ],
              ),
            ),
          ),
        for (final item in app.page.items)
          SliverPadding(
            padding: const EdgeInsets.symmetric(horizontal: 28),
            sliver: SliverToBoxAdapter(
              child: ListTile(
                leading: Artwork(item.artwork, size: 44, radius: 8),
                title: Text(
                  item.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                subtitle: Text(
                  item.subtitle,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                onTap: () => unawaited(app.browse(item)),
              ),
            ),
          ),
        SliverPadding(
          padding: const EdgeInsets.symmetric(horizontal: 28),
          sliver: SliverList.builder(
            itemCount: songs.length,
            itemBuilder: (context, index) {
              final song = songs[index];
              return ListTile(
                contentPadding: const EdgeInsets.symmetric(horizontal: 8),
                leading: Artwork(song.artwork, size: 44, radius: 8),
                title: Text(
                  song.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                subtitle: Text(
                  song.artist,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                trailing: Text(song.durationText ?? ''),
                onTap: () => unawaited(
                  app.play(
                    song,
                    context: songs,
                    index: index,
                    origin: app.page.title.isNotEmpty
                        ? app.page.title
                        : app.destination.label,
                  ),
                ),
              );
            },
          ),
        ),
        if (app.page.continuation != null)
          SliverToBoxAdapter(
            child: Center(
              child: TextButton(
                onPressed: app.busy ? null : () => unawaited(app.more()),
                child: const Text('Load more'),
              ),
            ),
          ),
        const SliverToBoxAdapter(child: SizedBox(height: 132)),
      ],
    );
  }

  Widget _dock() {
    final colors = paletteOf(context);
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 760),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: colors.barGlass,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: colors.barEdge, width: .5),
            ),
            child: SizedBox(
              height: 58,
              child: Stack(
                children: [
                  Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 8,
                    ),
                    child: Row(
                      children: [
                        TransportButton(
                          'player_transport_previous',
                          'Previous',
                          app.song == null
                              ? null
                              : () => unawaited(app.previous()),
                        ),
                        TransportButton(
                          app.playing
                              ? 'player_transport_pause'
                              : 'player_transport_play',
                          app.playing ? 'Pause' : 'Play',
                          app.song == null
                              ? null
                              : () => unawaited(app.audio.playPause()),
                          size: 36,
                        ),
                        TransportButton(
                          'player_transport_next',
                          'Next',
                          app.song == null ? null : () => unawaited(app.next()),
                        ),
                        const SizedBox(width: 8),
                        if (app.song != null)
                          InkWell(
                            borderRadius: BorderRadius.circular(6),
                            onTap: _expanded,
                            child: Artwork(
                              app.song!.artwork,
                              size: 42,
                              radius: 6,
                            ),
                          ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: InkWell(
                            onTap: _expanded,
                            child: Column(
                              mainAxisAlignment: MainAxisAlignment.center,
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  app.song?.title ?? 'Not Playing',
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    fontSize: 14,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                if (app.song != null)
                                  Text(
                                    app.song!.artist,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      fontSize: 12,
                                      color: colors.secondary,
                                    ),
                                  ),
                              ],
                            ),
                          ),
                        ),
                        TransportButton(
                          'player_transport_lyrics',
                          'Lyrics',
                          app.song == null
                              ? null
                              : () => _expanded(initialPane: PlayerPane.lyrics),
                        ),
                        TransportButton(
                          'player_transport_up_next',
                          'Queue',
                          app.song == null
                              ? null
                              : () => _expanded(initialPane: PlayerPane.queue),
                        ),
                      ],
                    ),
                  ),
                  if (app.loadingTrack)
                    const Align(
                      alignment: Alignment.topCenter,
                      child: LinearProgressIndicator(minHeight: 2),
                    ),
                  Align(
                    alignment: Alignment.bottomCenter,
                    child: ValueListenableBuilder(
                      valueListenable: app.position,
                      builder: (context, position, _) =>
                          LinearProgressIndicator(
                            value: app.duration.value.inMilliseconds <= 0
                                ? 0
                                : (position.inMilliseconds /
                                          app.duration.value.inMilliseconds)
                                      .clamp(0, 1),
                            minHeight: 2,
                            color: colors.navAccent,
                            backgroundColor: Colors.transparent,
                          ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

}
