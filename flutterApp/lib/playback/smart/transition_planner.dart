/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), merging its
 * TransitionPlanner.kt and WsolaPlanner.kt into one file.
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

// Pure Dart port of the 1.9.2 transition planner. A plan is a request for a
// capable renderer; it is not evidence that beat analysis or DSP has run.
import 'dart:math' as math;

import 'track_analysis.dart';
import 'transition_policy.dart';

enum CrossfadeMode { standard, smart }

enum TransitionStyle { equalPower, gapless, djBlend, djFilter }

enum FilterTransitionVariant { sweep, bassHandoff, echoRide }

class TransitionTrackInfo {
  const TransitionTrackInfo({
    required this.id,
    required this.durationMs,
    this.title = '',
    this.artist = '',
    this.album = '',
    this.albumId = '',
  });
  final String id, title, artist, album, albumId;
  final int durationMs;
}

class TransitionPlan {
  const TransitionPlan({
    this.shouldStart = false,
    this.markerVisible = false,
    this.blocked = false,
    this.reason = '',
    this.transitionStart = 0,
    this.transitionEnd = 0,
    this.fadeSeconds = 0,
    this.transitionStyle = TransitionStyle.equalPower,
    this.incomingCueTime = 0,
    this.incomingHandoffTime = 0,
    this.incomingPlaybackRate = 1,
    this.outgoingPlaybackRate = 1,
    this.handoffStartSeconds = 0,
    this.handoffDuration = 0,
    this.pickupSeconds = 0,
    this.transitionBeats = 0,
    this.bassSwap = false,
    this.handoffFraction = .5,
    this.bedPosition = .5,
    this.bassSwapFraction = .7,
    this.filterSweep = 0,
    this.filterVariant = FilterTransitionVariant.sweep,
    this.vocalOverlap = 0,
    this.outgoingBpm = 0,
    this.incomingBpm = 0,
    this.beatSeconds = 0,
    this.phaseLock = false,
    this.echoSeconds = 0,
    this.policyReasons = const [],
  });
  final bool shouldStart;
  final bool markerVisible;
  final bool blocked;
  final String reason;
  final double transitionStart;
  final double transitionEnd;
  final double fadeSeconds;
  final TransitionStyle transitionStyle;
  final double incomingCueTime;
  final double incomingHandoffTime;
  final double incomingPlaybackRate;
  final double outgoingPlaybackRate;
  final double handoffStartSeconds;
  final double handoffDuration;
  final double pickupSeconds;
  final int transitionBeats;
  final bool bassSwap;
  final double handoffFraction;
  final double bedPosition;
  final double bassSwapFraction;
  final double filterSweep;
  final FilterTransitionVariant filterVariant;
  final double vocalOverlap;
  final double outgoingBpm;
  final double incomingBpm;
  final double beatSeconds;
  final bool phaseLock;
  final double echoSeconds;
  final List<String> policyReasons;
  int get fadeMs => (fadeSeconds * 1000).round();
  TransitionPlan copyWith({
    bool? shouldStart,
    bool? markerVisible,
    bool? blocked,
    String? reason,
    double? transitionStart,
    double? transitionEnd,
    double? fadeSeconds,
    TransitionStyle? transitionStyle,
    double? incomingCueTime,
    double? incomingHandoffTime,
    double? incomingPlaybackRate,
    double? outgoingPlaybackRate,
    double? handoffStartSeconds,
    double? handoffDuration,
    double? pickupSeconds,
    int? transitionBeats,
    bool? bassSwap,
    double? handoffFraction,
    double? bedPosition,
    double? bassSwapFraction,
    double? filterSweep,
    FilterTransitionVariant? filterVariant,
    double? vocalOverlap,
    double? outgoingBpm,
    double? incomingBpm,
    double? beatSeconds,
    bool? phaseLock,
    double? echoSeconds,
    List<String>? policyReasons,
  }) => TransitionPlan(
    shouldStart: shouldStart ?? this.shouldStart,
    markerVisible: markerVisible ?? this.markerVisible,
    blocked: blocked ?? this.blocked,
    reason: reason ?? this.reason,
    transitionStart: transitionStart ?? this.transitionStart,
    transitionEnd: transitionEnd ?? this.transitionEnd,
    fadeSeconds: fadeSeconds ?? this.fadeSeconds,
    transitionStyle: transitionStyle ?? this.transitionStyle,
    incomingCueTime: incomingCueTime ?? this.incomingCueTime,
    incomingHandoffTime: incomingHandoffTime ?? this.incomingHandoffTime,
    incomingPlaybackRate: incomingPlaybackRate ?? this.incomingPlaybackRate,
    outgoingPlaybackRate: outgoingPlaybackRate ?? this.outgoingPlaybackRate,
    handoffStartSeconds: handoffStartSeconds ?? this.handoffStartSeconds,
    handoffDuration: handoffDuration ?? this.handoffDuration,
    pickupSeconds: pickupSeconds ?? this.pickupSeconds,
    transitionBeats: transitionBeats ?? this.transitionBeats,
    bassSwap: bassSwap ?? this.bassSwap,
    handoffFraction: handoffFraction ?? this.handoffFraction,
    bedPosition: bedPosition ?? this.bedPosition,
    bassSwapFraction: bassSwapFraction ?? this.bassSwapFraction,
    filterSweep: filterSweep ?? this.filterSweep,
    filterVariant: filterVariant ?? this.filterVariant,
    vocalOverlap: vocalOverlap ?? this.vocalOverlap,
    outgoingBpm: outgoingBpm ?? this.outgoingBpm,
    incomingBpm: incomingBpm ?? this.incomingBpm,
    beatSeconds: beatSeconds ?? this.beatSeconds,
    phaseLock: phaseLock ?? this.phaseLock,
    echoSeconds: echoSeconds ?? this.echoSeconds,
    policyReasons: policyReasons ?? this.policyReasons,
  );
}

const maxIncomingSkipFraction = .25;
const phraseBarsShort = 8;
const phraseBarsLong = 16;
const arrangementOverlapBeats = 8;
const handoffFraction = .5;
const bedPosition = .5;
const filterSweep = 1.0;
const _minClearance = 5.0;
const _vocalConflictProbability = .62;
const _energyWindowBeats = 8;
const _keyIndex = {
  'C': 0,
  'C♯': 1,
  'D♭': 1,
  'D': 2,
  'D♯': 3,
  'E♭': 3,
  'E': 4,
  'F': 5,
  'F♯': 6,
  'G♭': 6,
  'G': 7,
  'G♯': 8,
  'A♭': 8,
  'A': 9,
  'A♯': 10,
  'B♭': 10,
  'B': 11,
};
final _spokenText = RegExp(
  r'\b(podcast|episode|audiobook|concert)\b',
  caseSensitive: false,
);
final _liveText = RegExp(
  r'[(\[]\s*(?:[^)\]]*\s)?live(?:\s+(?:at|from|in|on|version|session|recording|performance|edit)\b[^)\]]*)?\s*[)\]]'
  r'|[-–—]\s*live(?:\s+(?:at|from|in|on|version|session|recording|performance|edit)\b.*)?\s*$'
  r'|^\s*live\s*(?::|\s(?:at|from|in|on)\b)',
  caseSensitive: false,
);

