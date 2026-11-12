"""Exact probe outcome tests, including actual >30-second native sink playback."""
import json
import subprocess
import wave
from pathlib import Path


def verify(probe, endpoint, directory, output_available=True):
    report = {}
    def run(args, expected, outcome=None, timeout=15):
        result = subprocess.run([str(probe), *args], capture_output=True, text=True, timeout=timeout)
        assert result.returncode == expected, (result.returncode, result.stdout, result.stderr)
        if outcome:
            data = json.loads(result.stdout.strip().splitlines()[-1])
            assert data['outcome'] == outcome and data['shutdown_ok'], data
            return data
    for args in [[], ['--timeout'], ['--timeout', 'nan', 'unused'],
                 ['--timeout', '-1', 'unused'], ['--timeout', '0', 'unused'],
                 ['--timeout', '1junk', 'unused'], ['--unknown']]:
        run(args, 64)
    report['invalid_arguments'] = 'PASS: 7 exact exit-64 cases'
    report['missing_file'] = run([str(Path(directory) / 'absent.wav'), *endpoint], 2, 'PLAYBACK_FAILURE')
    if not output_available:
        report['long_track'] = 'BLOCKED: no actual OS audio endpoint'
        return report
    track = Path(directory) / 'probe-31-seconds.wav'
    frames = 1500000  # 31.25 seconds, exceeding the old hardcoded 30-second cap.
    with wave.open(str(track), 'wb') as out:
        out.setparams((2, 2, 48000, 0, 'NONE', 'not compressed'))
        block = b'\x00\x01\x00\x01' * 12000
        for _ in range(frames // 12000):
            out.writeframesraw(block)
    report['timeout'] = run(['--timeout', '0.15', str(track), *endpoint], 4, 'TIMED_OUT')
    report['long_track'] = run([str(track), *endpoint], 0, 'EOF', timeout=45)
    assert report['long_track']['submitted_frames'] == frames, report
    assert report['long_track']['played_frames'] == frames, report
    assert report['long_track']['position_us'] >= 31200000, report
    report['long_track']['fixture_frames'] = frames
    report['long_track']['fixture_seconds'] = 31.25
    return report
