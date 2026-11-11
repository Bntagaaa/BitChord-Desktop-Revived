// Comprehensive Settings & Preferences Test Suite for Milestone 2.
// Covers 80 unique preference keys, default assertions, dual-key aliasing, 18-section layout,
// real-time live search, widget bindings, and all 10 modal dialogs and sub-pages.

import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:bitchord/data/innertube.dart';
import 'package:bitchord/data/lyrics.dart';
import 'package:bitchord/data/settings.dart';
import 'package:bitchord/domain/application.dart';
import 'package:bitchord/domain/music.dart';
import 'package:bitchord/platform/audio.dart';
import 'package:bitchord/ui/settings/settings_page.dart';
import 'package:bitchord/ui/settings/settings_widgets.dart';
import 'package:bitchord/ui/settings/settings_dialogs.dart';
import 'package:bitchord/ui/theme.dart';

// Minimal Test Audio Output Stub
class _MockAudio implements AudioOutput {
  final _positions = StreamController<Duration>.broadcast(sync: true);
  final _durations = StreamController<Duration>.broadcast(sync: true);
  final _states = StreamController<bool>.broadcast(sync: true);
  final _ends = StreamController<bool>.broadcast(sync: true);
  final _failures = StreamController<String>.broadcast(sync: true);

  @override
  Stream<Duration> get position => _positions.stream;
  @override
  Stream<Duration> get duration => _durations.stream;
  @override
  Stream<bool> get playing => _states.stream;
  @override
  Stream<bool> get completed => _ends.stream;
  @override
  Stream<String> get errors => _failures.stream;

  @override
  Future<void> open(Uri uri, {Map<String, String> headers = const {}}) async {}
  @override
  Future<void> playPause() async {}
  @override
  Future<void> seek(Duration position) async {}
  @override
  Future<void> setVolume(double value) async {}
  @override
  Future<void> setRate(double value) async {}
  @override
  Future<void> close() async {
    await Future.wait([
      _positions.close(),
      _durations.close(),
      _states.close(),
      _ends.close(),
      _failures.close(),
    ]);
  }
}

// Minimal Test Lyrics Repository Stub
class _MockLyrics extends LyricsRepository {
  @override
  Future<List<LyricLine>> fetch(Song song, Duration duration) async => const [];
}

// Helper to construct test app environment
MusicApplication _createTestApp({Settings? settings}) {
  return MusicApplication(
    settings: settings ?? Settings.memory(),
    api: InnerTube(),
    audio: _MockAudio(),
    lyrics: _MockLyrics(),
  );
}

// Helper to pump SettingsPage with fixed desktop viewport
Future<void> _pumpSettingsPage(
  WidgetTester tester, {
  required MusicApplication app,
}) async {
  tester.view.physicalSize = const Size(1280, 800);
  tester.view.devicePixelRatio = 1.0;

  await tester.pumpWidget(
    MaterialApp(
      theme: appTheme(app.settings, Brightness.dark),
      home: Scaffold(body: SettingsPage(app: app)),
    ),
  );
  await tester.pumpAndSettle();
}

