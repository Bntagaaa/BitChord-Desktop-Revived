import 'dart:math' as math;

import 'transition_planner.dart';

/// The source clock of the next PCM frame to write, not the device's heard
/// clock, determines the seam. A decoder may have many frames queued ahead.
int? transitionPrefixFrames(
  TransitionPlan plan,
  int renderPositionUs,
  int blockFrames,
  int sampleRate, {
  double sourceRate = 1,
}) {
  if (plan.blocked ||
      plan.fadeSeconds <= 0 ||
      !plan.transitionStart.isFinite ||
      sampleRate <= 0 ||
      blockFrames <= 0 ||
      !sourceRate.isFinite ||
      sourceRate <= 0) {
    return null;
  }
  final until = math.max(
    0.0,
    plan.transitionStart * 1000000 - renderPositionUs,
  );
  final frames = (until * sampleRate / (1000000 * sourceRate)).ceil();
  return frames < blockFrames ? frames : null;
}

/// A late decoder cannot claim a beat lock. Bound the fallback at the real
/// exit, leave the audible deck's tempo unchanged, and report the downgrade.
TransitionPlan lateTransitionFallback(
  TransitionPlan plan,
  double renderSeconds,
) {
  if (!renderSeconds.isFinite || renderSeconds <= plan.transitionStart + .002) {
    return plan;
  }
  final remaining = math.max(0.0, plan.transitionEnd - renderSeconds);
  final fade = math.min(plan.fadeSeconds, remaining);
  return plan.copyWith(
    transitionStyle: TransitionStyle.equalPower,
    incomingPlaybackRate: 1,
    outgoingPlaybackRate: 1,
    phaseLock: false,
    echoSeconds: 0,
    bassSwap: false,
    filterSweep: 0,
    transitionStart: renderSeconds,
    fadeSeconds: fade,
    incomingHandoffTime: plan.incomingCueTime + fade,
    policyReasons: [...plan.policyReasons, 'late-decoder-crossfade'],
  );
}

double overlapHeadroom(double outgoingGain, double incomingGain) =>
    1 / math.sqrt(math.max(1.0, outgoingGain + incomingGain));
