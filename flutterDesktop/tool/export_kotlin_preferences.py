"""Read-only, non-secret snapshot of the pinned Kotlin Preferences format.
Close Kotlin before capture to avoid observing a writer between chunks.
Linux: --source ~/.java/.userPrefs/com.music.bitchord.desktop/prefs.xml
Windows: omit --source to read HKCU with KEY_READ. No Java runtime is needed.
"""
import argparse
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

KEYS = {'queue', 'queue_index', 'history', 'downloads', 'playlists', 'liked_ids',
        'disliked_ids', 'original_versions', 'repeat_mode', 'shuffle', 'volume',
        'playback_speed', 'local_music_folder'}
REFERENCE = '348b613a0c3db1a54de56483ea6f8748d6465211'


def windows_value(value):
    # Java Preferences Windows escape convention, independently translated.
    out, at = [], 0
    while at < len(value):
        ch = value[at]
        if ch == '/' and at + 1 < len(value):
            nxt = value[at + 1]
            if nxt == 'u':
                if at + 6 > len(value):
                    raise ValueError('Truncated Windows preference escape')
                out.append(chr(int(value[at + 2:at + 6], 16)))
                at += 6
                continue
            if nxt == '/' or 'A' <= nxt <= 'Z':
                out.append('\\' if nxt == '/' else nxt)
                at += 2
                continue
        out.append('/' if ch == '\\' else ch)
        at += 1
    # Join surrogate pairs from Java's UTF-16 encoding.
    return ''.join(out).encode('utf-16', 'surrogatepass').decode('utf-16')


def capture(source=None):
    if source:
        raw = Path(source).read_bytes()
        if len(raw) > 16 * 1024 * 1024:
            raise ValueError('Preferences too large')
        if b'<!ENTITY' in raw:
            raise ValueError('Entity declarations are not accepted')
        root = ET.fromstring(raw)
        if root.tag != 'map':
            raise ValueError('Select the app prefs.xml map, not a user-root export')
        pairs = [(e.attrib['key'], e.attrib['value']) for e in root.findall('entry')]
    elif os.name == 'nt':
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                            r'Software\JavaSoft\Prefs\com.music.bitchord.desktop',
                            0, winreg.KEY_READ) as node:
            pairs = []
            for i in range(winreg.QueryInfoKey(node)[1]):
                name, value, kind = winreg.EnumValue(node, i)
                if kind == winreg.REG_SZ:
                    pairs.append((windows_value(name), windows_value(value)))
    else:
        raise ValueError('Pass --source with the app prefs.xml path')
    values = {}
    for key, value in pairs:
        if re.sub(r'\.\d+$', '', key) in KEYS:
            if key in values:
                raise ValueError('Duplicate preference')
            values[key] = value
    return {'version': 1, 'reference': REFERENCE, 'preferences': values}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    document = capture(args.source)
    # Exclusive new file and mode 0600, never overwrite Kotlin/user files.
    with open(os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600),
              'w', encoding='utf-8') as stream:
        json.dump(document, stream, ensure_ascii=False, indent=2)
        stream.flush()
        os.fsync(stream.fileno())


if __name__ == '__main__':
    main()