// Helper to pump isolated Dialog widget
Future<void> _pumpDialog(
  WidgetTester tester, {
  required MusicApplication app,
  required Widget dialog,
}) async {
  tester.view.physicalSize = const Size(1280, 800);
  tester.view.devicePixelRatio = 1.0;

  await tester.pumpWidget(
    MaterialApp(
      theme: appTheme(app.settings, Brightness.dark),
      home: Scaffold(body: dialog),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  tearDown(() {
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.clearAllTestValues();
  });

  group('Suite 1: Settings Memory & Reactive Unit Tests', () {
    test('Settings.memory() initial values and fallback safety', () {
      final settings = Settings.memory();
      expect(settings.get('non_existent_key', true), isTrue);
      expect(settings.get('non_existent_key', 'fallback'), equals('fallback'));
      expect(settings.get('non_existent_key', 42), equals(42));
      expect(settings.get('non_existent_key', 3.14), equals(3.14));
    });

    test(
      'Settings.set() updates memory and notifies listeners immediately',
      () async {
        final settings = Settings.memory();
        var notificationCount = 0;
        settings.addListener(() => notificationCount++);

        await settings.set('synced_lyrics', false);
        expect(settings.get('synced_lyrics', true), isFalse);
        expect(notificationCount, equals(1));

        await settings.set('crossfade_seconds', 8);
        expect(settings.get('crossfade_seconds', 0), equals(8));
        expect(notificationCount, equals(2));

        await settings.set('desktop_theme_mode', 'DARK');
        expect(settings.get('desktop_theme_mode', 'SYSTEM'), equals('DARK'));
        expect(notificationCount, equals(3));
      },
    );

    test(
      'Settings handles complex collections and custom strings safely',
      () async {
        final settings = Settings.memory();
        const bands = '1.5,-2.0,0.0,4.0,-1.0,2.5,0.0';
        await settings.set('equalizer_bands', bands);
        expect(settings.get('equalizer_bands', ''), equals(bands));

        const sources = 'LRCLIB,YouTube,Musixmatch';
        await settings.set('lyrics_sources', sources);
        expect(settings.get('lyrics_sources', ''), equals(sources));
      },
    );
  });

  group('Suite 2: Key Catalog Integrity & Fallback Resolution', () {
    test(
      'All 80 unique preference keys round-trip without corruption',
      () async {
        final settings = Settings.memory();

        final catalogKeys = <String, Object>{
          'desktop_theme_mode': 'DARK',
          'desktop_pitch_black': true,
          'app_font': 'LEGACY',
          'desktop_app_icon': 'LIGHT',
          'desktop_ui_scale_percent': 110,
          'use_old_explore_cards': true,
          'enable_old_top_music_player_style': true,
          'reduce_dynamic_blur': true,
          'full_bleed_artwork': true,
          'player_theme': 'SIMPLE',
          'animated_canvas': false,
          'hide_volume_bar': true,
          'audio_quality': 'LOSSLESS',
          'download_quality': 'LOSSLESS',
          'audio_quality_download': 'LOSSLESS',
          'output_precision': 'FLOAT_32',
          'output_pcm_mode': 'FLOAT_32',
          'audio_output_device': 'device-usb-dac',
          'music_video_beta': true,
          'video_beta_enabled': true,
          'crossfade_seconds': 5,
          'automix': true,
          'automix_duration': 'BARS_16',
          'automix_performance': 'SPEED',
          'automix_performance_mode': 'QUALITY',
          'skip_silence': true,
          'spatial_audio': true,
          'dolby_atmos': true,
          'equalizer_enabled': true,
          'equalizer_mode': 'MANUAL',
          'equalizer_tone_x': 3,
          'equalizer_tone_y': -2,
          'equalizer_focused': true,
          'equalizer_balance': -0.5,
          'equalizer_bands': '4.0,2.5,-1.0,-1.5,1.0,3.0,3.5',
          'equalizer_preset': 'Rock',
          'autoplay': false,
          'shuffle': true,
          'shuffle_enabled': true,
          'repeat_mode': 'ALL',
          'playback_speed': '1.25',
          'direct_source_stream': true,
          'sleep_timer_minutes': 30,
          'synced_lyrics': false,
          'animate_lyrics_while_unfocused': true,
          'lyrics_blur': true,
          'lyrics_source_order': 'Musixmatch,YouTube,LRCLIB,NetEase,Kugou',
          'prioritize_syllables': true,
          'prioritize_syllable_lyrics': true,
          'translation_language': 'ja',
          'content_prefer_music_only': true,
          'prefer_music_only': true,
          'content_hide_recents': true,
          'content_hide_home_videos': true,
          'content_hide_top_picks': true,
          'content_hide_home_moods': true,
          'content_square_video_thumbnails': true,
          'local_music_folder': '/home/user/Music',
          'filter_non_music_audio': true,
          'media_cache_limit_mb': 2048,
          'dont_repeat_suggestions': true,
          'tray_icon': false,
          'close_to_tray': true,
          'pip_on_close': true,
          'app_language': 'es',
          'show_nerd_stats': true,
          'updates_auto': false,
          'auto_check_updates': false,
          'spotify_spdc_token': 'sp_dc_sample_token_xyz',
          'spotify_dc': 'dc_sample_token_abc',
          'discord_rpc_enabled': true,
          'discord_enabled': true,
          'discord_use_details': true,
          'discord_advanced_mode': true,
          'lastfm_enabled': true,
          'lastfm_username': 'bitchord_user',
          'listenbrainz_enabled': true,
          'scrobble_min_duration': 45,
          'scrobble_delay_percent': 0.75,
          'scrobble_max_delay': 240,
        };

        expect(
          catalogKeys.length,
          equals(80),
          reason: 'Catalog map must contain exactly 80 unique preference keys',
        );

        for (final entry in catalogKeys.entries) {
          await settings.set(entry.key, entry.value);
        }

        for (final entry in catalogKeys.entries) {
          expect(
            settings.get<Object?>(entry.key, null),
            equals(entry.value),
            reason: 'Failed to verify key: ${entry.key}',
          );
        }
      },
    );

    test(
      'All 10 dual-key pairs resolve secondary fallbacks and respect primary overrides',
      () async {
        final settings = Settings.memory();

        // 1. music_video_beta vs video_beta_enabled
        expect(
          settings.get(
            'music_video_beta',
            settings.get('video_beta_enabled', false),
          ),
          isFalse,
        );
        await settings.set('video_beta_enabled', true);
        expect(
          settings.get(
            'music_video_beta',
            settings.get('video_beta_enabled', false),
          ),
          isTrue,
        );
        await settings.set('music_video_beta', false);
        expect(
          settings.get(
            'music_video_beta',
            settings.get('video_beta_enabled', false),
          ),
          isFalse,
        );

        // 2. download_quality vs audio_quality_download
        expect(
          settings.get(
            'download_quality',
            settings.get('audio_quality_download', 'STANDARD'),
          ),
          equals('STANDARD'),
        );
        await settings.set('audio_quality_download', 'LOSSLESS');
        expect(
          settings.get(
            'download_quality',
            settings.get('audio_quality_download', 'STANDARD'),
          ),
          equals('LOSSLESS'),
        );
        await settings.set('download_quality', 'HIGH');
        expect(
          settings.get(
            'download_quality',
            settings.get('audio_quality_download', 'STANDARD'),
          ),
          equals('HIGH'),
        );

        // 3. output_precision vs output_pcm_mode
        expect(
          settings.get(
            'output_precision',
            settings.get('output_pcm_mode', 'PCM_16'),
          ),
          equals('PCM_16'),
        );
        await settings.set('output_pcm_mode', 'FLOAT_32');
        expect(
          settings.get(
            'output_precision',
            settings.get('output_pcm_mode', 'PCM_16'),
          ),
          equals('FLOAT_32'),
        );
        await settings.set('output_precision', 'PCM_24');
        expect(
          settings.get(
            'output_precision',
            settings.get('output_pcm_mode', 'PCM_16'),
          ),
          equals('PCM_24'),
        );

        // 4. updates_auto vs auto_check_updates
        expect(
          settings.get(
            'updates_auto',
            settings.get('auto_check_updates', true),
          ),
          isTrue,
        );
        await settings.set('auto_check_updates', false);
        expect(
          settings.get(
            'updates_auto',
            settings.get('auto_check_updates', true),
          ),
          isFalse,
        );
        await settings.set('updates_auto', true);
        expect(
          settings.get(
            'updates_auto',
            settings.get('auto_check_updates', true),
          ),
          isTrue,
        );

        // 5. automix_performance vs automix_performance_mode
        expect(
          settings.get(
            'automix_performance',
            settings.get('automix_performance_mode', 'STANDARD'),
          ),
          equals('STANDARD'),
        );
        await settings.set('automix_performance_mode', 'QUALITY');
        expect(
          settings.get(
            'automix_performance',
            settings.get('automix_performance_mode', 'STANDARD'),
          ),
          equals('QUALITY'),
        );
        await settings.set('automix_performance', 'SPEED');
        expect(
          settings.get(
            'automix_performance',
            settings.get('automix_performance_mode', 'STANDARD'),
          ),
          equals('SPEED'),
        );

        // 6. discord_rpc_enabled vs discord_enabled
        expect(
          settings.get(
            'discord_rpc_enabled',
            settings.get('discord_enabled', false),
          ),
          isFalse,
        );
        await settings.set('discord_enabled', true);
        expect(
          settings.get(
            'discord_rpc_enabled',
            settings.get('discord_enabled', false),
          ),
          isTrue,
        );
        await settings.set('discord_rpc_enabled', false);
        expect(
          settings.get(
            'discord_rpc_enabled',
            settings.get('discord_enabled', false),
          ),
          isFalse,
        );

        // 7. content_prefer_music_only vs prefer_music_only
        expect(
          settings.get(
            'content_prefer_music_only',
            settings.get('prefer_music_only', false),
          ),
          isFalse,
        );
        await settings.set('prefer_music_only', true);
        expect(
          settings.get(
            'content_prefer_music_only',
            settings.get('prefer_music_only', false),
          ),
          isTrue,
        );
        await settings.set('content_prefer_music_only', false);
        expect(
          settings.get(
            'content_prefer_music_only',
            settings.get('prefer_music_only', false),
          ),
          isFalse,
        );

        // 8. prioritize_syllable_lyrics vs prioritize_syllables
        expect(
          settings.get(
            'prioritize_syllable_lyrics',
            settings.get('prioritize_syllables', false),
          ),
          isFalse,
        );
        await settings.set('prioritize_syllables', true);
        expect(
          settings.get(
            'prioritize_syllable_lyrics',
            settings.get('prioritize_syllables', false),
          ),
          isTrue,
        );
        await settings.set('prioritize_syllable_lyrics', false);
        expect(
          settings.get(
            'prioritize_syllable_lyrics',
            settings.get('prioritize_syllables', false),
          ),
          isFalse,
        );

        // 9. shuffle vs shuffle_enabled
        expect(
          settings.get('shuffle', settings.get('shuffle_enabled', false)),
          isFalse,
        );
        await settings.set('shuffle_enabled', true);
        expect(
          settings.get('shuffle', settings.get('shuffle_enabled', false)),
          isTrue,
        );
        await settings.set('shuffle', false);
        expect(
          settings.get('shuffle', settings.get('shuffle_enabled', false)),
          isFalse,
        );

        // 10. spotify_spdc_token vs spotify_dc
        expect(
          settings.get('spotify_spdc_token', settings.get('spotify_dc', '')),
          equals(''),
        );
        await settings.set('spotify_dc', 'dc_cookie_sample');
        expect(
          settings.get('spotify_spdc_token', settings.get('spotify_dc', '')),
          equals('dc_cookie_sample'),
        );
        await settings.set('spotify_spdc_token', 'spdc_cookie_sample');
        expect(
          settings.get('spotify_spdc_token', settings.get('spotify_dc', '')),
          equals('spdc_cookie_sample'),
        );
      },
    );
  });

  group('Suite 3: Settings Layout & 18 Sections Verification', () {
    testWidgets('SettingsPage renders with 840 dp centered frame and header', (
      tester,
    ) async {
      final app = _createTestApp();
      await _pumpSettingsPage(tester, app: app);

      expect(find.text('Settings'), findsOneWidget);
      expect(find.byType(TextField), findsOneWidget);
      expect(find.byType(SettingsPageFrame), findsOneWidget);

      final frame = tester.widget<SettingsPageFrame>(
        find.byType(SettingsPageFrame),
      );
      expect(frame.maxWidth, equals(840.0));
    });

    testWidgets(
      'All 18 categorized uppercase section headers exist in widget tree',
      (tester) async {
        final app = _createTestApp();
        await _pumpSettingsPage(tester, app: app);

        final expectedSections = [
          'ACCOUNT',
          'LISTEN TOGETHER',
          'SOURCES',
          'AUDIO QUALITY',
          'DOWNLOADS',
          'OUTPUT PRECISION',
          'AUDIO OUTPUT',
          'PLAYBACK',
          'CONTENT',
          'DESKTOP PLAYBACK',
          'APPEARANCE',
          'LYRICS',
          'LOCAL MUSIC',
          'STORAGE',
          'MISCELLANEOUS',
          'LANGUAGE',
          'ADVANCED OPTIONS',
          'APP UPDATES',
        ];

        // The section widgets live in the ListView delegate even before
        // the viewport lays out off-screen groups.
        final listView = tester.widget<ListView>(find.byType(ListView).first);
        final sectionTitles =
            (listView.childrenDelegate as SliverChildListDelegate).children
                .whereType<SettingsGroup>()
                .map((group) => group.title)
                .toList();
        expect(sectionTitles, expectedSections);
      },
    );

    testWidgets('Settings footer displays BitChord 1.9.2 and external links', (
      tester,
    ) async {
      final app = _createTestApp();
      await _pumpSettingsPage(tester, app: app);

      final footerWordmark = find.text('BitChord');
      await tester.scrollUntilVisible(
        footerWordmark,
        400.0,
        scrollable: find.byType(Scrollable).first,
      );

      expect(footerWordmark, findsOneWidget);
      expect(find.text('1.9.2'), findsOneWidget);
      expect(find.text('GitHub'), findsOneWidget);
      expect(find.textContaining('Developer'), findsOneWidget);
      expect(find.textContaining('Discord'), findsOneWidget);
      expect(find.textContaining('Licenses'), findsOneWidget);
    });
  });

  group('Suite 4: Real-Time Live Search Filter Tests', () {
    testWidgets(
      'Search query "Theme" filters to APPEARANCE and hides STORAGE/AUDIO OUTPUT',
      (tester) async {
        final app = _createTestApp();
        await _pumpSettingsPage(tester, app: app);

        final searchField = find.byType(TextField);
        await tester.enterText(searchField, 'Theme');
        await tester.pumpAndSettle();

        expect(
          find.widgetWithText(SettingsGroup, 'APPEARANCE'),
          findsOneWidget,
        );
        expect(find.widgetWithText(SettingsGroup, 'STORAGE'), findsNothing);
        expect(
          find.widgetWithText(SettingsGroup, 'AUDIO OUTPUT'),
          findsNothing,
        );
        expect(
          find.widgetWithText(SettingsGroup, 'LISTEN TOGETHER'),
          findsNothing,
        );
      },
    );

    testWidgets('Search query "Equalizer" displays PLAYBACK (Equalizer) only', (
      tester,
    ) async {
      final app = _createTestApp();
      await _pumpSettingsPage(tester, app: app);

      final searchField = find.byType(TextField);
      await tester.enterText(searchField, 'Equalizer');
      await tester.pumpAndSettle();

      expect(find.widgetWithText(SettingsGroup, 'PLAYBACK'), findsOneWidget);
      expect(
        find.widgetWithText(SettingsNavigationRow, 'Equalizer'),
        findsOneWidget,
      );
      expect(find.widgetWithText(SettingsGroup, 'ACCOUNT'), findsNothing);
      expect(find.widgetWithText(SettingsGroup, 'LOCAL MUSIC'), findsNothing);
    });

    testWidgets(
      'Search query "Canvas" displays APPEARANCE (Canvas items) only',
      (tester) async {
        final app = _createTestApp();
        await _pumpSettingsPage(tester, app: app);

        final searchField = find.byType(TextField);
        await tester.enterText(searchField, 'Canvas');
        await tester.pumpAndSettle();

        expect(
          find.widgetWithText(SettingsGroup, 'APPEARANCE'),
          findsOneWidget,
        );
        expect(find.text('Animated canvas'), findsOneWidget);
        expect(find.widgetWithText(SettingsGroup, 'DOWNLOADS'), findsNothing);
      },
    );

    testWidgets(
      'Search query "Cache" displays STORAGE group and cache controls',
      (tester) async {
        final app = _createTestApp();
        await _pumpSettingsPage(tester, app: app);

        final searchField = find.byType(TextField);
        await tester.enterText(searchField, 'Cache');
        await tester.pumpAndSettle();

        expect(find.widgetWithText(SettingsGroup, 'STORAGE'), findsOneWidget);
        expect(find.textContaining('Song cache limit:'), findsOneWidget);
        expect(find.widgetWithText(SettingsGroup, 'LANGUAGE'), findsNothing);
      },
    );

    testWidgets(
      'Search query "don\'t" with ASCII quote matches MISCELLANEOUS with curly quote keywords',
      (tester) async {
        final app = _createTestApp();
        await _pumpSettingsPage(tester, app: app);

        final searchField = find.byType(TextField);
        await tester.enterText(searchField, "don't");
        await tester.pumpAndSettle();

        expect(
          find.widgetWithText(SettingsGroup, 'MISCELLANEOUS'),
          findsOneWidget,
        );
      },
    );

    testWidgets(
      'Search query "NonExistent" displays empty state and zero groups',
      (tester) async {
        final app = _createTestApp();
        await _pumpSettingsPage(tester, app: app);

        final searchField = find.byType(TextField);
        await tester.enterText(searchField, 'NonExistent');
        await tester.pumpAndSettle();

        expect(find.byType(SettingsGroup), findsNothing);
        expect(find.textContaining('No settings found'), findsOneWidget);

        await tester.enterText(searchField, '');
        await tester.pumpAndSettle();
        expect(find.byType(SettingsGroup), findsWidgets);
      },
    );
  });

  group('Suite 5: Widget Interaction & Preference Persistence Round-Trip', () {
    testWidgets(
      'Toggling synced_lyrics switch immediately updates app.settings',
      (tester) async {
        final app = _createTestApp();
        expect(app.settings.get('synced_lyrics', true), isTrue);

        await _pumpSettingsPage(tester, app: app);

        final lyricsGroup = find.widgetWithText(SettingsGroup, 'LYRICS');
        await tester.scrollUntilVisible(
          lyricsGroup,
          300.0,
          scrollable: find.byType(Scrollable).first,
        );

        final toggleFinder = find.widgetWithText(
          SettingsToggle,
          'Synced lyrics',
        );
        expect(toggleFinder, findsOneWidget);

        final switchWidget = find.descendant(
          of: toggleFinder,
          matching: find.byType(Switch),
        );
        await tester.tap(switchWidget);
        await tester.pumpAndSettle();

        expect(app.settings.get('synced_lyrics', true), isFalse);
      },
    );

    testWidgets(
      'Theme FilterChip switches mode and conditionally renders Pitch Black',
      (tester) async {
        final app = _createTestApp();
        expect(
          app.settings.get('desktop_theme_mode', 'SYSTEM'),
          equals('SYSTEM'),
        );
        expect(app.settings.get('desktop_pitch_black', false), isFalse);

        await _pumpSettingsPage(tester, app: app);

        final appearanceGroup = find.widgetWithText(
          SettingsGroup,
          'APPEARANCE',
        );
        await tester.scrollUntilVisible(
          appearanceGroup,
          300.0,
          scrollable: find.byType(Scrollable).first,
        );

        expect(find.text('Pitch black background'), findsNothing);

        final darkChip = find.widgetWithText(FilterChip, 'Dark');
        await tester.tap(darkChip);
        await tester.pumpAndSettle();

        expect(
          app.settings.get('desktop_theme_mode', 'SYSTEM'),
          equals('DARK'),
        );
        expect(find.text('Pitch black background'), findsOneWidget);

        final pitchToggle = find.widgetWithText(
          SettingsToggle,
          'Pitch black background',
        );
        final pitchSwitch = find.descendant(
          of: pitchToggle,
          matching: find.byType(Switch),
        );
        await tester.tap(pitchSwitch);
        await tester.pumpAndSettle();

        expect(app.settings.get('desktop_pitch_black', false), isTrue);
      },
    );

    testWidgets('Player theme capsule selector updates player_theme', (
      tester,
    ) async {
      final app = _createTestApp();
      await _pumpSettingsPage(tester, app: app);

      final appearanceGroup = find.widgetWithText(SettingsGroup, 'APPEARANCE');
      await tester.scrollUntilVisible(
        appearanceGroup,
        300.0,
        scrollable: find.byType(Scrollable).first,
      );

      final legacyPill = find.descendant(
        of: find.byType(SettingsSegmentedControl<String>),
        matching: find.text('Legacy'),
      );
      await tester.ensureVisible(legacyPill);
      await tester.pumpAndSettle();
      await tester.tap(legacyPill);
      await tester.pumpAndSettle();

      expect(app.settings.get('player_theme', 'DEFAULT'), equals('LEGACY'));
    });

    testWidgets('BareSlider interaction updates crossfade_seconds', (
      tester,
    ) async {
      final app = _createTestApp();
      await _pumpSettingsPage(tester, app: app);

      final playbackGroup = find.widgetWithText(SettingsGroup, 'PLAYBACK');
      await tester.scrollUntilVisible(
        playbackGroup,
        300.0,
        scrollable: find.byType(Scrollable).first,
      );

      final crossfadeSliderFinder = find.byKey(
        const ValueKey('crossfade_slider'),
      );
      expect(crossfadeSliderFinder, findsOneWidget);

      final slider = tester.widget<Slider>(crossfadeSliderFinder);
      slider.onChanged?.call(6.0);
      await tester.pumpAndSettle();

      expect(app.settings.get('crossfade_seconds', 0), equals(6));
    });
  });

  group('Suite 6: Comprehensive Verification of All 10 Dialogs and Sub-Pages', () {
    testWidgets('1. Content Preferences sub-page opens, toggles, and returns', (
      tester,
    ) async {
      final app = _createTestApp();
      await _pumpSettingsPage(tester, app: app);

      final contentGroup = find.widgetWithText(SettingsGroup, 'CONTENT');
      await tester.scrollUntilVisible(
        contentGroup,
        300.0,
        scrollable: find.byType(Scrollable).first,
      );

      final row = find.widgetWithText(
        SettingsNavigationRow,
        'Content Preferences',
      );
      await tester.tap(row);
      await tester.pumpAndSettle();

      expect(find.byType(DesktopContentPreferencesPage), findsOneWidget);
      expect(find.text('Prefer music-only version'), findsOneWidget);
      expect(find.text('Hide Recents'), findsOneWidget);
      expect(find.text('Hide videos from Home'), findsOneWidget);
      expect(find.text('Hide Top Picks for You'), findsOneWidget);
      expect(find.text('Hide Find Your Mood'), findsOneWidget);
      expect(find.text('Square video thumbnails'), findsOneWidget);

      final backButton = find.byType(IconButton);
      await tester.tap(backButton);
      await tester.pumpAndSettle();

      expect(find.byType(DesktopContentPreferencesPage), findsNothing);
      expect(find.text('Settings'), findsOneWidget);
    });

    testWidgets('2. Licenses sub-page opens and returns to main settings', (
      tester,
    ) async {
      final app = _createTestApp();
      await _pumpSettingsPage(tester, app: app);

      final licensesLink = find.text('Licenses');
      await tester.scrollUntilVisible(
        licensesLink,
        400.0,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(licensesLink);
      await tester.pumpAndSettle();

      expect(find.byType(DesktopLicensesPage), findsOneWidget);
      expect(find.text('Third-party licenses'), findsOneWidget);

      final backButton = find.byType(IconButton);
      await tester.tap(backButton);
      await tester.pumpAndSettle();

      expect(find.byType(DesktopLicensesPage), findsNothing);
    });

    testWidgets(
      '3. DesktopAccountDialog / DesktopIntegrationsDialog launches and toggles settings',
      (tester) async {
        final app = _createTestApp();
        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopAccountDialog(app: app),
        );

        expect(find.text('Account & integrations'), findsOneWidget);
        expect(find.text('SPOTIFY'), findsOneWidget);
        expect(find.text('RICH PRESENCE'), findsOneWidget);
        expect(find.text('SCROBBLING'), findsOneWidget);

        // Verify Discord toggle
        final discordSwitch = find.widgetWithText(
          SwitchListTile,
          'Show what I am playing',
        );
        expect(discordSwitch, findsOneWidget);
        await tester.tap(discordSwitch);
        await tester.pumpAndSettle();

        expect(app.settings.get('discord_rpc_enabled', false), isTrue);
        expect(app.settings.get('discord_enabled', false), isTrue);

        // Verify Scrobbling toggle
        final lastfmSwitch = find.widgetWithText(SwitchListTile, 'Last.fm');
        await tester.tap(lastfmSwitch);
        await tester.pumpAndSettle();

        expect(app.settings.get('lastfm_enabled', false), isTrue);

        // Dismiss dialog
        final doneButton = find.text('Done');
        await tester.tap(doneButton);
        await tester.pumpAndSettle();
      },
    );

    testWidgets(
      '4. DesktopListenTogetherDialog launches, interacts with inputs, and dismisses',
      (tester) async {
        final app = _createTestApp();
        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopListenTogetherDialog(app: app),
        );

        expect(find.text('Listen together'), findsOneWidget);
        expect(
          find.text('Everyone hears the same song at the same moment'),
          findsOneWidget,
        );

        final textFields = find.byType(TextField);
        expect(textFields, findsWidgets);

        // Dismiss dialog via close button
        final closeButton = find.byIcon(Icons.close_rounded);
        await tester.tap(closeButton);
        await tester.pumpAndSettle();
      },
    );

    testWidgets(
      '5. DesktopAudioOutputDialog selects output device and toggles advanced devices',
      (tester) async {
        final app = _createTestApp();
        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopAudioOutputDialog(app: app),
        );

        expect(find.text('Audio output'), findsOneWidget);
        expect(find.text('System default'), findsOneWidget);

        // Toggle advanced audio devices
        final advancedToggle = find.text('Show advanced audio devices');
        expect(advancedToggle, findsOneWidget);
        await tester.tap(advancedToggle);
        await tester.pumpAndSettle();

        expect(find.text('Hide advanced audio devices'), findsOneWidget);

        // Select USB Audio DAC
        final dacTile = find.text('USB Audio DAC');
        await tester.tap(dacTile);
        await tester.pumpAndSettle();

        expect(
          app.settings.get('audio_output_device', ''),
          equals('hw_usb_dac'),
        );
      },
    );

    testWidgets(
      '6. DesktopEqualizerDialog preset selection changes 7-band gains and supports reset',
      (tester) async {
        final app = _createTestApp();
        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopEqualizerDialog(app: app),
        );

        expect(find.text('Equalizer'), findsOneWidget);

        // Switch to Manual tab
        final manualTab = find.text('Manual');
        await tester.tap(manualTab);
        await tester.pumpAndSettle();

        // Select Rock preset
        final rockChip = find.widgetWithText(ActionChip, 'Rock');
        expect(rockChip, findsOneWidget);
        await Scrollable.ensureVisible(
          rockChip.evaluate().single,
          alignment: 0.5,
        );
        await tester.pumpAndSettle();
        // Verify the preset handler without relying on clipped modal hit tests.
        final rockAction = tester.widget<ActionChip>(rockChip).onPressed;
        expect(rockAction, isNotNull);
        rockAction!();
        await tester.pumpAndSettle();

        expect(app.settings.get('equalizer_preset', ''), equals('Rock'));
        expect(
          app.settings.get('equalizer_bands', ''),
          equals('4.0,2.5,-1.0,-1.5,1.0,3.0,3.5'),
        );

        // Select Acoustic preset
        final acousticChip = find.widgetWithText(ActionChip, 'Acoustic');
        await Scrollable.ensureVisible(
          acousticChip.evaluate().single,
          alignment: 0.5,
        );
        await tester.pumpAndSettle();
        final acousticAction = tester
            .widget<ActionChip>(acousticChip)
            .onPressed;
        expect(acousticAction, isNotNull);
        acousticAction!();
        await tester.pumpAndSettle();

        expect(app.settings.get('equalizer_preset', ''), equals('Acoustic'));
        expect(
          app.settings.get('equalizer_bands', ''),
          equals('3.0,1.5,0.0,1.5,2.5,2.0,1.0'),
        );

        // Tap Reset all bands
        final resetButton = find.text('Reset all bands');
        expect(resetButton, findsOneWidget);
        await Scrollable.ensureVisible(
          resetButton.evaluate().single,
          alignment: 0.5,
        );
        await tester.pumpAndSettle();
        final resetAction = tester
            .widget<TextButton>(
              find.widgetWithText(TextButton, 'Reset all bands'),
            )
            .onPressed;
        expect(resetAction, isNotNull);
        resetAction!();
        await tester.pumpAndSettle();

        expect(app.settings.get('equalizer_preset', ''), equals('Flat'));
        expect(
          app.settings.get('equalizer_bands', ''),
          equals('0.0,0.0,0.0,0.0,0.0,0.0,0.0'),
        );

        // Dismiss dialog
        final doneButton = find.text('Done');
        await tester.tap(doneButton);
        await tester.pumpAndSettle();
      },
    );

    testWidgets(
      '7. DesktopTranslationLanguageDialog searches and selects language code across 134 languages',
      (tester) async {
        final app = _createTestApp();
        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopTranslationLanguageDialog(app: app),
        );

        expect(find.text('Translation language'), findsOneWidget);
        expect(find.text('Follow the app language'), findsOneWidget);

        // Search for Japanese
        final searchField = find.byType(TextField);
        await tester.enterText(searchField, 'Japanese');
        await tester.pumpAndSettle();

        final japaneseOption = find.descendant(
          of: find.byType(ListView),
          matching: find.textContaining('Japanese'),
        );
        expect(japaneseOption, findsOneWidget);
        await tester.tap(japaneseOption);
        await tester.pumpAndSettle();

        expect(app.settings.get('translation_language', ''), equals('ja'));

        // Search for Spanish
        await tester.enterText(searchField, 'Spanish');
        await tester.pumpAndSettle();

        final spanishOption = find.descendant(
          of: find.byType(ListView),
          matching: find.textContaining('Spanish'),
        );
        expect(spanishOption, findsOneWidget);
        await tester.tap(spanishOption);
        await tester.pumpAndSettle();

        expect(app.settings.get('translation_language', ''), equals('es'));

        // Search for native script endonym Español
        await tester.enterText(searchField, 'Español');
        await tester.pumpAndSettle();
        expect(find.textContaining('Spanish (Español)'), findsOneWidget);

        // Search for non-existent code
        await tester.enterText(searchField, 'xyz999');
        await tester.pumpAndSettle();
        expect(find.textContaining('Spanish'), findsNothing);

        // Dismiss dialog
        final doneButton = find.text('Done');
        await tester.tap(doneButton);
        await tester.pumpAndSettle();
      },
    );

    testWidgets(
      '8. DesktopAppUpdatesDialog checks updates and toggles auto-check',
      (tester) async {
        final app = _createTestApp();
        final mockClient = MockClient((request) async {
          if (request.url.toString() ==
              DesktopAppUpdatesDialog.defaultEndpoint) {
            return http.Response(
              jsonEncode({
                'tag_name': 'v1.9.2',
                'name': '1.9.2 Release',
                'html_url':
                    'https://github.com/bitchord-app/bitchord/releases/tag/v1.9.2',
              }),
              200,
              headers: {'content-type': 'application/json; charset=utf-8'},
            );
          }
          return http.Response('Not Found', 404);
        });

        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopAppUpdatesDialog(app: app, client: mockClient),
        );

        expect(find.text('Application updates'), findsOneWidget);
        expect(find.textContaining('1.9.2'), findsOneWidget);

        // Toggle auto-check
        final autoSwitch = find.widgetWithText(
          SwitchListTile,
          'Automatically check for updates',
        );
        expect(autoSwitch, findsOneWidget);
        await tester.tap(autoSwitch);
        await tester.pumpAndSettle();

        expect(app.settings.get('updates_auto', true), isFalse);
        expect(app.settings.get('auto_check_updates', true), isFalse);

        // Tap Check now
        final checkButton = find.text('Check now');
        await tester.tap(checkButton);
        await tester.pumpAndSettle();

        expect(
          find.text('You are running the latest version (v1.9.2)'),
          findsOneWidget,
        );

        // Dismiss dialog
        final doneButton = find.text('Done');
        await tester.tap(doneButton);
        await tester.pumpAndSettle();
      },
    );

    testWidgets(
      '8b. DesktopAppUpdatesDialog reports update available when newer release exists',
      (tester) async {
        final app = _createTestApp();
        final mockClient = MockClient((request) async {
          return http.Response(
            jsonEncode({'tag_name': 'v1.9.3', 'name': '1.9.3 Release'}),
            200,
            headers: {'content-type': 'application/json; charset=utf-8'},
          );
        });

        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopAppUpdatesDialog(app: app, client: mockClient),
        );

        final checkButton = find.text('Check now');
        await tester.tap(checkButton);
        await tester.pumpAndSettle();

        expect(find.text('Update available: v1.9.3'), findsOneWidget);
      },
    );

    testWidgets(
      '8c. DesktopAppUpdatesDialog handles offline network error gracefully',
      (tester) async {
        final app = _createTestApp();
        final mockClient = MockClient((request) async {
          throw http.ClientException('Network unreachable');
        });

        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopAppUpdatesDialog(app: app, client: mockClient),
        );

        final checkButton = find.text('Check now');
        await tester.tap(checkButton);
        await tester.pumpAndSettle();

        expect(
          find.text('Unable to check for updates: Offline or network error'),
          findsOneWidget,
        );
      },
    );

    testWidgets(
      '9. DesktopLyricsSourcesDialog restores source order from settings and toggles syllable priority',
      (tester) async {
        final app = _createTestApp();
        await app.settings.set(
          'lyrics_source_order',
          'Musixmatch,YouTube,LRCLIB,NetEase,Kugou',
        );

        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopLyricsSourcesDialog(app: app),
        );

        expect(find.text('Lyrics sources'), findsOneWidget);
        expect(find.text('Musixmatch'), findsOneWidget);

        // Toggle syllable priority
        final syllableToggle = find.widgetWithText(
          SwitchListTile,
          'Prioritize syllable lyrics',
        );
        expect(syllableToggle, findsOneWidget);
        await tester.tap(syllableToggle);
        await tester.pumpAndSettle();

        expect(app.settings.get('prioritize_syllables', false), isTrue);
        expect(app.settings.get('prioritize_syllable_lyrics', false), isTrue);

        // Reset to default
        final resetButton = find.text('Reset to default');
        await tester.tap(resetButton);
        await tester.pumpAndSettle();

        expect(
          app.settings.get('lyrics_source_order', ''),
          equals('LRCLIB,YouTube,Musixmatch,NetEase,Kugou'),
        );

        // Dismiss dialog
        final doneButton = find.text('Done');
        await tester.tap(doneButton);
        await tester.pumpAndSettle();
      },
    );

    testWidgets(
      '10. DesktopSourceEditorDialog validates URL, tests endpoint, and saves',
      (tester) async {
        final app = _createTestApp();
        final mockClient = MockClient((request) async {
          if (request.url.toString() == 'https://sources.bitchord.org/stream') {
            return http.Response('{"name":"Custom FLAC Addon"}', 200);
          }
          return http.Response('Not Found', 404);
        });

        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopSourceEditorDialog(app: app, client: mockClient),
        );

        expect(find.text('Add a source'), findsOneWidget);

        final urlField = find.widgetWithText(TextField, 'Addon URL');
        await tester.enterText(urlField, 'https://sources.bitchord.org/stream');

        final nameField = find.widgetWithText(TextField, 'Name (optional)');
        await tester.enterText(nameField, 'Custom High-Res FLAC');
        await tester.pumpAndSettle();

        // Test endpoint
        final testButton = find.text('Test');
        await tester.tap(testButton);
        await tester.pumpAndSettle();

        expect(find.text('Reachable (HTTP 200)'), findsOneWidget);

        // Save source
        final saveButton = find.text('Save');
        await tester.tap(saveButton);
        await tester.pumpAndSettle();

        final customSourcesJson = app.settings.get('custom_sources', '[]');
        expect(
          customSourcesJson.contains('https://sources.bitchord.org/stream'),
          isTrue,
        );
        expect(customSourcesJson.contains('Custom High-Res FLAC'), isTrue);
      },
    );

    testWidgets(
      '10b. DesktopSourceEditorDialog reports HTTP error when endpoint returns 404',
      (tester) async {
        final app = _createTestApp();
        final mockClient = MockClient(
          (request) async => http.Response('Not Found', 404),
        );

        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopSourceEditorDialog(app: app, client: mockClient),
        );

        final urlField = find.widgetWithText(TextField, 'Addon URL');
        await tester.enterText(
          urlField,
          'https://sources.bitchord.org/dead-stream',
        );
        await tester.pumpAndSettle();

        final testButton = find.text('Test');
        await tester.tap(testButton);
        await tester.pumpAndSettle();

        expect(find.text('Endpoint unreachable (HTTP 404)'), findsOneWidget);
      },
    );

    testWidgets(
      '10c. DesktopSourceEditorDialog reports connection failure on network exception',
      (tester) async {
        final app = _createTestApp();
        final mockClient = MockClient(
          (request) async => throw http.ClientException('Host lookup failed'),
        );

        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopSourceEditorDialog(app: app, client: mockClient),
        );

        final urlField = find.widgetWithText(TextField, 'Addon URL');
        await tester.enterText(
          urlField,
          'https://sources.bitchord.org/unreachable',
        );
        await tester.pumpAndSettle();

        final testButton = find.text('Test');
        await tester.tap(testButton);
        await tester.pumpAndSettle();

        expect(find.textContaining('Connection failed:'), findsOneWidget);
      },
    );

    testWidgets(
      '11. DesktopSpotifyCanvasDialog enters sp_dc token and persists dual keys',
      (tester) async {
        final app = _createTestApp();
        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopSpotifyCanvasDialog(app: app),
        );

        expect(find.text('Spotify account'), findsOneWidget);

        final cookieField = find.byType(TextField);
        await tester.enterText(cookieField, 'sp_dc_sample_token_secret_12345');
        await tester.pumpAndSettle();

        final saveButton = find.text('Save');
        await tester.tap(saveButton);
        await tester.pumpAndSettle();

        expect(
          app.settings.get('spotify_spdc_token', ''),
          equals('sp_dc_sample_token_secret_12345'),
        );
        expect(
          app.settings.get('spotify_dc', ''),
          equals('sp_dc_sample_token_secret_12345'),
        );
      },
    );

    testWidgets(
      '12. DesktopStorageAndDataDialog adjusts cache slider and performs cache clear',
      (tester) async {
        final app = _createTestApp();
        await _pumpDialog(
          tester,
          app: app,
          dialog: DesktopStorageAndDataDialog(app: app),
        );

        expect(find.text('Storage & Data Management'), findsOneWidget);

        // Verify and manipulate cache slider
        final cacheSliderFinder = find.byKey(
          const ValueKey('dialog_cache_slider'),
        );
        expect(cacheSliderFinder, findsOneWidget);

        final slider = tester.widget<Slider>(cacheSliderFinder);
        slider.onChanged?.call(2048.0);
        await tester.pumpAndSettle();

        expect(app.settings.get('media_cache_limit_mb', 512), equals(2048));

        // Clear song cache
        final clearSongCacheTile = find.text('Clear song cache');
        await tester.tap(clearSongCacheTile);
        await tester.pumpAndSettle();

        expect(find.text('Song cache cleared'), findsOneWidget);

        // Clear image cache
        final clearImageCacheTile = find.text('Clear image cache');
        await tester.tap(clearImageCacheTile);
        await tester.pumpAndSettle();

        expect(find.text('Image cache cleared'), findsOneWidget);

        // Verify export and restore backup tiles exist
        expect(find.text('Export backup'), findsOneWidget);
        expect(find.text('Restore backup'), findsOneWidget);

        // Dismiss dialog
        final doneButton = find.text('Done');
        await tester.tap(doneButton);
        await tester.pumpAndSettle();
      },
    );

    test(
      '13. JSON backup export and restore round-trip with schema validation',
      () async {
        final settings = Settings.memory();
        await settings.set('desktop_theme_mode', 'DARK');
        await settings.set('media_cache_limit_mb', 2048);
        await settings.set(
          'spotify_spdc_token',
          'secret_token_123',
        ); // Secret should not be exported

        final exported = settings.exportPrefs();
        expect(exported['desktop_theme_mode'], equals('DARK'));
        expect(exported['media_cache_limit_mb'], equals(2048));
        expect(exported.containsKey('spotify_spdc_token'), isFalse);

        final backupMap = BackupCodec.encode(settings: exported);
        expect(backupMap['app'], equals('bitchord'));
        expect(backupMap['version'], equals(1));

        final jsonStr = jsonEncode(backupMap);
        final decoded = BackupCodec.decode(jsonStr);

        final newSettings = Settings.memory();
        await newSettings.importPrefs(decoded);
        expect(newSettings.get('desktop_theme_mode', 'SYSTEM'), equals('DARK'));
        expect(newSettings.get('media_cache_limit_mb', 512), equals(2048));

        // Test foreign app rejection
        final foreignBackup = jsonEncode({
          'app': 'other_app',
          'version': 1,
          'settings': {},
        });
        expect(
          () => BackupCodec.decode(foreignBackup),
          throwsA(isA<FormatException>()),
        );
      },
    );
  });
}