bool isSpokenOrLive(TransitionTrackInfo? track) =>
    track != null &&
    (_spokenText.hasMatch('${track.title} ${track.artist} ${track.album}') ||
        _liveText.hasMatch(track.title) ||
        _liveText.hasMatch(track.album));

TransitionPlan _blocked(String reason, {double start = 0, double end = 0}) =>
    TransitionPlan(
      blocked: true,
      reason: reason,
      transitionStart: start,
      transitionEnd: end,
    );
double _trackDuration(TransitionTrackInfo? track) =>
    track == null || track.durationMs <= 0 ? 0 : track.durationMs / 1000;
double _roundRate(double value) => (value * 10000).round() / 10000;

bool _sameAlbum(TransitionTrackInfo? left, TransitionTrackInfo? right) {
  if (left == null || right == null) return false;
  if (left.albumId.trim().isNotEmpty && left.albumId == right.albumId) {
    return true;
  }
  return left.album.trim().isNotEmpty &&
      left.album == right.album &&
      left.artist == right.artist;
}

double _tempoRatio(double outgoing, double incoming) =>
    outgoing <= 0 || incoming <= 0
    ? 1
    : alignTempoOctave(outgoing, incoming) / outgoing;

(int?, String?) _splitKey(String key) {
  final parts = key.trim().split(' ');
  return (_keyIndex[parts.firstOrNull], parts.length > 1 ? parts[1] : null);
}

int? _keyDistance(String left, String right) {
  final (l, lm) = _splitKey(left);
  final (r, rm) = _splitKey(right);
  if (l == null || r == null) return null;
  return math.min((l - r + 12) % 12, (r - l + 12) % 12) +
      (lm != null && rm != null && lm != rm ? 1 : 0);
}

bool _harmonicallyCompatible(String left, String right) {
  final (l, lm) = _splitKey(left);
  final (r, rm) = _splitKey(right);
  if (l == null || r == null) return false;
  final distance = math.min((l - r + 12) % 12, (r - l + 12) % 12);
  if (lm != null && rm != null && lm != rm) return distance <= 1;
  return distance <= 2 || distance == 5;
}

String _trustedKey(TrackAnalysis analysis) =>
    analysis.key.trim().isEmpty || analysis.keyConfidence < .25
    ? ''
    : analysis.key;

bool _unmixablePair(TrackAnalysis analysis, TrackAnalysis nextAnalysis) {
  final outgoing = orZero(analysis.bpm), incoming = orZero(nextAnalysis.bpm);
  if (outgoing <= 0 || incoming <= 0 || stretchReachable(outgoing, incoming)) {
    return false;
  }
  final left = _trustedKey(analysis), right = _trustedKey(nextAnalysis);
  return (left.isNotEmpty &&
          right.isNotEmpty &&
          !_harmonicallyCompatible(left, right)) ||
      (analysis.vocalProbability >= _vocalConflictProbability &&
          nextAnalysis.vocalProbability >= _vocalConflictProbability);
}

double? _nearestTimedValue(
  List<double> values,
  double target, {
  double tolerance = double.infinity,
  double minimum = 0,
  bool before = false,
}) {
  final candidates = values
      .where(
        (v) =>
            v.isFinite &&
            v >= minimum &&
            (!before || v <= target) &&
            (v - target).abs() <= tolerance,
      )
      .toList();
  candidates.sort((a, b) => (a - target).abs().compareTo((b - target).abs()));
  return candidates.firstOrNull;
}

double? _nearestAtOrBefore(List<double> values, double target) =>
    _nearestTimedValue(values, target, before: true);

double _alignedStart(
  TrackAnalysis analysis,
  double target,
  double end, {
  required bool preferEarlier,
  required double minimum,
}) {
  final interval = orZero(analysis.beatInterval) > 0
      ? analysis.beatInterval
      : orZero(analysis.bpm) > 0
      ? 60 / analysis.bpm
      : 0.0;
  final phrase = _nearestTimedValue(
    analysis.phraseBoundaries,
    target,
    tolerance: math.max(1.0, interval * 4),
    minimum: minimum,
    before: preferEarlier,
  );
  final downbeat = _nearestTimedValue(
    analysis.downbeats,
    target,
    tolerance: math.max(.75, interval * 2),
    minimum: minimum,
    before: preferEarlier,
  );
  return clamp(phrase ?? downbeat ?? target, minimum, end);
}

double _onDownbeatBefore(
  TrackAnalysis analysis,
  double target,
  double tolerance,
) =>
    _nearestTimedValue(
      analysis.downbeats,
      target,
      tolerance: tolerance,
      before: true,
    ) ??
    target;

double latestIncomingCue(double length) => length.isFinite && length > 0
    ? length * maxIncomingSkipFraction
    : double.infinity;

double incomingCuePoint(
  TrackAnalysis analysis, {
  double latest = double.infinity,
}) {
  final ranked = rankMixInCandidates(
    analysis,
  ).where((c) => c.time <= latest).firstOrNull;
  if (ranked != null) return ranked.time;
  final interval = orZero(analysis.beatInterval) > 0
      ? analysis.beatInterval
      : orZero(analysis.bpm) > 0
      ? 60 / analysis.bpm
      : 0.0;
  double within(double value) => value <= latest
      ? value
      : _nearestAtOrBefore(analysis.downbeats, latest) ?? latest;
  if (analysis.mixInTime.isFinite && analysis.mixInTime > 0) {
    return within(
      _nearestTimedValue(
            analysis.downbeats,
            analysis.mixInTime,
            tolerance: math.max(.5, interval * 2),
          ) ??
          analysis.mixInTime,
    );
  }
  final intro = orZero(analysis.introEndTime);
  final audible = orZero(analysis.audibleStartTime ?? analysis.pickupTime);
  final pickup = math.max(
    0.0,
    intro != 0
        ? intro
        : audible != 0
        ? audible
        : orZero(analysis.firstBeat),
  );
  final duration = orZero(analysis.duration) != 0 ? analysis.duration : 300;
  if (pickup > 0 && pickup < duration - 10) {
    final beat = analysis.downbeats.where((v) => v >= pickup).firstOrNull;
    if (beat != null) return within(beat);
  }
  if (analysis.phraseBoundaries.length > 1 &&
      analysis.phraseBoundaries[1] > 4) {
    return within(analysis.phraseBoundaries[1]);
  }
  if (analysis.downbeats.length >= 8) {
    return within(
      orZero(analysis.downbeats[math.min(8, analysis.downbeats.length - 1)]),
    );
  }
  return within(pickup);
}

