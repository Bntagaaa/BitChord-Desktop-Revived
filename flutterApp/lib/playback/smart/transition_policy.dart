/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard).
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

// Dart port of 1.9.2's confidence-aware transition policy.
import 'dart:math' as math;

import 'track_analysis.dart';

const minBeatmatchConfidence = .55;
const minDjConfidence = .2;
const minBpm = 40.0;
const maxBpm = 220.0;
const minIncomingRate = .9;
const maxIncomingRate = 1.25;
const vocalActiveThreshold = .25;
const maxDiscardedMusicSeconds = 12.0;
const vocalClashTolerance = .05;

double orZero(double? value) => value != null && value.isFinite ? value : 0;
double clamp(double value, double low, double high) =>
    value.isFinite ? math.max(low, math.min(high, value)) : low;

double alignTempoOctave(double outgoing, double incoming) {
  if (!outgoing.isFinite ||
      !incoming.isFinite ||
      outgoing <= 0 ||
      incoming <= 0) {
    return incoming;
  }
  var aligned = incoming;
  while (aligned / outgoing > 1.5) {
    aligned /= 2;
  }
  while (aligned / outgoing < .67) {
    aligned *= 2;
  }
  return aligned;
}

double incomingRateBetween(double outgoing, double incoming) =>
    outgoing <= 0 || incoming <= 0
    ? double.nan
    : outgoing / alignTempoOctave(outgoing, incoming);

bool stretchReachable(double outgoing, double incoming) {
  final rate = incomingRateBetween(outgoing, incoming);
  return rate.isFinite &&
      rate >= minIncomingRate - 1e-9 &&
      rate <= maxIncomingRate + 1e-9;
}

double heuristicVocalOnModelScale(double value) {
  if (!value.isFinite) return value;
  final v = value.clamp(0.0, 1.0);
  return v < .6
      ? v / .6 * vocalActiveThreshold
      : vocalActiveThreshold + (v - .6) / .4 * (1 - vocalActiveThreshold);
}

double? vocalActivityBetween(TrackAnalysis analysis, double start, double end) {
  final mask = analysis.vocalActivityMask;
  final curve = analysis.energyCurve;
  if (mask.isEmpty || mask.length != curve.length || end <= start) return null;
  var sum = 0.0, count = 0;
  for (var i = 0; i < mask.length; i++) {
    final time = curve[i].time;
    if (!time.isFinite || time < start || time > end || !mask[i].isFinite) {
      continue;
    }
    sum += mask[i];
    count++;
  }
  return count > 0 ? sum / count : null;
}

bool isVocalClash(double? outgoing, double? incoming) =>
    outgoing != null &&
    incoming != null &&
    outgoing >= vocalActiveThreshold &&
    incoming >= vocalActiveThreshold;

double vocalOverlapAmount(double? outgoing, double? incoming) {
  if (outgoing == null || incoming == null) return 0;
  final both = math.min(outgoing, incoming);
  return both <= vocalActiveThreshold
      ? 0
      : ((both - vocalActiveThreshold) / (1 - vocalActiveThreshold)).clamp(
          0.0,
          1.0,
        );
}

double? simultaneousVocalFraction({
  required TrackAnalysis outgoing,
  required TrackAnalysis incoming,
  required double outStart,
  required double outEnd,
  required double inStart,
  required double rate,
}) {
  final outMask = outgoing.vocalActivityMask,
      inMask = incoming.vocalActivityMask;
  final outCurve = outgoing.energyCurve, inCurve = incoming.energyCurve;
  if (outMask.isEmpty ||
      outMask.length != outCurve.length ||
      inMask.isEmpty ||
      inMask.length != inCurve.length ||
      outEnd <= outStart) {
    return null;
  }
  final step = rate.isFinite && rate > 0 ? rate : 1.0;
  var inIndex = 0, both = 0, total = 0;
  for (var i = 0; i < outMask.length; i++) {
    final time = outCurve[i].time;
    if (!time.isFinite || time < outStart) continue;
    if (time > outEnd) break;
    total++;
    if (outMask[i] < vocalActiveThreshold) continue;
    final target = inStart + (time - outStart) * step;
    while (inIndex + 1 < inCurve.length &&
        inCurve[inIndex + 1].time <= target) {
      inIndex++;
    }
    if (inMask[inIndex] >= vocalActiveThreshold) both++;
  }
  return total > 0 ? both / total : null;
}

