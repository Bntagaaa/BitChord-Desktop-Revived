"""Dependency/provenance checks that do not require the Flutter runtime."""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    for source in (ROOT / 'lib').rglob('*.dart'):
        imports = re.findall(r"(?:import|export)\s+['\"]([^'\"]+)", source.read_text())
        relative = source.relative_to(ROOT / 'lib')
        if 'domain' in relative.parts:
            for dependency in imports:
                if dependency.startswith('package:') or dependency.startswith(('dart:ffi', 'dart:io', 'dart:ui')):
                    raise SystemExit(f'Domain dependency violation: {relative}: {dependency}')
        if 'application' in relative.parts:
            for dependency in imports:
                if '/platform/' in dependency or '/data/' in dependency:
                    raise SystemExit(f'Application bypasses ports: {relative}: {dependency}')
        if 'presentation' in relative.parts:
            for dependency in imports:
                if dependency.startswith(('dart:ffi', 'dart:io', 'package:shared_preferences', 'package:window_manager')) or '/data/' in dependency:
                    raise SystemExit(f'Presentation bypasses services: {relative}: {dependency}')
    manifest = json.loads((ROOT / 'assets/provenance.json').read_text())
    for name, expected in manifest.items():
        path = ROOT / 'assets/icons' / name
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise SystemExit(f'Asset differs: {name}')
        if path.suffix == '.svg':
            ET.fromstring(path.read_text())
    assert (ROOT / 'native/audio/include/bitchord_audio.h').is_file(), 'Phase 3 ABI header missing'
    print(f'Validated layer imports and {len(manifest)} asset hashes/XML documents.')


if __name__ == '__main__':
    main()
