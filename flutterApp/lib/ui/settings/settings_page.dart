import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import '../../domain/application.dart';
import '../theme.dart';
import 'settings_widgets.dart';
import 'settings_dialogs.dart';

enum SettingsSubPage {
  main,
  contentPreferences,
  licenses,
}

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.app});

  final MusicApplication app;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  SettingsSubPage _subPage = SettingsSubPage.main;
  String _searchQuery = '';

  @override
  void initState() {
    super.initState();
    widget.app.settings.addListener(_onSettingsChanged);
  }

  @override
  void dispose() {
    widget.app.settings.removeListener(_onSettingsChanged);
    super.dispose();
  }

  void _onSettingsChanged() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    if (_subPage == SettingsSubPage.contentPreferences) {
      return DesktopContentPreferencesPage(
        app: widget.app,
        onBack: () => setState(() => _subPage = SettingsSubPage.main),
      );
    }

    if (_subPage == SettingsSubPage.licenses) {
      return DesktopLicensesPage(
        onBack: () => setState(() => _subPage = SettingsSubPage.main),
      );
    }

    final palette = paletteOf(context);
    final settings = widget.app.settings;

    // Define all 18 categorized section specs in exact Kotlin Compose order
    final sections = [
      // 1. ACCOUNT
      _SettingsSectionSpec(
        title: 'ACCOUNT',
        keywords: const ['account', 'integrations', 'spotify', 'discord', 'last.fm', 'listenbrainz'],
        builder: (ctx) => SettingsGroup(
          title: 'ACCOUNT',
          children: [
            SettingsRow(
              icon: Icons.account_circle_rounded,
              title: 'Account & integrations',
              subtitle: 'Connect Discord, Last.fm, ListenBrainz and Spotify.',
              onTap: () {
                showDesktopDialog(
                  context: ctx,
                  builder: (_) => DesktopIntegrationsDialog(app: widget.app),
                );
              },
            ),
          ],
        ),
      ),

      // 2. LISTEN TOGETHER
      _SettingsSectionSpec(
        title: 'LISTEN TOGETHER',
        keywords: const ['listen together', 'party', 'room', 'share', 'sync'],
        builder: (ctx) => SettingsGroup(
          title: 'LISTEN TOGETHER',
          children: [
            SettingsNavigationRow(
              title: 'Listen together',
              subtitle: 'Host or join a shared playback room across devices.',
              onTap: () {
                showDesktopDialog(
                  context: ctx,
                  maxWidth: 920.0,
                  builder: (_) => DesktopListenTogetherDialog(app: widget.app),
                );
              },
            ),
          ],
        ),
      ),

      // 3. SOURCES
      _SettingsSectionSpec(
        title: 'SOURCES',
        keywords: const ['sources', 'youtube', 'jiosaavn', 'add a source', 'addon'],
        builder: (ctx) {
          final rawCustom = settings.get('custom_sources', '[]');
          List<Map<String, dynamic>> customSources = [];
          try {
            final decoded = jsonDecode(rawCustom);
            if (decoded is List) {
              customSources = decoded.whereType<Map<String, dynamic>>().toList();
            }
          } catch (_) {}

          return SettingsGroup(
            title: 'SOURCES',
            children: [
              SettingsRow(
                icon: Icons.play_circle_filled_rounded,
                title: 'YouTube Music',
                subtitle: 'Default provider · Always enabled',
              ),
              SettingsRow(
                icon: Icons.music_note_rounded,
                title: 'JioSaavn',
                subtitle: 'High quality audio stream provider',
              ),
              for (final src in customSources)
                SettingsRow(
                  icon: Icons.extension_rounded,
                  title: src['name'] as String? ?? 'Custom Source',
                  subtitle: src['url'] as String? ?? '',
                  onTap: () {
                    showDesktopDialog(
                      context: ctx,
                      maxWidth: 440.0,
                      builder: (_) => DesktopSourceEditorDialog(
                        app: widget.app,
                        sourceId: src['id'] as String?,
                        initialName: src['name'] as String? ?? '',
                        initialUrl: src['url'] as String? ?? '',
                      ),
                    );
                  },
                ),
              SettingsRow(
                icon: Icons.add_rounded,
                title: 'Add a source',
                subtitle: 'Connect an external addon or custom source index.',
                onTap: () {
                  showDesktopDialog(
                    context: ctx,
                    maxWidth: 440.0,
                    builder: (_) => DesktopSourceEditorDialog(app: widget.app),
                  );
                },
              ),
            ],
          );
        },
      ),

      // 4. AUDIO QUALITY
      _SettingsSectionSpec(
        title: 'AUDIO QUALITY',
        keywords: const ['audio quality', 'bitrate', 'low', 'medium', 'high', 'lossless'],
        builder: (ctx) {
          final quality = settings.get('audio_quality', 'LOSSLESS');
          final qualityDesc = switch (quality) {
            'LOW' => 'Saves data. Streams at 64 kbps AAC/Opus.',
            'MEDIUM' => 'Balanced. Streams at ~171 kbps Opus.',
            'HIGH' => 'High quality. Streams at 320 kbps AAC/Opus.',
            _ => 'Best available source stream up to uncompressed lossless FLAC.',
          };
          return SettingsGroup(
            title: 'AUDIO QUALITY',
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8.0),
                child: FilterChipGroup<String>(
                  items: const [
                    FilterChipItem(value: 'LOW', label: 'Low'),
                    FilterChipItem(value: 'MEDIUM', label: 'Medium'),
                    FilterChipItem(value: 'HIGH', label: 'High'),
                    FilterChipItem(value: 'LOSSLESS', label: 'Lossless'),
                  ],
                  selected: quality,
                  onSelected: (val) {
                    unawaited(settings.set('audio_quality', val));
                    setState(() {});
                  },
                ),
              ),
              Text(qualityDesc, style: TextStyle(color: palette.secondary, fontSize: 13)),
            ],
          );
        },
      ),

      // 5. DOWNLOADS
      _SettingsSectionSpec(
        title: 'DOWNLOADS',
        keywords: const ['downloads', 'download quality', 'standard', 'high', 'lossless'],
        builder: (ctx) {
          final dlQuality = settings.get('download_quality', settings.get('audio_quality_download', 'STANDARD'));
          return SettingsGroup(
            title: 'DOWNLOADS',
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8.0),
                child: FilterChipGroup<String>(
                  items: const [
                    FilterChipItem(value: 'STANDARD', label: 'Standard'),
                    FilterChipItem(value: 'HIGH', label: 'High'),
                    FilterChipItem(value: 'LOSSLESS', label: 'Lossless'),
                  ],
                  selected: dlQuality,
                  onSelected: (val) {
                    unawaited(settings.set('download_quality', val));
                    unawaited(settings.set('audio_quality_download', val));
                    setState(() {});
                  },
                ),
              ),
            ],
          );
        },
      ),

      // 6. OUTPUT PRECISION
      _SettingsSectionSpec(
        title: 'OUTPUT PRECISION',
        keywords: const ['output precision', 'pcm_16', 'float_32', '16-bit pcm', '32-bit float'],
        builder: (ctx) {
          final precision = settings.get('output_precision', settings.get('output_pcm_mode', 'PCM_16'));
          final caption = precision == 'FLOAT_32'
              ? 'Playing at 48 kHz, 2 channels, 32-bit IEEE float'
              : 'Playing at 48 kHz, 2 channels, 16-bit signed integer PCM';
          return SettingsGroup(
            title: 'OUTPUT PRECISION',
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8.0),
                child: FilterChipGroup<String>(
                  items: const [
                    FilterChipItem(value: 'PCM_16', label: '16-bit PCM'),
                    FilterChipItem(value: 'FLOAT_32', label: '32-bit float'),
                  ],
                  selected: precision,
                  onSelected: (val) {
                    unawaited(settings.set('output_precision', val));
                    unawaited(settings.set('output_pcm_mode', val));
                    setState(() {});
                  },
                ),
              ),
              Text(caption, style: TextStyle(color: palette.secondary, fontSize: 13)),
            ],
          );
        },
      ),

      // 7. AUDIO OUTPUT
      _SettingsSectionSpec(
        title: 'AUDIO OUTPUT',
        keywords: const ['audio output', 'output device', 'speakers', 'headphones', 'sound'],
        builder: (ctx) {
          final device = settings.get('audio_output_device', 'SYSTEM_DEFAULT');
          final label = device == 'SYSTEM_DEFAULT' ? 'System default' : device;
          return SettingsGroup(
            title: 'AUDIO OUTPUT',
            children: [
              SettingsNavigationRow(
                title: 'Output device',
                subtitle: label,
                onTap: () {
                  showDesktopDialog(
                    context: ctx,
                    maxWidth: 440.0,
                    builder: (_) => DesktopAudioOutputDialog(app: widget.app),
                  );
                },
              ),
            ],
          );
        },
      ),

      // 8. PLAYBACK
      _SettingsSectionSpec(
        title: 'PLAYBACK',
        keywords: const [
          'playback',
          'music video playback (beta)',
          'music video',
          'crossfade',
          'automix',
          'automix beta',
          'automix duration',
          'automix performance',
          'skip silence',
          'spatial audio',
          'dolby atmos',
          'equalizer',
        ],
        builder: (ctx) {
          final videoBeta = settings.get('music_video_beta', settings.get('video_beta_enabled', false));
          final automix = settings.get('automix', false);
          final crossfadeSec = settings.get('crossfade_seconds', 0);
          final automixDur = settings.get('automix_duration', 'ADAPTIVE');
          final automixPerf = settings.get('automix_performance', settings.get('automix_performance_mode', 'STANDARD'));
          final skipSilence = settings.get('skip_silence', false);
          final spatialAudio = settings.get('spatial_audio', false);
          final dolbyAtmos = settings.get('dolby_atmos', false);

          return SettingsGroup(
            title: 'PLAYBACK',
            children: [
              SettingsToggle(
                title: 'Music video playback (Beta)',
                subtitle: 'Play video stream instead of audio stream when available.',
                value: videoBeta,
                onChanged: (val) {
                  unawaited(settings.set('music_video_beta', val));
                  unawaited(settings.set('video_beta_enabled', val));
                  setState(() {});
                },
              ),
              if (!automix) ...[
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 8.0, vertical: 6.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Crossfade: ${crossfadeSec}s',
                        style: Theme.of(ctx).textTheme.bodyLarge?.copyWith(color: palette.foreground),
                      ),
                      const SizedBox(height: 2.0),
                      Text(
                        crossfadeSec == 0 ? 'Gapless transition' : '${crossfadeSec}s overlap between consecutive tracks',
                        style: Theme.of(ctx).textTheme.bodyMedium?.copyWith(color: palette.secondary),
                      ),
                      SettingsBareSlider(
                        sliderKey: const ValueKey('crossfade_slider'),
                        value: crossfadeSec.toDouble(),
                        min: 0.0,
                        max: 12.0,
                        divisions: 12,
                        onChanged: (val) {
                          unawaited(settings.set('crossfade_seconds', val.round()));
                          setState(() {});
                        },
                      ),
                    ],
                  ),
                ),
              ],
              SettingsToggle(
                title: 'Automix [BETA]',
                subtitle: 'Dynamic beat-matched transitions between songs.',
                value: automix,
                onChanged: (val) {
                  unawaited(settings.set('automix', val));
                  setState(() {});
                },
              ),
              if (automix) ...[
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 8.0, vertical: 6.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Automix duration', style: TextStyle(color: palette.foreground, fontWeight: FontWeight.w500)),
                      const SizedBox(height: 6.0),
                      FilterChipGroup<String>(
                        items: const [
                          FilterChipItem(value: 'ADAPTIVE', label: 'Adaptive'),
                          FilterChipItem(value: 'BARS_8', label: '8 Bars'),
                          FilterChipItem(value: 'BARS_16', label: '16 Bars'),
                        ],
                        selected: automixDur,
                        onSelected: (val) {
                          unawaited(settings.set('automix_duration', val));
                          setState(() {});
                        },
                      ),
                      const SizedBox(height: 8.0),
                      Text('Automix performance', style: TextStyle(color: palette.foreground, fontWeight: FontWeight.w500)),
                      const SizedBox(height: 6.0),
                      FilterChipGroup<String>(
                        items: const [
                          FilterChipItem(value: 'ECO', label: 'Eco'),
                          FilterChipItem(value: 'STANDARD', label: 'Standard'),
                          FilterChipItem(value: 'SPEED', label: 'Speed'),
                        ],
                        selected: automixPerf,
                        onSelected: (val) {
                          unawaited(settings.set('automix_performance', val));
                          unawaited(settings.set('automix_performance_mode', val));
                          setState(() {});
                        },
                      ),
                    ],
                  ),
                ),
              ],
              SettingsToggle(
                title: 'Skip silence',
                subtitle: 'Automatically skips dead air at track beginning and end.',
                value: skipSilence,
                onChanged: (val) {
                  unawaited(settings.set('skip_silence', val));
                  setState(() {});
                },
              ),
              SettingsToggle(
                title: 'Spatial audio',
                subtitle: 'Stereo soundstage widening expander.',
                value: spatialAudio,
                onChanged: (val) {
                  unawaited(settings.set('spatial_audio', val));
                  setState(() {});
                },
              ),
              SettingsToggle(
                title: 'Dolby Atmos',
                subtitle: 'Prefer immersive multichannel add-on streams.',
                value: dolbyAtmos,
                onChanged: (val) {
                  unawaited(settings.set('dolby_atmos', val));
                  setState(() {});
                },
              ),
              SettingsNavigationRow(
                title: 'Equalizer',
                subtitle: 'Dynamic tone pad and 7-band manual faders.',
                onTap: () {
                  showDesktopDialog(
                    context: ctx,
                    maxWidth: 560.0,
                    builder: (_) => DesktopEqualizerDialog(app: widget.app),
                  );
                },
              ),
            ],
          );
        },
      ),

      // 9. CONTENT
      _SettingsSectionSpec(
        title: 'CONTENT',
        keywords: const ['content', 'content preferences', 'recents', 'videos', 'top picks', 'find your mood'],
        builder: (ctx) => SettingsGroup(
          title: 'CONTENT',
          children: [
            SettingsNavigationRow(
              title: 'Content Preferences',
              subtitle: 'Filter music videos, home shelves, and explore cards.',
              onTap: () {
                setState(() => _subPage = SettingsSubPage.contentPreferences);
              },
            ),
          ],
        ),
      ),

      // 10. DESKTOP PLAYBACK
      _SettingsSectionSpec(
        title: 'DESKTOP PLAYBACK',
        keywords: const [
          'desktop playback',
          'autoplay',
          'shuffle',
          'repeat',
          'playback speed',
          'direct source stream',
          'direct source',
          'sleep timer',
        ],
        builder: (ctx) {
          final autoplay = settings.get('autoplay', true);
          final shuffle = settings.get('shuffle', settings.get('shuffle_enabled', false));
          final repeatMode = settings.get('repeat_mode', 'OFF');
          final speed = settings.get('playback_speed', '1.0');
          final directSource = settings.get('direct_source_stream', false);
          final sleepMinutes = settings.get('sleep_timer_minutes', 0);

          return SettingsGroup(
            title: 'DESKTOP PLAYBACK',
            children: [
              SettingsToggle(
                title: 'Autoplay',
                subtitle: 'Keep playing similar tracks when your queue ends.',
                value: autoplay,
                onChanged: (val) {
                  unawaited(settings.set('autoplay', val));
                  setState(() {});
                },
              ),
              SettingsToggle(
                title: 'Shuffle',
                subtitle: 'Randomize track playback sequence.',
                value: shuffle,
                onChanged: (val) {
                  unawaited(settings.set('shuffle', val));
                  unawaited(settings.set('shuffle_enabled', val));
                  setState(() {});
                },
              ),
              SettingsRow(
                icon: Icons.repeat_rounded,
                title: 'Repeat',
                subtitle: 'Current mode: $repeatMode (tap to cycle: OFF → ONE → ALL)',
                onTap: () {
                  final next = switch (repeatMode) {
                    'OFF' => 'ONE',
                    'ONE' => 'ALL',
                    _ => 'OFF',
                  };
                  unawaited(settings.set('repeat_mode', next));
                  setState(() {});
                },
              ),
              SettingsRow(
                icon: Icons.speed_rounded,
                title: 'Playback speed',
                subtitle: 'Current speed: ${speed}× (tap to cycle: 0.5× → 1.0× → 1.25× → 1.5× → 2.0×)',
                onTap: () {
                  final next = switch (speed) {
                    '0.5' => '1.0',
                    '1.0' => '1.25',
                    '1.25' => '1.5',
                    '1.5' => '2.0',
                    _ => '0.5',
                  };
                  unawaited(settings.set('playback_speed', next));
                  setState(() {});
                },
              ),
              SettingsToggle(
                title: 'Direct source stream',
                subtitle: 'Connect directly to high priority providers before falling back.',
                value: directSource,
                onChanged: (val) {
                  unawaited(settings.set('direct_source_stream', val));
                  setState(() {});
                },
              ),
              SettingsRow(
                icon: Icons.bedtime_rounded,
                title: 'Sleep timer',
                subtitle: sleepMinutes == 0
                    ? 'Off'
                    : (sleepMinutes == -1 ? 'End of song' : '${sleepMinutes} minutes'),
                onTap: () {
                  final next = switch (sleepMinutes) {
                    0 => 15,
                    15 => 30,
                    30 => 45,
                    45 => 60,
                    60 => -1,
                    _ => 0,
                  };
                  unawaited(settings.set('sleep_timer_minutes', next));
                  setState(() {});
                },
              ),
            ],
          );
        },
      ),

      // 11. APPEARANCE
      _SettingsSectionSpec(
        title: 'APPEARANCE',
        keywords: const [
          'appearance',
          'theme',
          'dark',
          'light',
          'system',
          'pitch black background',
          'pitch black',
          'font',
          'app icon',
          'ui scale',
          'use old explore cards',
          'old explore cards',
          'enable old top music player style',
          'top music player style',
          'reduce dynamic blur',
          'reduce blur',
          'full-screen cover art',
          'full-screen cover',
          'player theme',
          'legacy',
          'simple',
          'animated canvas',
          'canvas',
          'spotify canvas',
        ],
        builder: (ctx) {
          final themeMode = settings.get('desktop_theme_mode', 'SYSTEM');
          final pitchBlack = settings.get('desktop_pitch_black', false);
          final font = settings.get('app_font', 'DEFAULT');
          final appIcon = settings.get('desktop_app_icon', 'DARK');
          final uiScale = settings.get('desktop_ui_scale_percent', 100);
          final oldCards = settings.get('use_old_explore_cards', false);
          final oldTopPlayer = settings.get('enable_old_top_music_player_style', false);
          final reduceBlur = settings.get('reduce_dynamic_blur', false);
          final fullBleed = settings.get('full_bleed_artwork', false);
          final playerTheme = settings.get('player_theme', 'DEFAULT');
          final canvas = settings.get('animated_canvas', false);

          return SettingsGroup(
            title: 'APPEARANCE',
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 6.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Theme', style: TextStyle(color: palette.foreground, fontWeight: FontWeight.w500)),
                    const SizedBox(height: 6.0),
                    FilterChipGroup<String>(
                      items: const [
                        FilterChipItem(value: 'SYSTEM', label: 'System'),
                        FilterChipItem(value: 'LIGHT', label: 'Light'),
                        FilterChipItem(value: 'DARK', label: 'Dark'),
                      ],
                      selected: themeMode,
                      onSelected: (val) {
                        unawaited(settings.set('desktop_theme_mode', val));
                        setState(() {});
                      },
                    ),
                  ],
                ),
              ),
              if (themeMode == 'DARK') ...[
                SettingsToggle(
                  title: 'Pitch black background',
                  subtitle: 'Pure OLED black background surfaces.',
                  value: pitchBlack,
                  onChanged: (val) {
                    unawaited(settings.set('desktop_pitch_black', val));
                    setState(() {});
                  },
                ),
              ],
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 6.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Font', style: TextStyle(color: palette.foreground, fontWeight: FontWeight.w500)),
                    const SizedBox(height: 6.0),
                    FilterChipGroup<String>(
                      items: const [
                        FilterChipItem(value: 'DEFAULT', label: 'Default'),
                        FilterChipItem(value: 'LEGACY', label: 'Legacy'),
                      ],
                      selected: font,
                      onSelected: (val) {
                        unawaited(settings.set('app_font', val));
                        setState(() {});
                      },
                    ),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 6.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('App icon', style: TextStyle(color: palette.foreground, fontWeight: FontWeight.w500)),
                    AppIconPicker(
                      selected: appIcon,
                      onSelected: (val) {
                        unawaited(settings.set('desktop_app_icon', val));
                        setState(() {});
                      },
                    ),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8.0, vertical: 8.0),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text('UI scale', style: TextStyle(color: palette.foreground, fontSize: 15)),
                    DropdownButton<int>(
                      value: uiScale,
                      dropdownColor: palette.surfaceRaised,
                      items: const [
                        DropdownMenuItem(value: 80, child: Text('80%')),
                        DropdownMenuItem(value: 90, child: Text('90%')),
                        DropdownMenuItem(value: 100, child: Text('100%')),
                        DropdownMenuItem(value: 110, child: Text('110%')),
                        DropdownMenuItem(value: 125, child: Text('125%')),
                      ],
                      onChanged: (val) {
                        if (val != null) {
                          unawaited(settings.set('desktop_ui_scale_percent', val));
                          setState(() {});
                        }
                      },
                    ),
                  ],
                ),
              ),
              SettingsToggle(
                title: 'Use old Explore cards',
                subtitle: 'Angled gradient cards in Explore from v1.7.',
                value: oldCards,
                onChanged: (val) {
                  unawaited(settings.set('use_old_explore_cards', val));
                  setState(() {});
                },
              ),
              SettingsToggle(
                title: 'Enable old top music player style',
                subtitle: 'Docked player at the top of the window instead of floating bar.',
                value: oldTopPlayer,
                onChanged: (val) {
                  unawaited(settings.set('enable_old_top_music_player_style', val));
                  setState(() {});
                },
              ),
              SettingsToggle(
                title: 'Reduce dynamic blur',
                subtitle: 'Replace frosted glass blur with solid card fills.',
                value: reduceBlur,
                onChanged: (val) {
                  unawaited(settings.set('reduce_dynamic_blur', val));
                  setState(() {});
                },
              ),
              SettingsToggle(
                title: 'Full-screen cover art',
                subtitle: 'Extend album artwork to the edges of the player.',
                value: fullBleed,
                onChanged: (val) {
                  unawaited(settings.set('full_bleed_artwork', val));
                  setState(() {});
                },
              ),
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Player theme', style: TextStyle(color: palette.foreground, fontWeight: FontWeight.w500)),
                    const SizedBox(height: 8.0),
                    SettingsSegmentedControl<String>(
                      items: const [
                        FilterChipItem(value: 'DEFAULT', label: 'Default'),
                        FilterChipItem(value: 'LEGACY', label: 'Legacy'),
                        FilterChipItem(value: 'SIMPLE', label: 'Simple'),
                      ],
                      selected: playerTheme,
                      onSelected: (val) {
                        unawaited(settings.set('player_theme', val));
                        setState(() {});
                      },
                    ),
                  ],
                ),
              ),
              SettingsToggle(
                title: 'Animated canvas',
                subtitle: 'Show looping motion cover art when available.',
                value: canvas,
                onChanged: (val) {
                  unawaited(settings.set('animated_canvas', val));
                  setState(() {});
                },
              ),
              if (canvas) ...[
                SettingsNavigationRow(
                  title: 'Spotify Canvas setup',
                  subtitle: 'Configure session cookie for Spotify Canvas streaming.',
                  onTap: () {
                    showDesktopDialog(
                      context: ctx,
                      maxWidth: 420.0,
                      builder: (_) => DesktopSpotifyCanvasDialog(app: widget.app),
                    );
                  },
                ),
              ],
            ],
          );
        },
      ),

      // 12. LYRICS
      _SettingsSectionSpec(
        title: 'LYRICS',
        keywords: const [
          'lyrics',
          'synced lyrics',
          'continue lyrics animation when unfocused',
          'blur unfocused lyrics',
          'lyrics sources',
          'translation language',
        ],
        builder: (ctx) {
          final synced = settings.get('synced_lyrics', true);
          final unfocusedAnim = settings.get('animate_lyrics_while_unfocused', false);
          final blurLyrics = settings.get('lyrics_blur', false);
          final transLang = settings.get('translation_language', '');

          return SettingsGroup(
            title: 'LYRICS',
            children: [
              SettingsToggle(
                title: 'Synced lyrics',
                subtitle: 'Scroll lyrics line-by-line in real time.',
                value: synced,
                onChanged: (val) {
                  unawaited(settings.set('synced_lyrics', val));
                  setState(() {});
                },
              ),
              SettingsToggle(
                title: 'Continue lyrics animation when unfocused',
                subtitle: 'Maintain 60fps lyric smooth gliding in background.',
                value: unfocusedAnim,
                onChanged: (val) {
                  unawaited(settings.set('animate_lyrics_while_unfocused', val));
                  setState(() {});
                },
              ),
              SettingsToggle(
                title: 'Blur unfocused lyrics',
                subtitle: 'Blurs surrounding lyrics to highlight the current vocal line.',
                value: blurLyrics,
                onChanged: (val) {
                  unawaited(settings.set('lyrics_blur', val));
                  setState(() {});
                },
              ),
              SettingsNavigationRow(
                title: 'Lyrics sources',
                subtitle: 'Reorder and toggle lyrics providers.',
                onTap: () {
                  showDesktopDialog(
                    context: ctx,
                    maxWidth: 420.0,
                    builder: (_) => DesktopLyricsSourcesDialog(app: widget.app),
                  );
                },
              ),
              SettingsNavigationRow(
                title: 'Translation language',
                subtitle: transLang.isEmpty ? 'Follow the app language' : transLang,
                onTap: () {
                  showDesktopDialog(
                    context: ctx,
                    maxWidth: 420.0,
                    builder: (_) => DesktopTranslationLanguageDialog(app: widget.app),
                  );
                },
              ),
            ],
          );
        },
      ),

      // 13. LOCAL MUSIC
      _SettingsSectionSpec(
        title: 'LOCAL MUSIC',
        keywords: const [
          'local music',
          'local music folder',
          'use all audio folders',
          'filter non-music audio',
        ],
        builder: (ctx) {
          final localFolder = settings.get('local_music_folder', '');
          final filterAudio = settings.get('filter_non_music_audio', false);

          return SettingsGroup(
            title: 'LOCAL MUSIC',
            children: [
              SettingsRow(
                icon: Icons.folder_open_rounded,
                title: 'Local music folder',
                subtitle: localFolder.isEmpty ? 'System default Music & Downloads folders' : localFolder,
                onTap: () async {
                  final selected = await FilePicker.platform.getDirectoryPath();
                  if (selected != null) {
                    unawaited(settings.set('local_music_folder', selected));
                    unawaited(widget.app.scanLocal(selected));
                    setState(() {});
                  }
                },
              ),
              if (localFolder.isNotEmpty) ...[
                SettingsRow(
                  icon: Icons.library_music_rounded,
                  title: 'Use all audio folders',
                  subtitle: 'Reset to system default Music and Downloads directories.',
                  onTap: () {
                    unawaited(settings.set('local_music_folder', ''));
                    unawaited(widget.app.scanLocal(''));
                    setState(() {});
                  },
                ),
              ],
              SettingsToggle(
                title: 'Filter non-music audio',
                subtitle: 'Ignore recordings, voice memos, and clips under 30 seconds.',
                value: filterAudio,
                onChanged: (val) {
                  unawaited(settings.set('filter_non_music_audio', val));
                  setState(() {});
                },
              ),
            ],
          );
        },
      ),

      // 14. STORAGE
      _SettingsSectionSpec(
        title: 'STORAGE',
        keywords: const [
          'storage',
          'cache',
          'song cache limit',
          'clear song cache',
          'clear image cache',
        ],
        builder: (ctx) {
          final cacheLimit = settings.get('media_cache_limit_mb', 512);

          return SettingsGroup(
            title: 'STORAGE',
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8.0, vertical: 6.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Song cache limit: $cacheLimit MB',
                      style: Theme.of(ctx).textTheme.bodyLarge?.copyWith(color: palette.foreground),
                    ),
                    const SizedBox(height: 2.0),
                    Text(
                      'Keeps media on disk. Playback audio ranges use a separate cache capped at 256 MB.',
                      style: Theme.of(ctx).textTheme.bodyMedium?.copyWith(color: palette.secondary),
                    ),
                    SettingsBareSlider(
                      sliderKey: const ValueKey('cache_limit_slider'),
                      value: cacheLimit.toDouble(),
                      min: 128.0,
                      max: 10240.0,
                      divisions: 18,
                      onChanged: (val) {
                        unawaited(settings.set('media_cache_limit_mb', val.round()));
                        setState(() {});
                      },
                    ),
                  ],
                ),
              ),
              SettingsRow(
                icon: Icons.delete_sweep_rounded,
                title: 'Clear song cache',
                subtitle: 'Frees space used by cached media files.',
                onTap: () {
                  showDesktopDialog(
                    context: ctx,
                    maxWidth: 480.0,
                    builder: (_) => DesktopStorageAndDataDialog(app: widget.app),
                  );
                },
              ),
              SettingsRow(
                icon: Icons.photo_library_rounded,
                title: 'Clear image cache',
                subtitle: 'Frees space used by cached album covers.',
                onTap: () {
                  showDesktopDialog(
                    context: ctx,
                    maxWidth: 480.0,
                    builder: (_) => DesktopStorageAndDataDialog(app: widget.app),
                  );
                },
              ),
            ],
          );
        },
      ),

      // 15. MISCELLANEOUS
      _SettingsSectionSpec(
        title: 'MISCELLANEOUS',
        keywords: const [
          'miscellaneous',
          'misc',
          "don't repeat",
          'dont repeat',
          'don’t repeat songs in current session',
          'repeat songs',
          'tray icon',
          'keep playing when closed',
          'switch to pip mode when closing app',
          'hide volume bar',
        ],
        builder: (ctx) {
          final dontRepeat = settings.get('dont_repeat_suggestions', false);
          final trayIcon = settings.get('tray_icon', true);
          final closeToTray = settings.get('close_to_tray', false);
          final pipOnClose = settings.get('pip_on_close', false);
          final hideVolume = settings.get('hide_volume_bar', false);

          return SettingsGroup(
            title: 'MISCELLANEOUS',
            children: [
              SettingsToggle(
                title: 'Don’t repeat songs in current session',
                subtitle: 'Prevent recommendations from replaying songs.',
                value: dontRepeat,
                onChanged: (val) {
                  unawaited(settings.set('dont_repeat_suggestions', val));
                  setState(() {});
                },
              ),
              SettingsToggle(
                title: 'Tray icon',
                subtitle: 'Show BitChord icon with media controls in system tray.',
                value: trayIcon,
                onChanged: (val) {
                  unawaited(settings.set('tray_icon', val));
                  setState(() {});
                },
              ),
              if (trayIcon) ...[
                SettingsToggle(
                  title: 'Keep playing when closed',
                  subtitle: 'Minimize to system tray instead of closing application.',
                  value: closeToTray,
                  onChanged: (val) {
                    unawaited(settings.set('close_to_tray', val));
                    setState(() {});
                  },
                ),
              ],
              SettingsToggle(
                title: 'Switch to PiP Mode When Closing App',
                subtitle: 'Open floating mini-player when closing main window.',
                value: pipOnClose,
                onChanged: (val) {
                  unawaited(settings.set('pip_on_close', val));
                  setState(() {});
                },
              ),
              SettingsToggle(
                title: 'Hide volume bar',
                subtitle: 'Hide the volume slider on the player bar.',
                value: hideVolume,
                onChanged: (val) {
                  unawaited(settings.set('hide_volume_bar', val));
                  setState(() {});
                },
              ),
            ],
          );
        },
      ),

      // 16. LANGUAGE
      _SettingsSectionSpec(
        title: 'LANGUAGE',
        keywords: const ['language', 'app language', 'english', 'spanish', 'indonesian', 'japanese'],
        builder: (ctx) {
          final appLang = settings.get('app_language', '');
          final currentLabel = appLang.isEmpty ? 'System default' : appLang;
          return SettingsGroup(
            title: 'LANGUAGE',
            children: [
              SettingsRow(
                icon: Icons.language_rounded,
                title: 'App language',
                subtitle: 'Current: $currentLabel (tap to change language)',
                onTap: () {
                  showDesktopDialog(
                    context: ctx,
                    maxWidth: 420.0,
                    builder: (_) => DesktopTranslationLanguageDialog(app: widget.app),
                  );
                },
              ),
            ],
          );
        },
      ),

      // 17. ADVANCED OPTIONS
      _SettingsSectionSpec(
        title: 'ADVANCED OPTIONS',
        keywords: const ['advanced options', 'show stats for nerds', 'stats'],
        builder: (ctx) {
          final nerdStats = settings.get('show_nerd_stats', false);
          return SettingsGroup(
            title: 'ADVANCED OPTIONS',
            children: [
              SettingsToggle(
                title: 'Show stats for nerds',
                subtitle: 'Displays codec, bitrate, and audio sample rate in player.',
                value: nerdStats,
                onChanged: (val) {
                  unawaited(settings.set('show_nerd_stats', val));
                  setState(() {});
                },
              ),
            ],
          );
        },
      ),

      // 18. APP UPDATES
      _SettingsSectionSpec(
        title: 'APP UPDATES',
        keywords: const ['app updates', 'automatically check for updates', 'check for updates', 'version 1.9.2'],
        builder: (ctx) {
          final autoCheck = settings.get('updates_auto', settings.get('auto_check_updates', true));
          return SettingsGroup(
            title: 'APP UPDATES',
            children: [
              ListTile(
                contentPadding: const EdgeInsets.symmetric(horizontal: 8.0),
                title: Text('BitChord Version 1.9.2', style: TextStyle(color: palette.foreground, fontWeight: FontWeight.w600)),
                subtitle: Text('Last successful check: Today', style: TextStyle(color: palette.secondary, fontSize: 13)),
              ),
              SettingsToggle(
                title: 'Automatically check for updates',
                subtitle: 'Check GitHub releases for application updates on launch.',
                value: autoCheck,
                onChanged: (val) {
                  unawaited(settings.set('updates_auto', val));
                  unawaited(settings.set('auto_check_updates', val));
                  setState(() {});
                },
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8.0, vertical: 8.0),
                child: OutlinedButton(
                  onPressed: () {
                    showDesktopDialog(
                      context: ctx,
                      maxWidth: 440.0,
                      builder: (_) => DesktopAppUpdatesDialog(app: widget.app),
                    );
                  },
                  child: const Text('Check for Updates'),
                ),
              ),
            ],
          );
        },
      ),
    ];

    // Filter matching sections by search query
    final matchingSpecs = sections.where((spec) => spec.matches(_searchQuery)).toList();

    return SettingsPageFrame(
      maxWidth: 840.0,
      padding: const EdgeInsets.symmetric(horizontal: 28.0),
      child: ListView(
        padding: const EdgeInsets.fromLTRB(0.0, 24.0, 0.0, 120.0),
        children: [
          // Header Row with Title and DesktopSearchField
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Text(
                'Settings',
                style: Theme.of(context).textTheme.displayLarge?.copyWith(
                      color: palette.foreground,
                      fontWeight: FontWeight.w800,
                    ),
              ),
              DesktopSearchField(
                query: _searchQuery,
                width: 280.0,
                onQueryChanged: (q) => setState(() => _searchQuery = q),
              ),
            ],
          ),
          const SizedBox(height: 28.0),

          // Render matching sections or empty state
          if (matchingSpecs.isEmpty)
            Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 64.0),
                child: Text(
                  'No settings found for "$_searchQuery"',
                  style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                        color: palette.secondary,
                      ),
                ),
              ),
            )
          else
            for (final spec in matchingSpecs) spec.builder(context),

          const SizedBox(height: 36.0),

          // Desktop Settings Footer
          Center(
            child: Column(
              children: [
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      'BitChord',
                      style: TextStyle(
                        fontFamily: 'BintangMusic',
                        fontWeight: FontWeight.w900,
                        fontSize: 32.0,
                        color: palette.foreground,
                      ),
                    ),
                    const SizedBox(width: 8.0),
                    Text(
                      '1.9.2',
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                            color: palette.secondary,
                            fontWeight: FontWeight.w700,
                          ),
                    ),
                  ],
                ),
                const SizedBox(height: 8.0),
                Wrap(
                  spacing: 16.0,
                  children: [
                    Text('GitHub', style: TextStyle(color: palette.secondary, fontSize: 13)),
                    Text('Developer', style: TextStyle(color: palette.secondary, fontSize: 13)),
                    Text('Discord', style: TextStyle(color: palette.secondary, fontSize: 13)),
                    GestureDetector(
                      onTap: () => setState(() => _subPage = SettingsSubPage.licenses),
                      child: Text('Licenses', style: TextStyle(color: palette.accent, fontSize: 13, decoration: TextDecoration.underline)),
                    ),
                  ],
                ),
                const SizedBox(height: 8.0),
                Text(
                  '~ YouTube Music Backend',
                  style: Theme.of(context).textTheme.labelSmall?.copyWith(color: palette.secondary),
                ),
                if (settings.error != null) ...[
                  const SizedBox(height: 8.0),
                  Text(settings.error!, style: TextStyle(color: palette.destructive)),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SettingsSectionSpec {
  const _SettingsSectionSpec({
    required this.title,
    required this.keywords,
    required this.builder,
  });

  final String title;
  final List<String> keywords;
  final Widget Function(BuildContext) builder;

  static String _normalizeSearch(String s) {
    return s
        .toLowerCase()
        .replaceAll(RegExp(r"[\u2018\u2019\u201A\u201B`´]"), "'")
        .replaceAll(RegExp(r'[\u201C\u201D\u201E\u201F«»]'), '"')
        .trim();
  }

  bool matches(String query) {
    final q = _normalizeSearch(query);
    if (q.isEmpty) return true;
    if (_normalizeSearch(title).contains(q)) return true;
    for (final kw in keywords) {
      if (_normalizeSearch(kw).contains(q)) return true;
    }
    return false;
  }
}