double _incomingStart(TrackAnalysis analysis) =>
    [
      analysis.audibleStartTime,
      analysis.pickupTime,
      analysis.firstBeat,
    ].whereType<double>().where((v) => v.isFinite && v >= 0).firstOrNull ??
    0;

TransitionPlan _capIncomingSkip(
  TransitionPlan plan,
  TrackAnalysis next,
  double nextLength,
) {
  final latest = latestIncomingCue(nextLength);
  if (plan.incomingCueTime <= latest) return plan;
  final beat = orZero(next.beatInterval) > 0
      ? next.beatInterval
      : orZero(next.bpm) > 0
      ? 60 / next.bpm
      : 0.0;
  final over = plan.incomingCueTime - latest;
  final shift = beat > 0 ? (over / (4 * beat)).ceil() * 4 * beat : over;
  final cue = math.max(0.0, plan.incomingCueTime - shift);
  return plan.copyWith(
    incomingCueTime: cue,
    incomingHandoffTime: math.max(
      cue,
      plan.incomingHandoffTime - (plan.incomingCueTime - cue),
    ),
  );
}

double? _averageEnergy(List<EnergySample> curve, double from, double until) {
  if (until <= from) return null;
  var low = 0, high = curve.length;
  while (low < high) {
    final middle = (low + high) ~/ 2;
    if (curve[middle].time < from) {
      low = middle + 1;
    } else {
      high = middle;
    }
  }
  var sum = 0.0, count = 0;
  for (
    var index = low;
    index < curve.length && curve[index].time < until;
    index++
  ) {
    final point = curve[index];
    if (point.time.isFinite && point.energy.isFinite && point.energy >= 0) {
      sum += point.energy;
      count++;
    }
  }
  return count == 0 ? null : sum / count;
}

double? _lowEnergyReference(List<EnergySample> curve) {
  final values =
      curve.map((e) => e.energy).where((e) => e.isFinite && e >= 0).toList()
        ..sort();
  if (values.isEmpty) return null;
  final reference = math.max(
    values[((values.length - 1) * .9).floor()],
    values.last * .25,
  );
  return reference > 1e-9 ? reference : null;
}

double _lowEnergyResolution(List<EnergySample> curve) {
  final gaps = <double>[];
  for (var i = 1; i < curve.length; i++) {
    final gap = curve[i].time - curve[i - 1].time;
    if (gap.isFinite && gap > 0) gaps.add(gap);
  }
  gaps.sort();
  return gaps.isEmpty ? 0 : gaps[gaps.length ~/ 2];
}

double? _lowEnergyChange(
  List<EnergySample> curve,
  double? reference,
  double at,
  double window,
) {
  if (curve.isEmpty || reference == null || window <= 0) return null;
  final before = _averageEnergy(curve, at - window, at);
  final after = _averageEnergy(curve, at, at + window);
  if (before == null || after == null) return null;
  return (after / reference).clamp(0.0, 1.5) -
      (before / reference).clamp(0.0, 1.5);
}

double _bassSwapFraction({
  required TrackAnalysis analysis,
  required TrackAnalysis next,
  required double start,
  required double cue,
  required double outgoingBeat,
  required double incomingBeat,
  required double overlap,
  required int beats,
}) {
  if (overlap <= 0) return .7;
  final latestFraction = math.min(.85, 6 / overlap).clamp(0.0, 1.0);
  final prior = math.min(.7, latestFraction);
  if (beats < 2) return prior;
  final earliestBeat = (math.min(.5, latestFraction) * beats - 1e-9)
      .ceil()
      .clamp(1, beats - 1);
  final latestBeat = (latestFraction * beats + 1e-9).floor().clamp(
    earliestBeat,
    beats - 1,
  );
  final candidates = [for (var i = earliestBeat; i <= latestBeat; i++) i];
  candidates.sort((a, b) {
    final distance = (a / beats - prior).abs().compareTo(
      (b / beats - prior).abs(),
    );
    return distance != 0
        ? distance
        : (a % 4 == 0 ? 0 : 1).compareTo(b % 4 == 0 ? 0 : 1);
  });
  final outgoingReference = _lowEnergyReference(analysis.lowEnergyCurve);
  final incomingReference = _lowEnergyReference(next.lowEnergyCurve);
  final outgoingWindow = math.max(
    outgoingBeat,
    _lowEnergyResolution(analysis.lowEnergyCurve) * 1.1,
  );
  final incomingWindow = math.max(
    incomingBeat,
    _lowEnergyResolution(next.lowEnergyCurve) * 1.1,
  );
  final measured = <({int beat, double score})>[];
  for (final beat in candidates) {
    final out = _lowEnergyChange(
      analysis.lowEnergyCurve,
      outgoingReference,
      start + beat * outgoingBeat,
      outgoingWindow,
    );
    final into = _lowEnergyChange(
      next.lowEnergyCurve,
      incomingReference,
      cue + beat * incomingBeat,
      incomingWindow,
    );
    if (out != null || into != null) {
      measured.add((beat: beat, score: (into ?? 0) - (out ?? 0)));
    }
  }
  measured.sort((a, b) {
    final score = b.score.compareTo(a.score);
    if (score != 0) return score;
    final bar = (a.beat % 4 == 0 ? 0 : 1).compareTo(b.beat % 4 == 0 ? 0 : 1);
    return bar != 0
        ? bar
        : (a.beat / beats - prior).abs().compareTo(
            (b.beat / beats - prior).abs(),
          );
  });
  final strongest = measured.firstOrNull;
  return (strongest != null && strongest.score >= .25
          ? strongest.beat
          : candidates.first) /
      beats;
}

double _plannedVocalOverlap(
  TrackAnalysis analysis,
  TrackAnalysis next,
  double start,
  double end,
  double cue,
  double rate,
) => !start.isFinite || !end.isFinite || end <= start
    ? 0
    : simultaneousVocalFraction(
            outgoing: analysis,
            incoming: next,
            outStart: start,
            outEnd: end,
            inStart: cue,
            rate: rate,
          ) ??
          0;

