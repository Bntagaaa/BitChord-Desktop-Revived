// Arithmetic port of sharedUi/.../player/LyricClock.kt at 348b613a (1.9.2).
import 'dart:math' as math;
import 'lyrics.dart';

class LyricClock {
  LyricClock(int startMs) : displayedMs = startMs.toDouble();
  double displayedMs;
  double _lastNowMs = double.nan, _lastSampledAtMs = double.nan;
  int? _lastReportMs, _lastDiscontinuity;
  double _heldAtMs = double.negativeInfinity;
  bool _jumpPending = false;
  double _anchorAtMs = double.nan, _anchorMs = 0, _rate = 1;
  final List<double> _historyAt = [], _historyMs = [];
  bool _rateSettled = false;
  double _suspectAtMs = double.nan;
  int _suspectMs = 0, _rejections = 0;
  double _glideStartMs = double.nan, _glideOffsetMs = 0;

  void restart() {
    _lastNowMs = double.nan;
    _jumpPending = false;
    _glideStartMs = double.nan;
    _clearSuspect();
  }
  void hold(int positionMs, double nowMs) {
    restart(); displayedMs = positionMs.toDouble(); _heldAtMs = nowMs;
  }
  int frame({required double nowMs, required int reportedMs,
    required double sampledAtMs, required int discontinuity}) {
    final firstOfRun = _lastNowMs.isNaN;
    if (discontinuity != _lastDiscontinuity) {
      if (_lastDiscontinuity != null && !firstOfRun) _jumpPending = true;
      _lastDiscontinuity = discontinuity;
    }
    final isNew = reportedMs != _lastReportMs || (!sampledAtMs.isNaN && sampledAtMs != _lastSampledAtMs);
    _lastReportMs = reportedMs; _lastSampledAtMs = sampledAtMs;
    if (firstOfRun) {
      _lastNowMs = nowMs;
      final takenAt = sampledAtMs.isNaN || sampledAtMs <= _heldAtMs ? nowMs : math.min(sampledAtMs, nowMs);
      _anchor(takenAt, reportedMs); displayedMs = _targetAt(nowMs);
      return displayedMs.toInt();
    }
    final dt = math.max(0.0, nowMs - _lastNowMs);
    _lastNowMs = nowMs; _advance(nowMs, dt);
    if (isNew) {
      final takenAt = sampledAtMs.isNaN ? nowMs : math.min(sampledAtMs, nowMs);
      if (takenAt >= _anchorAtMs || _jumpPending) _take(nowMs, takenAt, reportedMs);
    }
    return displayedMs.toInt();
  }
  void _take(double nowMs, double takenAt, int reportedMs) {
    if (_jumpPending) {
      _jumpPending = false; _clearSuspect(); _anchor(takenAt, reportedMs); _jumpTo(nowMs); return;
    }
    final elapsed = takenAt - _anchorAtMs;
    if ((reportedMs - (_anchorMs + elapsed * _rate)).abs() <= _tolerance(elapsed)) {
      _clearSuspect(); _anchor(takenAt, reportedMs, continues: true); return;
    }
    final sinceSuspect = takenAt - _suspectAtMs;
    final agrees = !_suspectAtMs.isNaN && sinceSuspect >= 0 &&
      (reportedMs - (_suspectMs + sinceSuspect * _rate)).abs() <= _tolerance(sinceSuspect);
    _rejections++;
    if (agrees) {
      final firstAt = _suspectAtMs, first = _suspectMs;
      _clearSuspect(); _anchor(firstAt, first); _anchor(takenAt, reportedMs, continues: true);
    } else if (_rejections > 2) { _clearSuspect(); _anchor(takenAt, reportedMs); }
    else { _suspectAtMs = takenAt; _suspectMs = reportedMs; }
  }
  double _tolerance(double elapsedMs) => 250 + elapsedMs.abs() * (_rateSettled ? .2 : .75);
  void _anchor(double atMs, int positionMs, {bool continues = false}) {
    _anchorAtMs = atMs; _anchorMs = positionMs.toDouble();
    if (!continues) { _historyAt.clear(); _historyMs.clear(); }
    if (_historyAt.length == 16) { _historyAt.removeAt(0); _historyMs.removeAt(0); }
    _historyAt.add(atMs); _historyMs.add(_anchorMs); _learnRate();
  }
  void _learnRate() {
    final newest = _historyAt.length - 1;
    if (newest < 1) return;
    var oldest = 0;
    while (oldest < newest && _historyAt[newest] - _historyAt[oldest] > 4000) { oldest++; }
    final span = _historyAt[newest] - _historyAt[oldest];
    if (span >= 900) {
      _rate = ((_historyMs[newest] - _historyMs[oldest]) / span).clamp(.25, 4.0);
      _rateSettled = true; return;
    }
    final pairSpan = _historyAt[newest] - _historyAt[newest - 1];
    if (!_rateSettled && pairSpan >= 300) {
      final measured = ((_historyMs[newest] - _historyMs[newest - 1]) / pairSpan).clamp(.25, 4.0);
      _rate += (measured - _rate) * .5;
    }
  }
  void _clearSuspect() { _suspectAtMs = double.nan; _rejections = 0; }
  void _advance(double nowMs, double dt) {
    final target = _targetAt(nowMs), step = dt * _rate;
    final error = target - (displayedMs + step);
    if (!_glideStartMs.isNaN) {
      final progress = (nowMs - _glideStartMs) / 260;
      if (progress >= 1) { displayedMs = target; _glideStartMs = double.nan; }
      else { displayedMs = target + _glideOffsetMs * (1 - _smoothstep(progress)); }
    } else if (error <= -3000 || error >= 3000) { displayedMs = target; }
    else if (error >= 300) {
      _startGlide(nowMs - dt, displayedMs - (target - step));
      displayedMs = target + _glideOffsetMs * (1 - _smoothstep(dt / 260));
    } else {
      final closing = error * (dt / (dt + 600));
      displayedMs += step + closing.clamp(-.5 * step, .25 * step);
    }
  }
  void _jumpTo(double nowMs) {
    final offset = displayedMs - _targetAt(nowMs);
    if (offset.abs() > 2000) { displayedMs = _targetAt(nowMs); _glideStartMs = double.nan; }
    else { _startGlide(nowMs, offset); }
  }
  void _startGlide(double startMs, double offsetMs) { _glideStartMs = startMs; _glideOffsetMs = offsetMs; }
  double _targetAt(double atMs) => _anchorMs + (atMs - _anchorAtMs) * _rate;
  static double _smoothstep(double fraction) {
    final t = fraction.clamp(0.0, 1.0); return t * t * (3 - 2 * t);
  }
}

// Original LyricFocus.kt: all unfinished overlapping voices remain active.
List<int> activeLyricRows(List<LyricLine> lines, int positionMs) {
  final latest = lines.lastIndexWhere((line) => line.timeMs <= positionMs);
  if (latest < 0) return [];
  return [for (var i = 0; i <= latest; i++)
    if (i == latest || (!lines[i].isGap && (lines[i].hasKnownEnd || lines[i].background?.hasKnownEnd == true) &&
      lines[i].timeMs <= positionMs && positionMs < lines[i].endMs)) i];
}