double? audibleSecondsBetween(
  TrackAnalysis analysis,
  double start,
  double end,
) {
  final curve = analysis.energyCurve;
  if (curve.length < 2 || end <= start) return null;
  final energies =
      curve.map((p) => p.energy).where((e) => e.isFinite && e >= 0).toList()
        ..sort();
  if (energies.isEmpty) return null;
  final reference = orZero(energies[((energies.length - 1) * .85).floor()]);
  if (reference <= 0) return 0;
  final first = curve.first.time, last = curve.last.time;
  if (!first.isFinite || !last.isFinite || last <= first) return null;
  final sampleSeconds = (last - first) / (curve.length - 1);
  var audible = 0.0;
  for (final point in curve) {
    if (!point.time.isFinite || point.time < start || point.time > end) {
      continue;
    }
    if (point.energy >= reference * .1) audible += sampleSeconds;
  }
  return audible;
}

double audibleStartOf(TrackAnalysis analysis) {
  final candidates = [
    analysis.audibleStartTime,
    analysis.pickupTime,
    if (analysis.firstBeat.isFinite && analysis.firstBeat > 0)
      analysis.firstBeat,
  ].whereType<double>().where((v) => v.isFinite && v >= 0).toList();
  return candidates.isEmpty ? 0 : candidates.reduce(math.min);
}

double? nearestValue(List<double> values, double target, double tolerance) {
  final candidates =
      values
          .where((v) => v.isFinite && (v - target).abs() <= tolerance)
          .toList()
        ..sort((a, b) => (a - target).abs().compareTo((b - target).abs()));
  return candidates.firstOrNull;
}

List<RankedMixCandidate> rankMixInCandidates(TrackAnalysis analysis) {
  const typeWeight = {
    'main_drop': .5,
    'intro_drop': .4,
    'pickup': .15,
    'phrase': .1,
  };
  final beat = orZero(analysis.beatInterval) > 0
      ? analysis.beatInterval
      : orZero(analysis.bpm) > 0
      ? 60 / analysis.bpm
      : .5;
  final audibleStart = audibleStartOf(analysis);
  final ranked =
      analysis.mixInCandidates.where((c) => c.time.isFinite && c.time >= 0).map(
        (candidate) {
          var rank =
              orZero(candidate.score) + (typeWeight[candidate.type] ?? 0);
          if (nearestValue(analysis.downbeats, candidate.time, beat / 2) !=
              null) {
            rank += .1;
          }
          if (candidate.time - audibleStart < beat * 4) rank -= .2;
          final vocal = vocalActivityBetween(
            analysis,
            math.max(audibleStart, candidate.time - beat * 16),
            candidate.time,
          );
          if (vocal != null) rank += (.5 - vocal) * .4;
          return RankedMixCandidate(
            time: candidate.time,
            score: orZero(candidate.score),
            type: candidate.type,
            rankScore: rank,
          );
        },
      ).toList()..sort((a, b) => b.rankScore.compareTo(a.rankScore));
  return ranked;
}

double _contentEnd(TrackAnalysis analysis, double end, double duration) =>
    orZero(end) != 0
    ? orZero(end)
    : orZero(analysis.contentEndTime) != 0
    ? orZero(analysis.contentEndTime)
    : orZero(duration) != 0
    ? orZero(duration)
    : orZero(analysis.duration);