FilterTransitionVariant _fallbackVariant(
  TrackAnalysis analysis,
  TrackAnalysis next,
  double exit,
  double beat,
) {
  final window = _energyWindowBeats * (beat > 0 ? beat : .5);
  final entry = _incomingStart(next);
  final outReference = _lowEnergyReference(analysis.lowEnergyCurve);
  final inReference = _lowEnergyReference(next.lowEnergyCurve);
  final out = _averageEnergy(analysis.lowEnergyCurve, exit - window, exit);
  final into = _averageEnergy(next.lowEnergyCurve, entry, entry + window);
  if (outReference == null ||
      inReference == null ||
      out == null ||
      into == null) {
    return FilterTransitionVariant.sweep;
  }
  return out / outReference >= .5 && into / inReference >= .5
      ? FilterTransitionVariant.bassHandoff
      : FilterTransitionVariant.sweep;
}

sealed class WsolaPlanResult {
  const WsolaPlanResult();
}

class RefusedTransition extends WsolaPlanResult {
  const RefusedTransition(this.reason);
  final String reason;
}

class PlannedTransition extends WsolaPlanResult {
  const PlannedTransition({
    required this.tier,
    required this.beatConfidence,
    required this.mixOutType,
    required this.vocalClash,
    required this.transitionStart,
    required this.transitionEnd,
    required this.overlapSeconds,
    required this.beats,
    required this.fadeBeats,
    required this.handoffFraction,
    required this.bedPosition,
    required this.bassSwapFraction,
    required this.filterSweep,
    required this.outgoingBpm,
    required this.incomingBpm,
    required this.stretchRatio,
    required this.incomingCueTime,
    required this.incomingDropTime,
    required this.incomingHandoffTime,
    required this.incomingResumeTime,
  });
  final TransitionTier tier;
  final String mixOutType;
  final bool vocalClash;
  final int beats, fadeBeats;
  final double beatConfidence,
      transitionStart,
      transitionEnd,
      overlapSeconds,
      handoffFraction,
      bedPosition,
      bassSwapFraction,
      filterSweep,
      outgoingBpm,
      incomingBpm,
      stretchRatio,
      incomingCueTime,
      incomingDropTime,
      incomingHandoffTime,
      incomingResumeTime;
}

double? incomingMixInPoint(
  TrackAnalysis analysis, {
  double latest = double.infinity,
}) {
  final beat = orZero(analysis.beatInterval) > 0
      ? analysis.beatInterval
      : orZero(analysis.bpm) > 0
      ? 60 / analysis.bpm
      : 0.0;
  final best = rankMixInCandidates(
    analysis,
  ).where((c) => c.time <= latest).firstOrNull;
  final target = [best?.time, analysis.mixInTime]
      .whereType<double>()
      .where((v) => v.isFinite && v > 0 && v <= latest)
      .firstOrNull;
  if (target == null) return null;
  final nearby = nearestValue(
    analysis.downbeats,
    target,
    math.max(.5, beat * 2),
  );
  return nearby != null && nearby <= latest ? nearby : target;
}

double incomingAudibleStart(TrackAnalysis analysis) => audibleStartOf(analysis);

WsolaPlanResult planWsolaTransition({
  required TrackAnalysis analysis,
  required TrackAnalysis nextAnalysis,
  double duration = 0,
  double nextDuration = 0,
  bool advanced = false,
}) {
  final policy = assessTransitionTier(
    analysis,
    nextAnalysis,
    advanced: advanced,
  );
  if (policy.tier != TransitionTier.beatmatched) {
    return RefusedTransition(policy.reasons.firstOrNull ?? 'policy');
  }
  final outgoingBpm = orZero(analysis.bpm);
  final incomingBpm = alignTempoOctave(outgoingBpm, orZero(nextAnalysis.bpm));
  final ratio = outgoingBpm / incomingBpm;
  final outgoingLength = math.max(orZero(duration), orZero(analysis.duration));
  final incomingLength = math.max(
    orZero(nextDuration),
    orZero(nextAnalysis.duration),
  );
  if (outgoingLength <= 0 || incomingLength <= 0) {
    return const RefusedTransition('missing-duration');
  }
  final incomingBeat = 60 / incomingBpm, outgoingBeat = 60 / outgoingBpm;
  final drop = incomingMixInPoint(
    nextAnalysis,
    latest: latestIncomingCue(incomingLength),
  );
  if (drop == null || !drop.isFinite || drop < 0) {
    return const RefusedTransition('incoming-mix-in');
  }
  final contentEnd = orZero(analysis.contentEndTime) != 0
      ? analysis.contentEndTime
      : outgoingLength;
  final anchor = resolveMixOutAnchor(
    analysis,
    contentEnd: contentEnd,
    duration: outgoingLength,
  );
  final unshiftedEnd = math.min(outgoingLength, anchor.time);
  final arrangement = anchor.type == 'content_end'
      ? math.min(
          arrangementOverlapBeats * outgoingBeat,
          maxDiscardedMusicSeconds,
        )
      : 0;
  final endTarget = math.max(_minClearance, unshiftedEnd - arrangement);
  final audibleStart = incomingAudibleStart(nextAnalysis);
  final available = math.max(0.0, drop - audibleStart) / incomingBeat;
  final capped = ((16 / incomingBeat).floor() / 4).floor() * 4;
  if (capped < 4) return const RefusedTransition('overlap-too-long');
  var fadeBeats = math.min(16, math.min(capped, (available / 4).floor() * 4));
  fadeBeats = math.max(4, fadeBeats);
  bool clashOver(int beats) {
    final outStart = endTarget - beats * outgoingBeat;
    final inStart = math.max(audibleStart, drop - beats * incomingBeat);
    final outVocal = vocalActivityBetween(analysis, outStart, endTarget);
    final inVocal = vocalActivityBetween(nextAnalysis, inStart, drop);
    final simultaneous = simultaneousVocalFraction(
      outgoing: analysis,
      incoming: nextAnalysis,
      outStart: outStart,
      outEnd: endTarget,
      inStart: inStart,
      rate: incomingBeat / outgoingBeat,
    );
    if (simultaneous != null && simultaneous > vocalClashTolerance) return true;
    if (isVocalClash(outVocal, inVocal)) return true;
    if (beats > 8 && outVocal != null && outVocal >= vocalActiveThreshold) {
      final deep = vocalActivityBetween(
        analysis,
        outStart,
        endTarget - 8 * outgoingBeat,
      );
      if (deep != null && deep >= vocalActiveThreshold) return true;
    }
    return false;
  }

  var clash = clashOver(fadeBeats);
  while (clash && fadeBeats > 4) {
    fadeBeats -= 4;
    clash = clashOver(fadeBeats);
  }
  final coverable = (math.max(0.0, drop - audibleStart) / incomingBeat).floor();
  final beats = math.min(fadeBeats, coverable);
  if (beats < 1) return const RefusedTransition('incoming-no-intro');
  final outgoingOverlap = beats * outgoingBeat, overlap = beats * incomingBeat;
  final requestedHandoff = drop + arrangementOverlapBeats * incomingBeat;
  final maxHandoff = incomingLength - _minClearance;
  if (maxHandoff < drop) return const RefusedTransition('incoming-too-short');
  final handoff = math.min(requestedHandoff, maxHandoff);
  final cue = handoff - overlap;
  if (cue < audibleStart - .05) {
    return const RefusedTransition('incoming-no-runway');
  }
  final startTarget = endTarget - outgoingOverlap;
  final start =
      _nearestAtOrBefore(analysis.downbeats, startTarget) ?? startTarget;
  if (start < _minClearance) {
    return const RefusedTransition('outgoing-too-short');
  }
  final end = start + outgoingOverlap;
  if (end > outgoingLength + .05) {
    return const RefusedTransition('outgoing-overlap-overruns');
  }
  final resume = cue + overlap;
  if (resume + _minClearance > incomingLength) {
    return const RefusedTransition('incoming-too-short');
  }
  return PlannedTransition(
    tier: policy.tier,
    beatConfidence: policy.beatConfidence,
    mixOutType: anchor.type,
    vocalClash: clash,
    transitionStart: start,
    transitionEnd: end,
    overlapSeconds: overlap,
    beats: beats,
    fadeBeats: beats,
    handoffFraction: handoffFraction,
    bedPosition: bedPosition,
    bassSwapFraction: _bassSwapFraction(
      analysis: analysis,
      next: nextAnalysis,
      start: start,
      cue: cue,
      outgoingBeat: outgoingBeat,
      incomingBeat: incomingBeat,
      overlap: overlap,
      beats: beats,
    ),
    filterSweep: filterSweep,
    outgoingBpm: outgoingBpm,
    incomingBpm: incomingBpm,
    stretchRatio: ratio,
    incomingCueTime: cue,
    incomingDropTime: drop,
    incomingHandoffTime: handoff,
    incomingResumeTime: resume,
  );
}

