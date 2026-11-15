"""Extract and execute pinned Kotlin DSP without modifying/building the app.

Uses an already-installed Kotlin compiler classpath (no dependency downloads).
Usage: python tool/phase5_reference.py --classpath <compiler/runtime jars>
The checked-in corpus is generated from the unchanged source, not a Dart fake.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
REF = '348b613a0c3db1a54de56483ea6f8748d6465211'
DESKTOP = 'desktopApp/src/main/kotlin/com/music/bitchord/desktop/'
SOURCES = [DESKTOP + name + '.kt' for name in (
    'DesktopEqualizer', 'DesktopAudioProcessors', 'DesktopAudioSpeed',
    'DesktopTransitionEcho', 'DesktopPlayhead')]
SOURCES += ['shared/src/commonMain/kotlin/com/music/bitchord/playback/EqualizerCurve.kt']
DEST = ROOT / 'flutterDesktop/native/audio/test/fixtures/phase5'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--classpath')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.check:
        manifest = json.loads((DEST / 'manifest.json').read_text())
        assert manifest['reference'] == REF
        for source, expected in manifest['sources_sha256'].items():
            data = subprocess.check_output(['git', 'show', f'{REF}:{source}'], cwd=ROOT)
            assert hashlib.sha256(data).hexdigest() == expected, source
        oracle = ROOT / 'flutterDesktop/tool/phase5/Oracle.kt'
        assert hashlib.sha256(oracle.read_bytes().replace(b'\r\n', b'\n')).hexdigest() == manifest['driver_sha256']
        for name, expected in manifest['fixtures_sha256'].items():
            data = (DEST / name).read_bytes()
            if name.endswith('.skipped'):
                data = data.replace(b'\r\n', b'\n')
            assert hashlib.sha256(data).hexdigest() == expected, name
        print(f"Verified {len(manifest['fixtures_sha256'])} pinned Kotlin DSP fixture hashes.")
        return
    if not args.classpath:
        parser.error('--classpath is required to regenerate the oracle')
    provenance = {}
    with tempfile.TemporaryDirectory(prefix='bitchord-dsp-oracle-') as tmp:
        paths = []
        for source in SOURCES:
            data = subprocess.check_output(['git', 'show', f'{REF}:{source}'], cwd=ROOT)
            path = Path(tmp) / Path(source).name
            path.write_bytes(data)
            paths.append(str(path))
            provenance[source] = hashlib.sha256(data).hexdigest()
        oracle = ROOT / 'flutterDesktop/tool/phase5/Oracle.kt'
        paths.append(str(oracle))
        classes = Path(tmp) / 'classes'
        subprocess.run(['java', '-Xmx512m', '-cp', args.classpath,
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib',
                        '-no-reflect', '-jvm-target', '17', '-classpath', args.classpath,
                        '-d', str(classes), *paths], check=True)
        DEST.mkdir(parents=True, exist_ok=True)
        subprocess.run(['java', '-cp', str(classes) + os.pathsep + args.classpath,
                        'com.music.bitchord.desktop.OracleKt', str(DEST)], check=True)
    fixtures = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                for p in sorted(DEST.iterdir()) if p.suffix in ('.f32', '.skipped', '.clock')}
    (DEST / 'manifest.json').write_text(json.dumps({
        'reference': REF, 'sources_sha256': provenance,
        'driver_sha256': hashlib.sha256(oracle.read_bytes()).hexdigest(),
        'byte_order': 'little-endian float32',
        'tolerances': {'eq_abs': 0.0001, 'other_abs': 0.000002,
                       'frame_count': 0, 'skipped_frames': 0, 'source_clock_us': 2},
        'fixtures_sha256': fixtures,
        'limits': 'Pure executed Kotlin DSP; no reference OS output or listening claim.'
    }, indent=2) + '\n')


if __name__ == '__main__':
    main()
