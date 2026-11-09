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

// Dart port of the existing 1.9.2 analysis contract.
import 'dart:math' as math;

class TrackAnalysis {
  const TrackAnalysis({
    this.status = '',
    this.trackId = '',
    this.duration = 0,
    this.bpm = 0,
    this.beatInterval = 0,
    this.beatConfidence = 0,
    this.downbeats = const [],
    this.phraseBoundaries = const [],
    this.firstBeat = 0,
    this.key = '',
    this.keyConfidence = 0,
    this.audibleStartTime,
    this.pickupTime,
    this.introEndTime = 0,
    this.contentEndTime = 0,
    this.outroStartTime = 0,
    this.mixInTime = 0,
    this.mixOutTime = 0,
    this.mixInCandidates = const [],
    this.mixOutCandidates = const [],
    this.energyCurve = const [],
    this.lowEnergyCurve = const [],
    this.vocalActivityMask = const [],
    this.vocalProbability = 0,
    this.headBeats,
    this.tailBeats,
    this.vocalWindows = const [],
    this.levelWindows = const [],
  });
  final String status;
  final String trackId;
  final double duration;
  final double bpm;
  final double beatInterval;
  final double beatConfidence;
  final List<double> downbeats;
  final List<double> phraseBoundaries;
  final double firstBeat;
  final String key;
  final double keyConfidence;
  final double? audibleStartTime;
  final double? pickupTime;
  final double introEndTime;
  final double contentEndTime;
  final double outroStartTime;
  final double mixInTime;
  final double mixOutTime;
  final List<MixCandidate> mixInCandidates;
  final List<MixCandidate> mixOutCandidates;
  final List<EnergySample> energyCurve;
  final List<EnergySample> lowEnergyCurve;
  final List<double> vocalActivityMask;
  final double vocalProbability;
  final BeatSide? headBeats;
  final BeatSide? tailBeats;
  final List<CurveWindow> vocalWindows;
  final List<CurveWindow> levelWindows;
  static const statusReady = 'ready';
  bool get isUsable => status == statusReady && bpm > 0;
  TrackAnalysis copyWith({
    String? status,
    String? trackId,
    double? duration,
    double? bpm,
    double? beatInterval,
    double? beatConfidence,
    List<double>? downbeats,
    List<double>? phraseBoundaries,
    double? firstBeat,
    String? key,
    double? keyConfidence,
    double? audibleStartTime,
    double? pickupTime,
    double? introEndTime,
    double? contentEndTime,
    double? outroStartTime,
    double? mixInTime,
    double? mixOutTime,
    List<MixCandidate>? mixInCandidates,
    List<MixCandidate>? mixOutCandidates,
    List<EnergySample>? energyCurve,
    List<EnergySample>? lowEnergyCurve,
    List<double>? vocalActivityMask,
    double? vocalProbability,
    BeatSide? headBeats,
    BeatSide? tailBeats,
    List<CurveWindow>? vocalWindows,
    List<CurveWindow>? levelWindows,
  }) => TrackAnalysis(
    status: status ?? this.status,
    trackId: trackId ?? this.trackId,
    duration: duration ?? this.duration,
    bpm: bpm ?? this.bpm,
    beatInterval: beatInterval ?? this.beatInterval,
    beatConfidence: beatConfidence ?? this.beatConfidence,
    downbeats: downbeats ?? this.downbeats,
    phraseBoundaries: phraseBoundaries ?? this.phraseBoundaries,
    firstBeat: firstBeat ?? this.firstBeat,
    key: key ?? this.key,
    keyConfidence: keyConfidence ?? this.keyConfidence,
    audibleStartTime: audibleStartTime ?? this.audibleStartTime,
    pickupTime: pickupTime ?? this.pickupTime,
    introEndTime: introEndTime ?? this.introEndTime,
    contentEndTime: contentEndTime ?? this.contentEndTime,
    outroStartTime: outroStartTime ?? this.outroStartTime,
    mixInTime: mixInTime ?? this.mixInTime,
    mixOutTime: mixOutTime ?? this.mixOutTime,
    mixInCandidates: mixInCandidates ?? this.mixInCandidates,
    mixOutCandidates: mixOutCandidates ?? this.mixOutCandidates,
    energyCurve: energyCurve ?? this.energyCurve,
    lowEnergyCurve: lowEnergyCurve ?? this.lowEnergyCurve,
    vocalActivityMask: vocalActivityMask ?? this.vocalActivityMask,
    vocalProbability: vocalProbability ?? this.vocalProbability,
    headBeats: headBeats ?? this.headBeats,
    tailBeats: tailBeats ?? this.tailBeats,
    vocalWindows: vocalWindows ?? this.vocalWindows,
    levelWindows: levelWindows ?? this.levelWindows,
  );
}

class EnergySample {
  const EnergySample(this.time, this.energy);
  final double time;
  final double energy;
}

class MixCandidate {
  const MixCandidate(this.time, this.score, this.type);
  final double time;
  final double score;
  final String type;
}

class RankedMixCandidate {
  const RankedMixCandidate({
    required this.time,
    required this.score,
    required this.type,
    required this.rankScore,
    this.discardedMusicSeconds = 0,
    this.measured = true,
  });
  final double time, score, rankScore, discardedMusicSeconds;
  final String type;
  final bool measured;
}

class MixOutAnchor {
  const MixOutAnchor(this.time, this.type, this.discardedMusicSeconds);
  final double time, discardedMusicSeconds;
  final String type;
}

enum TransitionTier { beatmatched, djAssisted, plainCrossfade }

class TransitionPolicyVerdict {
  const TransitionPolicyVerdict(this.tier, this.reasons, this.beatConfidence);
  final TransitionTier tier;
  final List<String> reasons;
  final double beatConfidence;
}

class BeatSide {
  const BeatSide({
    required this.beats,
    required this.downbeats,
    required this.bpm,
    required this.confidence,
  });
  final List<double> beats, downbeats;
  final double bpm, confidence;
}

const levelFrameRate = 10.0;

class CurveWindow {
  const CurveWindow({
    required this.startTime,
    required this.frameRate,
    required this.values,
  });
  final double startTime, frameRate;
  final List<double> values;
  double get endTime => startTime + values.length / frameRate;
}

double levelFromRms(double rms) => rms <= .001
    ? 0
    : ((20 * math.log(rms) / math.ln10 + 60) / 60).clamp(0.0, 1.0);
double rmsFromLevel(double level) =>
    level <= 0 ? 0 : math.pow(10, (level * 60 - 60) / 20).toDouble();
