import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/data/legacy_profile.dart';
import 'legacy_fixture.dart';

void main() {
  test('legacy fractional volume converts to the Flutter percentage range', () {
    expect(
      LegacyProfile.parseXml(xml({'volume': '0.75'})).settings['volume'],
      75.0,
    );
    expect(
      LegacyProfile.parseXml(xml({'volume': '1.0'})).settings['volume'],
      100.0,
    );
    expect(
      LegacyProfile.parseXml(xml({'volume': '5'})).settings['volume'],
      100.0,
    );
    expect(
      LegacyProfile.parseXml(xml({'volume': '-0.5'})).settings['volume'],
      0.0,
    );
  });
  test('migration preserves disabled lyrics providers and user order', () {
    final chosen = LegacyProfile.parseXml(
      xml({
        'lyrics_sources': 'LRCLIB,BetterLyrics,SimpMusic',
        'lyrics_source_order': 'Genius,LRCLIB,BetterLyrics,lrc.red',
      }),
    );
    expect(chosen.settings['lyrics_providers'], ['LRCLIB', 'BetterLyrics']);
    final off = LegacyProfile.parseXml(xml({'lyrics_sources': ''}));
    expect(off.settings['lyrics_providers'], isEmpty);
  });

  test('migration honors source four-rung audio-quality upgrade rules', () {
    expect(
      LegacyProfile.parseXml(
        xml({'audio_quality': 'HIGH'}),
      ).settings['audio_quality'],
      'LOSSLESS',
    );
    expect(
      LegacyProfile.parseXml(
        xml({'audio_quality': 'HIGH', 'audio_quality_rungs': 'true'}),
      ).settings['audio_quality'],
      'HIGH',
    );
    expect(
      LegacyProfile.parseXml(
        xml({'audio_quality': 'STANDARD'}),
      ).settings['audio_quality'],
      'MEDIUM',
    );
  });

  test('a preferences export for another application is not imported', () {
    expect(
      () => LegacyProfile.parseXml(
        '<preferences><node name="different.app"><map><entry key="volume" value="1"/></map></node></preferences>',
      ),
      throwsFormatException,
    );
  });

  test('legacy Base64 URL fields preserve Unicode and occurrence metadata', () {
    final song = LegacyProfile.decodeSong(legacySong())!;
    expect(song.title, '日本語 • Déjà vu');
    expect(song.artist, 'Björk');
    expect(song.setVideoId, 'set-one');
    expect(song.isVideoOrigin, isTrue);
    expect(song.fromAutoplay, isTrue);
    expect(song.sourceQuality, 'LOSSLESS');
    expect(song.localDateAddedSeconds, 1700000000);
    expect(song.isExplicit, isTrue);
  });

  test(
    'missing first chunk fails without masking a genuine unchunked value',
    () {
      final encoded = legacySong();
      expect(
        () => LegacyProfile.parseXml(xml({'history.1': encoded})),
        throwsA(
          isA<FormatException>().having(
            (error) => error.message,
            'message',
            contains('Missing first legacy preference chunk for history'),
          ),
        ),
      );
      expect(
        () => LegacyProfile.parseXml(
          xml({'history': encoded, 'history.1': encoded}),
        ),
        throwsFormatException,
      );
      final legacy = LegacyProfile.parseXml(xml({'history': encoded}));
      expect(legacy.history.single.title, '日本語 • Déjà vu');
    },
  );

  test(
    'chunked preferences reassemble and missing middle chunks fail safely',
    () {
      final encoded = legacySong();
      final split = encoded.length ~/ 2;
      final profile = LegacyProfile.parseXml(
        xml({
          'history.0': encoded.substring(0, split),
          'history.1': encoded.substring(split),
          'queue': encoded,
          'queue_index': '0',
          'volume': '0.75',
          'youtube_cookie': 'must-not-import',
          'source_configs': 'private-url',
          'lastfm_session_key': 'private-session',
          'listenbrainz_token': 'private-token',
          'paxsenix_api_key': 'private-key',
        }),
      );
      expect(profile.history.single.title, '日本語 • Déjà vu');
      expect(profile.settings['volume'], 75.0);
      expect(profile.settings.containsKey('youtube_cookie'), isFalse);
      expect(
        profile.skippedKeys,
        containsAll([
          'youtube_cookie',
          'source_configs',
          'lastfm_session_key',
          'listenbrainz_token',
          'paxsenix_api_key',
        ]),
      );
      expect(
        () => LegacyProfile.parseXml(
          xml({
            'history.0': encoded.substring(0, split),
            'history.2': encoded.substring(split),
          }),
        ),
        throwsFormatException,
      );
    },
  );
}