double _incomingRate(double ratio) =>
    _roundRate(clamp(ratio, minIncomingRate, maxIncomingRate));

TransitionPlan? _phraseSwitch(
  TrackAnalysis analysis,
  TrackAnalysis next,
  double length,
  double nextLength,
  bool advanced,
) {
  if (!_harmonicallyCompatible(_trustedKey(analysis), _trustedKey(next))) {
    return null;
  }
  final result = planWsolaTransition(
    analysis: analysis,
    nextAnalysis: next,
    duration: length,
    nextDuration: nextLength,
    advanced: advanced,
  );
  if (result is! PlannedTransition ||
      result.stretchRatio < minIncomingRate ||
      result.stretchRatio > maxIncomingRate) {
    return null;
  }
  final overlap = result.transitionEnd - result.transitionStart;
  final rate = _incomingRate(result.stretchRatio);
  return TransitionPlan(
    markerVisible: true,
    transitionStart: result.transitionStart,
    transitionEnd: result.transitionEnd,
    fadeSeconds: overlap,
    handoffDuration: overlap,
    incomingCueTime: result.incomingCueTime,
    incomingHandoffTime: result.incomingHandoffTime,
    incomingPlaybackRate: rate,
    pickupSeconds: incomingAudibleStart(next),
    transitionBeats: result.beats,
    bassSwap: true,
    handoffFraction: result.handoffFraction,
    bedPosition: result.bedPosition,
    bassSwapFraction: result.bassSwapFraction,
    vocalOverlap: _plannedVocalOverlap(
      analysis,
      next,
      result.transitionStart,
      result.transitionEnd,
      result.incomingCueTime,
      rate,
    ),
    outgoingBpm: result.outgoingBpm,
    incomingBpm: result.incomingBpm,
    beatSeconds: 60 / result.outgoingBpm,
    transitionStyle: TransitionStyle.djBlend,
  );
}

TransitionPlan? _phraseBarPlan(
  TrackAnalysis analysis,
  TrackAnalysis next,
  double length,
  double nextLength,
  int bars,
  double mixAnchor,
  bool advanced,
) {
  final outBpm = orZero(analysis.bpm);
  if (outBpm < minBpm || outBpm > maxBpm) return null;
  final beat = orZero(analysis.beatInterval) > 0
      ? analysis.beatInterval
      : 60 / outBpm;
  if (beat <= 0) return null;
  final beats = bars * 4;
  final requested = beats * beat;
  if (requested <= 0 || requested > 64) return null;
  final contentEnd = orZero(analysis.contentEndTime) != 0
      ? analysis.contentEndTime
      : length;
  final anchor = math.min(math.min(mixAnchor, contentEnd), length);
  final gridTrusted = orZero(analysis.beatConfidence) >= minDjConfidence;
  final mixEnd = gridTrusted
      ? _onDownbeatBefore(analysis, anchor, 4 * beat)
      : anchor;
  if (mixEnd <= 0) return null;
  final latestStart = mixEnd - requested;
  if (latestStart < _minClearance) return null;
  final start = gridTrusted
      ? _alignedStart(
          analysis,
          latestStart,
          mixEnd - .05,
          preferEarlier: true,
          minimum: _minClearance,
        )
      : latestStart;
  final end = start + requested;
  if (end > length + .05) return null;
  final overlap = end - start;
  if (overlap <= 0) return null;
  final policy = assessTransitionTier(analysis, next, advanced: advanced);
  final beatmatched = policy.tier == TransitionTier.beatmatched;
  final inBpm = orZero(next.bpm);
  final alignedBpm = alignTempoOctave(outBpm, inBpm);
  final far = inBpm > 0 && !stretchReachable(outBpm, inBpm);
  final ratio = inBpm > 0 && !far
      ? _roundRate(1 / _tempoRatio(outBpm, inBpm))
      : 1.0;
  final rate = _incomingRate(ratio);
  final inBeat = alignedBpm > 0 ? 60 / alignedBpm : beat;
  final cue = _incomingStart(next);
  final drop = incomingCuePoint(next, latest: latestIncomingCue(nextLength));
  final handoff = nextLength > 0
      ? math.max(cue, math.min(drop, nextLength - _minClearance))
      : drop;
  if (nextLength > 0 && cue + overlap * rate + _minClearance > nextLength) {
    return null;
  }
  return TransitionPlan(
    markerVisible: true,
    transitionStart: start,
    transitionEnd: end,
    fadeSeconds: overlap,
    handoffDuration: overlap,
    incomingCueTime: cue,
    incomingHandoffTime: handoff,
    incomingPlaybackRate: rate,
    pickupSeconds: cue,
    transitionBeats: beats,
    bassSwap:
        beatmatched ||
        analysis.lowEnergyCurve.isNotEmpty ||
        next.lowEnergyCurve.isNotEmpty,
    bassSwapFraction: _bassSwapFraction(
      analysis: analysis,
      next: next,
      start: start,
      cue: cue,
      outgoingBeat: beat,
      incomingBeat: inBeat,
      overlap: overlap,
      beats: beats,
    ),
    filterSweep: beatmatched ? 0 : filterSweep,
    vocalOverlap: _plannedVocalOverlap(analysis, next, start, end, cue, rate),
    outgoingBpm: outBpm,
    incomingBpm: alignedBpm,
    beatSeconds: gridTrusted ? beat : 0,
    transitionStyle: beatmatched
        ? TransitionStyle.djBlend
        : TransitionStyle.djFilter,
    reason: 'smart-phrase-bars',
  );
}

