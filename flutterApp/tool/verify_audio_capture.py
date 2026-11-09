"""Verify actual native output from the CI-only 440 Hz integration fixture.

Input is a PulseAudio monitor recording: stereo signed 16-bit little-endian,
48 kHz. No production library data or microphone input is recorded.
"""
import argparse
import array
import json
import math
from pathlib import Path
import sys


def tone_power(samples, frequency):
    coefficient = 2 * math.cos(2 * math.pi * frequency / 48000)
    previous = before = 0.0
    for value in samples:
        current = value + coefficient * previous - before
        before, previous = previous, current
    return previous * previous + before * before - coefficient * previous * before


def verify(path):
    best = []
    best_rms = 0.0
    captured_frames = 0
    with path.open('rb') as stream:
        while raw := stream.read(4096 * 4):
            if len(raw) % 4:
                raise ValueError('Incomplete stereo PCM frame')
            pcm = array.array('h', raw)
            if sys.byteorder != 'little':
                pcm.byteswap()
            left = [sample / 32768 for sample in pcm[::2]]
            captured_frames += len(left)
            if len(left) < 1024:
                continue
            rms = math.sqrt(sum(sample * sample for sample in left) / len(left))
            if rms > best_rms:
                best_rms, best = rms, left
    if best_rms < 0.003:
        raise ValueError(f'No rendered native tone detected (peak window RMS {best_rms:.6f})')
    power = {hz: tone_power(best, hz) for hz in (220, 330, 440, 550, 880)}
    other = max(value for hz, value in power.items() if hz != 440)
    if power[440] < 5 * max(other, 1e-12):
        raise ValueError('Native output is not the integration fixture\'s 440 Hz tone')
    return {
        'captured_frames': captured_frames,
        'peak_window_rms': best_rms,
        'tone_hz': 440,
        'tone_to_comparison_power': power[440] / max(other, 1e-12),
        'output': 'PulseAudio virtual sink monitor; physical audibility unverified',
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('capture', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    report = verify(args.capture)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print('::notice::Native audio emitted verified 440 Hz PCM to the virtual output; '
          f'peak RMS {report["peak_window_rms"]:.5f}')
