"""Create hosts only via the official, pinned flutter create generator."""
from pathlib import Path
import json
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_VERSION = (ROOT / '.flutter-version').read_text().strip()
EXPECTED_FRAMEWORK = 'c197ca5199e9ee5661e865596c9c07294dfab51c'


def main():
    flutter = shutil.which('flutter')
    if not flutter:
        raise SystemExit('Flutter is required; no platform files were generated.')
    result = subprocess.run([flutter, '--version', '--machine'], check=True, text=True, capture_output=True)
    version = json.loads(result.stdout)
    if version['frameworkVersion'] != EXPECTED_VERSION or version['frameworkRevision'] != EXPECTED_FRAMEWORK:
        raise SystemExit('Use the pinned official Flutter SDK; generator version/provenance differs.')
    present = [(ROOT / platform).exists() for platform in ('windows', 'linux')]
    if any(present):
        if not all(present):
            raise SystemExit('Partial host tree exists; inspect it rather than overwrite it.')
        record = ROOT / 'tool' / 'platform_generation.json'
        if not record.is_file() or json.loads(record.read_text())['frameworkRevision'] != EXPECTED_FRAMEWORK:
            raise SystemExit('Existing hosts have no matching generation provenance; refusing overwrite.')
        print('Keeping existing officially generated hosts.')
        return
    with tempfile.TemporaryDirectory(prefix='bitchord-flutter-') as temporary:
        generated = Path(temporary) / 'bitchord_desktop'
        subprocess.run([flutter, 'create', '--empty', '--no-pub', '--platforms=windows,linux',
                        '--org=io.bitchord', '--project-name=bitchord_desktop', str(generated)], check=True)
        for platform in ('windows', 'linux'):
            if not (generated / platform / 'CMakeLists.txt').is_file():
                raise SystemExit(f'Official generator did not produce {platform}; nothing is fabricated.')
        for platform in ('windows', 'linux'):
            shutil.copytree(generated / platform, ROOT / platform)
        shutil.copyfile(generated / '.metadata', ROOT / '.metadata')
    evidence = ROOT / 'verification'
    evidence.mkdir(exist_ok=True)
    (evidence / 'generator.json').write_text(json.dumps(version, indent=2) + '\n')
    (ROOT / 'tool' / 'platform_generation.json').write_text(json.dumps(version, indent=2) + '\n')
    print('Hosts generated with official Flutter create. Application source was not overwritten.')


if __name__ == '__main__':
    main()