class _PairTraits {
  _PairTraits(TrackAnalysis analysis, TrackAnalysis next) {
    final outgoing = orZero(analysis.bpm), incoming = orZero(next.bpm);
    tempoFar =
        outgoing > 0 && incoming > 0 && !stretchReachable(outgoing, incoming);
    final left = _trustedKey(analysis), right = _trustedKey(next);
    keyClash =
        left.isNotEmpty &&
        right.isNotEmpty &&
        !_harmonicallyCompatible(left, right);
    vocalConflict =
        analysis.vocalProbability >= _vocalConflictProbability &&
        next.vocalProbability >= _vocalConflictProbability;
    phaseLockable =
        outgoing > 0 &&
        incoming > 0 &&
        !tempoFar &&
        orZero(analysis.beatConfidence) >= minBeatmatchConfidence &&
        orZero(next.beatConfidence) >= minBeatmatchConfidence;
    outgoingGrid =
        outgoing > 0 && orZero(analysis.beatConfidence) >= minDjConfidence;
    outgoingMeanEnergy = _meanEnergy(analysis.energyCurve);
    incomingMeanEnergy = _meanEnergy(next.energyCurve);
  }
  late final bool tempoFar,
      keyClash,
      vocalConflict,
      phaseLockable,
      outgoingGrid;
  late final double outgoingMeanEnergy, incomingMeanEnergy;
}

double _meanEnergy(List<EnergySample> curve) {
  final values = curve.map((e) => e.energy).where((e) => e.isFinite && e >= 0);
  return values.isEmpty ? 0 : values.reduce((a, b) => a + b) / values.length;
}

double _energyScale(
  TrackAnalysis analysis,
  TrackAnalysis next,
  _PairTraits traits,
  double exit,
  double beat,
) {
  if (beat <= 0 ||
      traits.outgoingMeanEnergy <= 0 ||
      traits.incomingMeanEnergy <= 0) {
    return 1;
  }
  final window = _energyWindowBeats * beat, entry = _incomingStart(next);
  final out = _averageEnergy(analysis.energyCurve, exit - window, exit);
  final into = _averageEnergy(next.energyCurve, entry, entry + window);
  return out != null &&
          into != null &&
          out / traits.outgoingMeanEnergy < .7 &&
          into / traits.incomingMeanEnergy < .7
      ? 2
      : 1;
}

double _wholeBars(double seconds, double beat, double maximum) {
  if (beat <= 0) return seconds;
  final bar = 4 * beat;
  var bars = (seconds / bar).round();
  if (bars * bar > maximum + 1e-6) bars = (maximum / bar + 1e-6).floor();
  return bars >= 1 ? bars * bar : seconds;
}

({double overlap, int beats, double rate}) _adaptiveOverlap(
  TrackAnalysis analysis,
  TrackAnalysis next,
  _PairTraits? traits,
  double energy,
  FilterTransitionVariant variant,
) {
  final outgoing = orZero(analysis.bpm), incoming = orZero(next.bpm);
  if (outgoing <= 0 || incoming <= 0) {
    return (overlap: 8.0, beats: 0, rate: 1.0);
  }
  final ratio = _tempoRatio(outgoing, incoming);
  final int beats;
  if (traits == null) {
    final distance = _keyDistance(_trustedKey(analysis), _trustedKey(next));
    final vocalConflict =
        analysis.vocalProbability >= _vocalConflictProbability &&
        next.vocalProbability >= _vocalConflictProbability;
    beats =
        !vocalConflict &&
            ((1 - ratio).abs() > .07 || (distance != null && distance > 4))
        ? 16
        : 8;
  } else {
    final base = traits.tempoFar
        ? (variant == FilterTransitionVariant.sweep ? 12 : 8)
        : traits.keyClash || traits.vocalConflict
        ? 8
        : 16;
    beats = (base * energy).round().clamp(4, 32);
  }
  final beat = 60 / outgoing, minimum = outgoing >= 140 ? 6.0 : 4.0;
  final maximum = traits != null && energy > 1 ? 16.0 : 12.0;
  final overlap = clamp(beats * beat, minimum, maximum);
  return (
    overlap: traits != null ? _wholeBars(overlap, beat, maximum) : overlap,
    beats: beats,
    rate: traits != null
        ? (traits.tempoFar ? 1.0 : _roundRate(1 / ratio))
        : 1 / ratio >= minIncomingRate && 1 / ratio <= maxIncomingRate
        ? _roundRate(1 / ratio)
        : 1.0,
  );
}

const echoRepeats = 5;
const filterEchoAt = .5;
const maxEchoSeconds = 1.0;
double echoTailSeconds(double seconds) => seconds * echoRepeats;

TransitionPlan _standardTransition(
  double length,
  double time,
  double seconds,
  double minimum, {
  String reason = 'standard',
}) {
  final fade = clamp(seconds, minimum, 12);
  final start = math.max(0.0, length - fade);
  final started = time >= start;
  return TransitionPlan(
    shouldStart: started,
    markerVisible: true,
    transitionStart: start,
    transitionEnd: length,
    fadeSeconds: fade,
    reason: started ? reason : 'before-$reason-window',
  );
}

bool _analysisReady(TrackAnalysis analysis, TransitionTrackInfo? track) =>
    analysis.status.trim().isEmpty ||
    (analysis.status == TrackAnalysis.statusReady &&
        (analysis.trackId.trim().isEmpty ||
            track == null ||
            track.id.trim().isEmpty ||
            analysis.trackId == track.id));

