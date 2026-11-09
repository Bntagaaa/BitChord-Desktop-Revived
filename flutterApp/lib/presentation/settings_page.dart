import 'package:flutter/material.dart';

import '../application/app_controller.dart';
import '../playback/equalizer.dart';
import '../data/lyrics_service.dart';
import 'desktop_theme.dart';
import 'desktop_shortcuts.dart';
import 'widgets.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.app});
  final AppController app;
  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  String query = '';
  AppController get app => widget.app;

  @override
  Widget build(BuildContext context) {
    final p = DesktopPalette.of(context);
    return Align(
      alignment: Alignment.topCenter,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 850),
        child: ListView(
          key: const PageStorageKey('settings'),
          padding: const EdgeInsets.fromLTRB(30, 24, 30, 110),
          children: [
            Text('Settings', style: Theme.of(context).textTheme.headlineLarge),
            const SizedBox(height: 22),
            TextField(
              onChanged: (value) => setState(() => query = value.toLowerCase()),
              decoration: const InputDecoration(
                hintText: 'Search settings',
                prefixIcon: Icon(Icons.search),
              ),
            ),
            group('Account', [
              nav(
                'YouTube Music',
                app.signedIn
                    ? app.account?.name ?? 'Connected'
                    : 'Connect your music account',
                Icons.account_circle_outlined,
                () => showAccounts(context, app),
              ),
            ]),
            group('Sources', [
              toggleSetting(
                'JioSaavn',
                'Try matching high-quality tracks before falling back to YouTube.',
                'source_jiosaavn_enabled',
                fallback: true,
              ),
              nav(
                'Music sources',
                'Manage HTTP music add-ons and their playback priority.',
                Icons.extension_outlined,
                () => showAddonSources(context, app),
              ),
            ]),
            group('Audio quality', [
              choice(
                'Audio quality',
                'Choose the source quality for new playback requests.',
                'audio_quality',
                'LOSSLESS',
                const {
                  'LOW': 'Low',
                  'MEDIUM': 'Medium',
                  'HIGH': 'High',
                  'LOSSLESS': 'Lossless',
                },
              ),
              staticRow('Source policy', switch (app.setting(
                'audio_quality',
                'LOSSLESS',
              )) {
                'LOW' => 'YouTube · up to 64 kbps',
                'MEDIUM' => 'YouTube · best available',
                'HIGH' => 'JioSaavn, then YouTube',
                _ => 'Add-ons, JioSaavn, then YouTube',
              }),
            ]),
            group('Downloads', [
              choice(
                'Download quality',
                'Choose the preferred quality for new downloads.',
                'download_quality',
                'LOSSLESS',
                const {
                  'STANDARD': 'Standard',
                  'HIGH': 'High',
                  'LOSSLESS': 'Lossless',
                },
              ),
            ]),
            group('Playback', [
              toggle(
                'Autoplay',
                'Keep listening with related songs after the queue ends.',
                app.playback.autoplay,
                (value) {
                  app.setSetting('autoplay', value);
                },
              ),
              slider(
                'Crossfade',
                'Blend the end of a song into the next one.',
                app.setting<num>('crossfade_duration_ms', 0).toDouble() / 1000,
                0,
                12,
                (value) {
                  app.setSetting(
                    'crossfade_duration_ms',
                    (value * 1000).round(),
                  );
                },
                label:
                    '${(app.setting<num>('crossfade_duration_ms', 0) / 1000).toStringAsFixed(1)} s',
                divisions: 24,
              ),
              nav(
                'Equalizer',
                'Adjust the seven frequency bands for your audio output.',
                Icons.graphic_eq,
                () => showEqualizer(context, app),
              ),
              nav(
                'Audio output',
                'Choose a connected output device.',
                Icons.headphones,
                () => showAudioOutputs(context, app),
              ),
            ]),
            group('Appearance', [
              choice(
                'Theme',
                'Follow your device appearance, or choose a theme.',
                'desktop_theme_mode',
                'SYSTEM',
                const {'SYSTEM': 'System', 'LIGHT': 'Light', 'DARK': 'Dark'},
              ),
              if (app.setting('desktop_theme_mode', 'SYSTEM') == 'DARK')
                toggleSetting(
                  'Pitch black background',
                  'Make both the content area and sidebar true black.',
                  'desktop_pitch_black',
                ),
              choice(
                'Font',
                'SF Pro Display · Default lighter weights or original Legacy weights.',
                'app_font',
                'DEFAULT',
                const {'DEFAULT': 'Default', 'LEGACY': 'Legacy'},
              ),
              choice(
                'UI scale',
                'Zoom text, controls and panels throughout BitChord.',
                'ui_scale_percent',
                100,
                const {
                  80: '80%',
                  90: '90%',
                  100: '100%',
                  110: '110%',
                  125: '125%',
                },
              ),
              toggleSetting(
                'Enable old top music player style',
                'Use the original top player instead of the floating bottom player.',
                'enable_old_top_music_player_style',
              ),
              toggleSetting(
                'Reduce dynamic blur',
                'Use solid surfaces for menus, panels and the floating player.',
                'reduce_dynamic_blur',
              ),
              toggleSetting(
                'Reduce animation',
                'Reduce motion in the interface and lyrics transitions.',
                'reduce_animation',
              ),
              toggleSetting(
                'Hide volume bar',
                'Keep the player compact by hiding its volume slider.',
                'hide_volume_bar',
              ),
              choice(
                'Player theme',
                'Choose how artwork colours fill Now Playing.',
                'player_theme',
                'DEFAULT',
                const {
                  'DEFAULT': 'Default',
                  'LEGACY': 'Legacy',
                  'SIMPLE': 'Simple',
                },
              ),
              toggleSetting(
                'Full-screen cover art',
                'Let the artwork fill the Now Playing background.',
                'full_screen_cover_art',
              ),
            ]),
            group('Lyrics', [
              toggleSetting(
                'Synced lyrics',
                'Lights up the words on the player as they’re sung.',
                'synced_lyrics',
                fallback: true,
              ),
              if (app.setting('synced_lyrics', true)) ...[
                choice(
                  'Translation language',
                  'Choose a language for translated lyrics.',
                  'translation_language',
                  '',
                  const {
                    '': 'App language',
                    'en': 'English',
                    'id': 'Indonesian',
                    'ja': 'Japanese',
                    'ko': 'Korean',
                    'es': 'Spanish',
                    'fr': 'French',
                    'de': 'German',
                    'zh-CN': 'Chinese',
                  },
                ),
                nav(
                  'Lyrics sources',
                  'Enable and reorder supported lyrics providers.',
                  Icons.lyrics_outlined,
                  () => showLyricsSources(context, app),
                ),
                toggleSetting(
                  'Animate lyrics while unfocused',
                  'Keep synced lyrics scrolling when another window has focus.',
                  'animate_lyrics_while_unfocused',
                ),
                slider(
                  'Lyrics text size',
                  'Change text size in the lyrics panel.',
                  app.setting<num>('lyrics_text_size', 28).toDouble(),
                  18,
                  42,
                  (value) => app.setSetting('lyrics_text_size', value),
                  label:
                      '${app.setting<num>('lyrics_text_size', 28).round()} px',
                  divisions: 24,
                ),
                slider(
                  'Lyrics timing offset',
                  'Positive values advance the displayed lyrics.',
                  app.setting<num>('lyrics_offset_ms', 0).toDouble(),
                  -5000,
                  5000,
                  (value) => app.setSetting('lyrics_offset_ms', value.round()),
                  label:
                      '${app.setting<num>('lyrics_offset_ms', 0).round()} ms',
                  divisions: 100,
                ),
              ],
            ]),
            group('Content', [
              toggleSetting(
                'Prefer music-only version',
                'Resolve music videos to their catalogue audio version before playback.',
                'content_prefer_music_only',
              ),
              toggleSetting(
                'Hide Recents',
                'Hide recently played music from Home.',
                'content_hide_recents',
              ),
              toggleSetting(
                'Hide videos from Home',
                'Hide video-only shelves and video cards from Home.',
                'content_hide_home_videos',
              ),
              toggleSetting(
                'Hide Top Picks for You',
                'Hide Top Picks shelves from Home.',
                'content_hide_top_picks',
              ),
              toggleSetting(
                'Hide Find Your Mood',
                'Hide mood shelves from Home.',
                'content_hide_home_moods',
              ),
              toggleSetting(
                'Square video thumbnails',
                'Display video artwork with square covers.',
                'content_square_video_thumbnails',
              ),
              choice(
                'Content language',
                'Language used for catalogue content.',
                'content_language',
                'en',
                const {
                  'en': 'English',
                  'id': 'Indonesian',
                  'ja': 'Japanese',
                  'ko': 'Korean',
                  'es': 'Spanish',
                },
              ),
              choice(
                'Content region',
                'Region used for discovery and music charts.',
                'content_country',
                'US',
                const {
                  'US': 'United States',
                  'ID': 'Indonesia',
                  'JP': 'Japan',
                  'KR': 'South Korea',
                  'GB': 'United Kingdom',
                },
              ),
            ]),
            group('Storage', [
              nav(
                'Import legacy profile',
                'Import playlists, history and settings from a Java preferences XML export.',
                Icons.file_open_outlined,
                app.importLegacyProfile,
              ),
            ]),
            group('Local Music', [
              toggleSetting(
                'Filter non-music audio',
                'Hide short clips, voice notes, recordings and system sounds.',
                'filter_non_music_audio',
              ),
              nav(
                'Add music',
                'Import audio files from your computer.',
                Icons.library_music_outlined,
                app.importLocalMusic,
              ),
              nav(
                'Open Local Music',
                '${app.localSongs.length} songs in your collection.',
                Icons.folder_open,
                () => app.navigate(AppRoute.localMusic),
              ),
            ]),
            group('Keyboard shortcuts', [
              for (final shortcut in desktopShortcuts)
                staticRow(shortcut.label, shortcut.keys),
              nav(
                'All shortcuts',
                'Open the keyboard reference.',
                Icons.keyboard_outlined,
                () => showDesktopShortcuts(context),
              ),
            ]),
            group('About', [
              nav(
                'BitChord Desktop Revived',
                '1.9.2 · Flutter rewrite',
                Icons.info_outline,
                () => showBitChordAbout(context, app),
              ),
              nav(
                'Source code',
                'Bntagaaa / BitChord-Desktop-Revived',
                Icons.code,
                () => app.openExternal(
                  'https://github.com/Bntagaaa/BitChord-Desktop-Revived',
                ),
              ),
              nav(
                'Open-source licences',
                'Libraries used by BitChord.',
                Icons.description_outlined,
                () => showLicensePage(
                  context: context,
                  applicationName: 'BitChord Desktop Revived',
                  applicationVersion: '1.9.2-Rewrite',
                ),
              ),
            ]),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 26),
              child: Column(
                children: [
                  Image.asset(
                    'assets/branding/app-icon.png',
                    width: 56,
                    height: 56,
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    'BitChord 1.9.2',
                    style: TextStyle(
                      fontFamily: 'BintangMusicBlack',
                      fontSize: 32,
                      fontWeight: FontWeight.w900,
                      letterSpacing: 0,
                    ),
                  ),
                  Text(
                    'Desktop Revived',
                    style: TextStyle(color: p.secondary, fontSize: 12),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  bool visible(String text) =>
      query.isEmpty || text.toLowerCase().contains(query);
  Widget group(String title, List<Widget?> children) {
    final items = children
        .whereType<Widget>()
        .where((item) => item is! _HiddenSetting)
        .toList();
    if (items.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 25, bottom: 9, left: 3),
          child: Text(title, style: Theme.of(context).textTheme.titleMedium),
        ),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 18),
          decoration: BoxDecoration(
            color: DesktopPalette.of(context).surface,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: DesktopPalette.of(context).edge),
          ),
          child: Column(
            children: [
              for (var i = 0; i < items.length; i++) ...[
                if (i != 0) const Divider(),
                items[i],
              ],
            ],
          ),
        ),
      ],
    );
  }

  Widget heading(String title, String subtitle) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(title, style: const TextStyle(fontWeight: FontWeight.w500)),
      if (subtitle.isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text(
            subtitle,
            style: TextStyle(
              color: DesktopPalette.of(context).secondary,
              fontSize: 12,
              height: 1.3,
            ),
          ),
        ),
    ],
  );
  Widget toggleSetting(
    String title,
    String subtitle,
    String key, {
    bool fallback = false,
  }) => toggle(
    title,
    subtitle,
    app.setting(key, fallback),
    (value) => app.setSetting(key, value),
  );
  Widget toggle(
    String title,
    String subtitle,
    bool value,
    ValueChanged<bool> changed,
  ) => visible('$title $subtitle')
      ? Padding(
          padding: const EdgeInsets.symmetric(vertical: 12),
          child: Row(
            children: [
              Expanded(child: heading(title, subtitle)),
              const SizedBox(width: 16),
              Switch.adaptive(
                value: value,
                onChanged: changed,
                activeTrackColor: DesktopPalette.of(context).navAccent,
              ),
            ],
          ),
        )
      : const _HiddenSetting();
  Widget choice<T>(
    String title,
    String subtitle,
    String key,
    T fallback,
    Map<T, String> values,
  ) => visible('$title $subtitle')
      ? Padding(
          padding: const EdgeInsets.symmetric(vertical: 14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              heading(title, subtitle),
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 7,
                children: values.entries
                    .map(
                      (entry) => ChoiceChip(
                        label: Text(entry.value),
                        selected: app.setting<T>(key, fallback) == entry.key,
                        onSelected: (_) => app.setSetting(key, entry.key),
                      ),
                    )
                    .toList(),
              ),
            ],
          ),
        )
      : const _HiddenSetting();
  Widget slider(
    String title,
    String subtitle,
    double value,
    double min,
    double max,
    ValueChanged<double> changed, {
    required String label,
    int? divisions,
  }) => visible('$title $subtitle')
      ? Padding(
          padding: const EdgeInsets.symmetric(vertical: 14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(child: heading(title, subtitle)),
                  const SizedBox(width: 12),
                  Text(label),
                ],
              ),
              Slider(
                value: value.clamp(min, max),
                min: min,
                max: max,
                divisions: divisions,
                onChanged: changed,
              ),
            ],
          ),
        )
      : const _HiddenSetting();
  Widget nav(
    String title,
    String subtitle,
    IconData icon,
    VoidCallback action,
  ) => visible('$title $subtitle')
      ? HoverTile(
          onTap: action,
          padding: const EdgeInsets.symmetric(vertical: 16),
          child: Row(
            children: [
              Icon(icon, size: 21, color: DesktopPalette.of(context).secondary),
              const SizedBox(width: 14),
              Expanded(child: heading(title, subtitle)),
              const SizedBox(width: 12),
              const Icon(Icons.chevron_right, size: 18),
            ],
          ),
        )
      : const _HiddenSetting();
  Widget staticRow(String title, String value) => visible('$title $value')
      ? Padding(
          padding: const EdgeInsets.symmetric(vertical: 14),
          child: Row(
            children: [
              Expanded(child: Text(title)),
              Text(
                value,
                style: TextStyle(
                  color: DesktopPalette.of(context).secondary,
                  fontSize: 12,
                ),
              ),
            ],
          ),
        )
      : const _HiddenSetting();
}

