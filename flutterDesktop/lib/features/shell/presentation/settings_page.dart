import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../design_system/palette.dart';
import '../../online/application/catalog_controller.dart';
import '../../online/presentation/account_session.dart';
import '../../playback/presentation/listening_panel.dart';
import '../application/shell_controller.dart';
import '../domain/shell_settings.dart';

// DesktopApp.kt:DesktopSettingsSection/Screen and DesktopOverlays.kt at
// 348b613a. Navigation is real; absent capabilities never receive callbacks.
class SettingsPage extends ConsumerStatefulWidget {
  const SettingsPage({super.key, required this.palette});
  final BitChordPalette palette;
  @override
  ConsumerState<SettingsPage> createState() => SettingsPageState();
}

class SettingsPageState extends ConsumerState<SettingsPage> {
  final _search = TextEditingController();
  final _scroll = ScrollController();
  final _pages = <String>[];
  String get _page => _pages.lastOrNull ?? 'Settings';
  void _open(String page) => setState(() => _pages.add(page));
  bool back() {
    if (_pages.isEmpty) return false;
    setState(() => _pages.removeLast());
    return true;
  }

  void _back() => back();
  @override
  void dispose() {
    _search.dispose();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _pages.isEmpty,
    onPopInvokedWithResult: (didPop, _) {
      if (!didPop && _pages.isNotEmpty) _back();
    },
    child: Align(
      alignment: Alignment.topCenter,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 840),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 28),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 20, bottom: 12),
                child: Row(
                  children: [
                    if (_pages.isNotEmpty)
                      IconButton(
                        onPressed: _back,
                        tooltip: 'Back to Settings',
                        icon: const Icon(Icons.arrow_back_rounded, size: 20),
                      ),
                    Expanded(
                      child: Text(
                        _page,
                        key: const Key('page-heading'),
                        style: Theme.of(context).textTheme.headlineMedium
                            ?.copyWith(fontWeight: FontWeight.w700),
                      ),
                    ),
                    if (_pages.isEmpty)
                      SizedBox(
                        width: MediaQuery.sizeOf(context).width < 700
                            ? 180
                            : 280,
                        height: 36,
                        child: TextField(
                          key: const Key('settings-search'),
                          controller: _search,
                          onChanged: (_) => setState(() {}),
                          decoration: InputDecoration(
                            hintText: 'Search settings',
                            prefixIcon: const Icon(
                              Icons.search_rounded,
                              size: 18,
                            ),
                            suffixIcon: _search.text.isEmpty
                                ? null
                                : IconButton(
                                    tooltip: 'Clear search',
                                    icon: const Icon(Icons.close, size: 16),
                                    onPressed: () => setState(_search.clear),
                                  ),
                            contentPadding: const EdgeInsets.symmetric(
                              horizontal: 10,
                            ),
                            enabledBorder: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(7),
                              borderSide: BorderSide(
                                color: widget.palette['cardEdge'],
                              ),
                            ),
                            border: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(7),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              Expanded(child: _pages.isEmpty ? _main() : _subpage()),
            ],
          ),
        ),
      ),
    ),
  );

  Widget _main() {
    final settings = ref.watch(shellProvider);
    void update(ShellSettings value) =>
        ref.read(shellProvider.notifier).update(value);
    final providers =
        ref.watch(onlinePortProvider)?.providers ?? const <String>[];
    return ListView(
      key: const PageStorageKey('settings-main'),
      controller: _scroll,
      padding: const EdgeInsets.only(top: 12, bottom: 132),
      children: [
        _group('Account', [
          _row(
            'Account & integrations',
            'Spotify, Discord, Last.fm and ListenBrainz',
            icon: Icons.share_rounded,
            page: 'Account & integrations',
          ),
        ]),
        _group('Listen together', [
          _row(
            'Listen together',
            'Get a code to share with friends',
            page: 'Listen together',
          ),
        ]),
        _group('Sources · tried in this order', [
          for (final (index, provider) in providers.indexed)
            _row(
              provider == 'youtube'
                  ? 'YouTube Music'
                  : provider == 'jiosaavn'
                  ? 'JioSaavn'
                  : provider,
              provider == 'youtube'
                  ? 'Fallback source'
                  : 'Source management unavailable',
              leading: Text(
                '${index + 1}',
                style: TextStyle(color: widget.palette['secondary']),
              ),
              toggle: provider == 'youtube',
            ),
          _row(
            'Add a source',
            'Add-on server and custom module',
            icon: Icons.add,
          ),
        ]),
        _group('Audio quality', [
          _row(
            'Audio quality',
            'Lossless',
            options: const ['Standard', 'High', 'Lossless'],
          ),
        ]),
        _group('Downloads', [
          _row(
            'Download quality',
            'Lossless',
            options: const ['Standard', 'High', 'Lossless'],
          ),
        ]),
        _group('Output precision', [
          _row(
            'Output precision',
            'Device format',
            options: const ['16-bit PCM', '32-bit float'],
          ),
        ]),
        _group('Audio output', [
          _row('Output device', 'System default', page: 'Audio output'),
        ]),
        _group('Playback', [
          _row(
            'Music video playback (Beta)',
            'Experimental video player and audio/video switching',
            toggle: false,
          ),
          _row('Crossfade', 'Off', slider: true),
          _row(
            'Automix [BETA]',
            'Automatically calculates transition timing',
            toggle: false,
          ),
          _row(
            'Automix duration',
            'How long the two songs play together',
            options: const ['Adaptive', '8 Bars', '16 Bars'],
          ),
          _row(
            'Automix performance',
            'Sets how much CPU background analysis may use',
            options: const ['Efficient', 'Balanced', 'Performance'],
          ),
          // Phase 5 controls keep their production controller/capability gates.
          if (_matches('Playback', 'Skip silence Spatial audio Equalizer'))
            ListeningPanel(
              section: ListeningSection.effects,
              query: 'playback'.contains(_search.text.trim().toLowerCase())
                  ? ''
                  : _search.text,
              onOpenEqualizer: () => _open('Equalizer'),
            ),
          _row(
            'Dolby Atmos',
            'Immersive mix playback is unavailable',
            toggle: false,
          ),
        ]),
        _group('Content', [
          _row(
            'Content Preferences',
            'Customize Home recommendations, music-only playback and video thumbnail shapes',
            page: 'Content Preferences',
          ),
        ]),
        _group('Desktop playback', [
          _row(
            'Autoplay',
            'Keep the music going with similar songs',
            toggle: false,
          ),
          _row('Shuffle', 'Mix the order of the current queue', toggle: false),
          _row('Repeat', 'Off', icon: Icons.repeat_rounded),
          if (_matches('Desktop playback', 'Playback speed Sleep timer'))
            ListeningPanel(
              section: ListeningSection.transport,
              query:
                  'desktop playback'.contains(_search.text.trim().toLowerCase())
                  ? ''
                  : _search.text,
            ),
          _row(
            'Direct source stream',
            'Start on a higher priority source; YouTube remains the fallback.',
            toggle: false,
          ),
        ]),
        _group('Appearance', [
          _row(
            'Theme',
            'Follow your device appearance by default, or choose a theme.',
            control: Wrap(
              spacing: 8,
              children: [
                for (final mode in Appearance.values)
                  ChoiceChip(
                    label: Text(
                      '${mode.name[0].toUpperCase()}${mode.name.substring(1)}',
                    ),
                    selected: settings.appearance == mode,
                    onSelected: (_) =>
                        update(settings.copyWith(appearance: mode)),
                  ),
              ],
            ),
          ),
          if (settings.appearance == Appearance.dark)
            _row(
              'Pitch black background',
              'Make both the content area and sidebar true black.',
              toggle: settings.pitchBlack,
              onToggle: (value) => update(settings.copyWith(pitchBlack: value)),
            ),
          _row(
            'Font',
            'SF Pro Display · Apple Music weights',
            options: const ['Default', 'Legacy'],
          ),
          _row(
            'App icon',
            'Change the window and tray icon',
            options: const ['Dark', 'Light', 'Classic'],
          ),
          _row(
            'UI scale',
            'Zoom text, controls, and panels throughout BitChord.',
            control: Wrap(
              spacing: 6,
              children: [
                for (final percent in uiScaleOptions)
                  ChoiceChip(
                    key: Key('ui-scale-$percent'),
                    label: Text('$percent%'),
                    selected: settings.uiScalePercent == percent,
                    onSelected: (_) =>
                        update(settings.copyWith(uiScalePercent: percent)),
                  ),
              ],
            ),
          ),
          _row(
            'Use old Explore cards',
            'Bring back the v1.7 gradient cards with angled cover art',
            toggle: false,
          ),
          _row(
            'Enable old top music player style',
            'Use the original desktop top player instead of the floating bottom player',
            toggle: false,
          ),
          _row(
            'Graphics profile & blur',
            'Graphics profile, reduce dynamic blur and customize blur',
            page: 'Graphics profile & blur',
          ),
          _row(
            'Reduce animation',
            'Reduce motion throughout BitChord',
            toggle: settings.reduceMotion,
            onToggle: (value) => update(settings.copyWith(reduceMotion: value)),
          ),
          _row(
            'Full-screen cover art',
            'Runs the cover to the edges of the player instead of a square sleeve',
            toggle: false,
          ),
          _row(
            'Player theme',
            'The cover’s own colours carry on below it.',
            options: const ['Default', 'Legacy', 'Simple'],
          ),
          _row(
            'Animated canvas',
            'Show motion artwork when it is available',
            toggle: false,
          ),
          _row(
            'Spotify Canvas setup',
            'Connect Spotify for motion artwork',
            page: 'Spotify Canvas setup',
          ),
          if (Theme.of(context).platform == TargetPlatform.windows) ...[
            _row(
              'Use Mica-style sidebar',
              'Wallpaper-tinted Windows 11 sidebar',
              toggle: false,
            ),
            _row(
              'Native title bar material',
              'Changes the Windows title bar only',
              options: const ['Off', 'Mica', 'Acrylic'],
            ),
          ],
        ]),
        _group('Lyrics', [
          _row(
            'Synced lyrics',
            'Lights up the words on the player as they’re sung',
            toggle: false,
          ),
          _row(
            'Continue lyrics animation when unfocused',
            'Keep synced lyrics moving on a visible second monitor',
            toggle: false,
          ),
          _row(
            'Blur unfocused lyrics',
            'Keeps the spotlight on the current line',
            toggle: false,
          ),
          _row(
            'Lyrics sources',
            'Choose and order lyric providers',
            page: 'Lyrics sources',
          ),
          _row(
            'Translation language',
            'Original language',
            page: 'Translation language',
          ),
        ]),
        _group('Local Music', [
          _row(
            'Local music folder',
            'Choose a folder',
            icon: Icons.folder_rounded,
          ),
          _row(
            'Use all audio folders',
            'Scan music across the device',
            icon: Icons.library_music,
          ),
          _row(
            'Filter non-music audio',
            'Hides clips under 30 seconds, WAV files, voice notes, recordings and system sounds',
            toggle: false,
          ),
        ]),
        _group('Storage', [
          _row(
            'Song cache limit',
            'Playback audio ranges use a separate cache capped at 256 MB',
            slider: true,
          ),
          _row(
            'Clear song cache',
            'Frees space used by downloaded audio',
            icon: Icons.delete_sweep_rounded,
          ),
          _row(
            'Clear image cache',
            'Frees space used by album artwork',
            icon: Icons.delete_sweep_rounded,
          ),
        ]),
        _group('Miscellaneous', [
          _row(
            'Don’t repeat songs in current session',
            'AutoPlay won’t suggest a song already played or suggested this session',
            toggle: false,
          ),
          _row(
            'Tray icon',
            'Show BitChord in the system tray, with playback controls',
            toggle: false,
          ),
          _row(
            'Keep playing when closed',
            'Closing the window leaves BitChord in the tray instead of quitting',
            toggle: false,
          ),
        ]),
        _group('Language', [
          _row('App language', 'System default', icon: Icons.language),
        ]),
        _group('Advanced options', [
          _row(
            'Show stats for nerds',
            'Codec, bitrate and sample rate on the player',
            toggle: false,
          ),
        ]),
        _group('App Updates', [
          _row(
            'Check for updates',
            'BitChord Desktop 2.0.0+1',
            icon: Icons.system_update_alt,
          ),
        ]),
        const SizedBox(height: 30),
        Center(
          child: Text(
            'BitChord',
            style: Theme.of(context).textTheme.headlineLarge,
          ),
        ),
        Center(
          child: Text(
            '2.0.0+1',
            style: TextStyle(color: widget.palette['secondary']),
          ),
        ),
        Center(
          child: TextButton(
            onPressed: () => showLicensePage(
              context: context,
              applicationName: 'BitChord Desktop',
              applicationVersion: '2.0.0+1',
            ),
            child: const Text('Third-party licenses'),
          ),
        ),
      ],
    );
  }

  bool _matches(String group, String text) =>
      _search.text.trim().isEmpty ||
      '$group $text'.toLowerCase().contains(_search.text.trim().toLowerCase());
  Widget _group(String title, List<Widget> rows) {
    if (rows.every((row) => row is SizedBox)) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 10, bottom: 4),
            child: Text(
              title.toUpperCase(),
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: widget.palette['secondary'],
              ),
            ),
          ),
          ...rows,
        ],
      ),
    );
  }

  Widget _row(
    String title,
    String subtitle, {
    IconData? icon,
    Widget? leading,
    String? page,
    bool? toggle,
    ValueChanged<bool>? onToggle,
    Widget? control,
    List<String>? options,
    bool slider = false,
  }) {
    // Subpages do not inherit the main-page query.
    final groups = _rowGroup(title);
    if (_pages.isEmpty && !_matches(groups, '$title $subtitle'))
      return const SizedBox.shrink();
    final enabled = page != null || onToggle != null || control != null;
    final content = Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              if (leading != null) ...[
                SizedBox(width: 24, child: leading),
                const SizedBox(width: 12),
              ],
              if (icon != null) ...[
                Container(
                  width: 34,
                  height: 34,
                  decoration: BoxDecoration(
                    color: widget.palette['foreground'].withValues(alpha: .08),
                    borderRadius: BorderRadius.circular(9),
                  ),
                  child: Icon(icon, size: 19),
                ),
                const SizedBox(width: 14),
              ],
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: Theme.of(context).textTheme.bodyLarge),
                    if (subtitle.isNotEmpty)
                      Text(
                        subtitle,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: widget.palette['secondary'],
                        ),
                      ),
                  ],
                ),
              ),
              const SizedBox(width: 18),
              if (toggle != null)
                Switch(
                  value: toggle,
                  onChanged: onToggle,
                  thumbColor: WidgetStatePropertyAll(
                    widget.palette['foreground'],
                  ),
                  activeTrackColor: widget.palette['foreground'].withValues(
                    alpha: .25,
                  ),
                ),
              if (page != null)
                Icon(
                  Icons.chevron_right,
                  size: 20,
                  color: widget.palette['secondary'],
                ),
            ],
          ),
          if (control != null)
            Padding(padding: const EdgeInsets.only(top: 10), child: control),
          if (options != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Wrap(
                spacing: 8,
                children: [
                  for (final option in options)
                    ChoiceChip(
                      label: Text(option),
                      selected: false,
                      onSelected: null,
                    ),
                ],
              ),
            ),
          if (slider) const Slider(value: 0, onChanged: null),
        ],
      ),
    );
    return Semantics(
      enabled: enabled,
      child: Opacity(
        opacity: enabled ? 1 : .38,
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: BorderRadius.circular(7),
            onTap: page == null ? null : () => _open(page),
            child: content,
          ),
        ),
      ),
    );
  }

  // Section names participate in filtering as in Kotlin's LocalSettingsQuery.
  String _rowGroup(String title) => switch (title) {
    'Theme' ||
    'Pitch black background' ||
    'Font' ||
    'App icon' ||
    'UI scale' ||
    'Use old Explore cards' ||
    'Enable old top music player style' ||
    'Graphics profile & blur' ||
    'Reduce animation' ||
    'Full-screen cover art' ||
    'Player theme' ||
    'Animated canvas' ||
    'Spotify Canvas setup' ||
    'Use Mica-style sidebar' ||
    'Native title bar material' => 'Appearance',
    'Music video playback (Beta)' ||
    'Crossfade' ||
    'Automix [BETA]' ||
    'Automix duration' ||
    'Automix performance' ||
    'Dolby Atmos' => 'Playback',
    'Autoplay' ||
    'Shuffle' ||
    'Repeat' ||
    'Direct source stream' => 'Desktop playback',
    'Synced lyrics' ||
    'Continue lyrics animation when unfocused' ||
    'Blur unfocused lyrics' ||
    'Lyrics sources' ||
    'Translation language' => 'Lyrics',
    'YouTube Music' ||
    'JioSaavn' ||
    'Add a source' => 'Sources · tried in this order',
    'Song cache limit' ||
    'Clear song cache' ||
    'Clear image cache' => 'Storage',
    'Don’t repeat songs in current session' ||
    'Tray icon' ||
    'Keep playing when closed' => 'Miscellaneous',
    'Local music folder' ||
    'Use all audio folders' ||
    'Filter non-music audio' => 'Local Music',
    'Show stats for nerds' => 'Advanced options',
    'Check for updates' => 'App Updates',
    'Output device' => 'Audio output',
    'Download quality' => 'Downloads',
    'App language' => 'Language',
    _ => title,
  };

  Widget _subpage() {
    final settings = ref.watch(shellProvider);
    return ListView(
      key: ValueKey(_page),
      padding: const EdgeInsets.only(top: 12, bottom: 28),
      children: [
        if (_page == 'Equalizer') ...[
          EqualizerPanel(onDone: _back),
        ] else if (_page == 'Account & integrations') ...[
          _group('YouTube Music', [const AccountSession()]),
          for (final name in ['Spotify', 'Discord', 'Last.fm', 'ListenBrainz'])
            _row(name, 'Not connected', page: name),
        ] else if (_page == 'Graphics profile & blur') ...[
          _row(
            'Graphics profile',
            'Choose how much GPU work BitChord uses',
            options: const ['Efficiency', 'Balanced', 'Quality'],
          ),
          _row(
            'Reduce dynamic blur',
            'Use solid surfaces in place of live blur',
            toggle: settings.reduceBlur,
            onToggle: (value) => ref
                .read(shellProvider.notifier)
                .update(settings.copyWith(reduceBlur: value)),
          ),
          for (final area in [
            'Sidebar',
            'Floating player',
            'Search page',
            'Menus',
            'Lyrics',
          ])
            _row(area, 'Customize blur', slider: true),
        ] else if (_page == 'Content Preferences') ...[
          for (final title in [
            'Prefer music-only version',
            'Hide Recents',
            'Hide videos from Home',
            'Hide Top Picks for You',
            'Hide Find Your Mood',
            'Square video thumbnails',
          ])
            _row(title, '', toggle: false),
        ] else if (_page == 'Lyrics sources') ...[
          const Text('Lyrics providers are not connected in this build.'),
          _row(
            'lrc.red',
            'Apple timings by recording, syllable by syllable',
            toggle: false,
          ),
          _row(
            'BiniLyrics',
            'The same Apple timings, matched on the recording itself',
            toggle: false,
          ),
          _row(
            'BetterLyrics',
            'Apple Music timings, word by word',
            toggle: false,
          ),
          _row(
            'BetterLyrics Portato',
            'QQ Music karaoke timings through BetterLyrics',
            toggle: false,
          ),
          _row(
            'PaxSenix',
            'Apple Music timings through the original keyless provider',
            toggle: false,
          ),
          _row(
            'PaxSenix: Spotify',
            'Spotify lyrics with PaxSenix fallback; API key required',
            toggle: false,
          ),
          _row(
            'PaxSenix: Musixmatch',
            'Musixmatch timings with PaxSenix fallback; API key required',
            toggle: false,
          ),
          _row(
            'LyricsPlus',
            'Syllable by syllable, on community mirrors',
            toggle: false,
          ),
          _row(
            'Unison',
            'Contributed by listeners, so it has what nobody licensed',
            toggle: false,
          ),
          _row(
            'YouTube captions',
            'Timed captions matched to the exact playing video',
            toggle: false,
          ),
          _row(
            'YouTube Music',
            "Plain lyrics from the playing video's Lyrics tab",
            toggle: false,
          ),
          _row(
            'KuGou',
            'Whole lines, strong outside the English catalogue',
            toggle: false,
          ),
          _row('LRCLIB', 'Whole lines only, and always up', toggle: false),
          _row(
            'Musixmatch',
            'Whole lines, from the biggest lyrics database there is',
            toggle: false,
          ),
          _row(
            'Genius',
            'Plain text fallback, massive web catalogue',
            toggle: false,
          ),
          _row(
            'Prioritize syllable lyrics',
            'Keep searching past a line-synced match for word-by-word lyrics',
            toggle: false,
          ),
        ] else if (_page == 'Translation language') ...[
          _row(
            'Translation language',
            'Original language',
            options: const ['Original', 'English', 'Indonesian'],
          ),
        ] else if (_page == 'Audio output') ...[
          _row('Output device', 'System default', icon: Icons.headphones),
        ] else if (_page == 'Listen together') ...[
          _row(
            'Start a party',
            'Get a code to share with friends',
            icon: Icons.people,
          ),
          _row('Join a party', 'Enter a party code', icon: Icons.login),
        ] else if (_page == 'Spotify' || _page == 'Spotify Canvas setup') ...[
          _row(
            'Connect Spotify',
            'Sign in to Spotify',
            icon: Icons.account_circle,
          ),
          _row(
            'Animated canvas',
            'Motion artwork is unavailable',
            toggle: false,
          ),
        ] else if (_page == 'Discord') ...[
          for (final title in [
            'Show what I am playing',
            'Lead with the song',
            'Customize the card',
          ])
            _row(title, '', toggle: false),
          for (final title in [
            'Status',
            'Activity',
            'Name',
            'First button',
            'Second button',
            'Enter a token instead',
          ])
            _row(title, 'Not connected'),
        ] else ...[
          _row(
            _page == 'Last.fm' ? 'Sign in to Last.fm' : 'ListenBrainz token',
            'Not connected',
            icon: Icons.account_circle,
          ),
          for (final title in [
            'Scrobble tracks',
            'Only scrobble primary artist',
            'Now playing',
          ])
            _row(title, '', toggle: false),
          for (final title in [
            'Minimum track duration',
            'Scrobble at percentage',
            'Scrobble after seconds',
          ])
            _row(title, '', slider: true),
        ],
      ],
    );
  }
}