TransitionPlan planTransition({
  TrackAnalysis analysis = const TrackAnalysis(),
  TrackAnalysis nextAnalysis = const TrackAnalysis(),
  TransitionTrackInfo? currentTrack,
  TransitionTrackInfo? nextTrack,
  double currentTime = 0,
  double duration = 0,
  double fadeSeconds = 6,
  double minFadeSeconds = 1,
  CrossfadeMode mode = CrossfadeMode.standard,
  bool albumSequential = false,
  bool advanced = false,
  int phraseBars = 0,
  bool mixWindow = false,
}) {
  var plan = _planTransition(
    analysis: analysis,
    next: nextAnalysis,
    current: currentTrack,
    nextTrack: nextTrack,
    currentTime: currentTime,
    duration: duration,
    fade: fadeSeconds,
    minimum: minFadeSeconds,
    mode: mode,
    albumSequential: albumSequential,
    advanced: advanced,
    bars: phraseBars,
  );
  if (!mixWindow || plan.blocked) return plan;
  final length = math.max(orZero(duration), _trackDuration(currentTrack));
  if (length <= 0) return plan;
  final time = math.max(0.0, orZero(currentTime));
  final earliest = math.max(0.0, length - 30);
  if (plan.transitionStart < earliest) {
    final end = math.max(earliest, plan.transitionEnd);
    plan = plan.copyWith(
      shouldStart: time >= earliest,
      transitionStart: earliest,
      transitionEnd: end,
      fadeSeconds: math.max(0.0, end - earliest),
    );
  }
  return plan.incomingCueTime > 30 ? plan.copyWith(incomingCueTime: 30) : plan;
}

TransitionPlan _planTransition({
  required TrackAnalysis analysis,
  required TrackAnalysis next,
  required TransitionTrackInfo? current,
  required TransitionTrackInfo? nextTrack,
  required double currentTime,
  required double duration,
  required double fade,
  required double minimum,
  required CrossfadeMode mode,
  required bool albumSequential,
  required bool advanced,
  required int bars,
}) {
  final length = math.max(orZero(duration), _trackDuration(current));
  final time = math.max(0.0, orZero(currentTime));
  if (length <= 0) return _blocked('no-duration');
  final standardFade = clamp(fade, minimum, 12);
  if (mode != CrossfadeMode.smart) {
    return _standardTransition(length, time, standardFade, minimum);
  }
  if (length < 45) {
    return _blocked('short-duration-guard', start: length, end: length);
  }
  final analyzedEnd = orZero(analysis.contentEndTime) != 0
      ? analysis.contentEndTime
      : length;
  final finalAnchor = analyzedEnd > 0 && analyzedEnd <= length
      ? analyzedEnd
      : length;
  final rankedOuts = rankMixOutCandidates(
    analysis,
    contentEnd: finalAnchor,
    duration: length,
  );
  final best = rankedOuts.firstOrNull;
  final outAnchor = best == null
      ? MixOutAnchor(finalAnchor, 'content_end', 0)
      : MixOutAnchor(best.time, best.type, best.discardedMusicSeconds);
  final interior = outAnchor.time < finalAnchor - 1;
  if (albumSequential && _sameAlbum(current, nextTrack) && !interior) {
    final start = math.max(0.0, length - .45), started = time >= start;
    return TransitionPlan(
      shouldStart: started,
      markerVisible: true,
      transitionStart: start,
      transitionEnd: length,
      fadeSeconds: .12,
      transitionStyle: TransitionStyle.gapless,
      reason: started ? 'same-album-gapless' : 'before-gapless-window',
    );
  }
  if (isSpokenOrLive(current) || isSpokenOrLive(nextTrack)) {
    return _blocked('blocked-speech-or-live');
  }
  if (!_analysisReady(analysis, current) || !_analysisReady(next, nextTrack)) {
    return _standardTransition(
      length,
      time,
      standardFade,
      minimum,
      reason: 'smart-analysis-fallback',
    );
  }
  final preferredAnchor = math.min(length, outAnchor.time);
  final anchor =
      time >= preferredAnchor - .05 && preferredAnchor < finalAnchor - 1
      ? finalAnchor
      : preferredAnchor;
  final policy = assessTransitionTier(analysis, next, advanced: advanced);
  if (policy.tier == TransitionTier.plainCrossfade) {
    final start = math.max(0.0, anchor - standardFade), started = time >= start;
    return TransitionPlan(
      shouldStart: started,
      markerVisible: true,
      transitionStart: start,
      transitionEnd: anchor,
      fadeSeconds: anchor - start,
      incomingCueTime: _incomingStart(next),
      policyReasons: policy.reasons,
      reason: started
          ? 'smart-plain-crossfade'
          : 'before-plain-crossfade-window',
    );
  }
  final nextLength = math.max(orZero(next.duration), _trackDuration(nextTrack));
  if (_unmixablePair(analysis, next)) {
    final start = math.max(0.0, finalAnchor - standardFade),
        cue = _incomingStart(next);
    final started = time >= start;
    return TransitionPlan(
      shouldStart: started,
      markerVisible: true,
      transitionStart: start,
      transitionEnd: finalAnchor,
      fadeSeconds: finalAnchor - start,
      incomingCueTime: cue,
      incomingHandoffTime: cue,
      vocalOverlap: _plannedVocalOverlap(
        analysis,
        next,
        start,
        finalAnchor,
        cue,
        1,
      ),
      policyReasons: [...policy.reasons, 'unmixable-pair'],
      reason: started
          ? 'smart-unmixable-crossfade'
          : 'before-unmixable-crossfade',
    );
  }
  if (bars == phraseBarsShort || bars == phraseBarsLong) {
    final plan = _phraseBarPlan(
      analysis,
      next,
      length,
      nextLength,
      bars,
      anchor,
      advanced,
    );
    if (plan != null && time < plan.transitionEnd) {
      final started = time >= plan.transitionStart;
      return _capIncomingSkip(
        plan.copyWith(
          shouldStart: started,
          policyReasons: policy.reasons,
          reason: started
              ? 'smart-phrase-$bars-bar'
              : 'before-phrase-$bars-bar',
        ),
        next,
        nextLength,
      );
    }
  }
  final phrase = _phraseSwitch(analysis, next, length, nextLength, advanced);
  if (phrase != null && time < phrase.transitionEnd) {
    final started = time >= phrase.transitionStart;
    return _capIncomingSkip(
      phrase.copyWith(
        shouldStart: started,
        policyReasons: policy.reasons,
        reason: started ? 'smart-phrase-switch' : 'before-phrase-switch',
      ),
      next,
      nextLength,
    );
  }
  final traits = advanced ? _PairTraits(analysis, next) : null;
  final primary = _mixPlan(
    analysis,
    next,
    anchor,
    outAnchor.type,
    nextLength,
    traits,
  );
  var plan = primary;
  if (traits != null &&
      primary.vocalOverlap > .3 &&
      time < primary.transitionStart) {
    final alternatives =
        rankedOuts
            .where((c) => (c.time - anchor).abs() > 1 && c.time <= length)
            .take(3)
            .map(
              (c) =>
                  _mixPlan(analysis, next, c.time, c.type, nextLength, traits),
            )
            .where((p) => time < p.transitionStart)
            .toList()
          ..sort((a, b) => a.vocalOverlap.compareTo(b.vocalOverlap));
    final alternative = alternatives.firstOrNull;
    if (alternative != null &&
        alternative.vocalOverlap <= primary.vocalOverlap * .5) {
      plan = alternative;
    }
  }
  final started = time >= plan.transitionStart;
  return _capIncomingSkip(
    plan.copyWith(
      shouldStart: started,
      policyReasons: policy.reasons,
      reason: started ? plan.reason : 'before-${plan.reason}',
    ),
    next,
    nextLength,
  );
}

