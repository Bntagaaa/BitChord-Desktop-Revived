// Regression fixtures ported from 1.9.2 AdvancedAutomixPlannerTest,
// PhraseBarAutomixTest, SpokenOrLiveTest and BeatAwareTransitionTest.
import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/playback/smart/beat_aware_transition.dart';
import 'package:bitchord_desktop/playback/smart/track_analysis.dart';
import 'package:bitchord_desktop/playback/smart/transition_planner.dart';
import 'package:bitchord_desktop/playback/smart/transition_policy.dart';

TrackAnalysis track(
  String id,
  double bpm, {
  String key = 'C major',
  double vocal = .1,
  double duration = 200,
  double? bass,
}) {
  final beat = 60 / bpm;
  final downbeats = <double>[];
  for (var time = .5; time < duration - 1; time += 4 * beat) {
    downbeats.add(time);
  }
  final energy = [
    for (var i = 0; i < (duration * 2).toInt(); i++) EnergySample(i / 2, 1),
  ];
  return TrackAnalysis(
    status: TrackAnalysis.statusReady,
    trackId: id,
    duration: duration,
    bpm: bpm,
    beatInterval: beat,
    beatConfidence: .9,
    downbeats: downbeats,
    phraseBoundaries: [
      for (var i = 0; i < downbeats.length; i += 8) downbeats[i],
    ],
    firstBeat: .5,
    key: key,
    keyConfidence: .9,
    audibleStartTime: .5,
    contentEndTime: duration - 2,
    mixInTime: downbeats[4],
    energyCurve: energy,
    vocalProbability: vocal,
    lowEnergyCurve: bass == null
        ? []
        : energy.map((s) => EnergySample(s.time, bass)).toList(),
  );
}

TransitionPlan plan(
  TrackAnalysis current,
  TrackAnalysis next, {
  bool advanced = true,
  int bars = 0,
  bool mixWindow = false,
}) => planTransition(
  analysis: current,
  nextAnalysis: next,
  currentTrack: TransitionTrackInfo(
    id: current.trackId,
    durationMs: (current.duration * 1000).toInt(),
  ),
  nextTrack: TransitionTrackInfo(
    id: next.trackId,
    durationMs: (next.duration * 1000).toInt(),
  ),
  currentTime: 10,
  duration: current.duration,
  mode: CrossfadeMode.smart,
  advanced: advanced,
  phraseBars: bars,
  mixWindow: mixWindow,
);

