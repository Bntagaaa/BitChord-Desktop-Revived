"""Source-level palette/glyph comparison; intentionally not a visual parity test."""
from pathlib import Path
import hashlib
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT.parent
DESKTOP = REFERENCE / 'desktopApp/src/main/kotlin/com/music/bitchord/desktop'


def main():
    original = (DESKTOP / 'DesktopTheme.kt').read_text()
    dart = (ROOT / 'lib/design_system/palette.dart').read_text()
    for name in ('DARK', 'LIGHT'):
        block = original.split(f'internal val {name}_PALETTE = DesktopThemePalette(')[1].split('\n)')[0]
        expected = dict(re.findall(r'(\w+) = Color\((0x[0-9A-Fa-f]+)\)', block))
        expected.update({key: '0xFFFFFFFF' if value == 'White' else '0xFF000000'
                         for key, value in re.findall(r'(\w+) = Color\.(White|Black)', block)})
        section = dart.split(f'static const {name.lower()} = BitChordPalette({{')[1].split('});')[0]
        actual = dict(re.findall(r"'(\w+)': Color\((0x[0-9A-Fa-f]+)\)", section))
        assert expected == actual, name
    for path in (REFERENCE / 'desktopApp/src/main/composeResources/drawable').glob('player_transport_*.svg'):
        assert hashlib.sha256(path.read_bytes()).digest() == hashlib.sha256((ROOT / 'assets/icons' / path.name).read_bytes()).digest(), path
    glyphs = (REFERENCE / 'sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/icons/BitChordIcons.kt').read_text()
    for name in ('TabExplore', 'TabSearch', 'TabLibrary'):
        block = glyphs.split(f'val {name}: ImageVector by lazy {{')[1].split('\n    }')[0]
        expected = ''.join(re.findall(r'"(M[^\"]+)"', block))
        actual = ET.parse(ROOT / 'assets/icons' / f'{name}.svg').getroot()[0].attrib['d']
        assert actual == expected, name
    print('Both semantic palettes, nine transport SVGs and three tab path outlines match reference source.')


if __name__ == '__main__':
    main()
