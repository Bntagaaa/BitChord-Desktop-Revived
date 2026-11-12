"""Generate original synthetic audio and publisher-tool decode expectations.
No downloaded music, credentials, catalogs or reference app files are used.
"""
from pathlib import Path
import hashlib
import json
import math
import struct
import subprocess
import wave

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'native/audio/test/fixtures'


def main():
    OUT.mkdir(exist_ok=True)
    source = OUT / 'tone.wav'
    with wave.open(str(source), 'wb') as stream:
        stream.setparams((2, 2, 48000, 0, 'NONE', 'not compressed'))
        stream.writeframes(b''.join(struct.pack('<hh',
            int(10000 * math.sin(i * math.tau * 440 / 48000)),
            int(8000 * math.sin(i * math.tau * 997 / 48000))) for i in range(24000)))
    formats = [('wav', []), ('flac', ['-c:a', 'flac']), ('mp3', ['-c:a', 'libmp3lame']),
               ('m4a', ['-c:a', 'aac']), ('aac', ['-c:a', 'aac']),
               ('ogg', ['-c:a', 'libvorbis']), ('webm', ['-c:a', 'libopus'])]
    manifest = {'generator': subprocess.check_output(['ffmpeg', '-version'], text=True).splitlines()[0],
                'origin': 'Synthetic stereo sine, 440/997Hz; 48000Hz; 24000 source frames',
                'lossless_tolerance': 1e-7, 'lossy_tolerance': 0.001, 'files': {}}
    for suffix, options in formats:
        target = OUT / ('tone.' + suffix)
        if options:
            subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', str(source),
                            '-map_metadata', '-1', *options, str(target)], check=True)
        pcm = OUT / ('tone.' + suffix + '.f32')
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', str(target),
                        '-f', 'f32le', '-c:a', 'pcm_f32le', str(pcm)], check=True)
        manifest['files'][target.name] = {'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
            'frames': pcm.stat().st_size // 8, 'pcm_sha256': hashlib.sha256(pcm.read_bytes()).hexdigest()}
    (OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({name: entry['frames'] for name, entry in manifest['files'].items()}))


if __name__ == '__main__':
    main()