void main() {
  test(
    'unmixable tempo/key/vocal pair crossfades at content end into intro',
    () {
      final current = track('a', 120, vocal: .9);
      final next = track('b', 94, key: 'F♯ major', vocal: .9);
      for (final advanced in [false, true]) {
        final result = plan(current, next, advanced: advanced);
        expect(result.transitionStyle, TransitionStyle.equalPower);
        expect(result.transitionEnd, current.contentEndTime);
        expect(result.incomingCueTime, next.audibleStartTime);
        expect(result.incomingPlaybackRate, 1);
        expect(result.echoSeconds, 0);
        expect(result.policyReasons, contains('unmixable-pair'));
      }
    },
  );

  test('tempo mismatch keeps compact filter gesture and follows low end', () {
    final current = track('a', 120, bass: 1);
    final next = track('b', 94, bass: 1);
    final result = plan(current, next);
    expect(result.transitionStyle, TransitionStyle.djFilter);
    expect(result.fadeSeconds, lessThanOrEqualTo(8));
    expect(result.filterVariant, FilterTransitionVariant.bassHandoff);
    expect(
      plan(current, track('b', 94)).filterVariant,
      FilterTransitionVariant.sweep,
    );
  });

  test('unmatched pair never echoes and enters at its intro', () {
    final current = track('a', 120).copyWith(contentEndTime: 180);
    for (var i = 0; i < 24; i++) {
      final next = track('next-$i', 94, bass: 1);
      final result = plan(current, next);
      expect(result.transitionStyle, TransitionStyle.djFilter);
      expect(result.echoSeconds, 0);
      expect(result.filterVariant, isNot(FilterTransitionVariant.echoRide));
      expect(result.incomingCueTime, next.audibleStartTime);
    }
  });

  test('two vocal tracks with weak incoming grid use protective sweep', () {
    final result = plan(
      track('a', 120, vocal: .9, bass: 1),
      track('b', 130, vocal: .9, bass: 1).copyWith(beatConfidence: .4),
    );
    expect(result.transitionStyle, TransitionStyle.djFilter);
    expect(result.filterVariant, FilterTransitionVariant.sweep);
    expect(result.vocalOverlap, greaterThanOrEqualTo(.5));
    expect(result.echoSeconds, 0);
  });

  test('slower incoming track speeds up without retiming outgoing deck', () {
    final result = plan(track('a', 120), track('b', 111));
    expect(result.transitionStyle, TransitionStyle.djBlend);
    expect(result.incomingPlaybackRate, closeTo(120 / 111, 1e-3));
    expect(result.outgoingPlaybackRate, 1);
  });

  test('asymmetric stretch ceiling rejects slowing incoming more than 10%', () {
    for (final bpm in [135.0, 136.0]) {
      final result = plan(track('a', 120), track('b', bpm));
      expect(result.transitionStyle, TransitionStyle.djFilter);
      expect(result.incomingPlaybackRate, 1);
      expect(result.outgoingPlaybackRate, 1);
    }
    for (final advanced in [false, true]) {
      final result = plan(track('a', 120), track('b', 130), advanced: advanced);
      expect(result.incomingPlaybackRate, lessThan(1));
      expect(result.outgoingPlaybackRate, 1);
    }
  });

  test('a blend never echoes or applies audible phase speed correction', () {
    final result = plan(track('a', 120, vocal: .9), track('b', 120, vocal: .9));
    expect(result.echoSeconds, 0);
    expect(result.phaseLock, isFalse);
  });

  test(
    'a high-ranked late drop cannot skip over a quarter of incoming song',
    () {
      var next = track('b', 120);
      next = next.copyWith(
        mixInCandidates: [MixCandidate(next.downbeats[70], 1, 'main_drop')],
        mixInTime: next.downbeats[70],
      );
      for (final advanced in [false, true]) {
        expect(
          plan(track('a', 120), next, advanced: advanced).incomingCueTime,
          lessThanOrEqualTo(next.duration * .25 + 1e-6),
        );
      }
    },
  );

  test('advanced overlap is whole bars with trusted outgoing grid', () {
    final result = plan(track('a', 120), track('b', 110, key: 'G major'));
    expect(result.beatSeconds, greaterThan(0));
    final bars = result.fadeSeconds / (4 * result.beatSeconds);
    expect(bars, closeTo(bars.toInt(), 1e-6));
    expect(result.phaseLock, isFalse);
  });

  for (final bars in [8, 16]) {
    test('$bars-bar mode has exactly $bars bars on outgoing grid', () {
      final current = track('a', 120), next = track('b', 120);
      final result = plan(current, next, bars: bars);
      expect(result.transitionStyle, TransitionStyle.djBlend);
      expect(result.transitionBeats, bars * 4);
      expect(result.fadeSeconds, closeTo(bars * 4 * .5, 1e-6));
      expect(
        result.transitionEnd - result.transitionStart,
        closeTo(result.fadeSeconds, 1e-6),
      );
      expect(
        current.downbeats.any((d) => (d - result.transitionStart).abs() < 1e-6),
        isTrue,
      );
      expect(result.transitionEnd, lessThanOrEqualTo(current.duration));
    });
  }

  test('phrase mode retains asymmetric incoming-only tempo matching', () {
    final result = plan(track('a', 120), track('b', 111), bars: 8);
    expect(result.transitionStyle, TransitionStyle.djBlend);
    expect(result.incomingPlaybackRate, closeTo(120 / 111, 1e-3));
    expect(result.outgoingPlaybackRate, 1);
  });

  test('mix window clamps outgoing tail and incoming cue at 30 seconds', () {
    final result = plan(
      track('a', 120),
      track('b', 120),
      bars: 16,
      mixWindow: true,
    );
    expect(result.transitionStart, greaterThanOrEqualTo(170));
    expect(result.incomingCueTime, lessThanOrEqualTo(30));
    expect(result.fadeSeconds, result.transitionEnd - result.transitionStart);
  });

  test('ordinary Live titles and band name are not live recordings', () {
    for (final title in [
      'Live It Up (feat. Pitbull)',
      'Live Your Life',
      "Live While We're Young",
      'Live Forever',
      'Lightning Crashes',
      'Performance',
    ]) {
      expect(
        isSpokenOrLive(
          TransitionTrackInfo(
            id: 't',
            durationMs: 0,
            title: title,
            artist: 'Live',
          ),
        ),
        isFalse,
        reason: title,
      );
    }
  });

  test('labelled live recordings and speech keep protective exclusion', () {
    for (final title in [
      'Bohemian Rhapsody (Live)',
      'Creep [Live]',
      'Yellow (Live at Glastonbury 2016)',
      'Song (Acoustic Live)',
      'Hello - Live',
      'Hello – Live from the Royal Albert Hall',
      'Live at Wembley',
      'Live: At The Hall',
      'Episode 12: Interview',
      'The Hobbit (Audiobook)',
    ]) {
      expect(
        isSpokenOrLive(
          TransitionTrackInfo(id: 't', durationMs: 0, title: title),
        ),
        isTrue,
        reason: title,
      );
    }
  });

  test('stale analysis falls back and short tracks have an explicit guard', () {
    final current = track('a', 120).copyWith(trackId: 'older-track');
    final stale = planTransition(
      analysis: current,
      nextAnalysis: track('b', 120),
      currentTrack: const TransitionTrackInfo(id: 'a', durationMs: 200000),
      duration: 200,
      mode: CrossfadeMode.smart,
    );
    expect(stale.transitionStyle, TransitionStyle.equalPower);
    expect(stale.reason, 'before-smart-analysis-fallback-window');
    final short = planTransition(duration: 30, mode: CrossfadeMode.smart);
    expect(short.blocked, isTrue);
    expect(short.reason, 'short-duration-guard');
  });

  test(
    'policy excludes invalid tempo, low confidence and unattainable stretch',
    () {
      expect(
        assessTransitionTier(
          track('a', 120).copyWith(bpm: double.nan),
          track('b', 120),
        ).tier,
        TransitionTier.plainCrossfade,
      );
      expect(
        assessTransitionTier(
          track('a', 120).copyWith(beatConfidence: .1),
          track('b', 120).copyWith(beatConfidence: .1),
        ).tier,
        TransitionTier.plainCrossfade,
      );
      expect(
        assessTransitionTier(track('a', 120), track('b', 94)).tier,
        TransitionTier.djAssisted,
      );
      expect(stretchReachable(120, 96), isTrue);
      expect(stretchReachable(120, 95), isFalse);
    },
  );

  test('mix-out ranking refuses cutting more than 12 seconds of music', () {
    final current = track('a', 120).copyWith(
      mixOutCandidates: const [
        MixCandidate(100, 1, 'energy_cliff'),
        MixCandidate(190, .8, 'outro_start'),
      ],
    );
    final ranked = rankMixOutCandidates(current);
    expect(ranked.any((c) => c.time == 100), isFalse);
    expect(ranked.first.time, 190);
  });

  test(
    'sample seam uses source clock and late decoder downgrades honestly',
    () {
      const p = TransitionPlan(
        transitionStart: 10,
        transitionEnd: 18,
        fadeSeconds: 8,
        incomingCueTime: 2,
        incomingPlaybackRate: 1.1,
        transitionStyle: TransitionStyle.djBlend,
        bassSwap: true,
        phaseLock: true,
      );
      expect(transitionPrefixFrames(p, 9990000, 512, 48000), 480);
      expect(transitionPrefixFrames(p, 9980000, 512, 48000), isNull);
      final late = lateTransitionFallback(p, 15);
      expect(late.transitionStyle, TransitionStyle.equalPower);
      expect(late.fadeSeconds, 3);
      expect(late.incomingPlaybackRate, 1);
      expect(late.incomingHandoffTime, 5);
      expect(late.policyReasons, contains('late-decoder-crossfade'));
    },
  );
}