List<MixCandidate> _mixOutCandidates(
  TrackAnalysis analysis,
  double contentEnd,
) {
  final supplied = analysis.mixOutCandidates
      .where((c) => c.time.isFinite && c.time > 0)
      .toList();
  final candidates = supplied
      .map((c) => MixCandidate(c.time, orZero(c.score), c.type))
      .toList();
  if (supplied.isEmpty) {
    final mixOut = orZero(analysis.mixOutTime),
        outro = orZero(analysis.outroStartTime);
    if (mixOut > 0 && mixOut < contentEnd - 1) {
      candidates.add(MixCandidate(mixOut, .95, 'energy_cliff'));
    }
    if (outro > 0 && outro < contentEnd - 1) {
      candidates.add(MixCandidate(outro, .9, 'outro_start'));
    }
  }
  if (!candidates.any((c) => (c.time - contentEnd).abs() < .05)) {
    candidates.add(MixCandidate(contentEnd, .75, 'content_end'));
  }
  return candidates;
}

List<RankedMixCandidate> rankMixOutCandidates(
  TrackAnalysis analysis, {
  double contentEnd = 0,
  double duration = 0,
}) {
  final end = _contentEnd(analysis, contentEnd, duration);
  if (end <= 0) return [];
  const typeScore = {
    'energy_cliff': .95,
    'interior_mix_out': .95,
    'outro_start': .9,
    'content_end': .75,
  };
  final ranked = _mixOutCandidates(analysis, end)
      .map((candidate) {
        final measured = audibleSecondsBetween(analysis, candidate.time, end);
        return RankedMixCandidate(
          time: candidate.time,
          score: candidate.score,
          type: candidate.type,
          rankScore: candidate.score + (typeScore[candidate.type] ?? 0),
          discardedMusicSeconds:
              measured ?? math.max(0.0, end - candidate.time),
          measured: measured != null,
        );
      })
      .where((c) => c.discardedMusicSeconds <= maxDiscardedMusicSeconds)
      .toList();
  ranked.sort((a, b) {
    final rank = b.rankScore.compareTo(a.rankScore);
    return rank == 0 ? b.time.compareTo(a.time) : rank;
  });
  return ranked;
}

MixOutAnchor resolveMixOutAnchor(
  TrackAnalysis analysis, {
  double contentEnd = 0,
  double duration = 0,
}) {
  final end = _contentEnd(analysis, contentEnd, duration);
  final best = rankMixOutCandidates(
    analysis,
    contentEnd: end,
    duration: duration,
  ).firstOrNull;
  return MixOutAnchor(
    best?.time ?? end,
    best?.type ?? 'content_end',
    best?.discardedMusicSeconds ?? 0,
  );
}

TransitionPolicyVerdict assessTransitionTier(
  TrackAnalysis analysis,
  TrackAnalysis nextAnalysis, {
  bool advanced = false,
}) {
  final outgoing = orZero(analysis.bpm), incoming = orZero(nextAnalysis.bpm);
  final outConfidence = orZero(analysis.beatConfidence),
      inConfidence = orZero(nextAnalysis.beatConfidence);
  final floor = math.min(outConfidence, inConfidence);
  final reasons = <String>[];
  if (outgoing < minBpm || outgoing > maxBpm) reasons.add('outgoing-tempo');
  if (incoming < minBpm || incoming > maxBpm) reasons.add('incoming-tempo');
  if (reasons.isNotEmpty) {
    return TransitionPolicyVerdict(
      TransitionTier.plainCrossfade,
      reasons,
      floor,
    );
  }
  if (outConfidence < minDjConfidence && inConfidence < minDjConfidence) {
    return TransitionPolicyVerdict(TransitionTier.plainCrossfade, [
      'beat-confidence',
    ], floor);
  }
  if (!stretchReachable(outgoing, incoming)) reasons.add('tempo-distance');
  if (outConfidence < minBeatmatchConfidence ||
      inConfidence < minBeatmatchConfidence) {
    reasons.add('beat-confidence');
  }
  return TransitionPolicyVerdict(
    reasons.isEmpty ? TransitionTier.beatmatched : TransitionTier.djAssisted,
    reasons,
    floor,
  );
}
