"""Hosted native output evidence: real OS sink, explicit physical-test limits."""
import json
import math
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import wave

ROOT = Path(__file__).resolve().parents[1]


def main():
    report = {'physical_audibility': 'BLOCKED: hosted runner has no listener',
              'physical_hotplug': 'BLOCKED: no removable endpoint under test',
              'loopback_latency': 'BLOCKED: no calibrated physical loopback',
              'platform': os.name}
    native = ROOT / 'build/native'
    probe = native / ('Release/bitchord_audio_probe.exe' if os.name == 'nt' else 'bitchord_audio_probe')
    with tempfile.TemporaryDirectory(prefix='bitchord-pcm-') as tmp:
        audio = Path(tmp) / 'tone.wav'
        with wave.open(str(audio), 'wb') as out:
            out.setparams((2, 2, 48000, 0, 'NONE', 'not compressed'))
            out.writeframes(b''.join(struct.pack('<hh', int(1000 * math.sin(i * math.tau * 440 / 48000)),
                                                int(1000 * math.sin(i * math.tau * 440 / 48000))) for i in range(24000)))
        endpoint = []
        server = None
        try:
            if os.name != 'nt':
                # An isolated real libpulse server and its software sink. No user
                # default route changes. This is not physical-output acceptance.
                socket = Path(tmp) / 'pulse'
                server = subprocess.Popen(['pulseaudio', '--daemonize=no', '--exit-idle-time=-1',
                    '--use-pid-file=no', '--disallow-exit', '-n',
                    '-L', f'module-native-protocol-unix socket={socket} auth-anonymous=1',
                    '-L', 'module-null-sink sink_name=bitchord_phase3 rate=48000 channels=2'],
                    stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
                os.environ['PULSE_SERVER'] = f'unix:{socket}'
                import time
                for _ in range(100):
                    if socket.exists():
                        break
                    if server.poll() is not None:
                        raise RuntimeError(server.stderr.read().decode())
                    time.sleep(.02)
                endpoint = ['pulse:bitchord_phase3']
                report['endpoint'] = 'isolated Pulse software sink (no physical output)'
            result = subprocess.run([str(probe), str(audio), *endpoint], capture_output=True, text=True, timeout=35)
            report['probe'] = json.loads(result.stdout.strip().splitlines()[-1])
            report['probe_exit'] = result.returncode
            if os.name != 'nt':
                if result.returncode != 0 or report['probe']['submitted_frames'] != 24000 or report['probe']['played_frames'] != 24000:
                    raise RuntimeError(f'Pulse real PCM output failed: {report}')
            elif result.returncode != 0:
                if report['probe']['error'] != 101:
                    raise RuntimeError('Windows decode/lifecycle failure, not missing device')
                report['wasapi_output'] = 'BLOCKED: Windows hosted audio endpoint unavailable'
            else:
                report['wasapi_output'] = 'Endpoint accepted/presented PCM; audibility unverified'
        finally:
            if server:
                server.terminate()
                server.communicate(timeout=10)
    (ROOT / 'verification').mkdir(exist_ok=True)
    (ROOT / 'verification/phase3-platform.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