class _HiddenSetting extends StatelessWidget {
  const _HiddenSetting();
  @override
  Widget build(BuildContext context) => const SizedBox.shrink();
}

Future<void> showAccounts(BuildContext context, AppController app) =>
    showDialog<void>(
      context: context,
      builder: (context) => ListenableBuilder(
        listenable: app,
        builder: (context, _) => AlertDialog(
          title: const Text('Account'),
          content: SizedBox(
            width: 440,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Artwork(app.account?.thumbnailUrl, size: 56, radius: 28),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            app.signedIn
                                ? app.account?.name ?? 'YouTube Music'
                                : 'YouTube Music',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          Text(
                            app.account?.email ??
                                (app.signedIn ? 'Connected' : 'Not connected'),
                            style: TextStyle(
                              fontSize: 12,
                              color: DesktopPalette.of(context).secondary,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 20),
                if (!app.signedIn)
                  const Text(
                    'Connect an existing YouTube Music browser session to access your personal recommendations and library.',
                    style: TextStyle(height: 1.5),
                  ),
                if (!app.signedIn)
                  Padding(
                    padding: const EdgeInsets.only(top: 14),
                    child: FilledButton(
                      onPressed: () async {
                        Navigator.pop(context);
                        final cookie = await askText(
                          context,
                          title: 'Connect YouTube Music',
                          label: 'Cookie header',
                          helper: 'Paste the Cookie header from your signed-in music.youtube.com browser session. This is a secret; do not share it.',
                          action: 'Connect',
                          obscure: true,
                        );
                        if (cookie != null) await app.signInWithCookie(cookie);
                      },
                      child: const Text('Import browser session'),
                    ),
                  ),
                if (app.signedIn)
                  TextButton(
                    onPressed: () {
                      app.signOut();
                      Navigator.pop(context);
                    },
                    child: const Text('Sign out'),
                  ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Done'),
            ),
          ],
        ),
      ),
    );

Future<void> showAudioOutputs(BuildContext context, AppController app) =>
    showDialog<void>(
      context: context,
      builder: (context) => ListenableBuilder(
        listenable: app.playback,
        builder: (context, _) => AlertDialog(
          title: const Text('Audio output'),
          content: SizedBox(
            width: 430,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 400),
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (final device in app.playback.audioDevices)
                    ListTile(
                      leading: const Icon(Icons.headphones),
                      title: Text(
                        device.description.isEmpty
                            ? device.name
                            : device.description,
                      ),
                      trailing: app.playback.audioDevice?.name == device.name
                          ? const Icon(Icons.check)
                          : null,
                      onTap: () => app.playback.setAudioDevice(device),
                    ),
                  if (app.playback.audioDevices.isEmpty)
                    const Padding(
                      padding: EdgeInsets.all(16),
                      child: Text(
                        'Start playback to discover audio output devices.',
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
      ),
    );

Future<void> showEqualizer(BuildContext context, AppController app) async {
  final frequencies = EqualizerCurve.manualFrequencies;
  var bands = List<double>.of(app.playback.equalizer);
  var preset = equalizerPresets.keys.firstWhere(
    (name) => List.generate(
      bands.length,
      (i) => (equalizerPresets[name]![i] - bands[i]).abs() < .01,
    ).every((v) => v),
    orElse: () => 'Custom',
  );
  await showDialog<void>(
    context: context,
    builder: (context) => StatefulBuilder(
      builder: (context, setState) => AlertDialog(
        title: const Text('Equalizer'),
        content: SizedBox(
          width: 600,
          height: 330,
          child: Column(
            children: [
              Row(
                children: [
                  const Text('Preset'),
                  const SizedBox(width: 16),
                  Expanded(
                    child: DropdownButton<String>(
                      isExpanded: true,
                      value: preset,
                      items: ['Custom', ...equalizerPresets.keys]
                          .map(
                            (name) => DropdownMenuItem(
                              value: name,
                              child: Text(name),
                            ),
                          )
                          .toList(),
                      onChanged: (name) {
                        if (name == null || name == 'Custom') return;
                        setState(() {
                          preset = name;
                          bands = List<double>.of(equalizerPresets[name]!);
                        });
                        app.setSetting(
                          'equalizer_gains',
                          List<double>.of(bands),
                        );
                      },
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 18),
              Expanded(
                child: Row(
                  children: List.generate(
                    frequencies.length,
                    (i) => Expanded(
                      child: Column(
                        children: [
                          Text(
                            '${bands[i].round()} dB',
                            style: const TextStyle(fontSize: 11),
                          ),
                          Expanded(
                            child: RotatedBox(
                              quarterTurns: 3,
                              child: Slider(
                                value: bands[i].clamp(-12, 12),
                                min: -12,
                                max: 12,
                                divisions: 24,
                                onChanged: (value) {
                                  setState(() {
                                    bands[i] = value;
                                    preset = 'Custom';
                                  });
                                  app.setSetting(
                                    'equalizer_gains',
                                    List<double>.of(bands),
                                  );
                                },
                              ),
                            ),
                          ),
                          Text(
                            frequencies[i] >= 1000
                                ? '${frequencies[i] / 1000}k'
                                : '${frequencies[i].round()}',
                            style: const TextStyle(fontSize: 11),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () {
              setState(() {
                bands = List<double>.filled(frequencies.length, 0);
                preset = 'Flat';
              });
              app.setSetting('equalizer_gains', List<double>.of(bands));
            },
            child: const Text('Reset'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Done'),
          ),
        ],
      ),
    ),
  );
}

Future<void> showLyricsSources(BuildContext context, AppController app) async {
  final selected = app
      .setting<List<dynamic>>(
        'lyrics_providers',
        LyricsService.offeredProviders,
      )
      .cast<String>()
      .toList();
  final ordered = [
    ...selected,
    ...LyricsService.offeredProviders.where((p) => !selected.contains(p)),
  ];
  await showDialog<void>(
    context: context,
    builder: (context) => StatefulBuilder(
      builder: (context, setState) => AlertDialog(
        title: const Text('Lyrics sources'),
        content: SizedBox(
          width: 460,
          height: 460,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Enabled providers are tried in this order. Drag a source to change its priority.',
                style: TextStyle(
                  color: DesktopPalette.of(context).secondary,
                  height: 1.4,
                ),
              ),
              const SizedBox(height: 14),
              Expanded(
                child: ReorderableListView.builder(
                  itemCount: ordered.length,
                  buildDefaultDragHandles: false,
                  onReorderItem: (from, to) {
                    setState(() {
                      final value = ordered.removeAt(from);
                      ordered.insert(to, value);
                    });
                    app.setSetting(
                      'lyrics_providers',
                      ordered.where(selected.contains).toList(),
                    );
                  },
                  itemBuilder: (context, index) => CheckboxListTile(
                    key: ValueKey(ordered[index]),
                    title: Text(ordered[index]),
                    value: selected.contains(ordered[index]),
                    secondary: ReorderableDragStartListener(
                      index: index,
                      child: const Icon(Icons.drag_handle),
                    ),
                    onChanged: (value) {
                      setState(() {
                        if (value == true) {
                          selected.add(ordered[index]);
                        } else {
                          selected.remove(ordered[index]);
                        }
                      });
                      app.setSetting(
                        'lyrics_providers',
                        ordered.where(selected.contains).toList(),
                      );
                    },
                  ),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Done'),
          ),
        ],
      ),
    ),
  );
}

void showBitChordAbout(BuildContext context, AppController app) =>
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        content: SizedBox(
          width: 450,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const SizedBox(height: 16),
              Image.asset(
                'assets/branding/app-icon.png',
                width: 100,
                height: 100,
              ),
              const SizedBox(height: 22),
              const Text(
                'BitChord 1.9.2',
                style: TextStyle(
                  fontFamily: 'BintangMusicBlack',
                  fontSize: 32,
                  fontWeight: FontWeight.w900,
                  letterSpacing: 0,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                'Desktop Revived',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 12),
              const SelectableText('1.9.2-Rewrite'),
              const SizedBox(height: 18),
              const Text('YouTube Music Backend', textAlign: TextAlign.center),
              const SizedBox(height: 20),
              TextButton(
                onPressed: () => app.openExternal(
                  'https://github.com/Bntagaaa/BitChord-Desktop-Revived',
                ),
                child: const Text('View source on GitHub'),
              ),
            ],
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

Future<void> showAddonSources(BuildContext context, AppController app) =>
    showDialog<void>(
      context: context,
      builder: (dialogContext) => ListenableBuilder(
        listenable: app,
        builder: (context, _) => AlertDialog(
          title: const Text('Music sources'),
          content: SizedBox(
            width: 530,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 450),
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Enabled sources are checked in order. YouTube Music remains available as the fallback.',
                      style: TextStyle(
                        color: DesktopPalette.of(context).secondary,
                        height: 1.4,
                      ),
                    ),
                    const SizedBox(height: 16),
                    for (final source in app.addonSources)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 5),
                        child: Row(
                          children: [
                            const Icon(Icons.extension_outlined, size: 22),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    source.label,
                                    style: const TextStyle(
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                  Text(
                                    source.baseUrl,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: DesktopPalette.of(context)
                                          .secondary,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            Switch.adaptive(
                              value: source.enabled,
                              onChanged: (value) =>
                                  app.toggleAddon(source.id, value),
                            ),
                            ToolButton(
                              icon: Icons.arrow_upward,
                              label: 'Move source earlier',
                              size: 28,
                              iconSize: 16,
                              onPressed: app.addonSources.indexOf(source) == 0
                                  ? null
                                  : () => app.reorderAddons(
                                      app.addonSources.indexOf(source),
                                      app.addonSources.indexOf(source) - 1,
                                    ),
                            ),
                            ToolButton(
                              icon: Icons.arrow_downward,
                              label: 'Move source later',
                              size: 28,
                              iconSize: 16,
                              onPressed:
                                  app.addonSources.indexOf(source) ==
                                      app.addonSources.length - 1
                                  ? null
                                  : () => app.reorderAddons(
                                      app.addonSources.indexOf(source),
                                      app.addonSources.indexOf(source) + 1,
                                    ),
                            ),
                            ToolButton(
                              icon: Icons.delete_outline,
                              label: 'Remove source',
                              onPressed: () => app.removeAddon(source.id),
                            ),
                          ],
                        ),
                      ),
                    const Divider(),
                    const ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(Icons.music_note_outlined),
                      title: Text('YouTube Music'),
                      subtitle: Text('Default catalogue and playback fallback'),
                      trailing: Icon(Icons.check),
                    ),
                    if (app.error != null)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 10),
                        child: Text(
                          app.error!,
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.error,
                            fontSize: 12,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
          actions: [
            TextButton.icon(
              onPressed: () async {
                final value = await askText(
                  context,
                  title: 'Add music source',
                  label: 'Manifest URL',
                  helper: 'Paste the HTTPS URL to the add-on manifest.json.',
                  action: 'Add source',
                );
                if (value != null) await app.addAddon(value);
              },
              icon: const Icon(Icons.add, size: 18),
              label: const Text('Add source'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Done'),
            ),
          ],
        ),
      ),
    );