TransitionPlan _mixPlan(
  TrackAnalysis analysis,
  TrackAnalysis next,
  double anchor,
  String outType,
  double nextLength,
  _PairTraits? traits,
) {
  final outgoingBpm = orZero(analysis.bpm), incomingBpm = orZero(next.bpm);
  final gridBeat = traits?.outgoingGrid == true ? 60 / outgoingBpm : 0.0;
  final variant = traits != null
      ? _fallbackVariant(
          analysis,
          next,
          anchor,
          outgoingBpm > 0 ? 60 / outgoingBpm : 0,
        )
      : FilterTransitionVariant.sweep;
  final energy = traits != null
      ? _energyScale(analysis, next, traits, anchor, gridBeat)
      : 1.0;
  final adaptive = _adaptiveOverlap(analysis, next, traits, energy, variant);
  final handoffBpm = outgoingBpm > 0 ? outgoingBpm : incomingBpm;
  final sameBeat =
      (traits == null || adaptive.rate >= 1) &&
      ((outgoingBpm > 0 &&
              incomingBpm > 0 &&
              (1 - _tempoRatio(outgoingBpm, incomingBpm)).abs() <= .05 &&
              (orZero(analysis.beatConfidence) >= .2 ||
                  orZero(next.beatConfidence) >= .2)) ||
          traits?.phaseLockable == true);
  final arrangement = sameBeat && outType == 'content_end'
      ? math.min(
          arrangementOverlapBeats * 60 / outgoingBpm,
          maxDiscardedMusicSeconds,
        )
      : 0.0;
  final arrangementEnd = math.max(0.0, anchor - arrangement);
  final mixEnd = gridBeat > 0
      ? _onDownbeatBefore(analysis, arrangementEnd, 4 * gridBeat)
      : arrangementEnd;
  final maxBeats = traits != null ? 32 : 16;
  final fallbackMax = variant == FilterTransitionVariant.sweep ? 8.0 : 7.0;
  final maxSeconds = traits != null && !sameBeat
      ? fallbackMax
      : traits != null && energy > 1
      ? 16.0
      : 12.0;
  final maximum = [
    handoffBpm > 0 ? maxBeats * 60 / handoffBpm : maxSeconds,
    maxSeconds,
    mixEnd * .4,
    nextLength > 0 ? nextLength * .4 : maxSeconds,
  ].reduce(math.min);
  final handoffBeats = sameBeat ? 8 : 4;
  final beat = handoffBpm > 0 ? 60 / handoffBpm : .5;
  final handoffSeconds = handoffBpm > 0
      ? clamp(handoffBeats * 60 / handoffBpm, 2, sameBeat ? 6 : 5)
      : 4.0;
  final analyzedPickup = next.audibleStartTime ?? next.pickupTime;
  final pickup =
      analyzedPickup != null && analyzedPickup.isFinite && analyzedPickup >= 0
      ? analyzedPickup
      : 0.0;
  final drop = incomingCuePoint(next, latest: latestIncomingCue(nextLength));
  final alignedBpm = alignTempoOctave(outgoingBpm, incomingBpm);
  final requestedHandoff = sameBeat && alignedBpm > 0
      ? drop + arrangementOverlapBeats * 60 / alignedBpm
      : drop;
  final maxHandoff = nextLength - _minClearance;
  final handoff = maxHandoff >= drop
      ? math.min(requestedHandoff, maxHandoff)
      : drop;
  final double cue, start;
  if (sameBeat && beat > 0) {
    final introDrop = handoff / math.max(.8, adaptive.rate);
    final clamped = clamp(introDrop, math.min(12.0, maximum), maximum);
    final overlap = traits != null
        ? _wholeBars(clamped, beat, maximum)
        : clamped;
    start = _alignedStart(
      analysis,
      math.max(0.0, mixEnd - overlap),
      mixEnd - .05,
      preferEarlier: true,
      minimum: math.max(0.0, mixEnd - maximum),
    );
    cue = math.max(0.0, handoff - (mixEnd - start) * adaptive.rate);
  } else {
    final clamped = clamp(
      adaptive.overlap,
      math.min(handoffSeconds, maximum),
      maximum,
    );
    final overlap = traits != null
        ? _wholeBars(clamped, beat, maximum)
        : clamped;
    start = _alignedStart(
      analysis,
      math.max(0.0, mixEnd - overlap),
      mixEnd - .05,
      preferEarlier: false,
      minimum: math.max(0.0, mixEnd - maximum),
    );
    cue = _incomingStart(next);
  }
  final overlap = mixEnd - start;
  final measuredVocal = _plannedVocalOverlap(
    analysis,
    next,
    start,
    mixEnd,
    cue,
    adaptive.rate,
  );
  final vocal = math.max(
    measuredVocal,
    traits?.vocalConflict == true &&
            (analysis.vocalActivityMask.isEmpty ||
                next.vocalActivityMask.isEmpty)
        ? .55
        : 0.0,
  );
  final vocalSafe = !sameBeat && vocal >= .18;
  return TransitionPlan(
    markerVisible: true,
    transitionStart: start,
    transitionEnd: mixEnd,
    fadeSeconds: overlap,
    handoffDuration: overlap,
    incomingCueTime: cue,
    incomingHandoffTime: handoff,
    incomingPlaybackRate: _incomingRate(adaptive.rate),
    pickupSeconds: pickup,
    transitionBeats: adaptive.beats,
    bassSwap:
        sameBeat ||
        analysis.lowEnergyCurve.isNotEmpty ||
        next.lowEnergyCurve.isNotEmpty,
    transitionStyle: sameBeat
        ? TransitionStyle.djBlend
        : TransitionStyle.djFilter,
    filterSweep: sameBeat ? 0 : filterSweep,
    filterVariant: sameBeat || vocalSafe
        ? FilterTransitionVariant.sweep
        : variant,
    vocalOverlap: vocal,
    beatSeconds: gridBeat,
    reason: 'smart-duration',
  );
}
